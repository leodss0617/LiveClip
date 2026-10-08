"""Verificação local do painel; a parada remota é simulada no navegador."""
import os
import sys
import tempfile
import threading
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))


def main():
    import uvicorn
    from playwright.sync_api import sync_playwright, expect
    from liveclip.app import create_app
    from liveclip.settings import Settings
    os.environ['CODESPACE_NAME'] = 'local-ui-fixture'
    os.environ['LIVECLIP_CODESPACES_TOKEN'] = 'local-ui-fixture-not-a-token'
    with tempfile.TemporaryDirectory() as d:
        app = create_app(Settings(data=Path(d), password='long-local-test-password', start_worker=False))
        server = uvicorn.Server(uvicorn.Config(app, host='127.0.0.1', port=8877, log_level='error'))
        thread = threading.Thread(target=server.run, daemon=True)
        thread.start()
        try:
            for _ in range(100):
                if server.started:
                    break
                time.sleep(.05)
            with sync_playwright() as p:
                browser = p.chromium.launch(executable_path=os.getenv('LIVECLIP_TEST_CHROME') or None, args=['--no-sandbox'])
                page = browser.new_page(viewport={'width': 390, 'height': 844})
                errors = []
                page.on('pageerror', lambda error: errors.append(str(error)))
                page.goto('http://127.0.0.1:8877')
                page.locator('#password').fill('long-local-test-password')
                page.locator('#login-form button').click()
                expect(page.locator('#cloud-panel')).to_be_visible(timeout=20000)
                page.locator('#cloud-hours').fill('0.01')
                page.locator('#cloud-balance button').click()
                expect(page.locator('#cloud-time')).to_contain_text('estimado')
                first = page.locator('#cloud-time').inner_text()
                page.wait_for_timeout(2100)
                assert first != page.locator('#cloud-time').inner_text()
                assert page.evaluate('document.documentElement.scrollWidth <= innerWidth')
                page.route('**/api/cloud/stop', lambda route: route.fulfill(json={'accepted': True, 'detail': 'Parada simulada aceita.'}))
                page.on('dialog', lambda dialog: dialog.accept())
                page.locator('#cloud-stop').click()
                expect(page.locator('#message')).to_contain_text('Parada simulada aceita.')
                output = Path('test-output')
                output.mkdir(exist_ok=True)
                page.locator('#cloud-panel').screenshot(path=str(output / 'cloud-mobile.png'))
                page.set_viewport_size({'width': 1280, 'height': 900})
                assert page.evaluate('document.documentElement.scrollWidth <= innerWidth')
                assert errors == [], errors
                browser.close()
            print('CLOUD_UI_PASSED: celular/desktop, formulário, contagem regressiva e parada simulada; zero erros JS')
        finally:
            server.should_exit = True
            thread.join(5)


if __name__ == '__main__':
    main()
