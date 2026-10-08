import json
import os
import secrets
import subprocess
import sys
import tempfile
import time
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from liveclip.store import Store


def main():
    from playwright.sync_api import expect, sync_playwright

    root = Path(__file__).resolve().parents[1]
    output = root / "test-output"
    output.mkdir(exist_ok=True)
    password = secrets.token_urlsafe(24)
    with tempfile.TemporaryDirectory() as d:
        server = subprocess.Popen(
            [sys.executable, __file__, "--serve"],
            env=dict(os.environ, LIVECLIP_PASSWORD=password, LIVECLIP_DATA=d),
            cwd=root,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        try:
            for i in range(100):
                try:
                    urllib.request.urlopen(
                        "http://127.0.0.1:8876/api/health", timeout=0.2
                    )
                    break
                except Exception:
                    time.sleep(0.1)
            with sync_playwright() as p:
                browser = p.chromium.launch(headless=True, args=["--no-sandbox"])
                page = browser.new_page(viewport={"width": 1280, "height": 900})
                errors = []
                page.on("pageerror", lambda e: errors.append(str(e)))
                page.goto("http://127.0.0.1:8876")
                page.fill("#password", password)
                page.click("#login-form button")
                page.wait_for_selector("#studio", state="visible")
                expect(page.locator('#runtime-worker')).to_have_text('Processamento: parado')
                expect(page.locator('#runtime-ai')).to_contain_text('IA: aguardando modelo')
                page.click('#refresh')
                expect(page.locator('#refresh')).to_be_enabled()
                page.fill("#url", "https://example.org/test")
                page.click("#start")
                page.wait_for_selector("#message.error", state="visible")
                page.fill("#url", "https://kick.com/test")
                page.click("#start")
                page.wait_for_selector(".session")
                store = Store(Path(d) / "liveclip.db")
                s = store.sessions()[0]
                store.update_session(
                    s["id"], status="stopped", capture_seconds=2, analyzed_until=2
                )
                c = store.add_clip(
                    s["id"],
                    0,
                    2,
                    "Teste controlado",
                    "Fixture de validação do navegador",
                    0.9,
                )
                store.update_clip(c["id"], status="ready", filename=c["id"] + ".mp4")
                clips = Path(d) / "clips"
                clips.mkdir()
                subprocess.run(
                    [
                        "ffmpeg",
                        "-v",
                        "error",
                        "-f",
                        "lavfi",
                        "-i",
                        "testsrc2=size=1080x1920:rate=15",
                        "-t",
                        "2",
                        "-c:v",
                        "libx264",
                        "-pix_fmt",
                        "yuv420p",
                        str(clips / (c["id"] + ".mp4")),
                    ],
                    check=True,
                )
                store.activity(s['id'], 'transcribing', 'Transcrevendo o áudio do trecho.', progress=42, window_start=0, window_end=90)
                page.reload()
                expect(page.locator('.activity-panel')).to_contain_text('42.0%')
                expect(page.locator('.activity-panel')).to_contain_text('Fila de vídeo')
                page.click('summary')
                expect(page.locator('.activity-panel ol')).to_contain_text('Transcrevendo o áudio')
                page.wait_for_selector(".clip-card")
                page.click(".play-button")
                for i in range(50):
                    info = page.locator("#preview-video").evaluate(
                        "(v)=>({ready:v.readyState,error:v.error&&v.error.message,width:v.videoWidth,height:v.videoHeight})"
                    )
                    if info["ready"] >= 1 or info["error"]:
                        break
                    time.sleep(0.1)
                print("VIDEO_INFO", info)
                if info["error"]:
                    assert page.locator("#preview-error").is_visible()
                with page.expect_download() as down:
                    page.click("#preview-download")
                down.value.save_as(str(output / "browser-download.mp4"))
                assert (output / "browser-download.mp4").stat().st_size > 1000
                page.click("#close-preview")
                expect(page.locator('.clip-meta')).to_contain_text('9.0/10')
                page.locator('.feedback-form select').select_option('3')
                page.locator('.feedback-form input').fill('Faltou começo; quero mais contexto')
                page.get_by_role('button',name='Ensinar à seleção').click()
                expect(page.locator('.clip-body')).to_contain_text('Sua avaliação: 3/10')
                expect(page.locator('#knowledge-summary')).to_contain_text('1 avaliações suas')
                page.once('dialog',lambda dialog: dialog.accept())
                page.get_by_role('button',name='Apagar memória').click()
                expect(page.locator('#knowledge-summary')).to_contain_text('0 registros')

                page.screenshot(path=str(output / "desktop.png"), full_page=True)
                page.set_viewport_size({"width": 390, "height": 844})
                assert page.evaluate(
                    "document.documentElement.scrollWidth<=window.innerWidth"
                )
                page.screenshot(path=str(output / "mobile.png"), full_page=True)
                with page.expect_download() as diag:
                    page.get_by_role('link',name='Baixar diagnóstico').first.click()
                diag.value.save_as(str(output/'diagnostic.json'))
                assert password not in (output/'diagnostic.json').read_text()
                store.update_session(s['id'],status='finishing',stop_requested=1,cancel_requested=0)
                page.click('#refresh')
                cancel=page.get_by_role('button',name='Cancelar tarefa')
                expect(cancel).to_be_enabled()
                cancel.click()
                expect(page.get_by_role('button',name='Cancelando…')).to_be_disabled()
                assert store.session(s['id'])['cancel_requested']==1
                expect(page.locator('.activity-panel')).to_contain_text('Cancelamento solicitado.')
                store.update_session(s['id'],status='cancelled')
                page.click('#refresh')
                expect(page.locator('#start')).to_be_enabled()
                page.on('dialog', lambda dialog: dialog.accept())
                page.get_by_role('button', name='Excluir tarefa', exact=True).click()
                expect(page.locator('.session')).to_have_count(0)
                expect(page.locator('.clip-card')).to_have_count(1)
                assert store.session(s['id']) is None
                page.click("#logout")
                page.wait_for_selector("#login", state="visible")
                assert not errors, errors
                browser.close()
            report = {
                "controls_passed": True,
                "rating_feedback_and_clear": True,
                "download_passed": True,
                "mobile_emulation": [390, 844],
                "desktop": [1280, 900],
                "video_preview": info,
                "javascript_errors": errors,
            }
            (output / "ui-result.json").write_text(json.dumps(report, indent=2))
            print("UI_CONTROLS_AND_DOWNLOAD_PASSED")
        finally:
            server.terminate()
            server.wait(timeout=10)


if __name__ == "__main__":
    if "--serve" in sys.argv:
        import uvicorn

        from liveclip.app import create_app
        from liveclip.settings import Settings

        uvicorn.run(
            create_app(Settings(start_worker=False)),
            host="127.0.0.1",
            port=8876,
            log_level="warning",
        )
    else:
        main()
