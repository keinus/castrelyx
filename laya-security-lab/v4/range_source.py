"""Bounded, cached HTTP range reads for explicitly labelled pilot subsets."""
import io, json, hashlib, urllib.request, time
from pathlib import Path

class RangeFile(io.RawIOBase):
    def __init__(self, url, size, cache, block_size=1 << 20, budget=512 << 20):
        super().__init__(); self.url=url; self.size=size; self.position=0
        self.cache=Path(cache); self.cache.mkdir(parents=True,exist_ok=True)
        self.block_size=block_size; self.budget=budget; self.downloaded=0
    def readable(self): return True
    def seekable(self): return True
    def tell(self): return self.position
    def seek(self, offset, whence=0):
        value=offset if whence==0 else self.position+offset if whence==1 else self.size+offset
        if value<0: raise ValueError('Negative seek')
        self.position=value; return value
    def _block(self, number):
        start=number*self.block_size; end=min(self.size,start+self.block_size)-1
        path=self.cache/f'{number:08d}.bin'; expected=end-start+1
        if path.exists() and path.stat().st_size==expected: return path.read_bytes()
        if self.downloaded+expected>self.budget: raise RuntimeError('Pilot network budget exceeded')
        for attempt in range(3):
            try:
                req=urllib.request.Request(self.url,headers={'Range':f'bytes={start}-{end}','User-Agent':'LayaPublicBenchmark/4.0'})
                with urllib.request.urlopen(req,timeout=60) as response:
                    if response.status!=206 or response.headers.get('Content-Range')!=f'bytes {start}-{end}/{self.size}':
                        raise ValueError('Server did not honor exact byte range')
                    data=response.read(expected+1)
                if len(data)!=expected: raise ValueError('Incomplete byte range')
                path.write_bytes(data); self.downloaded+=len(data); return data
            except (OSError,TimeoutError):
                if attempt==2: raise
                time.sleep(1)
    def read(self, size=-1):
        size=self.size-self.position if size<0 else min(size,self.size-self.position)
        if size<=0: return b''
        result=[]
        while size:
            number, offset=divmod(self.position,self.block_size); block=self._block(number)
            count=min(size,len(block)-offset); result.append(block[offset:offset+count]); self.position+=count; size-=count
        return b''.join(result)
    def readinto(self, buffer):
        data=self.read(len(buffer));buffer[:len(data)]=data;return len(data)
    def manifest(self):
        return {'url':self.url,'source_size':self.size,'verification':'Individual cached ranges SHA256; whole publisher checksum NOT verified in pilot',
                'ranges':[{'file':p.name,'bytes':p.stat().st_size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p in sorted(self.cache.glob('*.bin'))]}

def public_file(record_id, filename, cache):
    with urllib.request.urlopen('https://zenodo.org/api/records/'+record_id,timeout=30) as response: record=json.load(response)
    if record['metadata']['access_right']!='open': raise PermissionError('Dataset is not publicly accessible')
    entry=next(f for f in record['files'] if f['key']==filename)
    root=Path(cache); root.mkdir(parents=True,exist_ok=True)
    (root/'record.json').write_text(json.dumps(record,indent=2),encoding='utf8')
    remote=RangeFile(entry['links']['self'],entry['size'],root/entry['checksum'].replace(':','_'))
    return remote,entry
