#!/usr/bin/env python3
"""Apply the newest locally downloaded LiveClip ZIP, preserving desktop state."""
import os
from pathlib import Path, PurePosixPath
import stat
import sys
import tempfile
import zipfile

LIMIT = 30 * 1024 * 1024
RESERVED = {'.env', 'native.json', 'data', 'models', '.venv', 'venv', '__pycache__', '.git', '.desktop-update-stamp'}


def update(project, downloads):
    project, downloads = Path(project).resolve(), Path(downloads)
    candidates = [p for p in downloads.glob('LiveClip*.zip') if p.is_file() and not p.is_symlink()]
    if not candidates:
        return False
    archive = max(candidates, key=lambda p: (p.stat().st_mtime_ns, p.name))
    identity = f'{archive.resolve()}|{archive.stat().st_mtime_ns}|{archive.stat().st_size}'
    stamp = project / '.desktop-update-stamp'
    if stamp.exists() and stamp.read_text() == identity:
        return False
    if archive.stat().st_size > LIMIT:
        raise ValueError('ZIP excede 30 MiB')
    with zipfile.ZipFile(archive) as z, tempfile.TemporaryDirectory(prefix='liveclip-update-') as temp:
        infos = z.infolist()
        if sum(i.file_size for i in infos) > LIMIT or len(infos) > 10000:
            raise ValueError('ZIP expandido excede o limite')
        paths = []
        for i in infos:
            name = i.filename
            p = PurePosixPath(name)
            mode = i.external_attr >> 16
            if ('\\' in name or p.is_absolute() or '..' in p.parts or any(':' in part for part in p.parts)
                    or any(part.endswith((' ', '.')) for part in p.parts)
                    or any(part.split('.')[0].upper() in {'CON', 'PRN', 'AUX', 'NUL', *(f'COM{n}' for n in range(1, 10)), *(f'LPT{n}' for n in range(1, 10))} for part in p.parts)
                    or stat.S_ISLNK(mode) or (stat.S_IFMT(mode) and not (stat.S_ISREG(mode) or stat.S_ISDIR(mode)))):
                raise ValueError(f'Caminho inseguro no ZIP: {name}')
            paths.append((i, p))
        roots = [p.parent for i, p in paths if p.name == 'compose.yaml']
        if len(roots) != 1:
            raise ValueError('ZIP não contém uma única pasta LiveClip')
        root = roots[0]
        names = {str(p.relative_to(root)) for i, p in paths if p.is_relative_to(root)}
        if not {'compose.yaml', 'iniciar.sh', 'iniciar.ps1'} <= names or not any(n.startswith('liveclip/') for n in names):
            raise ValueError('ZIP LiveClip incompleto')
        files = []
        seen = set()
        for i, p in paths:
            if not p.is_relative_to(root) or i.is_dir():
                continue
            rel = p.relative_to(root)
            if any(part.lower() in RESERVED for part in rel.parts):
                continue
            key = str(rel).lower()
            if key in seen:
                raise ValueError('Caminhos duplicados no ZIP')
            seen.add(key)
            dest = project / str(rel)
            if any(parent.is_symlink() for parent in [dest, *dest.parents]):
                raise ValueError('Destino contém link simbólico')
            if dest.exists() and not dest.is_file():
                raise ValueError('Destino incompatível')
            staged = Path(temp) / str(rel)
            staged.parent.mkdir(parents=True, exist_ok=True)
            staged.write_bytes(z.read(i))
            files.append((staged, dest))
        # Everything is validated and CRC checked before replacing existing code.
        for staged, dest in files:
            dest.parent.mkdir(parents=True, exist_ok=True)
            with tempfile.NamedTemporaryFile(dir=dest.parent, delete=False) as out:
                out.write(staged.read_bytes()); pending = out.name
            os.replace(pending, dest)
        stamp.write_text(identity)
    print(f'LiveClip atualizado com {archive.name}. Dados e configurações preservados.')
    return True


if __name__ == '__main__':
    try:
        update(sys.argv[1], sys.argv[2])
    except (ValueError, OSError, zipfile.BadZipFile) as error:
        print(f'Atualização recusada: {error}. Remova o ZIP problemático de Downloads e execute liveclip novamente.', file=sys.stderr)
        sys.exit(1)
