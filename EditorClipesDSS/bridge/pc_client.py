"""Run each edit on the paired Windows computer and publish results on Android."""
import base64
import errno
import hashlib
import json
import os
import re
import shutil
import socket
import time
import threading
from pathlib import Path

import paramiko

HOME = Path(os.environ['EDITOR_TERMUX_HOME'])
INSTALL = 'AppData/Local/EditorClipesDSS'
JOBS = INSTALL + '/jobs'
OUTPUT = Path('/mnt/downloads/EditorClipesDSS')
PC_SETUP_LOCK = threading.Lock()
PC_TARGETS = {
    'baodm22': ('desktop-baodm22', '100.73.112.48', 'farma'),
    'go3kspe': ('desktop-go3kspe', '100.91.215.92', 'jm03'),
}
PRIMARY_PC = 'baodm22'
PC_SLOTS = {target: threading.Lock() for target in PC_TARGETS}


def configured_pcs():
    if Path(__file__).with_name('.pc-go3kspe-pronto').is_file():
        return tuple(PC_TARGETS)
    return (PRIMARY_PC,)


def assigned_pc(folder):
    marker = folder/'pc-alvo'
    target = marker.read_text().strip() if marker.is_file() else PRIMARY_PC
    return target if target in PC_TARGETS else PRIMARY_PC

class PCUnavailable(ConnectionError):
    """The job may be taken over on the phone."""

    retomar = False


def connect_pc(target=PRIMARY_PC):
    alias, address, username = PC_TARGETS[target]
    suffix = 'desktop_' + target
    client = paramiko.SSHClient()
    client.load_host_keys(str(HOME/'.ssh'/('known_hosts_'+suffix)))
    try:
        connection = socket.create_connection((address,22), timeout=8)
    except OSError as exc:
        unreachable = {errno.ECONNREFUSED, errno.ECONNRESET, errno.ENETDOWN,
                       errno.ENETUNREACH, errno.EHOSTDOWN, errno.EHOSTUNREACH,
                       errno.ETIMEDOUT, errno.EADDRNOTAVAIL}
        if isinstance(exc, TimeoutError) or exc.errno in unreachable:
            raise PCUnavailable('PC indisponível pela rede.') from exc
        raise
    try:
        client.connect(alias, username=username, sock=connection,
                       key_filename=str(HOME/'.ssh'/('id_ed25519_'+suffix)),
                       look_for_keys=False, allow_agent=False, timeout=8,
                       banner_timeout=8, auth_timeout=8)
    except Exception:
        client.close()
        connection.close()
        raise
    client.get_transport().set_keepalive(10)
    return client

def open_pc_sftp(client, target=PRIMARY_PC):
    if target == PRIMARY_PC:
        return client.open_sftp()
    # This Windows installation closes subsystem channels after accepting them.
    # Use the same SFTP server over the authenticated SSH exec channel.
    channel = client.get_transport().open_session(timeout=10)
    channel.settimeout(15)
    try:
        channel.exec_command(r'"C:\Program Files\OpenSSH\sftp-server.exe"')
        return paramiko.SFTPClient(channel)
    except BaseException:
        channel.close()
        raise


def apply_pending_update(sftp):
    directory = Path(__file__).resolve().parent
    pending = directory/'.atualizar-cortes-no-pc'
    if not pending.is_file():
        return
    if sftp.listdir(JOBS):
        raise RuntimeError('O PC ainda está terminando um trabalho. Tente novamente após a limpeza.')
    files = {
        'momentos.py': directory/'momentos.py',
        'clipes.py': directory/'clipes.py',
        'ao_vivo.py': directory/'ao_vivo.py',
        'live_clock.py': directory/'live_clock.py',
        'maquina-de-cortes/scripts/cortar.py': Path('/opt/maquina-de-cortes/scripts/cortar.py'),
        'bridge.py': directory/'bridge.py',
        'pc_worker.py': directory/'pc_worker.py',
    }
    for name,source in files.items():
        target = INSTALL+'/'+name
        staged = target+'.editor-update'
        backup = target+'.editor-backup'
        sftp.put(str(source), staged)
        with sftp.open(staged,'rb') as downloaded:
            if hashlib.sha256(downloaded.read()).digest() != hashlib.sha256(source.read_bytes()).digest():
                raise RuntimeError('O PC recebeu um arquivo de atualização incompleto.')
        try:
            sftp.posix_rename(staged,target)
        except OSError:
            try:
                sftp.stat(target)
            except FileNotFoundError:
                # The prior update may have stopped after moving the old file to backup.
                pass
            else:
                try:
                    sftp.remove(backup)
                except FileNotFoundError:
                    pass
                sftp.rename(target,backup)
            try:
                sftp.rename(staged,target)
            except OSError:
                try:
                    sftp.rename(backup,target)
                except OSError:
                    pass
                raise
        try:
            sftp.remove(backup)
        except FileNotFoundError:
            pass
    pending.unlink()


# The paired Windows worker stops if its last lease is older than 180 seconds.
TAKEOVER_GRACE = 205
LEASE_INTERVAL = 8
CHECKPOINT_INTERVAL = 20
CHECKPOINT_LIMIT = 32 * 1024**2


def connection_lost(exc):
    if isinstance(exc, (paramiko.BadHostKeyException, paramiko.AuthenticationException)):
        return False
    return isinstance(exc, (PCUnavailable, EOFError, TimeoutError, paramiko.SSHException)) or (
        isinstance(exc, OSError) and exc.errno in {
            errno.EPIPE, errno.ECONNRESET, errno.ECONNABORTED, errno.ENOTCONN,
            errno.ENETDOWN, errno.ENETUNREACH, errno.EHOSTUNREACH, errno.ETIMEDOUT
        })


def atomic_bytes(path, data):
    temporary = path.with_suffix(path.suffix+'.incoming')
    temporary.write_bytes(data)
    temporary.replace(path)


def retire(folder, reason):
    if (folder/'pc-iniciado').exists():
        (folder/'pc-abandonado').touch()
    exc = PCUnavailable(reason)
    exc.retomar = True
    raise exc


def ask_pc_to_stop(sftp, job):
    try:
        with sftp.open(job+'/parar','w') as stop:
            stop.write('1')
    except FileNotFoundError:
        pass


def clean_retired(client, root, target=PRIMARY_PC):
    for marker in sorted(root.glob('*/pc-abandonado')):
        if assigned_pc(marker.parent) != target:
            continue
        name = marker.parent.name
        if not re.fullmatch(r'[a-f0-9]{32}',name):
            continue
        script = r"""
$ErrorActionPreference='Stop'
$job=Join-Path $env:LOCALAPPDATA 'EditorClipesDSS\jobs\JOB_ID'
if (Test-Path -LiteralPath $job) {
    $running=Get-CimInstance Win32_Process | Where-Object {
        $_.ProcessId -ne $PID -and $_.CommandLine -and
        ($_.CommandLine -match '[\\/]jobs[\\/]JOB_ID([\\/]|\b)' -or
         $_.CommandLine -match 'pc_worker\.py[^\r\n]*\bJOB_ID\b')
    }
    if ($running) { exit 3 }
    Remove-Item -LiteralPath $job -Recurse -Force
}
""".replace('JOB_ID',name)
        cmd = base64.b64encode(script.encode('utf-16-le')).decode()
        with client.get_transport().open_session(timeout=10) as channel:
            channel.settimeout(15)
            channel.exec_command('powershell.exe -NoProfile -NonInteractive -EncodedCommand '+cmd)
            limit = time.monotonic()+15
            while not channel.exit_status_ready() and time.monotonic()<limit:
                time.sleep(0.25)
            if channel.exit_status_ready() and channel.recv_exit_status()==0:
                marker.unlink(missing_ok=True)


def sync_checkpoint(sftp, job, folder, versions):
    dest = folder/'progresso-pc'
    for name in ('referencia.json','_parcial.jsonl','plano-retomada.json'):
        path = job+'/analise/'+name
        try:
            meta = sftp.stat(path)
        except FileNotFoundError:
            continue
        version = meta.st_size,meta.st_mtime
        if version == versions.get(name) or not 0 < meta.st_size <= CHECKPOINT_LIMIT:
            continue
        try:
            with sftp.open(path,'rb') as source:
                data = source.read(meta.st_size)
        except FileNotFoundError:
            continue
        if name == '_parcial.jsonl':
            data = data[:data.rfind(b'\n')+1]
        if data:
            dest.mkdir(exist_ok=True)
            atomic_bytes(dest/name,data)
            versions[name] = version


def copy_clip_metadata(sftp, clips, folder, name):
    relative = name+'.meta.json'
    try:
        with sftp.open(clips+'/'+relative,'rb') as remote:
            data=remote.read(65537)
    except FileNotFoundError:
        return
    if len(data) > 65536:
        return
    try:
        json.loads(data)
    except ValueError:
        return
    directory=folder/'detalhes'
    directory.mkdir(exist_ok=True)
    atomic_bytes(directory/relative,data)


def sync_marks(sftp, job, folder, previous):
    path=folder/'marcacoes.json'
    if not path.is_file():
        return previous
    data=path.read_bytes()
    if data == previous:
        return previous
    target=job+'/marcacoes.json'
    staged=target+'.incoming'
    with sftp.open(staged,'wb') as f:
        f.write(data)
    try:
        sftp.posix_rename(staged,target)
    except OSError:
        try:
            sftp.remove(target)
        except FileNotFoundError:
            pass
        sftp.rename(staged,target)
    return data


def collect_clips(sftp, job, folder, output, state, renew, spec, checkpoint):
    clips = job+'/resultados/'+folder.name
    try:
        names = sftp.listdir(clips)
    except FileNotFoundError:
        return
    for name in sorted(names):
        if not name.endswith('.mp4') or '/' in name or '\\' in name or (output/name).is_file():
            continue
        if spec.get('modo') != 'ao_vivo':
            checkpoint()
        temp = output/(name+'.recebendo')
        try:
            expected = sftp.stat(clips+'/'+name).st_size
            sftp.get(clips+'/'+name,str(temp),callback=renew)
            if not expected or temp.stat().st_size != expected:
                raise RuntimeError('O PC retornou uma transferência incompleta.')
            copy_clip_metadata(sftp,clips,folder,name)
            temp.replace(output/name)
            if spec.get('modo') == 'ao_vivo':
                try:
                    sftp.remove(clips+'/'+name)
                    sftp.remove(clips+'/'+name+'.meta.json')
                except OSError:
                    pass
            state(folder,'Recebendo clipes do PC',prontos=len(list(output.glob('*.mp4'))))
        finally:
            temp.unlink(missing_ok=True)


def worker_available(sftp, job, check_stop, folder):
    heartbeat = job+'/worker-vivo'
    try:
        with sftp.open(job+'/estado.json') as f:
            if json.load(f).get('status') in ('done','error','cancelled'):
                return True
    except FileNotFoundError:
        pass
    try:
        before = sftp.stat(heartbeat).st_mtime
        for _ in range(4):
            check_stop(folder)
            time.sleep(1)
            if sftp.stat(heartbeat).st_mtime != before:
                return True
    except FileNotFoundError:
        pass
    return False


def execute(folder, spec, state, check_stop):
    started = folder/'pc-iniciado'
    sticky = started.exists() or (folder/'pc-abandonado').exists()
    choices = (assigned_pc(folder),) if sticky else configured_pcs()
    unavailable = {}
    while len(unavailable) < len(choices):
        check_stop(folder)
        for target in choices:
            slot = PC_SLOTS[target]
            if target in unavailable or not slot.acquire(blocking=False):
                continue
            try:
                if not sticky:
                    atomic_bytes(folder/'pc-alvo', target.encode())
                return execute_on_pc(folder, spec, state, check_stop, target)
            except PCUnavailable as exc:
                if started.exists() or sticky or exc.retomar:
                    raise
                unavailable[target] = exc
            finally:
                slot.release()
        if len(unavailable) < len(choices):
            state(folder, 'Aguardando um PC livre', status='queued', motor_ativo='pc', erro=None)
            time.sleep(1)
    raise next(reversed(unavailable.values()))


def execute_on_pc(folder, spec, state, check_stop, target):
    alias = PC_TARGETS[target][0]
    state(folder,'Conectando ao PC',status='running',motor_ativo='pc',pc_nome=alias,erro=None)
    output = OUTPUT/folder.name
    output.mkdir(parents=True, exist_ok=True)
    log = folder/'processo.log'
    job = JOBS+'/'+folder.name
    started = folder/'pc-iniciado'
    lease = folder/'pc-ultima-conexao'
    if started.exists() and not lease.exists():
        lease.touch()
    last_renewal = -float('inf')
    last_checkpoint = -float('inf')
    versions = {}
    last_marks = None
    offset = 0

    def renew(sftp, *_):
        nonlocal last_renewal
        now = time.monotonic()
        if now-last_renewal >= LEASE_INTERVAL:
            # Record a possible remote renewal before writing to the network.
            lease.touch()
            with sftp.open(job+'/lease','w') as heartbeat:
                heartbeat.write('1')
            last_renewal = now

    def ensure_active():
        if started.exists() and (folder/'parar').exists():
            (folder/'pc-abandonado').touch()
        check_stop(folder)

    reconnecting = started.exists()
    while True:
        ensure_active()
        if reconnecting and time.time()-lease.stat().st_mtime >= TAKEOVER_GRACE:
            retire(folder,'O PC não voltou dentro do prazo.')
        try:
            with (connect_pc() if target == PRIMARY_PC else connect_pc(target)) as client, open_pc_sftp(client, target) as sftp:
                sftp.get_channel().settimeout(15)
                channel = None
                try:
                    ensure_active()
                    if reconnecting:
                        try:
                            sftp.stat(job)
                        except FileNotFoundError:
                            retire(folder,'O trabalho foi encerrado no PC.')
                        if not worker_available(sftp,job,check_stop,folder):
                            raise EOFError('Aguardando confirmação do processo no PC.')
                        if time.time()-lease.stat().st_mtime >= TAKEOVER_GRACE:
                            retire(folder,'O prazo de reconexão expirou.')
                    else:
                        with PC_SETUP_LOCK:
                            clean_retired(client,folder.parent,target)
                            if target == PRIMARY_PC:
                                apply_pending_update(sftp)
                        ensure_active()
                        sftp.mkdir(job)
                        try:
                            renew(sftp)
                            settings = dict(spec,logo='')
                            with sftp.open(job+'/pedido.json','w') as f:
                                f.write(json.dumps(settings))
                            sftp.put('/root/.maquina-de-cortes/licenca.json',job+'/licenca.json')

                            def upload_progress(*_):
                                ensure_active()
                                renew(sftp)

                            for name in ('logo.png','entrada.mp4'):
                                if (folder/name).is_file():
                                    sftp.put(str(folder/name),job+'/'+name,callback=upload_progress)
                            renew(sftp)
                            channel = client.get_transport().open_session(timeout=10)
                            channel.set_combine_stderr(True)
                            started.touch()
                            cmd = f'powershell.exe -NoProfile -NonInteractive -Command "& (Join-Path $env:LOCALAPPDATA \'EditorClipesDSS/run.cmd\') {folder.name}"'
                            channel.exec_command(cmd)
                        except BaseException:
                            if not started.exists():
                                try:
                                    for name in sftp.listdir(job):
                                        sftp.remove(job+'/'+name)
                                    sftp.rmdir(job)
                                except OSError:
                                    (folder/'pc-abandonado').touch()
                            raise
                    reconnecting = False
                    while True:
                        if spec.get('modo') == 'ao_vivo':
                            last_marks = sync_marks(sftp,job,folder,last_marks)
                        if (folder/'parar').exists():
                            ask_pc_to_stop(sftp,job)
                            ensure_active()
                        # The Windows relay must be drained; the SFTP log also works after reconnect.
                        if channel is not None and channel.recv_ready():
                            channel.recv(65536)
                        try:
                            with sftp.open(job+'/processo.log','rb') as report:
                                report.seek(offset)
                                data = report.read(65536)
                            if data:
                                with log.open('ab') as local:
                                    local.write(data)
                                offset += len(data)
                        except FileNotFoundError:
                            pass
                        try:
                            with sftp.open(job+'/estado.json') as f:
                                remote = json.load(f)
                        except FileNotFoundError:
                            remote = None
                            try:
                                sftp.stat(job)
                            except FileNotFoundError:
                                retire(folder,'O trabalho foi encerrado no PC.')
                        if remote:
                            live = spec.get('modo') == 'ao_vivo'
                            terminal = remote.get('status') in ('done','error','cancelled')
                            state(folder,remote['fase']+' no PC',total=remote.get('total',spec['quantidade']))
                            if not live and (terminal or time.monotonic()-last_checkpoint >= CHECKPOINT_INTERVAL):
                                sync_checkpoint(sftp,job,folder,versions)
                                last_checkpoint = time.monotonic()
                            collect_clips(sftp,job,folder,output,state,lambda *_: renew(sftp),spec,
                                          lambda: sync_checkpoint(sftp,job,folder,versions))
                            if terminal:
                                received = len(list(output.glob('*.mp4')))
                                if remote['status']=='cancelled' and not (folder/'parar').exists():
                                    try:
                                        with sftp.open(job+'/recebido','w') as f:
                                            f.write('1')
                                    except OSError:
                                        pass
                                    retire(folder,'O processo do PC foi interrompido.')
                                if remote['status']=='done' and received < remote['prontos']:
                                    raise RuntimeError('Transferência incompleta.')
                                # All final files are already on the phone before confirming receipt.
                                started.unlink(missing_ok=True)
                                (folder/'pc-abandonado').touch()
                                if remote['status']=='error':
                                    state(folder,'Falha no processamento no PC',status='error',
                                          prontos=received,erro=remote.get('erro') or 'Falha no processamento no PC.')
                                else:
                                    state(folder,remote['fase'],status=remote['status'],prontos=received,erro=None)
                                    shutil.rmtree(folder/'progresso-pc',ignore_errors=True)
                                try:
                                    with sftp.open(job+'/recebido','w') as f:
                                        f.write('1')
                                except OSError:
                                    pass
                                else:
                                    for _ in range(12):
                                        try:
                                            sftp.stat(job)
                                        except FileNotFoundError:
                                            (folder/'pc-abandonado').unlink(missing_ok=True)
                                            break
                                        except OSError:
                                            break
                                        time.sleep(0.5)
                                return
                        if channel is not None and channel.exit_status_ready():
                            raise EOFError('A sessão do PC terminou antes do estado final.')
                        renew(sftp)
                        time.sleep(2)
                except BaseException as exc:
                    if started.exists() and (folder/'parar').exists():
                        try:
                            ask_pc_to_stop(sftp,job)
                        except OSError:
                            pass
                        (folder/'pc-abandonado').touch()
                    if not connection_lost(exc) and started.exists():
                        (folder/'pc-abandonado').touch()
                        try:
                            ask_pc_to_stop(sftp,job)
                        except OSError:
                            pass
                    raise
        except Exception as exc:
            ensure_active()
            if isinstance(exc,PCUnavailable) and exc.retomar:
                raise
            if not connection_lost(exc):
                raise
            if not started.exists():
                raise PCUnavailable('PC indisponível antes de começar.') from exc
            reconnecting = True
            remaining = max(0,int(TAKEOVER_GRACE-(time.time()-lease.stat().st_mtime)))
            state(folder,f'Reconectando ao PC · Termux assume em até {remaining} s',status='running',erro=None)
            for _ in range(min(5,remaining)):
                ensure_active()
                time.sleep(1)
