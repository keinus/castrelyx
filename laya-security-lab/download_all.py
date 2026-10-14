"""Reproduce the pinned public inputs. Run on a new checkout before setup_remote.sh."""
from pathlib import Path
import argparse,hashlib,json,shutil,subprocess,sys,urllib.request,zipfile

ROOT=Path(__file__).resolve().parent
MODEL_REV='7b928d828b7b0e022f929d9bd2e44165aa270148'
CIC_REV='70bac6246d99cf046186a02e1cce6883e2ffe7ea'
LAYA_REV='2e4d9c87e8b1621deb344eac7de5c7258f32f849'
HOST_REV='de71d5a71e774489013e9be8e08894ff9873bd4f'
HOST_SHA='dab3e1810341d7bdbb47c1ca86adfa0af511dd6ee18b667d3c1cc791eec34007'

def sha(path):
    h=hashlib.sha256()
    with path.open('rb') as f:
        for chunk in iter(lambda:f.read(1024*1024),b''):h.update(chunk)
    return h.hexdigest()

def main():
    ap=argparse.ArgumentParser();ap.add_argument('--skip-runtime',action='store_true',help='Use an already supplied vendor/laya tree');args=ap.parse_args()
    hf=shutil.which('hf') or str(Path(sys.executable).with_name('hf.exe' if sys.platform=='win32' else 'hf'))
    subprocess.run([hf,'download','convaiinnovations/laya','--revision',MODEL_REV,'--local-dir',str(ROOT/'models/base'),'--include','model.safetensors','rl_agent_config.json','encoder/config.json','tokenizer/*','README.md'],check=True)
    subprocess.run([hf,'download','bvsam/cic-ids-2017','--repo-type','dataset','--revision',CIC_REV,'--local-dir',str(ROOT/'data/raw/cicids2017'),'--include','traffic_labels/*','README.md'],check=True)
    subprocess.run([sys.executable,str(ROOT/'download_public.py')],check=True)
    folder=ROOT/'data/raw/otrf_historical';folder.mkdir(parents=True,exist_ok=True)
    archive=folder/'evals_apt29_day1_manual.zip'
    url=f'https://raw.githubusercontent.com/OTRF/detection-hackathon-apt29/{HOST_REV}/datasets/day1/evals_apt29_day1_manual.zip'
    if not archive.exists():
        temp=archive.with_suffix('.part')
        with urllib.request.urlopen(url,timeout=120) as source,temp.open('wb') as out:shutil.copyfileobj(source,out)
        temp.replace(archive)
    if sha(archive)!=HOST_SHA:raise ValueError('Historical host archive checksum mismatch')
    with zipfile.ZipFile(archive) as z:
        for member in z.infolist():
            if member.filename.endswith('.json') and not member.is_dir():
                target=folder/Path(member.filename).name
                with z.open(member) as source,target.open('wb') as out:shutil.copyfileobj(source,out)
    (folder/'manifest.json').write_text(json.dumps({'url':url,'revision':HOST_REV,'sha256':HOST_SHA},indent=2))
    vendor=ROOT/'vendor/laya'
    if not args.skip_runtime:
        if not vendor.exists():
            vendor.parent.mkdir(exist_ok=True)
            subprocess.run(['git','clone','https://github.com/NandhaKishorM/laya.git',str(vendor)],check=True)
        if not (vendor/'.git').exists():raise ValueError('Existing vendor tree has no Git metadata; use --skip-runtime for the bundled verified copy')
        current=subprocess.check_output(['git','-C',str(vendor),'rev-parse','HEAD'],text=True).strip()
        if current!=LAYA_REV:
            if subprocess.check_output(['git','-C',str(vendor),'status','--porcelain'],text=True).strip():raise ValueError('Refusing to overwrite changes in vendor/laya')
            subprocess.run(['git','-C',str(vendor),'checkout','--detach',LAYA_REV],check=True)
    print('PINNED_DOWNLOADS_COMPLETE')

if __name__=='__main__':main()
