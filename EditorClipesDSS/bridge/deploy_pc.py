import os
import sys
import stat
from tempfile import TemporaryDirectory
from pathlib import Path, PurePosixPath
from pc_client import connect_pc, INSTALL, JOBS, PRIMARY_PC, PC_TARGETS, open_pc_sftp
home=Path(os.environ['EDITOR_TERMUX_HOME'])
source=home/'maquina-de-cortes-android/maquina-de-cortes'
local=Path(__file__).resolve().parent
target_pc=sys.argv[1] if len(sys.argv) == 2 else PRIMARY_PC
if target_pc not in PC_TARGETS:
    raise SystemExit('PC desconhecido')
client=connect_pc(target_pc)
try:
    with open_pc_sftp(client,target_pc) as sftp:
        print('SFTP',sftp.normalize('.'),flush=True)
        def mkdir(path):
            try: sftp.mkdir(path)
            except OSError:
                sftp.stat(path)
        mkdir(INSTALL)
        mkdir(JOBS)
        if sftp.listdir(JOBS):
            raise SystemExit('Há trabalhos em execução no PC indicado.')
        for name in ('momentos.py','trechos.py','ao_vivo.py','live_clock.py','clipes.py','bridge.py','pc_worker.py'):
            sftp.put(str(local/name),INSTALL+'/'+name)
        target=INSTALL+'/maquina-de-cortes'
        mkdir(target)
        for name in ('scripts','fontes','elementos'):
            mkdir(target+"/"+name)
            for file in sorted((source/name).rglob('*')):
                if '__pycache__' in file.parts: continue
                remote=str(PurePosixPath(target,file.relative_to(source).as_posix()))
                if file.is_dir(): mkdir(remote)
                else: sftp.put(str(file),remote)
        if target_pc != PRIMARY_PC:
            with connect_pc() as origin, origin.open_sftp() as cached, TemporaryDirectory() as scratch:
                scratch_file=Path(scratch)/'model-item'
                models=INSTALL+'/whisper-cache/hub/models--Systran--faster-whisper-base'
                for directory in (INSTALL+'/whisper-cache', INSTALL+'/whisper-cache/hub'):
                    mkdir(directory)
                remaining=[models]
                while remaining:
                    directory=remaining.pop()
                    mkdir(directory)
                    for item in cached.listdir_attr(directory):
                        remote=directory+'/'+item.filename
                        flags=cached.stat(remote)
                        if stat.S_ISDIR(flags.st_mode):
                            remaining.append(remote)
                            continue
                        if not stat.S_ISREG(flags.st_mode):
                            continue
                        try:
                            if flags.st_size and sftp.stat(remote).st_size == flags.st_size:
                                continue
                        except FileNotFoundError:
                            pass
                        cached.get(remote,scratch_file,max_concurrent_prefetch_requests=24)
                        sftp.put(str(scratch_file),remote,confirm=True)
                        if flags.st_size != sftp.stat(remote).st_size:
                            raise RuntimeError('O cache da transcrição foi recebido incompleto.')
                print('Cache de transcrição copiado.',flush=True)
finally:
    client.close()
