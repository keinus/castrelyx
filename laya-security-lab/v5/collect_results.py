"""One-shot local result collection for this running experiment; no stored credentials."""
import getpass, hashlib, io, json, os, time, zipfile
from datetime import datetime, timezone
from pathlib import Path
import paramiko

ROOT=Path(__file__).resolve().parent
REMOTE='/home/keinus/laya-security-lab/v5'

def main():
    client=paramiko.SSHClient();client.load_host_keys(str(Path.home()/'.ssh/known_hosts'))
    client.set_missing_host_key_policy(paramiko.RejectPolicy())
    password=getpass.getpass('SSH password: ')
    client.connect('192.168.50.20',username='keinus',password=password,timeout=15,allow_agent=False,look_for_keys=False)
    password=None;client.get_transport().set_keepalive(30)
    print('COLLECTOR_READY',flush=True)
    seen=None
    try:
        with client.open_sftp() as sftp:
            while True:
                def read_json(path):
                    with sftp.open(REMOTE+'/'+path,'r') as f:return json.load(f)
                completion=read_json('completion.json')
                workers={k:read_json(k+'_worker.json') for k in ['flow','laya']}
                state={'completion':completion,'workers':workers,'checked_at':time.time()}
                try:state['grid']=read_json('grid_worker.json')
                except FileNotFoundError:pass
                (ROOT/'remote_status.json').write_text(json.dumps(state,indent=2),encoding='utf8')
                n=sum(len(w['completed']) for w in workers.values())
                checked=datetime.fromtimestamp(state['checked_at'],timezone.utc).astimezone().isoformat(timespec='seconds')
                text=['# 벤치마크 실행 상태','',f'완료된 학습: **{n}/27회**. 전체 처리 상태: `{completion["status"]}`.',f'최근 확인: {checked}','',
                    '최종 결론은 모든 학습·평가·동일 GPU 시간 측정과 결과 검증이 끝난 뒤 RESULTS.ko.md에 저장한다.','',
                    '| 작업 | 상태 | 현재 모델과 seed |','|---|---|---|']
                for name,w in workers.items():text.append(f'| {name} | {w["status"]} | {w.get("current")} |')
                if 'grid' in state:
                    g=state['grid'];text.append(f'| 추가 학습률 탐색 | {g["status"]} | {g.get("current")} |')
                text+=['',f'비교군 추가 학습률 탐색: {completion.get("grid_completed",0)}/96회 처리. 원래 고정 실험과 별도 집계한다.']
                text+=['', '고정 실험 조건과 원문과의 차이는 README.md, 세부 명세는 protocol.json을 참고한다.','']
                (ROOT/'STATUS.ko.md').write_text('\n'.join(text),encoding='utf8')
                key=(n,completion['status'])
                if key!=seen:print('STATUS',n,'/27',completion['status'],flush=True);seen=key
                if completion['status']=='failed':
                    print('EXPERIMENT_FAILED',json.dumps(completion),flush=True);return
                if completion['status']=='complete':
                    archive=ROOT/'results_bundle.zip';partial=archive.with_suffix('.part')
                    sftp.get(REMOTE+'/results_bundle.zip',str(partial))
                    with partial.open('rb') as f:sha=hashlib.file_digest(f,'sha256').hexdigest()
                    if sha!=completion['bundle_sha256']:raise ValueError('Transfer checksum mismatch')
                    partial.replace(archive)
                    with zipfile.ZipFile(archive) as z:
                        inventory=json.loads(z.read('artifact_hashes.json'))
                        for name,expected in inventory.items():
                            payload=z.read(name)
                            if hashlib.sha256(payload).hexdigest()!=expected:raise ValueError('Artifact checksum mismatch: '+name)
                            # Extract results only; preserve locally edited source and documentation.
                            if name=='RESULTS.ko.md' or name.startswith('runs/') or name=='data/prepared/manifest.json':
                                target=(ROOT/name).resolve()
                                if not target.is_relative_to(ROOT.resolve()):raise ValueError('Unsafe archive member')
                                target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(payload)
                        (ROOT/'artifact_hashes.json').write_bytes(z.read('artifact_hashes.json'))
                    print('RESULTS_COLLECTED',ROOT/'RESULTS.ko.md',flush=True);return
                time.sleep(30)
    finally:client.close()

if __name__=='__main__':main()
