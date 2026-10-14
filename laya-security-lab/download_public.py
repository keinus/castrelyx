"""Download immutable source logs; never execute archive contents."""
from pathlib import Path
import concurrent.futures, hashlib, json, urllib.request, zipfile

ROOT = Path(__file__).resolve().parent
REV = 'd9d40ef123d2c87d5d3df28c96bcab4f0faccc87'
BASE = f'https://raw.githubusercontent.com/OTRF/Security-Datasets/{REV}/'

def get(path):
    dest=ROOT/'data/raw/otrf'/path
    dest.parent.mkdir(parents=True,exist_ok=True)
    if not dest.exists():
        tmp=dest.with_suffix(dest.suffix+'.part')
        with urllib.request.urlopen(BASE+path,timeout=60) as src, tmp.open('wb') as out:
            while chunk:=src.read(1024*1024): out.write(chunk)
        tmp.replace(dest)
    if dest.suffix=='.zip':
        # These two approved archives contain JSON logs only. No arbitrary extraction.
        with zipfile.ZipFile(dest) as archive:
            for member in archive.infolist():
                if member.filename.endswith('.json') and not member.is_dir():
                    target=dest.parent/Path(member.filename).name
                    if not target.exists():
                        with archive.open(member) as src,target.open('wb') as out:
                            while chunk:=src.read(1024*1024): out.write(chunk)
    print(path, dest.stat().st_size, flush=True)
    return {'path':str(dest.relative_to(ROOT)), 'url':BASE+path,
            'bytes':dest.stat().st_size,'sha256':hashlib.sha256(dest.read_bytes()).hexdigest()}

def main():
    with urllib.request.urlopen(f'https://api.github.com/repos/OTRF/Security-Datasets/git/trees/{REV}?recursive=1') as r:
        tree=json.load(r)['tree']
    paths=['LICENSE','README.md']
    for f in tree:
        p=f['path']
        if not p.startswith('datasets/compound/apt29/') or f['type']!='blob': continue
        if p.endswith('README.md') or '/individual_zeek_logs/' in p or p.endswith('.xlsx') or p.endswith('_manual.zip'):
            paths.append(p)
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        files=list(pool.map(get,paths))
    manifest={'source':'OTRF/Security-Datasets','revision':REV,'files':files,
              'note':'Public adversary-emulation logs. Response labels are not provided by the publisher.'}
    (ROOT/'data/raw/otrf_manifest.json').write_text(json.dumps(manifest,indent=2),encoding='utf-8')

if __name__=='__main__': main()
