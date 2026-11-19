"""Run inside the Codespace over GitHub CLI SSH; credentials stay on the server."""
import base64
import json
import sys
import time
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen

action, argument = json.loads(base64.b64decode(sys.argv[1]))
base = "http://127.0.0.1:8080"


def api(path, data=None, cookie=""):
    headers = {"Content-Type": "application/json"}
    if cookie:
        headers["Cookie"] = cookie
    request = Request(base + path, json.dumps(data).encode() if data is not None else None, headers)
    with urlopen(request, timeout=8) as response:
        return json.load(response), response.headers.get("Set-Cookie", "").split(";", 1)[0]


try:
    for attempt in range(12):
        try:
            api("/api/health")
            break
        except (OSError, ValueError):
            if attempt == 11:
                raise
            time.sleep(3)

    if action == "prepare":
        print("Codespace conectado. Serviço de monitoramento disponível.")
    else:
        env = Path(".env").read_text().splitlines()
        password = next(line.partition("=")[2].strip() for line in env
                        if line.startswith("LIVECLIP_PASSWORD="))
        _, cookie = api("/api/login", {"password": password})
        if not cookie:
            raise ValueError("Sessão do LiveClip indisponível.")
        if action == "monitor":
            try:
                session, _ = api("/api/sessions", {"url": argument}, cookie)
                print("Monitoramento enviado ao Codespace. Sessão: " + session["id"])
            except HTTPError as error:
                if error.code != 409:
                    raise
                print("O LiveClip recusou uma nova sessão. Confira as lives já em monitoramento.")
        else:
            raise ValueError("Operação desconhecida.")
except HTTPError as error:
    print(f"O serviço LiveClip respondeu com erro HTTP {error.code}.", file=sys.stderr)
    sys.exit(1)
except (OSError, ValueError, StopIteration, KeyError):
    print("Serviço LiveClip indisponível. Confira a inicialização do Codespace.", file=sys.stderr)
    sys.exit(1)
