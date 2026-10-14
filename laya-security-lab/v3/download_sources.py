"""Download only publisher text logs and README files; never malware binaries."""
from pathlib import Path
import concurrent.futures,urllib.request,hashlib,json,time
ROOT=Path(__file__).resolve().parent
BASE='https://mcfp.felk.cvut.cz/publicDatasets/IoT-23-Dataset/IndividualScenarios/'
CAPTURES={f'CTU-IoT-Malware-Capture-{n}-1':family for n,family in [(1,'HideAndSeek'),(3,'Muhstik'),(34,'Mirai'),(44,'Mirai'),(8,'Hakai'),(20,'Torii'),(21,'Torii'),(42,'Trojan')]}
CAPTURES.update({f'CTU-Honeypot-Capture-{n}-1':'BenignDevice' for n in (4,5,7)})
def get(capture):
    files=[]
    for name in (['bro/conn.log.labeled'] if capture=='CTU-Honeypot-Capture-7-1' else ['README.md','bro/conn.log.labeled']):
        sub='Somfy-01/' if capture=='CTU-Honeypot-Capture-7-1' else ''
        url=BASE+capture+'/'+sub+name;dest=ROOT/'data/raw/zeek'/capture/name;dest.parent.mkdir(parents=True,exist_ok=True)
        if not dest.exists():
            error=None
            for attempt in range(3):
                try:
                    with urllib.request.urlopen(url,timeout=60) as src,dest.with_suffix('.part').open('wb') as out:
                        while chunk:=src.read(1048576):out.write(chunk)
                    dest.with_suffix('.part').replace(dest);error=None;break
                except Exception as exc:error=exc;time.sleep(1)
            if error:raise error
        h=hashlib.sha256()
        with dest.open('rb') as f:
            for b in iter(lambda:f.read(1048576),b''):h.update(b)
        files.append({'path':dest.relative_to(ROOT).as_posix(),'url':url,'sha256':h.hexdigest(),'bytes':dest.stat().st_size})
    print(json.dumps({'capture':capture,'files':files}),flush=True)
    return {'capture':capture,'family':CAPTURES[capture],'files':files}
def main():
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:rows=list(pool.map(get,CAPTURES))
    (ROOT/'data/source_manifest.json').write_text(json.dumps({'publisher':'Stratosphere Laboratory IoT-23','retrieved_date_kst':'2026-10-05','captures':rows},indent=2))
    print('DOWNLOAD_COMPLETE',flush=True)
if __name__=='__main__':main()
