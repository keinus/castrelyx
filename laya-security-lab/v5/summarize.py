"""Audit all completed runs and generate a report only after the full comparison exists."""
import argparse, csv, io, json, time
import numpy as np
from common import ROOT, CONFIGS, SEEDS, Data, digest, metrics, write_json

def mean_sd(v):return float(np.mean(v)),float(np.std(v,ddof=1))

def blocked_delta(a,b,y):
    """Paired block bootstrap, conditional on the three fitted checkpoints."""
    boundaries=np.arange(0,len(y),1024)
    def counts(probs):
        runs=[]
        for p in probs:
            pred=p>.5
            c=np.stack([pred & y,pred & ~y,~pred & ~y,~pred & y],axis=1).astype(np.int64)
            runs.append(np.add.reduceat(c,boundaries,axis=0))
        return np.stack(runs,axis=1)
    ca=counts(a);cb=counts(b);nb=len(boundaries)
    rng=np.random.default_rng(20261005)
    w=rng.multinomial(nb,np.full(nb,1/nb),size=10000)
    def f1(c):
        c=(w@c.reshape(nb,-1)).reshape(10000,len(SEEDS),4)
        return (2*c[:,:,0]/np.maximum(1,2*c[:,:,0]+c[:,:,1]+c[:,:,3])).mean(1)
    delta=f1(ca)-f1(cb)
    return dict(ci95_pp=[float(x*100) for x in np.quantile(delta,[.025,.975])],
        familywise8_ci_pp=[float(x*100) for x in np.quantile(delta,[.05/16,1-.05/16])])

def summarize(allow_partial=False):
    data=Data();names=list(CONFIGS)+['laya']; rows=[]; missing=[]; stored={}; timings={}
    overlap=np.load(ROOT/'data/prepared/test_feature_overlap.npy')
    manifest=json.loads((ROOT/'data/prepared/manifest.json').read_text())
    for name in names:
        probs=[]
        for seed in SEEDS:
            folder=ROOT/f'runs/{name}/seed{seed}';p=folder/'training.json'
            if not p.exists():missing.append(f'{name}/{seed}');continue
            train=json.loads(p.read_text())
            if train['status']!='complete':raise ValueError(f'Not complete: {folder}')
            assert train['source_sha256']==manifest['source_csv_sha256']
            assert train['schedule_sha256']==manifest['schedule'][str(seed)]['sha256']
            epoch=train['epochs_completed']; pred_path=folder/f'predictions_epoch{epoch}_sequence.npz'
            pred=np.load(pred_path); assert np.array_equal(pred['row'],data.test)
            assert np.array_equal(pred['y'],data.y[data.test])
            calculated=metrics(pred['y'],pred['probability'])
            reported=json.loads((folder/train['primary_evaluation']).read_text())['overall']
            for k,v in calculated.items():assert np.isclose(v,reported[k],rtol=1e-10,atol=1e-12),(folder,k)
            unique=metrics(pred['y'][~overlap],pred['probability'][~overlap])
            repeated=metrics(pred['y'][overlap],pred['probability'][overlap])
            row=dict(model=name,seed=seed,epochs=epoch,parameters=train['parameter_count'],
                prediction_sha256=digest(pred_path),weights_sha256=train['weights_sha256'],**calculated,
                nonduplicate_f1=unique['f1'],nonduplicate_recall=unique['recall'],nonduplicate_fpr=unique['fpr'],
                duplicate_f1=repeated['f1'])
            rows.append(row);probs.append(pred['probability'])
        if len(probs)==len(SEEDS):stored[name]=probs
        timing=ROOT/f'runs/{name}/seed{SEEDS[0]}/timing.json'
        if timing.exists():timings[name]=json.loads(timing.read_text())
        elif not allow_partial:missing.append(f'{name}/timing')
    write_json(ROOT/'runs/verified_progress.json',dict(updated=time.time(),completed_runs=len(rows),expected_runs=27,missing=missing,rows=rows))
    if allow_partial:
        print(json.dumps(dict(completed_runs=len(rows),expected_runs=27,missing=missing),indent=2));return
    if missing:raise RuntimeError('Cannot write a final comparison: '+', '.join(missing))
    from grid_results import collect_grid
    grid=collect_grid(data,rows)
    aggregate={}
    for name in names:
        r=[x for x in rows if x['model']==name]
        aggregate[name]={k:dict(zip(['mean','sd'],mean_sd([x[k] for x in r]))) for k in ['f1','precision','recall','fpr','nonduplicate_f1','nonduplicate_recall','nonduplicate_fpr']}
        aggregate[name]['best_f1']=max(x['f1'] for x in r)
        aggregate[name]['parameters']=r[0]['parameters']
    paired={name:dict(delta_mean_f1_pp=(aggregate['laya']['f1']['mean']-aggregate[name]['f1']['mean'])*100,
        **blocked_delta(stored['laya'],stored[name],np.asarray(data.y[data.test],dtype=bool))) for name in CONFIGS}
    best=max(CONFIGS,key=lambda n:aggregate[n]['f1']['mean'])
    l=aggregate['laya'];b=aggregate[best]
    delta=(l['f1']['mean']-b['f1']['mean'])*100
    slower=timings['laya']['batch1_serialization_inclusive']['p95_ms']>timings[best]['batch1_serialization_inclusive']['p95_ms']
    worse_fpr=l['fpr']['mean']>b['fpr']['mean']
    ci=paired[best]['ci95_pp']; adjusted=paired[best]['familywise8_ci_pp']
    grid_best=grid['best_mean_model']; grid_mean=grid['models'][grid_best]['best_mean']
    selected_rate=float(grid['models'][grid_best]['best_mean_lr'])
    laya_by_seed={r['seed']:r['f1'] for r in rows if r['model']=='laya'}
    grid_seed_rows=[r for r in grid['rows'] if r['model']==grid_best and r['lr']==selected_rate and r['status']=='complete']
    all_seed_gains=all(laya_by_seed[r['seed']]>r['f1'] for r in grid_seed_rows)
    meets_latency=timings['laya']['batch1_serialization_inclusive']['p95_ms']<=200
    if l['recall']['mean']<.01 and b['recall']['mean']>.5:
        verdict='이번 고정 조건에서는 탐지 기능의 퇴보로 평가한다. Laya가 공격을 거의 탐지하지 못하므로 낮은 오탐률만으로 개선이라고 해석할 수 없다.'
    elif not meets_latency:
        verdict='이번 구현은 사용자가 제시한 200 ms 모델 판정 목표를 충족하지 못했다. 정확도 차이와 별개로 현재 목표에서 실용적 진일보를 입증하지 못했다.'
    elif adjusted[1]<0 and slower and worse_fpr:
        verdict='이번 고정 조건에서는 퇴보로 평가한다. Laya는 가장 높은 평균 F1을 얻은 재실행 비교군보다 F1이 낮고 오탐률과 지연도 높다.'
    elif adjusted[0]>0 and not worse_fpr and all_seed_gains and l['fpr']['mean']<=grid_mean['fpr_mean']:
        verdict='이번 데이터와 측정 부하에서는 조건부 진일보로 평가한다. Laya는 탐지 F1을 높이고 오탐률을 악화시키지 않으면서 200 ms 모델 판정 목표를 충족했다. 실제 네트워크에서의 미학습 공격 탐지와 자동 대응 효과까지 입증한 결과는 아니다.'
    else:
        verdict='일괄적인 진일보 또는 퇴보로 단정할 근거가 부족하다. 정확도·오탐·속도 사이의 차이와 세 반복의 변동을 함께 판단해야 한다.'
    result=dict(status='complete',scope='NF-UNSW-NB15-v2, paper 90/10, eight published configurations plus Laya',
        protocol_sha256=digest(ROOT/'protocol.json'),aggregate=aggregate,paired=paired,timings=timings,
        best_reproduced_baseline=best,verdict=verdict,rows=rows,learning_rate_grid=grid,
        assessment=dict(latency_target_ms=200,meets_latency=meets_latency,all_three_seed_f1_gains_over_best_grid_mean_setting=all_seed_gains,
            definition='Detection improvement within the user latency budget; being faster than every numeric baseline is not required. Unseen-attack and closed-loop effectiveness remain untested.'))
    write_json(ROOT/'runs/comparison.json',result)
    buf=io.StringIO();w=csv.DictWriter(buf,fieldnames=list(rows[0]));w.writeheader();w.writerows(rows)
    (ROOT/'runs/results.csv').write_text(buf.getvalue(),encoding='utf-8-sig')
    def cell(name,key):
        x=aggregate[name][key];return f'{x["mean"]*100:.2f} ± {x["sd"]*100:.2f}'
    report=['# FlowTransformer 논문 기준 Laya 비교 결과','',verdict,'',
        '판정 기준은 사용자의 목적에 맞춰 F1·오탐률과 모델 판정 p95 200 ms 충족 여부를 함께 본 것이다. 가장 빠른 수치 모델보다 빨라야만 개선으로 인정하는 기준은 아니다. 다만 window 수집·대기열·장비 적용을 포함한 운영 지연과 고부하 처리량의 충족 여부는 별도 검증 대상이다.','',
        f'재실행 비교군의 최고 평균 F1 설정은 `{best}`이다. Laya와의 평균 F1 차이는 {delta:+.2f}%p이며, 고정된 세 체크포인트에 대한 1,024행 블록 부트스트랩 95% 구간은 [{ci[0]:+.2f}, {ci[1]:+.2f}]%p이다. 8개 비교의 Bonferroni 보정 구간은 [{adjusted[0]:+.2f}, {adjusted[1]:+.2f}]%p이며 우열 판정에는 이 보정 구간을 사용했다. 10,000회 재추출했다. 이 구간은 학습 seed나 실제 네트워크 전체의 불확실성을 모두 포함하지 않는다.','',
        '## 같은 데이터와 평가 방식에서 다시 실행한 결과','',
        '전체 2,390,275개 흐름 중 학습 구간 2,151,248행에서 표본을 추출하고 마지막 239,027행 전부를 평가했다. 공격 16,271개와 정상 222,756개가 평가 대상이다. 모든 모델은 같은 37개 특성과 8행 window, 같은 배치 표본 스케줄, 공격 확률 > 0.5를 사용했다. 아래는 세 반복의 평균 ± 표본 표준편차다.','',
        '| 모델 설정 | F1 % | 탐지율 % | 오탐률 % | 최고 반복 F1 % | 배치 1 p95 ms |',
        '|---|---:|---:|---:|---:|---:|']
    for name in names:
        t=timings[name]['batch1_serialization_inclusive']['p95_ms']
        report.append(f'| {name} | {cell(name,"f1")} | {cell(name,"recall")} | {cell(name,"fpr")} | {aggregate[name]["best_f1"]*100:.2f} | {t:.2f} |')
    report += ['', '## 논문 학습률 탐색을 적용한 비교군 결과','',
        '고정 설정 BERT의 첫 실행에서 학습 손실이 약 0.693에 머물고 공격 탐지율이 0%로 나온 뒤 추가한 민감도 분석이다. Laya의 첫 테스트 평가 전에 추가 조건을 확정했다. 8개 비교 설정 모두 논문 Table I의 학습률 0.01, 0.001, 0.0005, 0.0001, 0.00001을 각 3회 시도했다. 최초 실험의 0.001 결과 24회를 재사용하고 96회를 추가했다. 수치 발산은 실패로 기록하고 성능값을 만들어 넣지 않았다.','',
        '아래는 논문처럼 평가 F1을 기준으로 선택한 최고 반복 값이다. 같은 테스트 집합에서 학습률과 반복을 선택하므로 낙관적인 선택 결과이며 독립 검증이 아니다. Laya는 사전 선택한 0.00001 한 가지 학습률의 3회 반복만 사용했다. 탐색량이 같지 않으며 Laya의 최적 성능 한계를 주장하지 않는다.','',
        '| 모델 | 선택 학습률 | 최고 반복 F1 % | 해당 탐지율 % | 해당 오탐률 % | 최상 평균 학습률의 F1 평균 ± SD % |',
        '|---|---:|---:|---:|---:|---:|']
    for name,g in grid['models'].items():
        r=g['best_repeat'];m=g['best_mean']
        report.append(f'| {name} | {r["lr"]:g} | {r["f1"]*100:.2f} | {r["recall"]*100:.2f} | {r["fpr"]*100:.2f} | {m["f1_mean"]*100:.2f} ± {m["f1_sd"]*100:.2f} (lr {g["best_mean_lr"]}) |')
    lr=max([r for r in rows if r['model']=='laya'],key=lambda r:r['f1'])
    report.append(f'| laya | 0.00001 | {lr["f1"]*100:.2f} | {lr["recall"]*100:.2f} | {lr["fpr"]*100:.2f} | {cell("laya","f1")} |')
    gb=grid['best_mean_model'];gm=grid['models'][gb]['best_mean'];gap=(l['f1']['mean']-gm['f1_mean'])*100
    report += ['',f'추가 탐색 후 평균 F1이 가장 높은 비교군은 `{gb}`이며 Laya와의 평균 차이는 {gap:+.2f}%p다. 이 차이는 테스트에서 학습률을 선택한 비교이므로 위 고정 설정의 신뢰구간과 혼합하지 않는다. 탐지율이 0%인 고정 학습률 결과만으로 BERT 구조의 성능 한계를 판단하지 않는다. 전체 학습률·반복·실패 내역은 `runs/grid_results.csv`에 있다.','']
    report += ['',f'고정 설정 결과를 정상 10,000개 흐름당 오탐 수로 환산하면 Laya는 {l["fpr"]["mean"]*10000:.1f}개, `{best}`는 {b["fpr"]["mean"]*10000:.1f}개다. 이 값은 이번 평가 집단에서의 환산이며 실제 호스트 차단 건수를 뜻하지 않는다.','',
        '## 동일 GPU에서 측정한 처리 속도','',
        '고정 설정 실험의 각 모델 첫 seed 체크포인트를 학습 완료 후 같은 GPU에 단독 로드했다. 학습률 탐색에서 선택한 최고값 체크포인트와는 구분한다. 논문식 처리량은 50개 무작위 배치마다 4회 반복하고 중앙값을 낸 뒤 평균했다. 배치 1의 지연 p95는 직렬화·토큰화를 포함한 개별 요청 200개의 분포에서 계산했다. 네 반복을 요약한 중앙값 50개의 p95와는 다르다. 아래 처리량은 입력 준비를 제외하고 모델 실행·장치 전송·결과 회수를 포함한다. Laya의 논리 배치 128은 메모리 한도 때문에 물리 배치로 나눠 실행했다.','',
        '| 모델 | 배치 8 flows/s | 배치 128 flows/s | 파라미터 수 |','|---|---:|---:|---:|']
    for name in names:
        report.append(f'| {name} | {timings[name]["batch8"]["flows_per_second"]:.1f} | {timings[name]["batch128"]["flows_per_second"]:.1f} | {aggregate[name]["parameters"]:,} |')
    p95=timings['laya']['batch1_serialization_inclusive']['p95_ms']
    report += ['',f'Laya의 측정된 단일 요청 p95는 {p95:.2f} ms이므로 이번 장비·입력에서는 200 ms 기준을 {"충족했다" if p95<=200 else "충족하지 못했다"}. 이 수치는 window 수집 시간, Zeek의 흐름 종료 대기, 장비 정책 적용 시간을 제외한다. 자동 대응 전체가 200 ms라는 결론은 낼 수 없다.','',
        '## 논문 발표값과 이번 측정값의 관계','',
        '다음은 [FlowTransformer 원문 Table III](https://arxiv.org/pdf/2304.14746v1)의 참고값이다. 논문은 전체 탐색과 반복 중 최댓값을 보고했으나, 설정별 최적 학습률과 정확한 평가 행 목록을 공개하지 않았다. 공개 예제의 97.5:2.5 분할과 원문 90:10도 다르다. 따라서 발표값과의 단순 차이는 진보의 근거로 사용하지 않는다.','',
        '| 설정 | 발표 F1 % | 발표 탐지율 % | 발표 오탐률 % |','|---|---:|---:|---:|']
    for name,c in CONFIGS.items():report.append(f'| {name} | {c["paper_f1"]:.2f} | {c["paper_recall"]:.2f} | {c["paper_fpr"]:.2f} |')
    report += ['', '## 중복과 연관 탐지의 해석','',
        '평가 흐름 59,793개(약 25.02%)는 학습 구간에도 같은 37개 입력 특성을 가진 행이 있다. 이는 동일한 원본 연결이나 패킷이 중복 수집됐다는 증명은 아니다. 논문 분할을 보존하면서, 이런 흐름을 제외한 나머지 179,234개에 대해서도 같은 체크포인트의 지표를 계산했다. 이 추가 집계는 중복을 제거하여 재학습한 실험이 아니다.','',
        '| 모델 | 비중복 평가 F1 % | 비중복 탐지율 % | 비중복 오탐률 % |','|---|---:|---:|---:|']
    for name in names:report.append(f'| {name} | {cell(name,"nonduplicate_f1")} | {cell(name,"nonduplicate_recall")} | {cell(name,"nonduplicate_fpr")} |')
    report += ['', 'v2 데이터에는 흐름 시작 시각이 없어 window는 시간 기반 연쇄가 아닌 CSV 행 순서다. 모든 공격 종류가 학습 구간에 있으므로 이 실험은 미학습 공격 평가가 아니다. 단일 흐름으로는 구분할 수 없는 공격만을 따로 구성하거나 같은 모델을 단일 흐름으로 재학습한 대조 실험도 아니므로, 성능 차이를 연관 추론 능력의 차이라고 단정할 수 없다.','',
        '## 재현 조건과 결론의 범위','',
        '원본 [저자 코드](https://github.com/liamdm/FlowTransformer)에서 모델 구성요소를 변경 없이 불러왔고 8개 설정 모두 논문 파라미터 수와 일치했다. 메모리 사용량을 줄이기 위한 window 조립 어댑터는 원본 pandas 처리와 같은 값을 내는지 검증했다. 수치 입력 순서를 정렬하고 같은 학습 표본 스케줄을 모든 모델에 적용했다.','',
        'BERT 두 설정은 XLA의 첫 학습 단계 컴파일 오류로 모델 전체 JIT을 해제(jit_compile=False)했다. 실패 기록은 보존했으며 학습 설정이나 표본을 바꾸지 않고 같은 seed로 처음부터 실행했다. 추론은 모든 비교군에서 같은 표준 TensorFlow 호출 방식으로 측정했다. 원문의 Dense embedding 비선형 설명과 달리 공개 구현의 activation은 linear이며 이번 실행은 저자 코드를 따랐다. 파라미터 수 일치만으로 논문의 모든 동작이 같다고 주장하지 않는다.','',
        '배치 128, epoch당 64개 배치는 공개 예제, 최대 20 epochs와 patience 5는 원문을 따랐다. epoch는 전체 데이터 1회 통과가 아니라 8,192회 균형 표본 추출이다. 주 실험은 비교군 Adam 0.001, Laya Adam 0.00001로 학습률을 사전에 고정했다. 추가 분석에는 비교군의 논문 학습률 다섯 가지를 적용했지만 논문 전체의 구조·입력 인코딩·분류 헤드 탐색을 재현한 것은 아니다. 각 모델의 최적 성능 한계도 주장하지 않는다. Laya는 기본 영어 체크포인트에서 전체 파인튜닝하고 원래 이진 판정 헤드를 사용했다. 입력이 잘리지 않도록 최대 길이를 8,192토큰으로 설정했다.','',
        '위 우열 평가는 이 데이터셋과 고정 학습 조건에서의 결과다. 이전 v4 수치는 데이터와 평가 규칙이 달라 직접 비교하지 않았다. QoS 조정·경로 변경·선택적 패킷 차단·호스트 연결 차단의 효과와 피해는 이번 벤치마크에서 측정하지 않았다.','',
        '근거 파일: `protocol.json`, `data/prepared/manifest.json`, `runs/architecture_audit.json`, `runs/input_audit.json`, `runs/comparison.json`, `runs/results.csv`, 각 실행의 정답·예측 확률 NPZ와 학습 기록.','',
        '## 출처','',
        '- [기준 논문 공개 원문](https://arxiv.org/pdf/2304.14746v1): 모델·지표·반복·학습 상한·시간 측정 방식.',
        '- [FlowTransformer 저자 저장소](https://github.com/liamdm/FlowTransformer): 실제 모델·전처리·표본 추출 구현.',
        '- [UQ 데이터 안내](https://staff.itee.uq.edu.au/marius/NIDS_datasets/): 공개 NetFlow 데이터의 배포 근거.',
        '- [NF-UNSW-NB15-v2 공식 다운로드](https://rdm.uq.edu.au/files/8c6e2a00-ef9c-11ed-827d-e762de186848): 실험 데이터.',
        '- [Laya 모델 카드](https://huggingface.co/convaiinnovations/laya): 모델 정체와 기본 구조. 모델 카드의 속도·정확도 수치를 이 실험의 측정값으로 대체하지 않았다.','']
    (ROOT/'RESULTS.ko.md').write_text('\n'.join(report),encoding='utf8')
    print(json.dumps(dict(status='complete',verdict=verdict,best_baseline=best,laya_f1=l['f1'],baseline_f1=b['f1']),ensure_ascii=False),flush=True)

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--progress',action='store_true');a=p.parse_args();summarize(a.progress)
