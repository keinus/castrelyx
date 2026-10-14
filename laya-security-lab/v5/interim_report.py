"""Render audited baseline progress without manufacturing a final comparison."""
import csv
import io
import json
from datetime import datetime, timezone
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parent


def main():
    progress = json.loads((ROOT / 'runs/verified_progress.json').read_text())
    rows = progress['rows']
    if len(rows) != 24 or any(r['model'] == 'laya' for r in rows):
        raise ValueError('This interim snapshot is only for the 24 completed baseline runs')
    stamp = datetime.fromtimestamp(progress['updated'], timezone.utc).astimezone().isoformat(timespec='seconds')
    names = list(dict.fromkeys(r['model'] for r in rows))
    lines = [
        '# Laya와 FlowTransformer 비교: 중간 결과', '',
        '**최종 비교는 아직 완료되지 않았다. Laya의 진일보·퇴보 판정은 보류한다.**', '',
        f'예측 파일을 재검산한 시점: {stamp}. 완료된 고정 설정 학습은 {len(rows)}/27회다.', '',
        '기준은 [FlowTransformer 공개 원문](https://arxiv.org/pdf/2304.14746v1)의 '
        'Table III UNSW-NB15 실험이다. 원문의 네 Transformer 계열, 각 두 설정을 '
        '공개 저자 코드로 다시 학습하고 Laya와 비교한다. 이전 v1–v4 결과는 섞지 않았다.', '',
        '공식 NF-UNSW-NB15-v2의 전체 2,390,275행을 확보했다. 처음 2,151,248행에서 '
        '학습 표본을 추출하고 마지막 239,027행 전부를 평가한다. 입력은 같은 37개 특성의 '
        '8행 window다. 모든 모델에 같은 seed별 표본 스케줄을 적용한다.', '',
        '## 검증이 끝난 기본 설정 결과', '',
        '아래는 비교군 Adam 학습률 0.001의 세 반복 평균 ± 표본 표준편차다. '
        '최적 학습률의 성적이 아니며, 일부 설정은 학습에 실패한 것에 가까운 판정을 냈다. '
        '이 표만으로 Laya나 모델 구조의 우열을 판단하지 않는다.', '',
        '| 설정 | 반복 수 | F1 % | 탐지율 % | 오탐률 % |',
        '|---|---:|---:|---:|---:|',
    ]
    for name in names:
        selected = [r for r in rows if r['model'] == name]
        cells = []
        for metric in ['f1', 'recall', 'fpr']:
            values = np.asarray([r[metric] for r in selected]) * 100
            cells.append(f'{values.mean():.2f} ± {values.std(ddof=1):.2f}')
        lines.append(f'| {name} | {len(selected)} | ' + ' | '.join(cells) + ' |')
    lines += [
        '| Laya | 0 | 학습 중 | 평가 대기 | 평가 대기 |', '',
        '정답·행 번호·예측 확률 원본으로 혼동행렬과 지표를 다시 계산했고, 학습 데이터와 '
        '표본 스케줄의 해시도 확인했다. 개별 반복 값은 `runs/verified_progress.json` 및 '
        '`runs/primary_interim.csv`에 있다.', '',
        '## 진행 중인 작업', '',
        'Laya는 사전 지정한 Adam 0.00001로 세 번 전체 파인튜닝한다. 비교군에는 논문에 '
        '있는 다섯 학습률을 모두 적용하는 추가 96회 실험이 진행 중이다. 이는 기본 설정 '
        'BERT의 수렴 실패를 확인한 뒤, Laya의 첫 테스트 평가 전에 명세를 고정한 사후 '
        '민감도 분석이다. 기본 설정 결과는 보존한다. 탐색량이 서로 같다는 주장은 하지 않는다.', '',
        '학습 후 각 모델을 동일한 RTX 4090에서 단독 실행하여 논문식 처리량과 직렬화·토큰화를 '
        '포함한 개별 요청 p95를 측정한다. 사용자 목표인 200ms 충족 여부는 이 p95로 판단한다.', '',
        '최종 결과는 모든 학습·평가·속도 측정과 검증이 성공해야 `RESULTS.ko.md` 및 '
        '`results_bundle.zip`으로 생성된다. 현재 진행 상황은 `STATUS.ko.md`에 기록된다.', '',
        '## 현재 확정할 수 있는 한계', '',
        '- 원문은 90:10 분할을 명시하지만 공개 예제는 97.5:2.5다. 발표 수치와의 단순 차이는 개선의 근거로 삼지 않는다.',
        '- v2 CSV에는 흐름 시작 시각이 없다. 8행의 순서가 실제 시간·호스트별 공격 연쇄임을 보장하지 않는다.',
        '- 모든 공격 종류가 학습 구간에도 있다. 이번 실험으로 미학습 공격 탐지를 입증할 수 없다.',
        '- 평가 행 59,793개는 학습 구간에도 같은 37개 특성을 가진 행이 있다. 중복 포함 여부별 지표를 별도로 산출한다.',
        '- QoS·라우팅·선택 차단·호스트 차단의 효과와 window 수집·장비 적용 지연은 이 탐지 실험에서 측정하지 않는다.', '',
        '자세한 차이와 복원 근거는 `README.md`, 실행 전 고정 조건은 `protocol.json`, '
        '추가 탐색 조건은 `sensitivity_protocol.json`에 있다.', '',
        '## 출처', '',
        '- [FlowTransformer 원문](https://arxiv.org/pdf/2304.14746v1)',
        '- [FlowTransformer 저자 코드](https://github.com/liamdm/FlowTransformer)',
        '- [UQ 공식 NF-UNSW-NB15-v2](https://rdm.uq.edu.au/files/8c6e2a00-ef9c-11ed-827d-e762de186848)',
        '- [사용자 지정 Laya 모델](https://huggingface.co/convaiinnovations/laya)', '',
    ]
    (ROOT / 'INTERIM.ko.md').write_text('\n'.join(lines), encoding='utf8')
    buffer = io.StringIO()
    writer = csv.DictWriter(buffer, fieldnames=list(rows[0]))
    writer.writeheader()
    writer.writerows(rows)
    (ROOT / 'runs/primary_interim.csv').write_text(buffer.getvalue(), encoding='utf-8-sig')
    print(ROOT / 'INTERIM.ko.md')


if __name__ == '__main__':
    main()
