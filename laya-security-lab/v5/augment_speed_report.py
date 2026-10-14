"""Validate measured durations and revise the stopped report, without estimating missing results."""
import csv
import io
import json
import shutil
import zipfile
from pathlib import Path

import numpy as np
from common import ROOT,CONFIGS,digest


def main():
    speed=ROOT/'speed'
    state=json.loads((speed/'status.json').read_text())
    if state['status']!='complete' or len(state['completed'])!=10:
        raise RuntimeError('All nine models and the second Laya latency check must finish first')
    expected={(name,20261005) for name in list(CONFIGS)+['laya']}|{('laya',20261006)}
    assert {tuple(task) for task in state['completed']}==expected and state['training'] is False
    original=json.loads((ROOT/'stopped/summary.json').read_text())
    manifest=json.loads((ROOT/'data/prepared/manifest.json').read_text())
    grid=json.loads((ROOT/'runs/grid_comparison.json').read_text())
    first_training=json.loads((ROOT/'runs/laya_seed20261005_training.json').read_text())
    checkpoint_audit=json.loads((speed/'checkpoint_audit.json').read_text())
    for name,expected_hash in checkpoint_audit['sources'].items():
        assert digest(ROOT.parent/name)==expected_hash, f'Source differs from measured server: {name}'
    request_ids=None
    timings={}; uuids=set()
    for name,seed in state['completed']:
        d=json.loads((speed/f'{name}_seed{seed}.json').read_text())
        assert d['status']=='complete' and d['weights_unchanged']
        assert (d['model'],d['seed'])==(name,seed)
        assert d['protocol_sha256']==digest(speed/'protocol.json')
        assert d['script_sha256']==digest(ROOT/'inference_speed.py')
        assert d['source_csv_sha256']==manifest['source_csv_sha256']
        assert d['input_schedule_sha256']==first_training['schedule_sha256']
        checkpoint=checkpoint_audit['checkpoints'][f'{name}/{seed}']
        assert d['weights_sha256']==checkpoint['post_suite_weights_sha256']==checkpoint['training_metadata']['weights_sha256']
        assert checkpoint['training_metadata']['status']=='complete'
        assert set(d['raw'])==({'1'} if seed==20261006 else {'1','8','128'})
        if name=='laya':
            record=next(r for r in original['laya_rows'] if r['seed']==seed)
            assert d['weights_sha256']==record['weights_sha256']
            assert d['checkpoint']==f'runs/laya/seed{seed}'
        else:
            rate=float(grid['models'][name]['best_mean_lr'])
            selected=[r for r in grid['rows'] if r['model']==name and r['seed']==seed and r['lr']==rate and r['status']=='complete']
            assert len(selected)==1 and d['checkpoint']==selected[0]['folder'] and d['checkpoint_lr']==rate
        assert '4090' in d['hardware']
        uuids.add(d['cuda_visible_devices'])
        online=d['batch1_serialization_inclusive']; samples=np.asarray(online['raw_seconds'])
        assert online['n_requests']==200 and online['n_unique_windows']==50
        assert len(online['row_ids'])==50 and len(set(online['row_ids']))==50
        if request_ids is None:request_ids=online['row_ids']
        assert online['row_ids']==request_ids
        probabilities=np.asarray(online['probabilities'])
        assert probabilities.shape==(50,4) and np.isfinite(probabilities).all()
        assert ((probabilities>=0)&(probabilities<=1)).all()
        assert samples.shape==(50,4) and np.isfinite(samples).all() and (samples>0).all()
        assert np.isclose(online['p50_ms'],np.median(samples)*1000,rtol=1e-12)
        assert np.isclose(online['p95_ms'],np.quantile(samples,.95)*1000,rtol=1e-12)
        assert np.isclose(online['mean_ms'],np.mean(samples)*1000,rtol=1e-12)
        assert np.isclose(online['max_ms'],np.max(samples)*1000,rtol=1e-12)
        assert np.isclose(online['fraction_within_200ms'],np.mean(samples<=.2),rtol=1e-12)
        for b,raw in d['raw'].items():
            a=np.asarray(raw)
            assert a.shape==(50,4) and np.isfinite(a).all() and (a>0).all()
            duration=float(np.median(a,axis=1).mean())
            assert np.isclose(d['batch'+b]['flows_per_second'],int(b)/duration,rtol=1e-12)
        timings[f'{name}/{seed}']=d
    assert len(uuids)==1
    best=original['best_baseline']
    laya=[timings[f'laya/{seed}'] for seed in [20261005,20261006]]
    lp=[d['batch1_serialization_inclusive']['p95_ms'] for d in laya]
    bp=timings[f'{best}/20261005']['batch1_serialization_inclusive']['p95_ms']
    met=all(x<=200 for x in lp)
    ratio=lp[0]/bp
    conclusion=(f'Laya의 두 완료 체크포인트는 이번 순차 측정에서 단일 요청 p95 200ms를 충족했다. '
                f'첫 체크포인트의 p95는 최상 정확도 비교군의 {ratio:.1f}배였다.' if met else
                'Laya의 완료 체크포인트 중 적어도 하나가 이번 순차 측정에서 단일 요청 p95 200ms를 충족하지 못했다.')
    summary=dict(status='complete',training_resumed=False,measured_models=9,latency_runs=10,
                 hardware='RTX 4090',gpu_uuid=next(iter(uuids)),laya_p95_ms=lp,
                 laya_both_meet_200ms=met,best_accuracy_baseline_p95_ms=bp,
                 laya_first_p95_ratio_to_baseline=ratio,conclusion=conclusion,timings=timings)
    (speed/'verified_summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding='utf8')
    blocks=['## 저장된 체크포인트의 추론 속도 실측','',conclusion,'',
        '학습 중단 후 사용자 요청에 따라 추론만 실행했다. 모델은 기존 완료 체크포인트이며 가중치 해시가 측정 전후 동일함을 확인했다. '
        '비교군은 본문에서 평균 F1로 선택한 학습률의 첫 seed, Laya도 첫 완료 seed를 사용했다. '
        'Laya의 두 번째 완료 seed에는 단일 요청 지연 확인만 추가했다. 속도가 잘 나오는 체크포인트를 골라 바꾸지 않았다.','',
        '모든 모델을 같은 RTX 4090에 하나씩 로드했다. WSL2 환경이며 측정 직전 네 번의 GPU 사용률은 0~2%였다. '
        '화면 출력에도 사용하는 GPU라 운영체제의 간섭까지 제거한 전용 장비 측정은 아니다. 배치 1·8·128별로 네 배치를 워밍업한 뒤, '
        '고정된 50개 무작위 학습 배치를 각각 네 번 측정했다. 처리량은 배치별 네 반복의 중앙값을 낸 후 50개 평균으로 계산했다. '
        '단일 요청 p50·p95는 직렬화·토큰화 또는 수치 입력 조립을 포함한 개별 요청 200개의 지연에서 계산했다.','',
        '| 모델 설정 | 단일 요청 p50 ms | 단일 요청 p95 ms | 배치 8 판정/초 | 배치 128 판정/초 |',
        '|---|---:|---:|---:|---:|']
    rows=[]
    for name in list(CONFIGS)+['laya']:
        d=timings[f'{name}/20261005'];o=d['batch1_serialization_inclusive']
        blocks.append(f'| {name} | {o["p50_ms"]:.2f} | {o["p95_ms"]:.2f} | {d["batch8"]["flows_per_second"]:.1f} | {d["batch128"]["flows_per_second"]:.1f} |')
        rows.append(dict(model=name,seed=20261005,checkpoint=d['checkpoint'],p50_ms=o['p50_ms'],p95_ms=o['p95_ms'],
                         batch8_per_second=d['batch8']['flows_per_second'],batch128_per_second=d['batch128']['flows_per_second']))
    second=laya[1]['batch1_serialization_inclusive']
    blocks+=['',f'Laya 두 번째 완료 체크포인트의 p50은 **{second["p50_ms"]:.2f}ms**, p95는 **{second["p95_ms"]:.2f}ms**다. '
             '두 번째 체크포인트의 배치 처리량은 별도로 측정하지 않았다.','',
             '단일 요청은 같은 50개 window를 네 번씩 실행한 표본이며, 독립적인 200개 입력이나 장시간 운영 측정이 아니다. '
             +'; '.join(f'seed {d["seed"]}: 200ms 이내 {d["batch1_serialization_inclusive"]["fraction_within_200ms"]*100:.1f}%, 최대 {d["batch1_serialization_inclusive"]["max_ms"]:.2f}ms' for d in laya)+'.','',
             '한 판정은 8개 흐름 window의 마지막 흐름에 대한 판정이다. Laya의 논리 배치 128은 메모리 한도에 따라 '
             '16개씩 순차 실행했다. 처리량 열은 사전 인코딩한 입력부터 CPU 확률 반환까지이며 직렬화·토큰화는 제외한다. '
             '단일 요청 지연 열에는 해당 입력 준비와 CPU/GPU 전송·확률 회수가 포함된다.','',
             '**측정 시작점은 이미 전처리된 수치·범주형 window다.** 원시 Zeek 로그 파싱·수치 정규화, 8개 흐름을 모으는 시간, '
             '동시 요청 대기열, 네트워크 전송, QoS·차단 정책 적용은 포함하지 않는다. 모델 로딩과 최초 컴파일도 제외한 warm 지연이다. '
             '따라서 이 수치가 공격 발생부터 실제 차단까지의 200ms나 고부하 SLA를 보장하지 않는다.','',
             'TensorFlow 비교군은 float32와 tf.function, Laya는 float32 가중치·BF16 autocast·SDPA를 사용했다. '
             '같은 수치 커널을 비교한 것이 아니라 이번에 학습·평가에 사용한 실행 구현을 비교한 결과다. '
             '생성형 LLM의 텍스트 응답 생성 속도와는 비교하지 않았다.','']
    report=ROOT/'STOPPED_REPORT.ko.md';backup=ROOT/'stopped/STOPPED_REPORT.before_speed.ko.md'
    if not backup.exists():shutil.copy2(report,backup)
    text=backup.read_text(encoding='utf8')
    text=text.replace('# 네트워크 흐름 기반 Laya 탐지 모델 중단 시점 종합 보고서',
                      '# 네트워크 흐름 기반 Laya 탐지 모델 종합 보고서\n\n학습 중단 결과 및 추론 속도 실측 보강')
    text=text.replace('속도 측정도 없어서 200ms 판정이나 실시간 자동 대응의 개선을 주장할 수 없다.',
                      conclusion+' 실제 네트워크 자동 대응의 개선은 별도 검증 대상이다.')
    text=text.replace('학습·추론·벤치마크를 재시작하지 않았으며,',
                      '학습은 재시작하지 않았고 이후 사용자 요청으로 추론 속도만 측정했으며,')
    text=text.replace('사용자의 학습 중단 지시에 따라 저장된 결과만 종합했다.',
                      '탐지 정확도는 사용자의 학습 중단 지시에 따라 저장된 결과만 종합했다.')
    text=text.replace('| 동일 GPU 지연 및 처리량 | 9개 설정 | 0개 | 측정 전 중단 |',
                      '| 동일 GPU 지연 및 처리량 | 9개 설정 | 9개 | 학습 중단 후 별도 측정 완료, Laya 두 번째 지연도 추가 확인 |')
    old='**200ms 충족 여부는 미측정이다.** 단일 요청 p50·p95와 동일 GPU 처리량 측정 전에 중단됐다. 배치 평가에 걸린 총시간을 단일 요청 응답 시간으로 나누어 대체하지 않았다. [5]의 모델 카드 속도도 이번 입력의 측정값으로 사용하지 않았다.'
    if old not in text:raise ValueError('Report speed section changed; manual reconciliation needed')
    text=text.replace(old,'학습 중단 뒤 추론 속도를 별도 측정했다. 아래 실측 절의 원시 요청 시간을 사용했고 배치 평가 총시간이나 [5]의 모델 카드 속도로 대체하지 않았다.')
    text=text.replace('속도·미학습 공격·실제 연관 탐지·자동 대응 효과는 검증되지 않았다.',
                      '미학습 공격·실제 연관 탐지·자동 대응 효과는 검증되지 않았다. '
                      f'추론은 두 Laya 체크포인트 모두 p95 200ms 이내였지만, 첫 체크포인트의 p95는 최상 정확도 비교군보다 {ratio:.1f}배 길었다.' if met else
                      '미학습 공격·실제 연관 탐지·자동 대응 효과는 검증되지 않았다. 추론 속도 실측에서도 두 체크포인트 모두 p95 200ms를 충족하지는 못했다.')
    marker='## 논문 발표값과 재현의 한계'
    text=text.replace(marker,'\n'.join(blocks)+'\n'+marker)
    text=text.replace('| 서버 재검산 출력에서 보존한 기록 | `stopped/remote_audit_20261006.json` | 두 번째 Laya, 세 번째 중단, 속도 미측정 |',
                      '| 서버 재검산 출력에서 보존한 기록 | `stopped/remote_audit_20261006.json` | 두 번째 Laya와 세 번째 중단의 당시 기록 |\n'
                      '| 추론 속도 원본 | `speed/verified_summary.json`, `speed/*_seed*.json` | 요청별 시간·가중치 해시·GPU·런타임 및 재검산 |\n'
                      '| 속도 측정 조건과 가중치 감사 | `speed/protocol.json`, `speed/preflight.json`, `speed/checkpoint_audit.json` | 사전 고정 조건·GPU 사전 상태·학습 메타데이터·측정 후 가중치 및 소스 해시 |')
    recovered=ROOT/'runs/laya_seed20261006_predictions.npz'
    recovery_files=[]
    if recovered.exists():
        record=next(r for r in original['laya_rows'] if r['seed']==20261006)
        assert digest(recovered)==record['prediction_sha256']
        prediction=np.load(recovered); y=prediction['y'].astype(bool); positive=prediction['probability']>.5
        assert np.array_equal(prediction['row'],np.arange(2151248,2390275))
        counts=dict(TP=int((y&positive).sum()),FP=int((~y&positive).sum()),
                    TN=int((~y&~positive).sum()),FN=int((y&~positive).sum()))
        assert all(record[k]==v for k,v in counts.items())
        old_evidence='그 뒤 SSH가 끊겨 두 번째 Laya의 원본 예측 NPZ와 갱신된 서버 묶음을 추가 다운로드하지 못했다. 두 번째 수치는 연결이 살아 있을 때 성공한 서버 재검산 출력에서 보존했고, 혼동행렬로 F1·정밀도·탐지율·오탐률·정확도를 다시 계산했다. 서버 원본이 로컬 증거 묶음에 모두 포함돼 있다고 주장하지 않는다.'
        assert old_evidence in text
        text=text.replace(old_evidence,'초기 종합 때는 SSH 연결 종료로 두 번째 Laya의 원본 예측 파일을 회수하지 못했으나, 추론 속도 측정을 위해 다시 접속하면서 회수했다. 두 완료 반복의 원본 예측 파일을 로컬에 보관하고, 기존 SHA-256 및 혼동행렬과 일치하는지 확인했다. 비교군 전체 119회의 원본 예측 NPZ는 서버에 보존하며 로컬 묶음에는 전체 시도별 성적표와 해시를 포함한다.')
        text=text.replace('| 첫 Laya 원본 | `runs/laya_seed20261005_predictions.npz` | 평가 행·정답·예측 확률 |',
                          '| Laya 두 완료 반복의 원본 | `runs/laya_seed20261005_predictions.npz`, `runs/laya_seed20261006_predictions.npz` | 평가 행·정답·예측 확률 |')
        original['evidence_limit']='Both completed Laya prediction NPZ files recovered and verified locally; full baseline prediction NPZ files remain on server, with complete trial metrics and hashes in the local bundle.'
        recovery_files=[recovered,ROOT/'runs/laya_seed20261006_training.json',ROOT/'runs/laya_seed20261006_evaluation.json']
    report.write_text(text,encoding='utf8')
    buffer=io.StringIO();writer=csv.DictWriter(buffer,fieldnames=list(rows[0]));writer.writeheader();writer.writerows(rows)
    (speed/'results.csv').write_text(buffer.getvalue(),encoding='utf-8-sig',newline='')
    (ROOT/'STATUS.ko.md').write_text('# 학습 중단 결과 및 추론 속도 보강 완료\n\n'
        '학습은 중단 상태를 유지했다. 기존 완료 체크포인트로 9개 모델의 추론 속도와 두 번째 Laya의 단일 요청 지연을 측정했다.\n\n'
        '[종합 보고서](STOPPED_REPORT.ko.md) · [속도 원본](speed/verified_summary.json)\n',encoding='utf8')
    readme=ROOT/'README.md'
    readme_text=readme.read_text(encoding='utf8')
    readme_text=readme_text.replace('세 번째 Laya 반복과 속도 측정은 미완료이며 학습을 재개하지 않았다.',
        '세 번째 Laya 반복은 중단 상태다. 학습을 재개하지 않고 기존 체크포인트 9개 설정의 추론 속도와 두 번째 Laya의 지연을 추가 측정했다. [속도 결과](speed/results.csv)와 [원시 측정값](speed/verified_summary.json)을 함께 보존한다.')
    readme.write_text(readme_text,encoding='utf8')
    original.update(new_inference_performed=True,timing_measured=True,
                    timing_summary='speed/verified_summary.json',laya_both_meet_200ms=met,
                    conclusion='No demonstrated overall advancement. Observed F1 and FPR are worse than the selected baseline with slightly higher recall. Warm inference measured separately; unknown-attack and closed-loop effectiveness remain untested.')
    (ROOT/'stopped/summary.json').write_text(json.dumps(original,ensure_ascii=False,indent=2),encoding='utf8')
    old_inventory=json.loads((ROOT/'stopped/inventory.json').read_text())
    files={ROOT/name for name in old_inventory}
    files.update(p for p in speed.glob('*') if p.is_file() and p.suffix in {'.json','.csv','.log'})
    files.update([backup,ROOT/'inference_speed.py',ROOT/'run_inference_speed.py',ROOT/'augment_speed_report.py',
                  ROOT/'common.py',ROOT/'train_flow.py',ROOT/'laya_adapter.py'])
    source_copy=speed/'model_io.py'
    shutil.copy2(ROOT.parent/'model_io.py',source_copy)
    files.add(source_copy)
    files.update(recovery_files)
    inventory={str(p.relative_to(ROOT)).replace('\\','/'):digest(p) for p in sorted(files)}
    (ROOT/'stopped/inventory.json').write_text(json.dumps(inventory,indent=2),encoding='utf8')
    archive=ROOT/'stopped_report_and_evidence.zip'
    archived_before=ROOT/'stopped/report_before_speed.zip'
    if archive.exists() and not archived_before.exists():shutil.copy2(archive,archived_before)
    with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED) as z:
        for p in sorted(files|{ROOT/'stopped/inventory.json'}):z.write(p,str(p.relative_to(ROOT)).replace('\\','/'))
    import hashlib
    with zipfile.ZipFile(archive) as z:
        assert all(hashlib.sha256(z.read(name)).hexdigest()==expected for name,expected in inventory.items())
    print(json.dumps(dict(report=str(report),p95_ms=lp,baseline_p95_ms=bp,meets_target=met),ensure_ascii=False,indent=2))


if __name__=='__main__':main()
