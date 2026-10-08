import pytest
from liveclip.cloud import CloudMeter, stop_codespace

def test_meter_requires_baseline_and_deducts_observed_time(tmp_path):
    clock = [0]
    meter = CloudMeter(tmp_path, clock=lambda: clock[0])
    assert meter.status()['remaining_seconds'] is None
    meter.set_hours(2)
    clock[0] = 90
    assert meter.status()['remaining_seconds'] == 7110
    assert CloudMeter(tmp_path, clock=lambda: 0).status()['remaining_seconds'] == 7110
    with pytest.raises(ValueError):
        meter.set_hours(float('nan'))

def test_stop_requires_credentials_and_does_not_claim_success(monkeypatch):
    monkeypatch.delenv('LIVECLIP_CODESPACES_TOKEN', raising=False)
    monkeypatch.setenv('CODESPACE_NAME', 'example-name')
    with pytest.raises(ValueError):
        stop_codespace()

def test_stop_targets_only_current_machine(monkeypatch):
    monkeypatch.setenv('LIVECLIP_CODESPACES_TOKEN', 'fixture')
    monkeypatch.setenv('CODESPACE_NAME', 'example-name')
    class Response:
        status_code = 200
    calls = []
    monkeypatch.setattr('liveclip.cloud.httpx.post', lambda url, **kwargs: calls.append(url) or Response())
    assert stop_codespace()['accepted'] is True
    assert calls == ['https://api.github.com/user/codespaces/example-name/stop']


def test_cloud_api_auth_validation_and_active_task_guard(tmp_path, monkeypatch):
    from fastapi.testclient import TestClient
    from liveclip.app import create_app
    from liveclip.settings import Settings
    app = create_app(Settings(data=tmp_path, password='long-local-test-password', start_worker=False))
    calls = []
    monkeypatch.setattr('liveclip.app.stop_codespace', lambda: calls.append(True) or {'accepted': True})
    with TestClient(app) as c:
        assert c.get('/api/cloud').status_code == 401
        assert c.post('/api/cloud/stop').status_code == 401
        c.post('/api/login', json={'password': 'long-local-test-password'})
        assert c.post('/api/cloud/balance', json={'hours': True}).status_code == 422
        assert c.post('/api/cloud/balance', json={'hours': -1}).status_code == 422
        assert c.post('/api/cloud/balance', json={'hours': 2}).status_code == 200
        session = app.state.store.create_session('https://kick.com/test', 'kick')
        for status in ('queued', 'waiting', 'monitoring', 'reconnecting', 'finishing', 'stopping'):
            app.state.store.update_session(session['id'], status=status)
            assert c.post('/api/cloud/stop').status_code == 409
        assert calls == []
        app.state.store.update_session(session['id'], status='cancelled')
        assert c.post('/api/cloud/stop').status_code == 200
        assert calls == [True]


def test_github_denial_and_network_failure_are_not_success(monkeypatch):
    import httpx
    monkeypatch.setenv('LIVECLIP_CODESPACES_TOKEN', 'fixture')
    monkeypatch.setenv('CODESPACE_NAME', 'example-name')
    class Response:
        status_code = 403
    monkeypatch.setattr('liveclip.cloud.httpx.post', lambda *args, **kwargs: Response())
    with pytest.raises(ValueError):
        stop_codespace()
    def fail(*args, **kwargs):
        raise httpx.ReadTimeout('fixture')
    monkeypatch.setattr('liveclip.cloud.httpx.post', fail)
    with pytest.raises(ValueError):
        stop_codespace()
