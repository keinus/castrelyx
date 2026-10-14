"""Private SSH transport; password stays in memory and is never saved."""
import getpass, json, sys, pathlib, paramiko, time

client=paramiko.SSHClient()
client.load_host_keys(str(pathlib.Path.home()/'.ssh/known_hosts'))
client.set_missing_host_key_policy(paramiko.RejectPolicy())
password=getpass.getpass('SSH password: ')
client.connect('192.168.50.20',username='keinus',password=password,timeout=15,allow_agent=False,look_for_keys=False)
password=None
client.get_transport().set_keepalive(30)
print('READY',flush=True)
for line in sys.stdin:
    try:
        task=json.loads(line)
        op=task['op']
        if op=='exit':break
        if op=='exec':
            sin,sout,serr=client.exec_command(task['command'],timeout=task.get('timeout',120))
            sin.close()
            channel=sout.channel
            while not channel.exit_status_ready() or channel.recv_ready() or channel.recv_stderr_ready():
                if channel.recv_ready(): print(channel.recv(65536).decode('utf8','replace'),end='',flush=True)
                if channel.recv_stderr_ready(): print(channel.recv_stderr(65536).decode('utf8','replace'),end='',flush=True)
                time.sleep(.02)
            print('\nEXIT',channel.recv_exit_status(),flush=True)
        elif op in ('put','get'):
            with client.open_sftp() as sftp:
                if op=='put': sftp.put(task['local'],task['remote'])
                else: sftp.get(task['remote'],task['local'])
            print('TRANSFER_OK',flush=True)
        elif op=='sync':
            root=pathlib.Path(task['local']); remote=task['remote'].rstrip('/')
            with client.open_sftp() as sftp:
                done=0
                for path in root.rglob('*'):
                    rel=path.relative_to(root)
                    if any(x in {'.tools','.venv','.git','.cache','__pycache__'} for x in rel.parts):continue
                    dest=remote+'/'+rel.as_posix()
                    if path.is_dir():
                        try:sftp.mkdir(dest)
                        except IOError:pass
                    elif path.is_file():
                        try:
                            if sftp.stat(dest).st_size==path.stat().st_size and not task.get('force'):continue
                        except IOError:pass
                        sftp.put(str(path),dest);done+=1
            print('SYNC_OK',done,flush=True)
        else:raise ValueError('Unknown operation')
    except Exception as exc: print(type(exc).__name__,str(exc),flush=True)
client.close()
