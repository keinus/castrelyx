#!/usr/bin/env python3
"""Install the reviewed HTTPS endpoint on server 21; preserve other services."""
import datetime
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import ssl
import subprocess
import time
import urllib.request
import yaml

assert os.geteuid() == 0, 'Run as root on server 21'
os.umask(0o077)
repo = Path('/home/keinus/castrelyx')
override = repo / 'docker-compose.override.yml'
source = Path(__file__).resolve().parent
root = Path('/etc/castrelyx/manager-https')
tls = root / 'tls'
image = 'nginx@sha256:0985e772fb9f729e6fa0980da05fca5d9c468e870eed43071545afa9d2e27d94'

def run(args, **kw):
    return subprocess.run(args, check=True, cwd=repo, **kw)

def config(extra=None):
    args = ['docker', 'compose']
    if extra:
        args += ['-f', str(repo / 'docker-compose.yml'), '-f', str(extra)]
    return json.loads(run(args + ['config', '--format', 'json'], capture_output=True, text=True).stdout)

with socket.socket() as probe:
    probe.bind(('0.0.0.0', 443))
original = override.read_bytes()
original_hash = hashlib.sha256(original).digest()
before = config()
stamp = datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=9))).strftime('%Y%m%d-%H%M%S')
release = Path('/home/keinus/.codex-releases') / ('manager-https-' + stamp)
release.mkdir(mode=0o700)
shutil.copy2(override, release / 'docker-compose.override.before.yml')
shutil.copy2(repo / 'docker-compose.yml', release / 'docker-compose.before.yml')
shutil.copy2(source / 'nginx.conf', release / 'nginx.conf')
print('BACKUP', release, flush=True)

assert not root.exists(), 'HTTPS directory already exists; review it before reinstalling'
tls.mkdir(mode=0o700, parents=True)
shutil.copy2(source / 'nginx.conf', root / 'nginx.conf')
os.chmod(root / 'nginx.conf', 0o644)
ext = tls / 'server.ext'
ext.write_text('basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\nsubjectAltName=DNS:castrelyx-manager.internal,IP:192.168.50.21\n')
run(['openssl', 'req', '-new', '-newkey', 'rsa:3072', '-nodes', '-subj', '/CN=castrelyx-manager.internal', '-keyout', str(tls / 'server.key'), '-out', str(tls / 'server.csr')], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
run(['openssl', 'x509', '-req', '-in', str(tls / 'server.csr'), '-CA', '/etc/castrelyx/router/certs/ca.crt', '-CAkey', '/etc/castrelyx/router/private/ca.key', '-set_serial', '0x' + secrets.token_hex(16), '-days', '365', '-sha256', '-extfile', str(ext), '-out', str(tls / 'server.crt')], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
shutil.copy2('/etc/castrelyx/router/certs/ca.crt', tls / 'ca.crt')
(tls / 'fullchain.pem').write_bytes((tls / 'server.crt').read_bytes() + (tls / 'ca.crt').read_bytes())
run(['openssl', 'verify', '-CAfile', str(tls / 'ca.crt'), '-purpose', 'sslserver', '-verify_ip', '192.168.50.21', str(tls / 'server.crt')])

class PortBindings(list):
    pass

yaml.SafeDumper.add_representer(PortBindings, lambda dumper, data: dumper.represent_sequence('!override', data))
data = yaml.safe_load(original)
manager = data['services']['manager']
manager.setdefault('environment', {}).update({'MANAGER_PUBLIC_BASE_URL': 'https://192.168.50.21', 'SERVER_FORWARD_HEADERS_STRATEGY': 'native'})
manager['ports'] = PortBindings(['127.0.0.1:8780:8780'])
data['services']['manager-https'] = {
    'image': image, 'entrypoint': ['nginx'], 'command': ['-g', 'daemon off;'],
    'restart': 'unless-stopped', 'depends_on': {'manager': {'condition': 'service_started'}},
    'ports': ['443:443'],
    'volumes': [str(root / 'nginx.conf') + ':/etc/nginx/nginx.conf:ro', str(tls) + ':/etc/nginx/tls:ro'],
    'read_only': True, 'tmpfs': ['/var/cache/nginx:size=32m', '/var/run:size=1m'],
    'security_opt': ['no-new-privileges:true'],
    'logging': {'driver': 'json-file', 'options': {'max-size': '10m', 'max-file': '3'}},
}
candidate = release / 'docker-compose.override.candidate.yml'
candidate.write_text(yaml.safe_dump(data, sort_keys=False))
after = config(candidate)
for name, service in before['services'].items():
    if name != 'manager':
        assert after['services'][name] == service, 'Unexpected change to ' + name
assert after['services']['manager']['image'] == before['services']['manager']['image']
ports = after['services']['manager']['ports']
assert len(ports) == 1 and ports[0]['host_ip'] == '127.0.0.1' and ports[0]['published'] == '8780'
run(['docker', 'run', '--rm', '--network', 'none', '--entrypoint', 'nginx', '--read-only', '--tmpfs', '/var/run', '--tmpfs', '/var/cache/nginx', '-v', str(root / 'nginx.conf') + ':/etc/nginx/nginx.conf:ro', '-v', str(tls) + ':/etc/nginx/tls:ro', image, '-t'])
assert hashlib.sha256(override.read_bytes()).digest() == original_hash, 'Deployment configuration changed concurrently'
staged = override.with_suffix('.yml.https-new')
staged.write_bytes(candidate.read_bytes())
os.chmod(staged, override.stat().st_mode & 0o777)
os.chown(staged, override.stat().st_uid, override.stat().st_gid)
os.replace(staged, override)
try:
    run(['docker', 'compose', 'up', '-d', '--no-deps', '--no-build', '--pull', 'never', 'manager', 'manager-https'])
    ctx = ssl.create_default_context(cafile=str(tls / 'ca.crt'))
    for attempt in range(60):
        try:
            with urllib.request.urlopen('https://192.168.50.21/api/setup/status', context=ctx, timeout=5) as res:
                assert res.status == 200 and json.load(res)['required'] is False
            with urllib.request.urlopen('https://192.168.50.21/', context=ctx, timeout=5) as res:
                assert 'Castrelyx' in res.read().decode()
            break
        except Exception:
            if attempt == 59:
                raise
            time.sleep(2)
    print('HTTPS_READY https://192.168.50.21', flush=True)
    print('BACKUP', release, flush=True)
    (release / 'deployment.json').write_text(json.dumps({'url': 'https://192.168.50.21', 'image': image, 'oldPublicPort': 8780, 'newPublicPort': 443, 'backup': str(release)}, indent=2))
except Exception:
    subprocess.run(['docker', 'compose', 'stop', 'manager-https'], cwd=repo, check=False)
    override.write_bytes(original)
    run(['docker', 'compose', 'up', '-d', '--no-deps', '--no-build', '--pull', 'never', 'manager'])
    print('ROLLBACK: previous Manager endpoint restored', flush=True)
    raise
