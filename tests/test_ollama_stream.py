import io
import json
from types import SimpleNamespace

import pytest

from liveclip.intelligence import Intelligence


def ai():
    return Intelligence(
        SimpleNamespace(ollama_url="http://127.0.0.1:11434", ollama_model="test")
    )


def test_stream_reports_actual_output_and_bounds_generation(monkeypatch):
    packets = [
        {"message": {"content": '{"clips":'}, "done": False},
        {"message": {"content": "[]}"}, "done": True},
    ]

    class Response(io.BytesIO):
        pass

    seen = {}

    def open_request(request, timeout):
        seen.update(json.loads(request.data))
        return Response(b"\n".join(json.dumps(p).encode() for p in packets))

    monkeypatch.setattr("urllib.request.urlopen", open_request)
    instance = ai()
    updates = []
    instance.report = lambda *x: updates.append(x)
    assert instance._chat("editor", {}) == {"clips": []}
    assert seen["stream"] is True
    assert seen["think"] is False
    assert seen["options"]["num_predict"] <= 768
    assert updates


def test_interrupted_stream_is_not_treated_as_empty_selection(monkeypatch):
    monkeypatch.setattr(
        "urllib.request.urlopen",
        lambda *a, **kw: io.BytesIO(
            b'{"message":{"content":"{\\"clips\\":[]}"},"done":false}\n'
        ),
    )
    with pytest.raises(RuntimeError, match="interrompida"):
        ai()._chat("editor", {})


@pytest.mark.parametrize(
    "kind,expected",
    [
        ("missing", "não encontrado"),
        ("timeout", "5 minutos"),
        ("truncated", "limite de resposta"),
        ("invalid", "JSON inválido"),
        ("memory", "sem memória"),
    ],
)
def test_specific_errors(monkeypatch, kind, expected):
    import urllib.error

    def request(*a, **kw):
        if kind == "missing":
            raise urllib.error.HTTPError("local", 404, "missing", {}, None)
        if kind == "timeout":
            raise TimeoutError()
        if kind == "memory":
            packet = {"error": "memory allocation failed"}
        elif kind == "truncated":
            packet = {
                "message": {"content": "{}"},
                "done": True,
                "done_reason": "length",
            }
        else:
            packet = {"message": {"content": "not json"}, "done": True}
        return io.BytesIO(json.dumps(packet).encode() + b"\n")

    monkeypatch.setattr("urllib.request.urlopen", request)
    with pytest.raises(RuntimeError, match=expected):
        ai()._chat("editor", {})


def test_real_http_stream_selection_and_review():
    import threading
    from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

    packets = []

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def do_POST(self):
            body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            packets.append(body)
            data = json.loads(body["messages"][1]["content"])
            if "clip" in data:
                answer = {
                    "approved": True,
                    "starts_mid_story": False,
                    "ends_mid_story": False,
                }
            else:
                answer = {
                    "clips": [
                        {
                            "title": "A chave encontrada",
                            "reason": "O amigo esqueceu a chave e conseguiu voltar para casa.",
                            "score": 0.9,
                            "complete": True,
                            "arc": {
                                "opening_index": 0,
                                "development_index": 1,
                                "ending_index": 2,
                                "starts_mid_story": False,
                                "ends_mid_story": False,
                            },
                        }
                    ]
                }
            content = json.dumps(answer)
            self.send_response(200)
            self.send_header("Content-Type", "application/x-ndjson")
            self.end_headers()
            for i in range(0, len(content), 20):
                self.wfile.write(
                    json.dumps(
                        {"message": {"content": content[i : i + 20]}, "done": False}
                    ).encode()
                    + b"\n"
                )
                self.wfile.flush()
            self.wfile.write(b'{"message":{"content":""},"done":true}\n')

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        instance = Intelligence(
            SimpleNamespace(
                ollama_url=f"http://127.0.0.1:{server.server_port}",
                ollama_model="controlled",
                cpu_threads=2,
            )
        )
        transcript = [
            dict(start=0, end=20, text="Ontem meu amigo levou minha moto."),
            dict(
                start=20, end=40, text="Ele esqueceu a chave na loja e precisou voltar."
            ),
            dict(start=40, end=60, text="No final achou a chave e voltou para casa."),
        ]
        reports = []
        result = instance.select(transcript, 60, lambda *x: reports.append(x))
        assert len(result) == 1
        assert result[0]["start"] == 0 and result[0]["end"] == 60
        assert len(packets) == 2
        assert any("partes recebidas" in r[1] for r in reports)
    finally:
        server.shutdown()
        server.server_close()
        thread.join()


def test_selection_and_review_request_typed_output(monkeypatch):
    seen = []

    def answer(request, **kwargs):
        body = json.loads(request.data)
        seen.append(body)
        payload = (
            '{"clips":[]}'
            if "duration" in json.loads(body["messages"][1]["content"])
            else '{"approved":false,"starts_mid_story":true,"ends_mid_story":true}'
        )
        return io.BytesIO(
            json.dumps({"message": {"content": payload}, "done": True}).encode()
        )

    monkeypatch.setattr("urllib.request.urlopen", answer)
    instance = ai()
    instance._chat("editor", {"duration": 60, "transcript": []})
    instance._chat("review", {"clip": {}, "transcript": []})
    selection = seen[0]["format"]
    assert selection["properties"]["clips"]["maxItems"] == 2
    arc = selection["properties"]["clips"]["items"]["properties"]["arc"]
    assert arc["properties"]["opening_index"]["type"] == "integer"
    assert "ending_index" in arc["required"]
    assert seen[1]["format"]["properties"]["approved"]["type"] == "boolean"
    assert seen[0]["options"]["num_ctx"] == 8192
