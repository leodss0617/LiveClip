"""Windows worker. The phone sends a job and acknowledges its received exports."""
import ctypes
import json
import os
import re
import shutil
import sys
import threading
import time
from pathlib import Path

install = Path(__file__).resolve().parent
identifier = sys.argv[1]
if not re.fullmatch(r'[a-f0-9]{32}', identifier):
    sys.exit(2)
folder = install/'jobs'/identifier
if not folder.is_dir() or folder.is_symlink():
    sys.exit(2)
for name in ('home','temp'):
    (folder/name).mkdir(exist_ok=True)
os.environ.update(USERPROFILE=str(folder/'home'), HOME=str(folder/'home'),
                  TEMP=str(folder/'temp'), TMP=str(folder/'temp'),
                  HF_HOME=str(install/'whisper-cache'), HF_HUB_DISABLE_XET='1',
                  PYTHONUTF8='1', PYTHONDONTWRITEBYTECODE='1',
                  EDITOR_LIVE_SECTIONS='1', EDITOR_EXPORTS=str(folder/'resultados'))
stop = threading.Event()

def monitor():
    while not stop.wait(2):
        try:
            age = time.time()-(folder/'lease').stat().st_mtime
            if age > 180:
                (folder/'parar').touch()
                return
            (folder/'worker-vivo').touch()
        except FileNotFoundError:
            return

try:
    from bridge import perform
    import bridge
    bridge.MACHINE = install/'maquina-de-cortes'
    license_dir = folder/'home'/'.maquina-de-cortes'
    license_dir.mkdir()
    (folder/'licenca.json').replace(license_dir/'licenca.json')
    spec = json.loads((folder/'pedido.json').read_text())
    spec.pop('motor', None)
    if spec.get('modo') == 'ao_vivo' or os.getenv('EDITOR_PC_LIGHT') == '1':
        kernel = ctypes.windll.kernel32
        kernel.GetCurrentProcess.restype = ctypes.c_void_p
        kernel.SetPriorityClass.argtypes = (ctypes.c_void_p, ctypes.c_uint)
        kernel.SetPriorityClass(kernel.GetCurrentProcess(), 0x4000)
    threading.Thread(target=monitor, daemon=True).start()
    log=folder/'processo.log'
    def relay():
        offset=0
        while not stop.wait(0.5):
            if log.is_file():
                with log.open('rb') as stream:
                    stream.seek(offset)
                    data=stream.read()
                    offset=stream.tell()
                if data:
                    try:
                        sys.stdout.buffer.write(data)
                        sys.stdout.buffer.flush()
                    except OSError:
                        return
    relay_thread=threading.Thread(target=relay, daemon=True)
    relay_thread.start()
    perform(folder,spec)
    while not (folder/'recebido').exists():
        if time.time()-(folder/'lease').stat().st_mtime > 180:
            break
        time.sleep(0.5)
finally:
    stop.set()
    if 'relay_thread' in locals():
        relay_thread.join(timeout=2)
    for attempt in range(12):
        try:
            shutil.rmtree(folder)
            break
        except OSError:
            if attempt==11:
                raise
            time.sleep(1)

