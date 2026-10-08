"""Download verificado do modelo distribuído separadamente no GitHub."""
import hashlib
import os
import urllib.request
from pathlib import Path

EXPECTED = 'd3835ffbbd4a1bb3e777f0ca217b5007907f5171dd5d17c4236b95b2af8f908e'
URL = 'https://huggingface.co/audiomagic/yamnet-onnx/resolve/f25b741c2f0bdc6d7e6db24b5fddda23347dbafd/yamnet.onnx'

def main():
    dest = Path(__file__).resolve().parent.parent / 'liveclip/assets/yamnet/yamnet.onnx'
    if dest.exists() and hashlib.sha256(dest.read_bytes()).hexdigest() == EXPECTED:
        return
    tmp = dest.with_suffix('.download')
    try:
        print('Baixando detector de áudio (aproximadamente 16 MB)...', flush=True)
        with urllib.request.urlopen(URL, timeout=60) as source, tmp.open('wb') as target:
            total = 0
            while block := source.read(1024 * 1024):
                total += len(block)
                if total > 30 * 1024 * 1024:
                    raise ValueError('Modelo excedeu o limite esperado')
                target.write(block)
                print(f'Áudio: {total // (1024 * 1024)} MB recebidos', flush=True)
        if hashlib.sha256(tmp.read_bytes()).hexdigest() != EXPECTED:
            raise ValueError('Verificação SHA-256 do modelo falhou')
        os.replace(tmp, dest)
    finally:
        tmp.unlink(missing_ok=True)

if __name__ == '__main__':
    main()
