# 논문 기반 공개 네트워크 탐지 실험 v4

공개 네트워크 흐름만으로 학습·평가하는 재현 가능한 실험 파이프라인입니다. Laya, Qwen3-0.6B LoRA, XGBoost, Random Forest, GRU를 같은 자료로 비교합니다. 기존 v1–v3 모델과 결과는 변경하지 않습니다.

현재 실행은 `lspr_pilot_v2`입니다. 완료된 측정치는 `runs/lspr_pilot_v2/RESULTS.md`와 `comparison.json`에 기록됩니다. **파일럿 검증과 논문 본 실험을 구분합니다.** CIC IoT 2023 원본은 공식 이용자 등록/다운로드 링크가 필요하며 아직 확보하지 않았습니다. 개인 정보를 임의로 제출하지 않았습니다.

## 구현 범위

- 공개 원본 수집: Zenodo 레코드 고정, 전체 파일 다운로드 시 제공자 MD5 및 로컬 SHA256 검증.
- 파일럿: HTTP byte range를 정확히 검증하고 받은 구간별 SHA256 저장. 전체 파일의 MD5를 검증했다고 주장하지 않음.
- 정규화: 네트워크 수치만 허용. OSSEC·프로세스 로그·IDS 경보·장비 역할·공격 설명은 모델 입력에서 제외.
- 주소는 묶음 생성에만 사용하고 모델에는 입력마다 새로 부여하는 관계 ID만 전달. 포트도 현재 공통 입력에서는 제외.
- 학습/검증/보정/시험: 시간순으로 정렬하고 누적 흐름 수 약 60/15/10/15%를 기준으로 **5분 구간 전체**를 배정. 구간 시작 60초는 문맥 준비에만 사용. 구간 사이 문맥 초기화.
- 현재 완료된 흐름의 악성 여부를 판정. `single`, `aggregate`, `sequence`, `time_removed`, `unlinked` 입력을 지원. sequence의 기본 문맥은 이전 60초의 통계와 최근 6개 흐름.
- 공격 계열 제외: 해당 계열이 있는 시간 구간 전체를 학습·검증·보정에서 제외. 계열 라벨이 없는 LSPR에서는 이 모드를 명시적으로 거부.
- 모델 선택은 validation AP, 온도·임계값 선택은 calibration만 사용. 최종 시험으로 튜닝하지 않음.
- 오탐 목표 1%·0.1%, 재현율·오탐률·AP·AUROC·혼동행렬·시간 구간 bootstrap·batch 1/8 지연·가변 배치 부하 재생.
- 인퍼런스는 위협 판정을 출력. 최적 대응 정답이 없는 자료로 QoS/라우팅/차단 정확도를 만들어내지 않으며 실제 장비 조작도 하지 않음.

`windowing`은 현재 흐름에 이전 시간 문맥을 붙이는 방식입니다. 기존 v3의 “현재 5초 안에 악성 흐름 하나라도 있는가”와 정답 단위가 다릅니다. 현재 흐름에 대한 동일한 정답으로 단일 입력과 연관 입력을 공정하게 비교하기 위한 선택입니다. **호스트 감염, 다단계 공격 전체, 공격 성공 여부를 직접 라벨링한 실험은 아닙니다.**

## 실행 환경

검증 서버는 `/home/keinus/laya-security-lab`, 기존 `.venv`와 RTX 4090을 사용합니다. 기존 Laya 런타임 및 기초 가중치는 `vendor/laya`, `models/base`에, Qwen 기초 가중치는 `v2/models/qwen3-0.6b`에 있어야 합니다. 추가 패키지는 `requirements.txt`에 있습니다. 새 환경에서는 해당 패키지와 원래 Laya 런타임도 준비해야 합니다. 이 폴더만으로 모델 가중치가 자동 배포되지는 않습니다.

```bash
cd /home/keinus/laya-security-lab
uv pip install --python .venv/bin/python xgboost==3.1.1 duckdb==1.4.1
```

## 파일럿 재현

```bash
.venv/bin/python v4/normalize.py --source lspr23 --mode pilot --rows 300000
.venv/bin/python v4/normalize.py --source lspr24 --mode pilot --rows 150000
.venv/bin/python v4/prepare.py --name lspr_pilot_v2
.venv/bin/python v4/test_protocol.py --experiment lspr_pilot_v2
.venv/bin/python v4/run_experiment.py --experiment lspr_pilot_v2 --epochs 3 --queue
.venv/bin/python v4/smoke_cli.py --experiment lspr_pilot_v2
.venv/bin/python v4/audit_experiment.py --experiment lspr_pilot_v2 --expected-configurations 7
```

이미 준비·학습·평가된 파일은 덮어쓰지 않습니다. 완료된 학습/평가 단계는 실행기가 건너뜁니다. 학습 도중 실패한 디렉터리는 진단 후 보존하거나 별도 실험 이름을 사용해야 합니다. 같은 이름으로 다른 데이터·설정을 덮어쓰면 안 됩니다. `smoke_cli.py` 역시 기존 검증 디렉터리가 있으면 덮어쓰지 않고 중단합니다.

기본 실행 행렬은 XGBoost single/sequence, RF sequence, GRU sequence, Laya single/sequence, Qwen sequence의 7개 구성입니다. 학습 seed는 하나이고 신경망은 최대 3 epoch이므로 모델별 최적 성능 비교로 해석하지 않습니다.

## 전체 데이터와 확장 실험

```bash
.venv/bin/python v4/acquire.py --datasets lspr23 lspr24
.venv/bin/python v4/normalize.py --source lspr23 --mode full
.venv/bin/python v4/normalize.py --source lspr24 --mode full
.venv/bin/python v4/prepare.py --name lspr_full_source --mode full --grouping source --train-cap 10000 --eval-cap 10000 --cal-cap 10000 --eval-sampling uniform
.venv/bin/python v4/run_experiment.py --experiment lspr_full_source --seeds 20261005 20261006 20261007 --epochs 5 --views single aggregate sequence time_removed unlinked --queue
```

같은 설정으로 `--grouping pair` 또는 `--grouping network`를 다른 실험 이름에 적용하면 묶음 방식을 비교할 수 있습니다. 전체 다운로드/정규화 후에도 위 명령의 학습·시험 표본은 cap의 제한을 받습니다. `uniform`은 시험의 클래스 비율을 임의로 맞추지 않으며 각 적격 분할에서 균일 추출합니다. 3개 seed로 **학습 변동**을 측정하고, 공격 캠페인의 독립 반복을 대신한다고 주장하지 않습니다.

## CIC IoT 2023 연결

공식 PCAP을 확보한 뒤 아래 구조로 매니페스트를 작성합니다. 값은 실제 파일과 제공자 분류를 확인해서 채워야 합니다. 가짜 해시나 추정 공격 라벨을 허용하지 않습니다.

```json
{
  "captures": [
    {
      "capture": "unique_capture_id",
      "pcap": "/absolute/path/to/official.pcap",
      "pcap_sha256": "ACTUAL_SHA256",
      "family": "PUBLISHER_ATTACK_FAMILY_OR_EMPTY_FOR_BENIGN",
      "label": 1,
      "source_url": "OFFICIAL_DOWNLOAD_URL"
    }
  ]
}
```

```bash
.venv/bin/python v4/extract_cic.py --manifest /absolute/path/pcaps.json --output v4/data/cic/zeek_manifest.json --zeek /path/to/zeek
.venv/bin/python v4/normalize.py --source ciciot2023 --mode full --cic-manifest v4/data/cic/zeek_manifest.json
.venv/bin/python v4/prepare.py --name cic_holdout_family --source ciciot2023 --external '' --mode full --heldout-family EXACT_FAMILY_NAME --train-cap 10000 --eval-cap 10000 --eval-sampling uniform
.venv/bin/python v4/run_experiment.py --experiment cic_holdout_family --seeds 20261005 20261006 20261007
```

Zeek 바이너리는 필요하며 현재 서버에는 설치되어 있지 않습니다. Docker도 현재 사용자 권한으로 사용할 수 없습니다. 어댑터 구현과 TSV/JSON 파싱 검사는 완료하지만 **실제 CIC PCAP → Zeek 전환은 아직 실행하지 않았습니다.** 추출 시 Zeek 버전·명령·PCAP/로그 해시를 저장합니다. 이 어댑터는 제공자의 PCAP 단위 공격 분류를 흐름에 상속하므로 패킷별 독립 정답과 구분합니다. 짧은 캡처는 최소 구간 수 조건을 만족하도록 시간 설정을 검토해야 하며 임의 행 분할로 대체하지 않습니다.

`missed_bytes / (orig_bytes + resp_bytes + missed_bytes)`가 1%를 넘는 흐름은 제외합니다. 이는 Zeek 바이트 누락률의 대용 지표이며 실제 패킷 손실 정답이나 논문 필터의 완전한 재현으로 간주하지 않습니다.

## 인퍼런스

```bash
.venv/bin/python v4/infer_v4.py --folder v4/runs/lspr_pilot_v2/sequence/laya/seed-20261005 --input v4/data/experiments/lspr_pilot_v2/known_test.jsonl --output v4/runs/laya_inference.jsonl --batch-size 8
```

입력은 `state`를 가진 v4 JSONL입니다. 학습 정답은 인퍼런스에 필요하지 않습니다. 배치를 늘릴 수 있지만 보정 때의 batch 1과 부동소수점 경계 판정이 달라질 수 있습니다. 실제 서버라면 최대 배치 크기와 형성 대기 상한을 함께 적용해야 합니다. `benchmark_v4.py`의 부하 시험은 최대 8개·10ms 대기를 사용합니다.

## 해석상의 한계

- 파일럿은 LSPR23 CSV 앞 30만 행과 LSPR24의 row group 0/10/20에서 각 5만 행을 읽었습니다. 전체 연속 트래픽이 아니므로 문맥 손실이 있으며 대표 표본도 아닙니다.
- LSPR24 선택분에서 종료 시각이 시작보다 빠른 15,464행(정상 15,366, 공격 98)을 제외했습니다. 시각을 임의로 수정하지 않았으며 이 필터의 모집단 영향을 입증하지는 않았습니다.
- 파일럿은 클래스별 추출 상한을 사용합니다. 정밀도·AP는 이 표본의 값이며 실제 공격 발생률에서의 값이 아닙니다. 시험 오탐률 0은 향후 오탐이 없다는 뜻이 아닙니다.
- LSPR23→24는 다른 연도 일반화입니다. 미학습 공격이나 zero-day 검증으로 명명하지 않습니다.
- 흐름 종료 시각은 관측 가능 시각의 하한입니다. 센서 로그가 실제로 배출되는 시각을 알 수 없어 공격 시작부터 대응까지 200ms라고 주장할 수 없습니다.
- 대응 정답·개입 후 결과가 없으므로 피해 감소율과 정상 통신 손실은 별도 통제 재생 환경에서 측정해야 합니다.

## 논문 근거

원문 링크와 적용/재현 범위는 `paper_protocol.json`에 저장했습니다.

- [2026 LLM·ML·DL 비교](https://link.springer.com/article/10.1007/s10462-025-11432-2): 모델 비교 구성 참고. 저자의 비공개 분할을 재현했다고 주장하지 않음.
- [SAFE-NID, TMLR 2025](https://www.brianmatejek.com/assets/pdfs/research/publications/matejek2025safe.pdf): 공격 계열을 제외하는 평가 원칙 참고. 모델 구조/점수 재현 아님.
- [CyCon 2025](https://roland-meier.ch/files/2025_CyCon_AI.pdf): LSPR24 데이터 출처.
- [2026 흐름 묶음 연구](https://ssrn.com/abstract=6597680): 공개 초록에서 그룹 구성·연도 간 비교 설계 참고. 전체 프로토콜 및 정식 게재 여부 미확인.

자료 선택, 구간 길이, 입력 길이, 임계값 목표와 모델 학습 예산은 이 구현의 실험 설계입니다. 위 논문들이 이 설정의 최적성을 입증했다고 해석하지 않습니다.
