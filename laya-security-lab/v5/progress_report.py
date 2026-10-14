"""Build an explicitly provisional report from audited partial and grid results."""
import hashlib
import json
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo

import numpy as np

ROOT = Path(__file__).resolve().parent


def main():
    progress = json.loads((ROOT / 'runs/verified_progress.json').read_text())
    grid = json.loads((ROOT / 'runs/grid_comparison.json').read_text())
    laya = [r for r in progress['rows'] if r['model'] == 'laya']
    if len(laya) != 1:
        raise ValueError('This report describes the first completed Laya repeat only')
    l = laya[0]
    pred_path = ROOT / 'runs/laya_seed20261005_predictions.npz'
    with pred_path.open('rb') as f:
        assert hashlib.file_digest(f, 'sha256').hexdigest() == l['prediction_sha256']
    z = np.load(pred_path)
    y, pred = z['y'].astype(bool), z['probability'] > .5
    counts = dict(TP=int((pred & y).sum()), FP=int((pred & ~y).sum()),
                  TN=int((~pred & ~y).sum()), FN=int((~pred & y).sum()))
    assert all(l[k] == v for k, v in counts.items())
    assert np.array_equal(z['row'], np.arange(2151248, 2390275))
    name = grid['best_mean_model']
    b = grid['models'][name]['best_mean']
    gap = (l['f1'] - b['f1_mean']) * 100
    stamp = datetime.fromtimestamp(progress['updated'], ZoneInfo('Asia/Seoul')).isoformat(timespec='minutes')
    lines = [
        '# Laya와 FlowTransformer 비교: 첫 반복 완료 중간 보고', '',
        f'검증 시점: {stamp}. **Laya는 1/3회 완료했으며 최종 우열 판정은 보류한다.**', '',
        f'Laya 첫 반복의 F1은 {l["f1"]*100:.2f}%다. 학습률 탐색 후 최상 비교군 '
        f'`{name}`의 세 반복 평균은 {b["f1_mean"]*100:.2f}%이며, 차이는 {gap:+.2f}%p다. '
        'Laya는 탐지율이 높지만 오탐률도 조금 높다. 현재 관측에서는 종합 탐지 성능의 뚜렷한 개선을 확인하지 못했다.', '',
        '## 진행 상태', '',
        f'- 비교군: 120회 시도 완료. {120-grid["diverged"]}회 정상 완료, {grid["diverged"]}회 수치 발산. 발산을 0점으로 바꾸거나 누락하지 않았다.',
        '- Laya: 첫 seed는 17 epoch에서 사전 정의한 학습 손실 기준으로 조기 종료했다. 두 번째 seed 학습 중이며 세 번째는 대기 중이다.',
        '- 동일 GPU에서의 단일 요청 p95·처리량 측정은 아직 시행 전이다. 200ms 충족 여부는 판단할 수 없다.', '',
        '## 학습률 탐색을 마친 비교군과의 비교', '',
        '공식 NF-UNSW-NB15-v2의 마지막 239,027개 흐름을 모두 평가했다. 모든 모델에 '
        '같은 데이터 분할, 37개 특성, 8행 window, seed별 표본 스케줄을 적용했다. '
        '비교군은 평균 F1이 가장 높은 학습률의 세 반복 평균 ± 표본 표준편차, Laya는 첫 반복 단일 값이다.', '',
        '| 설정 | 학습률 | 완료 반복 | F1 % | 탐지율 % | 오탐률 % | 최고 단일 반복 F1 % |',
        '|---|---:|---:|---:|---:|---:|---:|',
    ]
    for model, result in grid['models'].items():
        m = result['best_mean']
        lines.append(f'| {model} | {result["best_mean_lr"]} | 3 | '
                     f'{m["f1_mean"]*100:.2f} ± {m["f1_sd"]*100:.2f} | '
                     f'{m["recall_mean"]*100:.2f} | {m["fpr_mean"]*100:.3f} | '
                     f'{result["best_repeat"]["f1"]*100:.2f} |')
    lines += [
        f'| **Laya** | 0.00001 | **1** | **{l["f1"]*100:.2f}** | **{l["recall"]*100:.2f}** | **{l["fpr"]*100:.3f}** | {l["f1"]*100:.2f} |', '',
        '학습률은 같은 테스트 집합의 F1을 기준으로 선택했다. 따라서 비교군 성적에는 선택에 따른 '
        '낙관성이 있으며 독립 검증이 아니다. Laya는 한 학습률만 사용했다. 또한 한 번의 결과와 '
        '세 번의 평균을 비교한 중간 집계이므로 통계적 우월성이나 열등성을 주장하지 않는다. '
        '최고 단일 반복 열은 모든 학습률과 반복 중 최댓값으로, 평균 열과 다른 선택이다.', '',
        '## Laya의 실제 판정', '',
        f'- 공격 {l["TP"]+l["FN"]:,}개 중 **{l["TP"]:,}개 탐지, {l["FN"]}개 누락**.',
        f'- 정상 {l["TN"]+l["FP"]:,}개 중 **{l["FP"]:,}개를 공격으로 오탐**.',
        f'- 정밀도 **{l["precision"]*100:.2f}%**. 정상 10,000개 흐름당 오탐 약 **{l["fpr"]*10000:.1f}개**에 해당한다.',
        f'- 학습 구간과 동일한 37개 특성을 가진 평가 행을 제외하면 F1 **{l["nonduplicate_f1"]*100:.2f}%**, 오탐률 **{l["nonduplicate_fpr"]*100:.3f}%**다. 이는 중복 제거 후 재학습한 실험이 아니다.', '',
        '## 중간 판단과 남은 검증', '',
        '**현재로서는 진일보를 입증하지 못했다.** 강한 비교군과 종합 F1이 비슷하며, '
        '높은 탐지율과 늘어난 오탐 사이의 교환 관계가 관측된다. Laya의 나머지 두 반복과 '
        '단일 요청 지연을 확인한 뒤 최종 판단한다. 지금의 차이만으로 퇴보라고 단정하지도 않는다.', '',
        '모든 공격 종류가 학습에도 포함되므로 미학습 공격 탐지를 입증한 결과가 아니다. '
        '8행 window는 타임스탬프 없는 CSV의 행 순서이며 실제 호스트별 공격 연쇄를 '
        '보장하지 않는다. QoS·라우팅·패킷 차단·호스트 차단의 효과 역시 이번 이진 판정 '
        '벤치마크에서는 측정하지 않았다.', '',
        '## 재검산 가능한 근거', '',
        '- `runs/verified_progress.json`: 완료된 25회 고정 실험의 지표 재검산 및 해시.',
        '- `runs/grid_comparison.json`, `runs/grid_results.csv`: 비교군 전체 120회 시도와 학습률별 결과.',
        '- `runs/laya_seed20261005_predictions.npz`: 첫 Laya 반복의 평가 행 번호, 정답, 예측 확률.',
        '- `runs/laya_seed20261005_training.json`, `runs/laya_seed20261005_evaluation.json`: 학습 이력과 공격별 탐지율.',
        '- 원문 및 구현 차이: `README.md`, `protocol.json`, `sensitivity_protocol.json`.', '',
        '원본 예측 파일로 지표를 재계산했고 데이터·표본 스케줄의 해시를 확인했다. '
        '다운로드한 Laya 예측 파일의 SHA-256과 혼동행렬도 로컬에서 다시 검증했다.', '',
        '## 출처', '',
        '- [FlowTransformer 기준 논문](https://arxiv.org/pdf/2304.14746v1)',
        '- [FlowTransformer 저자 코드](https://github.com/liamdm/FlowTransformer)',
        '- [공식 NF-UNSW-NB15-v2 데이터](https://rdm.uq.edu.au/files/8c6e2a00-ef9c-11ed-827d-e762de186848)',
        '- [Laya 모델](https://huggingface.co/convaiinnovations/laya)', '',
    ]
    (ROOT / 'INTERIM.ko.md').write_text('\n'.join(lines), encoding='utf8')
    print(json.dumps(dict(report=str(ROOT/'INTERIM.ko.md'),laya=l,baseline=b,gap_pp=gap), ensure_ascii=False))


if __name__ == '__main__':
    main()
