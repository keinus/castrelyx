"""Close out the stopped experiment using existing, audited measurements only."""
import csv
import hashlib
import io
import json
import shutil
import zipfile
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parent
OUT = ROOT/'stopped'


def read(p):
    return json.loads((ROOT/p).read_text(encoding='utf8'))


def sha(path):
    with path.open('rb') as f:
        return hashlib.file_digest(f,'sha256').hexdigest()


def mean(rows,k):
    return float(np.mean([r[k] for r in rows]))


def cell(rows,k,digits=2):
    x=np.asarray([r[k] for r in rows])*100
    return f'{x.mean():.{digits}f} ± {x.std(ddof=1):.{digits}f}'


def main():
    OUT.mkdir(exist_ok=True)
    audit=read('stopped/remote_audit_20261006.json')
    grid=read('runs/grid_comparison.json')
    primary=read('runs/verified_progress.json')['rows']
    first=next(r for r in primary if r['model']=='laya' and r['seed']==20261005)
    laya=[first,audit['laya_second_run']]
    assert sha(ROOT/'runs/laya_seed20261005_predictions.npz')==first['prediction_sha256']
    z=np.load(ROOT/'runs/laya_seed20261005_predictions.npz')
    y=z['y'].astype(bool); pred=z['probability']>.5
    assert first['TP']==int((y&pred).sum()) and first['FP']==int((~y&pred).sum())
    assert first['FN']==int((y&~pred).sum()) and first['TN']==int((~y&~pred).sum())
    for r in laya:
        assert len(r['prediction_sha256'])==64
        derived={'f1':2*r['TP']/(2*r['TP']+r['FP']+r['FN']),
                 'recall':r['TP']/(r['TP']+r['FN']),'fpr':r['FP']/(r['FP']+r['TN']),
                 'precision':r['TP']/(r['TP']+r['FP']),'accuracy':(r['TP']+r['TN'])/r['n']}
        assert all(np.isclose(r[k],v,rtol=1e-12,atol=1e-12) for k,v in derived.items())
    best=grid['best_mean_model']; selected=grid['models'][best]
    base=[dict(r) for r in grid['rows'] if r['model']==best and r['lr']==float(selected['best_mean_lr']) and r['status']=='complete']
    for r in base:
        # These exact rates were computed with these known denominators on the server.
        r.update(TP=round(r['recall']*16271),FP=round(r['fpr']*222756))
        r.update(FN=16271-r['TP'],TN=222756-r['FP'])
        assert np.isclose(r['f1'],2*r['TP']/(2*r['TP']+r['FP']+r['FN']),rtol=1e-12)
    matched=[r for r in base if r['seed'] in {a['seed'] for a in laya}]
    pairs=[dict(seed=a['seed'],laya_f1=a['f1'],baseline_f1=b['f1'],delta_f1_pp=(a['f1']-b['f1'])*100,
                laya_fp=a['FP'],baseline_fp=b['FP'],laya_fn=a['FN'],baseline_fn=b['FN'])
           for a in laya for b in matched if a['seed']==b['seed']]
    agg={k:dict(mean=mean(laya,k),sd=float(np.std([r[k] for r in laya],ddof=1)))
         for k in ['f1','recall','fpr','precision','accuracy','average_precision','roc_auc','nonduplicate_f1','nonduplicate_recall','nonduplicate_fpr']}
    gap=(mean(laya,'f1')-mean(base,'f1'))*100
    paired_gap=(mean(laya,'f1')-mean(matched,'f1'))*100
    params=read('protocol.json')['models'][best]['paper_params']
    result=dict(status='closed_after_user_stop',report_date='2026-10-06',training_resumed=False,new_inference_performed=False,
                laya_completed_repeats=2,laya_rows=laya,laya_aggregate=agg,baseline_trials=120,
                baseline_successes=119,baseline_divergences=1,best_baseline=best,best_baseline_rows=base,
                selected_baseline_lr=float(selected['best_mean_lr']),matched_pairs=pairs,
                f1_delta_vs_three_baseline_repeats_pp=gap,f1_delta_on_two_matched_seeds_pp=paired_gap,
                timing_measured=False,unknown_attack_evaluated=False,causal_context_benefit_evaluated=False,
                conclusion='No demonstrated advancement. Observed F1 and false-positive rate are worse than the selected baseline, with slightly higher recall; global regression and speed remain unproven.',
                evidence_limit=audit['collection_limit'])
    (OUT/'summary.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf8')
    labels={
        'encoder_dense':'Shallow Encoder · Dense', 'encoder_projection':'Shallow Encoder · Projection',
        'decoder_last':'Shallow Decoder · Last token', 'decoder_flatten':'Shallow Decoder · Flatten',
        'gpt_lookup':'GPT 구조 · Lookup', 'gpt_dense':'GPT 구조 · Dense',
        'bert_none':'BERT 구조 · 입력 인코딩 없음', 'bert_projection':'BERT 구조 · Projection'}
    L=[
        '# 네트워크 흐름 기반 Laya 탐지 모델 중단 시점 종합 보고서','',
        '작성일 2026년 10월 6일 · 대상 FlowTransformer 공개 논문의 NF-UNSW-NB15-v2 비교 실험','',
        '## 종합 결론','',
        f'**현재 결과는 Laya의 진일보를 입증하지 못했다.** 완료된 두 반복에서 Laya의 F1은 '
        f'평균 **{mean(laya,"f1")*100:.2f}%**, 최상 비교군의 세 반복 평균은 **{mean(base,"f1")*100:.2f}%**였다. '
        f'Laya가 **{abs(gap):.2f}%p 낮았고**, 오탐률도 **{mean(laya,"fpr")*100:.3f}% 대 {mean(base,"fpr")*100:.3f}%**로 높았다. '
        f'반면 공격 탐지율은 **{mean(laya,"recall")*100:.3f}% 대 {mean(base,"recall")*100:.3f}%**로 더 높았다.','',
        '**이번 설정의 F1과 오탐 성능은 비교군보다 불리하며, 그 지표에 한정하면 퇴보 방향이다.** '
        '다만 반복이 두 번이고 비교군 학습률을 테스트 성적으로 선택했으므로 모델 자체의 일반적인 우열을 확정할 수 없다. '
        '속도 측정도 없어서 200ms 판정이나 실시간 자동 대응의 개선을 주장할 수 없다. 현재 근거만으로 기존 탐지 모델을 Laya로 대체할 이유는 충분하지 않다.','',
        '사용자의 학습 중단 지시에 따라 저장된 결과만 종합했다. 학습·추론·벤치마크를 재시작하지 않았으며, '
        '중단된 세 번째 반복에는 성능값을 부여하지 않았다. 이 문서는 중단 시점의 결과 보고서이며 계획한 전체 벤치마크의 완료 보고서는 아니다.','',
        '## 실험 범위와 비교 기준','',
        '기준은 [FlowTransformer 공개 원문](https://arxiv.org/pdf/2304.14746v1)의 Table III UNSW-NB15 실험이다. '
        '네트워크 흐름을 묶어 마지막 흐름을 판정하는 구조이고, 공개 데이터와 저자 코드로 모델을 재실행할 수 있어 선정했다. '
        '원문의 네 계열과 각 두 설정, 총 여덟 설정을 비교했다. 논문의 세 데이터셋 전체나 구조·입력 인코딩·분류 헤드의 전체 탐색을 재현한 것은 아니다. [1][2]','',
        '논문에서 GPT와 BERT라고 부르는 비교군은 해당 구조를 저자 코드로 처음부터 학습한 모델이다. '
        'GPT 서비스, 사전학습 BERT 체크포인트 또는 Qwen과의 비교가 아니다. 이전 v1–v4 결과도 이번 수치에 포함하지 않았다.','',
        '| 항목 | 적용한 조건 |','|---|---|',
        '| 데이터 | UQ 공식 NF-UNSW-NB15-v2, 2,390,275개 흐름 |',
        '| 분할 | 원본 행 순서의 앞 2,151,248행 학습, 뒤 239,027행 평가 |',
        '| 평가 구성 | 공격 16,271개, 정상 222,756개 |',
        '| 입력 | 저자 선택 37개 특성 × 연속 8행, 마지막 흐름의 공격 여부 판정 |',
        '| 입력 제외 | IP 주소, 정답 Label, 공격 이름 Attack |',
        '| 전처리 | 학습 구간에서 적합한 수치 변환과 상위 32개 범주 처리 |',
        '| 학습 배치 | 정상 64개와 공격 64개, 유효 배치 128, epoch당 64개 배치 |',
        '| 학습 종료 | 최대 20 epoch, 원본 코드의 최소 개별 배치 손실 기준 조기 종료 |',
        '| 판정 | 공격 확률 > 0.5, 테스트 기반 임계값 조정 없음 |',
        '| 비교군 학습률 | 0.01, 0.001, 0.0005, 0.0001, 0.00001, 각 세 반복 |',
        '| Laya | 사전학습 모델 전체 파인튜닝, 원래 이진 헤드, Adam 0.00001 |','',
        '여기서 한 epoch는 8,192회 균형 표본 추출이며, 학습 구간 전체를 한 번 통과한다는 뜻이 아니다. '
        '모든 모델에 같은 seed별 표본 스케줄을 적용했다. Laya에는 같은 전처리 정보를 숫자 9자리 정밀도의 CSV 텍스트로 제공했다. '
        '입력을 자르지 않도록 길이 상한을 8,192토큰으로 두었고, 완료된 두 실행에서 관측된 최대 입력은 각각 2,080·2,074토큰이었다.','',
        '## 완료와 중단 내역','',
        '| 구분 | 계획 | 결과 반영 | 상태 |','|---|---:|---:|---|',
        '| 비교군 기본 학습률 | 24회 | 24회 | 완료 |',
        '| 비교군 추가 학습률 | 96회 | 95회 | 1회 수치 발산을 별도 기록 |',
        '| Laya | 3회 | 2회 | 17·12 epoch에서 조기 종료 후 전체 평가 완료 |',
        '| Laya 세 번째 반복 | 위 3회에 포함 | 0회 | 3 epoch 저장, 마지막 로그는 4 epoch의 33/64번째 배치, 평가 없음 |',
        '| 동일 GPU 지연 및 처리량 | 9개 설정 | 0개 | 측정 전 중단 |','',
        '총 123회 계획 중 121회가 정상 완료됐고, 1회는 수치 발산, 1회는 평가 전 중단됐다. '
        '수치 발산은 `bert_projection`, 학습률 0.0001, seed 20261005에서 발생했다. 발산과 중단은 0점으로 대체하지 않았다. '
        '마지막 로그의 배치 번호는 마지막으로 출력된 위치이며 정확한 중단 명령 시각이나 마지막 계산 위치를 뜻하지 않는다.','',
        '## 학습률 탐색을 반영한 모델 비교','',
        '각 비교군에서 세 반복 평균 F1이 가장 높은 학습률을 선택했다. 표의 F1은 평균 ± 표본 표준편차이고 '
        '탐지율·오탐률은 평균이다. Laya는 완료된 두 반복의 평균이다. 표준편차는 적은 반복에서 관측된 변동이며 신뢰구간이 아니다.','',
        '| 모델 설정 | 선택 학습률 | 반복 | F1 % | 탐지율 % | 오탐률 % |',
        '|---|---:|---:|---:|---:|---:|',
    ]
    table=[]
    for name,info in grid['models'].items():
        m=info['best_mean']
        L.append(f'| {labels[name]} | {info["best_mean_lr"]} | 3 | {m["f1_mean"]*100:.2f} ± {m["f1_sd"]*100:.2f} | {m["recall_mean"]*100:.3f} | {m["fpr_mean"]*100:.3f} |')
        table.append(dict(model=name,lr=info['best_mean_lr'],repeats=3,f1_mean=m['f1_mean'],f1_sd=m['f1_sd'],recall_mean=m['recall_mean'],fpr_mean=m['fpr_mean']))
    L += [f'| **Laya** | 0.00001 | **2** | **{cell(laya,"f1")}** | **{mean(laya,"recall")*100:.3f}** | **{mean(laya,"fpr")*100:.3f}** |','',
          '테스트 F1로 학습률을 선택했으므로 비교군 결과에는 선택에 따른 낙관성이 있다. 독립 검증 성적이 아니다. '
          'Laya에는 한 학습률만 적용해 탐색량도 다르다. 추가 탐색은 기본 설정 BERT의 수렴 실패를 확인한 뒤, '
          'Laya의 첫 테스트 평가 전에 조건을 고정한 사후 민감도 분석이다. 이런 비대칭 때문에 수치 차이를 모델의 본질적 한계로 해석해서는 안 된다.','',
          'BERT 계열은 낮은 학습률에서도 반복별 변동이 컸다. 높은 평균 탐지율과 함께 매우 높은 오탐률이 나온 것은 '
          '일부 반복이 거의 모든 흐름을 공격으로 판정했기 때문이다. 특정 반복의 수렴 실패를 BERT 일반의 성능으로 확대하지 않는다.','',
          '## 같은 두 seed로 비교한 결과','',
          '반복 수 차이를 확인하기 위해 최상 비교군의 설정을 그대로 두고 Laya와 공통인 두 seed만 따로 비교했다. '
          '학습률을 이 두 반복에 맞춰 다시 고르지 않았다.','',
          '| seed | Laya F1 % | 비교군 F1 % | 차이 %p | Laya 오탐 | 비교군 오탐 | Laya 누락 | 비교군 누락 |',
          '|---|---:|---:|---:|---:|---:|---:|---:|']
    for p in pairs:
        L.append(f'| {p["seed"]} | {p["laya_f1"]*100:.2f} | {p["baseline_f1"]*100:.2f} | {p["delta_f1_pp"]:+.2f} | {p["laya_fp"]:,} | {p["baseline_fp"]:,} | {p["laya_fn"]} | {p["baseline_fn"]} |')
    L += ['',f'같은 두 seed에서도 Laya F1 평균은 **{mean(laya,"f1")*100:.2f}%**, 비교군은 **{mean(matched,"f1")*100:.2f}%**로 '
          f'Laya가 **{abs(paired_gap):.2f}%p 낮았다**. 평균 누락은 {mean(matched,"FN"):.1f}개에서 {mean(laya,"FN"):.1f}개로 줄었지만, '
          f'평균 오탐은 {mean(matched,"FP"):.1f}개에서 {mean(laya,"FP"):.1f}개로 늘었다. '
          '두 반복 모두 같은 방향이지만 반복 수가 적고 테스트 기반 설정 선택이 있어 통계적 우월성·열등성을 확정하지 않는다.','',
          '## Laya 개별 결과와 경보 부담','',
          '| seed | 종료 epoch | F1 % | 정밀도 % | 탐지율 % | 오탐률 % | TP | FP | TN | FN |',
          '|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|']
    for r in laya:
        L.append(f'| {r["seed"]} | {r["epochs"]} | {r["f1"]*100:.2f} | {r["precision"]*100:.2f} | {r["recall"]*100:.3f} | {r["fpr"]*100:.3f} | {r["TP"]:,} | {r["FP"]:,} | {r["TN"]:,} | {r["FN"]} |')
    L += ['',f'정상 10,000개 흐름당 오탐으로 환산하면 Laya는 평균 **{mean(laya,"fpr")*10000:.1f}개**, '
          f'최상 비교군은 **{mean(base,"fpr")*10000:.1f}개**다. Laya의 평균 정밀도는 **{mean(laya,"precision")*100:.2f}%**다. '
          '이는 이번 평가 집단의 경보 부담이며, 호스트별 집계·정책 적용이 없으므로 실제 호스트 차단 수나 피해 규모로 바꿔 해석할 수 없다.','',
          '두 실행은 사전에 정한 학습 손실 기준에 따라 종료됐다. 테스트 성적이 가장 높은 epoch를 선택한 결과가 아니다. '
          '첫 반복만 보았던 중간 보고의 F1 94.45%는 두 번째 반복의 93.34%가 추가되면서 평균 93.90%로 낮아졌다.','',
          '## 학습 구간과 동일 특성이 있는 평가 흐름','',
          '평가 행 59,793개, 약 25.02%는 학습 구간에도 같은 37개 입력 특성을 가진 행이 있다. '
          '같은 원본 패킷이나 연결이 중복 수집됐음을 확인한 것은 아니다. 이 행들을 제외한 179,234개에 같은 모델의 예측을 집계한 결과는 다음과 같다.','',
          '| Laya 평가 대상 | F1 평균 % | 탐지율 평균 % | 오탐률 평균 % |',
          '|---|---:|---:|---:|',
          f'| 전체 | {mean(laya,"f1")*100:.2f} | {mean(laya,"recall")*100:.3f} | {mean(laya,"fpr")*100:.3f} |',
          f'| 동일 특성이 학습 구간에 없는 행 | {mean(laya,"nonduplicate_f1")*100:.2f} | {mean(laya,"nonduplicate_recall")*100:.3f} | {mean(laya,"nonduplicate_fpr")*100:.3f} |','',
          '하위 집단의 구성과 공격 비율도 달라지므로 F1 하락 전부를 암기나 데이터 누수의 효과라고 단정할 수 없다. '
          '중복을 제거하고 다시 학습한 실험도 아니다.','',
          '## 속도와 자원 및 자동 대응 평가','',
          '**200ms 충족 여부는 미측정이다.** 단일 요청 p50·p95와 동일 GPU 처리량 측정 전에 중단됐다. '
          '배치 평가에 걸린 총시간을 단일 요청 응답 시간으로 나누어 대체하지 않았다. [5]의 모델 카드 속도도 이번 입력의 측정값으로 사용하지 않았다.','',
          f'Laya의 파라미터 수는 **421,293,827개**, 최상 비교군은 **{params:,}개**로 약 **{first["parameters"]/params:,.0f}배** 차이다. '
          '이는 파라미터 수 비교이며 지연·GPU 메모리·처리 비용이 같은 배율이라는 뜻은 아니다. '
          'Laya는 RTX 4090에서, 비교군 학습은 RTX 2080 Ti에서 수행했으므로 학습 시간으로 하드웨어를 통제한 속도 우열을 판단할 수 없다.','',
          'QoS 조정, 라우팅 변경, 선택적 패킷 차단, 호스트 차단은 실행하거나 효과를 측정하지 않았다. '
          '현재 출력은 이진 탐지이며 대응 방책 선택의 정확도를 평가한 결과가 아니다. window 수집·흐름 종료 대기·대기열·장비 적용을 포함한 '
          '공격 확산 억제 시간도 검증되지 않았다.','',
          '## 논문 발표값과 재현의 한계','',
          '아래 발표값은 원문 Table III의 참고치다. 원문은 90:10 분할을 명시하지만 공개 UNSW 예제는 97.5:2.5를 사용한다. '
          '정확한 평가 행 목록, 설정별 최적 학습률과 seed도 공개되지 않았다. 따라서 Laya의 수치가 발표 F1보다 높아도 '
          '논문 모델을 능가했다는 근거로 삼지 않는다. 판단의 기준은 이번에 같은 평가 행에서 다시 실행한 비교군이다. [1][2]','',
          '| 설정 | 논문 F1 % | 이번 최고 단일 반복 F1 % |','|---|---:|---:|']
    protocol=read('protocol.json')
    for name,info in grid['models'].items():
        L.append(f'| {labels[name]} | {protocol["models"][name]["paper_f1"]:.2f} | {info["best_repeat"]["f1"]*100:.2f} |')
    L += [f'| Laya | 해당 없음 | {max(r["f1"] for r in laya)*100:.2f} |','',
          '최고 단일 반복은 모델별 학습률·반복 중 최대 테스트 F1이며 낙관적 선택값이다. '
          '주요 비교에서는 평균과 변동도 함께 제시했다. 비교군은 15회씩 시도했으나 Laya는 두 번만 완료해 최고값의 선택 기회도 다르다.','',
          '여덟 비교 설정 모두 원문 파라미터 수와 일치했고 입력 조립을 저자 pandas 처리와 대조했다. '
          '그러나 파라미터 수 일치가 완전한 원문 재현을 의미하지는 않는다. 원문의 Dense embedding 설명과 공개 코드의 linear activation이 다르고, '
          'BERT 두 설정은 TensorFlow XLA 컴파일 오류 때문에 모델 전체 JIT을 해제했다. 수치 특성 순서는 환경 간 일관성을 위해 정렬했다. '
          'Adam epsilon과 정밀도·프레임워크·GPU도 서로 다르다. 세부 근거와 실패 기록은 실험 README와 프로토콜에 보존했다.','',
          '## 미학습 공격과 연관 탐지에 대한 판단','',
          '**미학습 공격 탐지 능력은 이번 실험으로 평가하지 못했다.** 모든 공격 종류가 학습 구간에 들어 있으며, '
          '공격 종류를 학습에서 제외하는 평가가 없었다.','',
          '**단일 로그로는 탐지할 수 없는 이상을 연관 분석으로 찾아냈다는 증거도 없다.** '
          '8개 흐름을 입력했지만 v2 CSV에는 흐름 시작 시각이 없고, 행의 인접성이 시간·호스트·다단계 공격 관계를 보장하지 않는다. '
          '같은 모델의 단일 흐름 대조 학습이나 문맥 제거 비교도 없었다. 따라서 성능을 Laya의 연관 추론 능력 때문에 얻었다고 설명할 근거가 없다.','',
          '이 한계는 Laya가 그런 능력이 없다는 증명이 아니라, 선택한 데이터와 평가 방식이 사용자의 핵심 가설을 충분히 검증하지 못했다는 뜻이다. '
          '이번 보고서의 결론은 공개 흐름 데이터에서의 이진 지도학습 성능에 한정한다.','',
          '## 최종 평가','',
          '**진일보 여부는 입증되지 않았다. 관측된 F1·오탐 성능만 보면 현재 Laya 설정은 최상 비교군보다 퇴보했으며, 탐지율은 소폭 개선됐다.** '
          '동일한 두 seed 비교에서도 이 방향은 유지됐다. 그러나 두 반복과 비대칭 탐색만으로 Laya 모델 전체의 열등성을 일반화할 수 없고, '
          '속도·미학습 공격·실제 연관 탐지·자동 대응 효과는 검증되지 않았다. 현재 수치는 새로운 실시간 보안 라우터의 우월성을 주장하거나 '
          '기존 모델을 대체하는 근거로는 부족하다.','',
          '## 근거 자료와 보존 상태','',
          '서버에서 저장된 정답·행 번호·예측 확률로 기본 실험 26회와 비교군 120회 시도를 재검산했다. '
          '데이터 및 학습 표본 스케줄 해시도 확인했다. 첫 Laya의 원본 예측 파일은 로컬 SHA-256과 혼동행렬을 다시 확인했다.','',
          '그 뒤 SSH가 끊겨 두 번째 Laya의 원본 예측 NPZ와 갱신된 서버 묶음을 추가 다운로드하지 못했다. '
          '두 번째 수치는 연결이 살아 있을 때 성공한 서버 재검산 출력에서 보존했고, 혼동행렬로 F1·정밀도·탐지율·오탐률·정확도를 다시 계산했다. '
          '서버 원본이 로컬 증거 묶음에 모두 포함돼 있다고 주장하지 않는다.','',
          '| 근거 | 위치 | 범위 |','|---|---|---|',
          '| 종합 수치와 같은 seed 비교 | `stopped/summary.json` | 이 보고서의 집계값 |',
          '| 서버 재검산 출력에서 보존한 기록 | `stopped/remote_audit_20261006.json` | 두 번째 Laya, 세 번째 중단, 속도 미측정 |',
          '| 비교군 120회 전체 내역 | `runs/grid_results.csv`, `runs/grid_comparison.json` | 모든 학습률·반복·발산 |',
          '| 첫 Laya 원본 | `runs/laya_seed20261005_predictions.npz` | 평가 행·정답·예측 확률 |',
          '| 사전 조건 및 재현 차이 | `protocol.json`, `sensitivity_protocol.json`, `README.md` | 기준 및 변경 사유 |',
          '| 데이터·구조·입력 검증 | `data/prepared/manifest.json`, `runs/architecture_audit.json`, `runs/input_audit.json` | 해시·구성·입력 동등성 |','',
          '## 출처','',
          '1. [FlowTransformer 공개 원문 arXiv 2304.14746v1](https://arxiv.org/pdf/2304.14746v1), 2023-04-28. 2024년 저널 확장판과 구분했다.',
          '2. [FlowTransformer 저자 코드](https://github.com/liamdm/FlowTransformer/tree/52236c8c9feccdeb44acf578789db7b8b36bacea), 고정 커밋의 모델·전처리·표본 추출 구현.',
          '3. [UQ NetFlow 데이터 안내](https://staff.itee.uq.edu.au/marius/NIDS_datasets/).',
          '4. [NF-UNSW-NB15-v2 공식 배포](https://rdm.uq.edu.au/files/8c6e2a00-ef9c-11ed-827d-e762de186848).',
          '5. [convaiinnovations/laya](https://huggingface.co/convaiinnovations/laya/tree/7b928d828b7b0e022f929d9bd2e44165aa270148), 사용한 기본 모델 리비전.','',
          '외부 출처는 모델·데이터·평가 방법의 근거다. 이 보고서의 재실행 수치는 위 로컬 자료와 서버 재검산 기록에서 나온 관측값이다.','']
    report=ROOT/'STOPPED_REPORT.ko.md'
    report.write_text('\n'.join(L),encoding='utf8')
    table.append(dict(model='laya',lr='1e-05',repeats=2,f1_mean=mean(laya,'f1'),f1_sd=agg['f1']['sd'],recall_mean=mean(laya,'recall'),fpr_mean=mean(laya,'fpr')))
    stream=io.StringIO(); w=csv.DictWriter(stream,fieldnames=list(table[0]));w.writeheader();w.writerows(table)
    (OUT/'comparison.csv').write_text(stream.getvalue(),encoding='utf-8-sig')
    shutil.copy2(ROOT/'remote_status.json',OUT/'last_collector_status.json')
    shutil.copy2(ROOT/'runs/verified_progress.json',OUT/'locally_collected_progress_before_stop.json')
    (ROOT/'STATUS.ko.md').write_text('# 실험 중단 후 보고 완료\n\n2026년 10월 6일 사용자 요청으로 중단된 실험의 저장 결과를 종합했다.\n\n'
        '- 비교군 120회 시도 중 119회 정상 완료, 1회 수치 발산.\n- Laya 2/3회 완료, 세 번째 반복은 평가 전 중단.\n'
        '- 단일 요청 지연과 동일 GPU 처리량은 미측정.\n- 학습이나 추론을 재개하지 않았다.\n\n'
        '종합 보고서: [STOPPED_REPORT.ko.md](STOPPED_REPORT.ko.md)\n\n'
        '이전 자동 수집 상태는 stopped/last_collector_status.json에 보존했다. 보고서의 근거 자료 보존 상태를 함께 확인한다.\n',encoding='utf8')
    files=[report,ROOT/'STATUS.ko.md',ROOT/'README.md',ROOT/'protocol.json',ROOT/'sensitivity_protocol.json',
           ROOT/'runtime_provenance.json',ROOT/'data/prepared/manifest.json',ROOT/'write_stopped_report.py',
           ROOT/'runs/grid_comparison.json',ROOT/'runs/grid_results.csv',ROOT/'runs/laya_seed20261005_predictions.npz',
           ROOT/'runs/laya_seed20261005_evaluation.json',ROOT/'runs/laya_seed20261005_training.json',
           ROOT/'runs/architecture_audit.json',ROOT/'runs/input_audit.json',
           OUT/'summary.json',OUT/'remote_audit_20261006.json',OUT/'comparison.csv',
           OUT/'last_collector_status.json',OUT/'locally_collected_progress_before_stop.json']
    hashes={str(p.relative_to(ROOT)).replace('\\','/'):sha(p) for p in files}
    inventory=OUT/'inventory.json';inventory.write_text(json.dumps(hashes,indent=2),encoding='utf8')
    archive=ROOT/'stopped_report_and_evidence.zip'
    with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED) as z:
        for p in files+[inventory]:z.write(p,str(p.relative_to(ROOT)).replace('\\','/'))
    with zipfile.ZipFile(archive) as z:
        assert all(hashlib.sha256(z.read(name)).hexdigest()==expected for name,expected in hashes.items())
    print(json.dumps(dict(report=str(report),archive=str(archive),zip_sha256=sha(archive),
                         f1_mean=mean(laya,'f1'),baseline_f1=mean(base,'f1'),matched_delta_pp=paired_gap,
                         pairs=pairs,parameter_ratio=first['parameters']/params),ensure_ascii=False,indent=2))


if __name__=='__main__':
    main()
