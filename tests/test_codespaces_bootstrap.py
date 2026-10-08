import json
import os
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def test_creation_uses_default_environment_without_extra_feature_build():
    config = json.loads((ROOT / '.devcontainer/devcontainer.json').read_text())
    assert not config.get('features')
    assert not config.get('build')
    assert 'image' not in config
    assert config['remoteUser'] == 'codespace'
    assert config['postCreateCommand'] == 'bash .devcontainer/install.sh'


def test_diagnostic_runs_without_docker_or_models(tmp_path):
    fake = tmp_path / 'bin'
    fake.mkdir()
    (fake / 'dirname').symlink_to('/usr/bin/dirname')
    env = dict(os.environ, PATH=str(fake))
    run = subprocess.run(['/bin/bash', str(ROOT / '.devcontainer/start.sh'), 'diagnostico'], env=env, text=True, capture_output=True)
    assert run.returncode == 0, run.stderr
    assert 'Docker: ausente' in run.stdout
    assert 'logs de criação' in run.stdout


def test_private_port_failure_does_not_abort_after_services_start(tmp_path):
    import shutil
    project = tmp_path / 'project'
    scripts = project / '.devcontainer'
    scripts.mkdir(parents=True)
    shutil.copy(ROOT / '.devcontainer/start.sh', scripts / 'start.sh')
    fake = tmp_path / 'bin'
    fake.mkdir()
    for name in ('dirname', 'sed'):
        (fake / name).symlink_to('/usr/bin/' + name)
    for name, body in {'docker': 'exit 0', 'python3': 'exit 0', 'gh': 'exit 1'}.items():
        path = fake / name
        path.write_text('#!/bin/bash\n' + body + '\n')
        path.chmod(0o755)
    (project / '.env').write_text('LIVECLIP_PASSWORD=fixture-local-password\n')
    env = dict(os.environ, PATH=str(fake), CODESPACE_NAME='fixture-machine')
    run = subprocess.run(['/bin/bash', str(scripts / 'start.sh')], env=env, text=True, capture_output=True)
    assert run.returncode == 0, run.stderr
    assert 'Painel: https://fixture-machine-8080.app.github.dev' in run.stdout
    assert 'serviços já foram iniciados' in run.stderr
