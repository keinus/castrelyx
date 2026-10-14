"""Write a report only from measured batch sweep and bounded-wait load results."""
from pathlib import Path
from datetime import datetime,timezone,timedelta
import json
ROOT=Path(__file__).resolve().parent

def main():
    data=json.loads((ROOT/'runs/comparison.json').read_text())
    assert len(data['models'])==2 and all(len(m['offline'])==5 and len(m['load'])==5 for m in data['models'].values())
    offline=['| 모델 | 배치 | 윈도/초 | 배치 p95 | 윈도당 평균 연산 | 최대 GPU 할당 | 미학습 재현율 | 정상 오탐률 |','|---|---:|---:|---:|---:|---:|---:|---:|']
    load=['| 모델 | 최대 배치 | 실제 평균 배치 | 대기 포함 p95 | 200ms 이내 |','|---|---:|---:|---:|---:|']
    labels={'laya_v3_normal_relation_sft':'Laya 정상·관계','qwen3_06b_v3_lora':'Qwen3 0.6B'}
    changes=[]
    for name,m in data['models'].items():
        for r in m['offline']:
            if 'error' in r:offline.append(f"| {labels[name]} | {r['batch_size']} | OOM | — | — | — | — | — |");continue
            u=r['tests']['unseen_test']
            offline.append(f"| {labels[name]} | {r['batch_size']} | {r['windows_per_second']:.2f} | {r['batch_p95_ms']:.2f}ms | {r['amortized_compute_ms']:.2f}ms | {r['peak_allocated_mib']/1024:.2f}GiB | {100*u['recall']:.2f}% | {100*u['fpr']:.2f}% |")
            changes.append(f"- {labels[name]} 배치 {r['batch_size']}: 기존 v3 단건 판단과 {r['different_decisions_from_v3_batch1']}/{r['cases']}개 차이.")
        for r in m['load']:load.append(f"| {labels[name]} | {r['max_batch_size']} | {r['effective_mean_batch_size']:.2f} | {r['response_p95_ms']:.2f}ms | {100*r['within_200ms_fraction']:.2f}% |")
    text=f'''# Laya/Qwen 추론 배치 크기 비교

실행일: {datetime.now(timezone(timedelta(hours=9))).strftime('%Y-%m-%d')} KST. 장비: {data['gpu']}.

기존 v3 체크포인트와 온도·판정 문턱을 고정하고 배치 1·4·8·16·32를 시험했습니다. 배치는 **동시에 처리하는 호스트별 윈도 수**입니다. 윈도별 현재 5초·이전 60초 집계와 최대 6개 개별 흐름 입력은 그대로입니다. 재학습이나 공격 대응 장비 조작은 수행하지 않았습니다.

## 모든 입력이 준비된 경우

모델별·배치별 동일한 2,324개 최종 시험을 동일한 섞인 순서로 재생했습니다. 토큰화와 모델 계산을 포함합니다. 모델 로드·윈도 생성·요청을 모으는 대기·장비 적용은 제외했습니다. BF16, 모델별 CPU 스레드 4개, 모델을 하나씩 순차 실행했습니다.

{chr(10).join(offline)}

**배치 p95가 배치 안의 요청이 계산 완료를 기다리는 시간입니다.** 윈도당 평균 연산 시간은 처리 비용을 나눈 수치이며 개별 요청의 응답 지연이 아닙니다. GPU 수치는 PyTorch의 최대 할당량으로, 다른 프로그램의 사용량이나 예약 메모리까지 합친 전체 VRAM 사용량은 아닙니다. 최대 32까지의 측정이며 그 이상은 측정하지 않았습니다.

## 요청이 도착하는 경우

초당 50개 요청을 10초 동안 한 작업자에게 일정 간격으로 도착시켰습니다. 배치를 모으는 대기는 가장 오래된 미처리 요청의 도착부터 최대 10ms입니다. 작업자가 이미 바쁜 시간은 이 제한에 포함되지 않으므로 전체 대기는 10ms를 넘을 수 있습니다. 이전 시험의 큐가 완전히 비워진 뒤 다음 시험을 실행했습니다.

{chr(10).join(load)}

최대 배치를 지정해도 항상 그 개수로 실행하지 않습니다. 도착률이 낮으면 작은 배치로 출발합니다. 예를 들어 초당 50개에서 32개가 찰 때까지 무조건 기다리면 첫 요청은 계산 시작 전부터 약 620ms를 기다리므로 200ms 목표와 맞지 않습니다. 위 측정은 최대 대기를 제한한 짧은 합성 부하 시험이며 장시간 운영 보장은 아닙니다. 실제 센서·네트워크·윈도 대기·장비 조치는 포함하지 않았습니다.

## 정확도와 재현 범위

미학습 시험은 v3와 같은 Hakai·Torii 공격 창 274개와 정상 창 1,110개입니다. 기존 v3 단건 결과와 비교한 판단 차이는 다음과 같습니다.

{chr(10).join(changes)}

BF16에서 배치 크기·패딩 모양이 달라지면 점수와 경계 판정도 달라질 수 있습니다. 배치를 키우는 것 자체가 더 많은 로그 관계를 학습하거나 탐지력을 높이는 것은 아닙니다. 이번 시험 결과로 가중치나 문턱을 다시 맞추지 않았습니다. v3에서 관측된 높은 정상 오탐 문제도 배치 변경만으로 해결됐다고 해석하지 않습니다.

## 실행 코드

기존 `infer_v3.py`는 그대로 보존했고, 파일 입력에 배치 크기를 지정하는 CLI를 별도로 추가했습니다.

```bash
cd /home/keinus/laya-security-lab/v3
../.venv/bin/python batching/infer_batch.py \\
  --model laya_v3_normal_relation_sft --batch-size 16 \\
  --input runs/cli_smoke_input.jsonl --output batching/runs/inference_b16.jsonl
```

이 CLI는 준비된 JSONL 파일을 순서대로 읽으며 마지막 불완전 배치도 처리합니다. `model_batch_ms`는 배치 계산 시간이고 `amortized_compute_ms`는 윈도 수로 나눈 비용입니다. 실시간 큐 서비스는 배포하지 않았으며 최대 10ms 대기 방식은 `benchmark_batches.py`의 부하 시험으로 검증했습니다.

- `runs/comparison.json`: 전체 정확도·처리량·GPU 메모리·부하 측정.
- `runs/predictions_*_b*.jsonl`: 모든 배치 크기의 원시 예측.
- `runs/load_*_b*.jsonl`: 요청별 예정 도착·큐 대기·처리·응답 지연.
- `runs/verification.json`: 입력 개수·순서·마지막 배치·예측 집계·해시 검사.

기존 v3 데이터, 체크포인트, 공통 런타임 및 서버 가상환경을 재사용합니다. 배치 비교 재현 명령은 `../../.venv/bin/python benchmark_batches.py`이며, 이 폴더에서 실행하면 해당 배치 실험 산출물을 갱신합니다.
'''
    (ROOT/'README.md').write_text(text,encoding='utf8')

if __name__=='__main__':main()
