"""Fetch the public UQ v2 flow data; no private/host logs and no signed URLs in logs."""
import hashlib, json, shutil, urllib.request, zipfile
from pathlib import Path
from common import ROOT, digest, write_json

DATASET_ID='8c6e2a00-ef9c-11ed-827d-e762de186848'
EXPECTED_CSV='05019e3ac8d55b3f074f9f042623bbbb1af2527b8b78558c69353ccb2ccefa3f'
EXPECTED_ZIP='0e42c11dc9799a1e0851ff0b98c2ed1b47c56331a6eb99ba14c800cfe94d03ed'

def main():
    raw=ROOT/'data/raw';raw.mkdir(parents=True,exist_ok=True)
    target=raw/'NF-UNSW-NB15-v2.csv';archive=raw/'NF-UNSW-NB15-v2.zip'
    api=f'https://api.rdm.uq.edu.au/datasets/{DATASET_ID}/download'
    if not target.exists():
        if not archive.exists():
            with urllib.request.urlopen(api,timeout=60) as r:metadata=json.load(r)
            if metadata['accessType']!='OPEN':raise PermissionError('Expected a public OPEN dataset')
            partial=archive.with_suffix('.part')
            with urllib.request.urlopen(metadata['url'],timeout=120) as r,open(partial,'wb') as f:shutil.copyfileobj(r,f)
            if digest(partial)!=EXPECTED_ZIP:raise ValueError('Official archive changed; inspect before proceeding')
            partial.replace(archive)
        if digest(archive)!=EXPECTED_ZIP:raise ValueError('Archive checksum mismatch')
        with zipfile.ZipFile(archive) as z:
            name=next(n for n in z.namelist() if n.endswith('/data/NF-UNSW-NB15-v2.csv'))
            with z.open(name) as src,open(target,'wb') as dst:shutil.copyfileobj(src,dst)
    if digest(target)!=EXPECTED_CSV:raise ValueError('CSV checksum mismatch')
    with open(target,'rb') as f:sha1=hashlib.file_digest(f,'sha1').hexdigest()
    assert sha1=='dde9a9e38009ec60121cbb4fd884bf2c7cf5c0b0'
    write_json(raw/'source.json',dict(dataset_id=DATASET_ID,public_download_api=api,csv_sha256=EXPECTED_CSV,
        archive_sha256=EXPECTED_ZIP,publisher_bagit_sha1=sha1,access='OPEN'))
    print('VERIFIED',target)

if __name__=='__main__':main()
