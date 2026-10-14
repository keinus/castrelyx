"""Acquire public network-only artifacts; verify publisher checksums before use."""
import argparse, hashlib, json, os, time, urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent
RECORDS = {'lspr23': '8042347', 'lspr24': '14900873'}
FILES = {'lspr23': ['ls23pr_flows.zip', 'ls23pr_attacknarratives_v1.json'],
         'lspr24': ['lspr24_v2.parquet', 'attack_narratives.json']}

def digest(path, algorithm='sha256'):
    h = hashlib.new(algorithm)
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(8 << 20), b''): h.update(block)
    return h.hexdigest()

def request(url):
    return urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent': 'LayaPublicBenchmark/4.0'}), timeout=60)

def download(url, path, checksum=None, size=None):
    path = Path(path); path.parent.mkdir(parents=True, exist_ok=True)
    algorithm, expected = checksum.split(':', 1) if checksum else ('sha256', None)
    if path.exists() and (size is None or path.stat().st_size == size) and (expected is None or digest(path, algorithm) == expected):
        return {'path': str(path), 'bytes': path.stat().st_size, 'sha256': digest(path)}
    temporary = path.with_suffix(path.suffix + '.part')
    with request(url) as response, temporary.open('wb') as out:
        if 'text/html' in response.headers.get('Content-Type', ''):
            raise RuntimeError('Download returned HTML, possibly a registration form: ' + url)
        received = 0; last = time.monotonic()
        while True:
            block = response.read(8 << 20)
            if not block: break
            out.write(block); received += len(block)
            if time.monotonic() - last > 15:
                print(json.dumps({'file': path.name, 'received_bytes': received, 'expected_bytes': size}), flush=True); last = time.monotonic()
    if size is not None and temporary.stat().st_size != size: raise ValueError('Download size mismatch')
    if expected is not None and digest(temporary, algorithm) != expected: raise ValueError('Publisher checksum mismatch')
    os.replace(temporary, path)
    return {'path': str(path), 'bytes': path.stat().st_size, 'sha256': digest(path)}

def main():
    parser = argparse.ArgumentParser(); parser.add_argument('--datasets', nargs='+', choices=list(RECORDS), default=list(RECORDS)); args = parser.parse_args()
    root = ROOT / 'data/raw'; root.mkdir(parents=True, exist_ok=True)
    for name in args.datasets:
        with request('https://zenodo.org/api/records/' + RECORDS[name]) as response: record = json.load(response)
        if record['metadata']['access_right'] != 'open': raise PermissionError('Record is not open')
        (root / (name + '_record.json')).write_text(json.dumps(record, indent=2), encoding='utf8')
        available = {entry['key']: entry for entry in record['files']}; manifest = []
        for filename in FILES[name]:
            entry = available[filename]
            url = entry['links']['self']
            result = download(url, root / name / filename, entry['checksum'], entry['size'])
            manifest.append(dict(result, url=url, publisher_checksum=entry['checksum'], record_id=RECORDS[name]))
            print(json.dumps({'downloaded': name, 'file': filename, 'bytes': result['bytes']}), flush=True)
        (root / (name + '_manifest.json')).write_text(json.dumps(manifest, indent=2), encoding='utf8')

if __name__ == '__main__': main()
