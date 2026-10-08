import importlib.util
from pathlib import Path
import stat
import zipfile
import pytest

HELPER = Path(__file__).resolve().parents[1] / 'desktop-update.py'

def helper():
    assert HELPER.exists(), 'desktop update helper missing'
    spec = importlib.util.spec_from_file_location('desktop_update', HELPER)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module

def archive(path, extra=None):
    with zipfile.ZipFile(path, 'w') as z:
        for name in ('compose.yaml', 'iniciar.sh', 'iniciar.ps1'):
            z.writestr('LiveClip/' + name, 'new')
        z.writestr('LiveClip/liveclip/app.py', 'new app')
        for name, value in (extra or {}).items(): z.writestr(name, value)

def test_update_preserves_user_files_and_replaces_code(tmp_path):
    m = helper()
    project = tmp_path / 'project'; project.mkdir()
    for name in ('.env', 'native.json', 'data/video.mp4', 'models/model.bin'):
        target = project / name; target.parent.mkdir(parents=True, exist_ok=True); target.write_text('keep')
    downloads = tmp_path / 'Downloads'; downloads.mkdir()
    archive(downloads / 'LiveClip-new.zip', {'LiveClip/.env': 'bad', 'LiveClip/data/video.mp4': 'bad'})
    assert m.update(project, downloads)
    assert (project / 'liveclip/app.py').read_text() == 'new app'
    for name in ('.env', 'native.json', 'data/video.mp4', 'models/model.bin'): assert (project / name).read_text() == 'keep'
    assert not m.update(project, downloads)

@pytest.mark.parametrize('name', ['../escape', 'LiveClip/../../escape', 'LiveClip/C:/escape', 'LiveClip/link'])
def test_rejects_unsafe_archive_without_changing_code(tmp_path, name):
    m = helper(); project = tmp_path / 'project'; project.mkdir(); (project / 'compose.yaml').write_text('old')
    downloads = tmp_path / 'downloads'; downloads.mkdir(); path = downloads / 'LiveClip.zip'
    archive(path)
    with zipfile.ZipFile(path, 'a') as z:
        info = zipfile.ZipInfo(name)
        if name.endswith('link'): info.external_attr = (stat.S_IFLNK | 0o777) << 16
        z.writestr(info, 'target')
    with pytest.raises(ValueError): m.update(project, downloads)
    assert (project / 'compose.yaml').read_text() == 'old'

def test_rejects_oversized_archive(tmp_path):
    m = helper(); d = tmp_path / 'd'; d.mkdir(); p = tmp_path / 'p'; p.mkdir()
    archive(d / 'LiveClip.zip', {'LiveClip/huge': b'x' * (30 * 1024 * 1024 + 1)})
    with pytest.raises(ValueError): m.update(p, d)

def test_linux_command_uses_existing_project_and_forwards_arguments(tmp_path):
    import subprocess
    source = HELPER.parent
    launcher = source / 'liveclip-desktop.sh'
    assert launcher.exists(), 'desktop launcher missing'
    for name in ('liveclip-desktop.sh', 'desktop-update.py'):
        (tmp_path / name).write_bytes((source / name).read_bytes())
    (tmp_path / 'iniciar.sh').write_text('printf "%s" "$1" > forwarded\n')
    fakebin = tmp_path / 'bin'; fakebin.mkdir()
    docker = fakebin / 'docker'; docker.write_text('#!/bin/sh\nexit 0\n'); docker.chmod(0o755)
    import os
    env = dict(os.environ, PATH=str(fakebin) + os.pathsep + os.environ['PATH'], LIVECLIP_DOWNLOADS=str(tmp_path / 'downloads'))
    subprocess.run(['bash', str(tmp_path / 'liveclip-desktop.sh'), 'passed'], env=env, check=True)
    assert (tmp_path / 'forwarded').read_text() == 'passed'

def test_linux_registration_works_in_path_and_is_idempotent(tmp_path):
    import os
    import subprocess
    home = tmp_path / 'home'; home.mkdir()
    project = tmp_path / 'project with spaces'; project.mkdir()
    source = HELPER.parent
    (project / 'instalar-comando-linux.sh').write_bytes((source / 'instalar-comando-linux.sh').read_bytes())
    (project / 'liveclip-desktop.sh').write_text('printf "%s" "$1"\n')
    env = dict(os.environ, HOME=str(home))
    for _ in range(2): subprocess.run(['bash', str(project / 'instalar-comando-linux.sh')], env=env, check=True, capture_output=True)
    env['PATH'] = str(home / '.local/bin') + os.pathsep + env['PATH']
    assert subprocess.check_output(['liveclip', 'argument with spaces'], env=env, text=True) == 'argument with spaces'
    assert (home / '.bashrc').read_text().count('export PATH=') == 1


def test_update_rejects_existing_symlink_destination(tmp_path):
    m = helper(); p = tmp_path / 'p'; p.mkdir(); d = tmp_path / 'd'; d.mkdir()
    outside = tmp_path / 'outside'; outside.mkdir(); (p / 'liveclip').symlink_to(outside, target_is_directory=True)
    archive(d / 'LiveClip.zip')
    with pytest.raises(ValueError): m.update(p, d)
    assert not (outside / 'app.py').exists()


def test_updates_only_newest_zip_and_preserves_nested_native_config(tmp_path):
    import os
    m = helper(); p = tmp_path / 'p'; p.mkdir(); d = tmp_path / 'd'; d.mkdir()
    archive(d / 'LiveClip-old.zip', {'LiveClip/old-marker': 'old'})
    archive(d / 'LiveClip-new.zip', {'LiveClip/liveclip/native.json': 'bad'})
    os.utime(d / 'LiveClip-old.zip', (1, 1)); os.utime(d / 'LiveClip-new.zip', (2, 2))
    (p / 'liveclip').mkdir(); (p / 'liveclip/native.json').write_text('keep')
    m.update(p, d)
    assert not (p / 'old-marker').exists()
    assert (p / 'liveclip/native.json').read_text() == 'keep'
