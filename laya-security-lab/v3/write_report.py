"""Generate the Korean report from measured, completed runs only."""
from pathlib import Path
import json
ROOT=Path(__file__).resolve().parent
def pct(x):return f'{100*x:.2f}%'
def main():
    c=json.loads((ROOT/'runs/comparison.json').read_text());m=c['models'];a=json.loads((ROOT/'runs/analysis.json').read_text());data=c['data_manifest']['splits']
    labels={'laya_v2_transfer':'이전 Laya v2 전이¹','laya_v3_sft':'Laya: 분류 학습','laya_v3_normal_relation_sft':'Laya: 정상·관계 후 분류','qwen3_06b_v3_lora':'Qwen3 0.6B LoRA','extra_trees':'ExtraTrees','hist_gradient_boosting':'HistGradientBoosting','isolation_forest':'IsolationForest'}
    table=['| 모델 | 기존 공격 재현율 | 기존 정상 오탐률 | 미학습 공격 재현율 | 미학습 정상 오탐률 | 공격군 평균 재현율 | 단건 p95 |','|---|---:|---:|---:|---:|---:|---:|']
    family=['| 모델 | Hakai | Torii | 별도 Mirai 수집 시험 | 해당 정상 오탐률 |','|---|---:|---:|---:|---:|']
    context=['| 모델 | 전체 문맥: 미학습 재현율 / 오탐률 | 이전 문맥 제거: 재현율 / 오탐률 |','|---|---:|---:|']
    ranking=['| 모델 | 보정 자료로 선택한 문턱 | 미학습 PR-AUC | 미학습 ROC-AUC |','|---|---:|---:|---:|']
    for n,r in m.items():
        k=r['tests']['known_test'];u=r['tests']['unseen_test'];e=r['tests']['external_known_test'];f=u['attack_families'];ab=r['without_context']['unseen_test']
        table.append(f"| {labels[n]} | {pct(k['recall'])} ({k['tp']}/{k['positive']}) | {pct(k['fpr'])} | {pct(u['recall'])} ({u['tp']}/{u['positive']}) | {pct(u['fpr'])} | {pct(a['models'][n]['unseen_macro_family_recall'])} | {r['latency']['p95_ms']:.2f}ms |")
        family.append(f"| {labels[n]} | {f['Hakai']['detected']}/{f['Hakai']['n']} | {f['Torii']['detected']}/{f['Torii']['n']} | {e['tp']}/{e['positive']} | {pct(e['fpr'])} |")
        context.append(f"| {labels[n]} | {pct(u['recall'])} / {pct(u['fpr'])} | {pct(ab['recall'])} / {pct(ab['fpr'])} |")
        ranking.append(f"| {labels[n]} | {r['policy']['threshold']:.6f} | {u['auprc']:.4f} | {u['auroc']:.4f} |")
    d=a['paired_bootstrap']['unseen_test'];base=m['laya_v3_sft']['tests']['unseen_test'];new=m['laya_v3_normal_relation_sft']['tests']['unseen_test']
    if d['enhanced_minus_sft_recall']>0:
        conclusion='추가 학습 모델의 미학습 재현율이 더 높게 관측됐습니다.'
        conclusion+=(' 다만 오탐률도 증가했으므로 같은 오탐 비용에서 우수하다고 단정할 수 없습니다.' if d['enhanced_minus_sft_fpr']>0 else ' 시험 오탐률은 분류 학습 모델보다 높지 않았습니다.')
    else:conclusion='정상·관계 학습을 추가해도 미학습 재현율은 개선되지 않았습니다. 이번 설정에서 제안한 추가 학습의 탐지율 개선을 입증하지 못했습니다.'
    load=json.loads((ROOT/'runs/load_comparison.json').read_text());loadtable=['| 모델 | 요청/초 | 대기 포함 p95 | 200ms 이내 |','|---|---:|---:|---:|']
    for n,rs in load['models'].items():
        for r in rs:loadtable.append(f"| {labels[n]} | {r['offered_rps']} | {r['response_p95_ms']:.2f}ms | {pct(r['within_200ms_fraction'])} |")
    online=json.loads((ROOT/'runs/online_comparison.json').read_text())['models']
    ontable=['| 모델 | 기존 공격 재현율 | 미학습 공격 재현율 | 미학습 정상 오탐률 | 배치와 다른 판단 수 / 전체 시험 |','|---|---:|---:|---:|---:|']
    for n,r in online.items():
        k=r['tests']['known_test'];u=r['tests']['unseen_test'];diff=sum(v['different_decisions_from_batch'] for v in r['tests'].values())
        ontable.append(f"| {labels[n]} | {pct(k['recall'])} | {pct(u['recall'])} ({u['tp']}/{u['positive']}) | {pct(u['fpr'])} ({u['fp']}/{u['fp']+u['tn']}) | {diff}/2324 |")
    training=[]
    for mode in ['sft','normal_relation']:
        r=json.loads((ROOT/f'runs/laya_{mode}_complete.json').read_text());selected=json.loads((ROOT/f'models/laya_v3_{mode}/rl_agent_config.json').read_text())['training']
        training.append(f"- Laya {mode}: 총 {r['steps']}회 업데이트, {r['seconds']/60:.2f}분. 선택 체크포인트는 분류 {selected['selected_epoch']}에포크 종료·누적 {selected['steps']}회 업데이트. SHA-256 `{r['weights_sha256']}`.")
    q=json.loads((ROOT/'runs/qwen_complete.json').read_text());training.append(f"- Qwen3 0.6B: {q['steps']}회 업데이트, {q['seconds']/60:.2f}분, 어댑터 SHA-256 `{q['adapter_sha256']}`.")
    text=f'''# 실제 Zeek 시퀀스 재학습 및 미학습 공격 비교 — v3

실행일: 2026-10-05(KST). 서버: `192.168.50.20:/home/keinus/laya-security-lab/v3`. 이전 v1/v2 결과는 보존했습니다.

## 결과와 판단

원본 Laya에서 두 가지 학습을 실제 수행하고 Qwen3 0.6B도 새 자료로 LoRA 학습했습니다. 이전 Laya 전이와 수치 기반 모델을 포함해 7개 구성을 각각 {sum(data[s]['cases'] for s in ['known_test','unseen_test','external_known_test'])}개 최종 시험 창에서 평가했습니다.

실시간 사용 조건인 **배치 1로 전체 시험을 재생**했을 때 정상·관계 Laya의 미학습 재현율은 **{pct(online['laya_v3_normal_relation_sft']['tests']['unseen_test']['recall'])}**, 정상 오탐률은 **{pct(online['laya_v3_normal_relation_sft']['tests']['unseen_test']['fpr'])}**였습니다. Qwen은 각각 **{pct(online['qwen3_06b_v3_lora']['tests']['unseen_test']['recall'])}**, **{pct(online['qwen3_06b_v3_lora']['tests']['unseen_test']['fpr'])}**였습니다. 현재 자동 차단 적용의 가장 큰 미해결 문제는 높은 오탐입니다.

별도 배치 평가에서 분류 학습 Laya의 미학습 재현율은 **{pct(base['recall'])}**, 정상·관계 후 분류 모델은 **{pct(new['recall'])}**입니다. 정상 오탐률은 각각 **{pct(base['fpr'])}**, **{pct(new['fpr'])}**입니다. {conclusion}

배치 평가에서 추가 학습 모델에서 기준 모델을 뺀 재현율 차이는 {100*d['enhanced_minus_sft_recall']:.2f}%p입니다. 동일 호스트·30분 묶음을 함께 재표집한 95% 구간은 {100*d['recall_difference_95'][0]:.2f}~{100*d['recall_difference_95'][1]:.2f}%p입니다. 독립 공격 캠페인이 적고 학습 시드도 하나이므로 이 구간을 모든 현장에 대한 확신으로 확대하지 않습니다.

**v2의 CICIDS 시험과 v3의 IoT-23 시험은 서로 다릅니다.** v2의 7.58%와 이 보고서의 비율을 빼서 개선 폭으로 제시할 수 없습니다. 추가 학습의 효과는 같은 새 데이터·입력·최대 업데이트 예산·체크포인트 선택 규칙을 사용한 위 두 Laya 사이에서 판단합니다.

## 비교 결과

아래 정확도는 Laya 배치 16, Qwen 배치 8, 수치 모델 일괄 예측으로 계산한 배치 평가입니다. 오른쪽 지연은 별도로 단건 추론으로 측정했습니다. 같은 가중치의 단건 전체 재생 결과는 다음 절에서 분리해 제시합니다.

{chr(10).join(table)}

¹ 이전 Laya v2 가중치를 v3 질문과 상태에 적용한 전이 참고치입니다. 이전 입력 계약에서의 성능을 그대로 재측정한 수치가 아닙니다. Qwen은 긴 설명 생성 없이 thinking을 끄고 A/B 답 토큰의 점수를 비교합니다. Laya/Qwen은 RTX 4090, 수치 모델은 서버 CPU에서 실행했습니다. 동등한 연산 예산이나 최적화 수준을 보장한 아키텍처 대결은 아닙니다.

각 모델의 문턱은 별도 calibration의 정상 {data['calibration']['labels']['0']}개에서 관측 오탐률 1% 이하를 만족하는 문턱 중 F1이 가장 높은 것으로 고정했습니다. 외부 시험의 오탐률 1%를 보장하지 않습니다. 온도 보정도 calibration만 사용했습니다. 최종 시험 결과로 문턱·가중치를 다시 맞추지 않았습니다. 수치 모델은 같은 관측 상태의 집계·서비스·포트·상태·반복 관계 특징을 사용합니다. IsolationForest는 정상 학습 표본만으로 적합했지만 문턱 선택에는 알려진 공격 라벨도 사용했습니다. 그 출력은 정상 점수의 경험적 순위이며 보정된 공격 확률이 아닙니다.

{chr(10).join(ranking)}

두 Laya의 문턱이 크게 다르므로 재현율 차이를 표현 학습의 효과만으로 해석할 수 없습니다. 정상·관계 모델을 고정 문턱 0.5로 관찰하면 재현율은 {pct(new['at_05']['recall'])}, 오탐률은 {pct(new['at_05']['fpr'])}입니다. 이는 사전에 함께 계산한 참고치이며 시험 자료로 문턱을 다시 선택한 결과가 아닙니다. 문턱 전반의 순위 품질을 보는 PR-AUC도 함께 판단해야 합니다. 이번 시험에서 Qwen의 PR-AUC가 정상·관계 Laya보다 높았습니다. 모델별 점수의 수치 자체는 동일한 위험도를 뜻하지 않습니다.

{chr(10).join(family)}

미학습 공격은 Hakai 250개, Torii 24개의 창입니다. 전체 재현율은 Hakai에 크게 좌우되므로 두 공격군 재현율의 단순 평균도 함께 제시했습니다. 별도 Mirai 수집 시험은 이전과 다른 파일이지만 공격군은 학습에 등장했으므로 미학습 공격군 시험과 구분합니다. Trojan 수집의 악성 흐름 6개는 모두 블록 최초 60초 제외 규칙에 해당해 최종 표본에 공격 창이 남지 않았습니다(`runs/source_label_diagnostics.json`). 해당 파일의 정상 창은 포함됐으나 Trojan 공격 성능을 검증했다고 주장하지 않습니다.

배치 평가의 오탐을 수집별로 나누면 정상·관계 Laya의 217개 중 159개, Qwen의 107개 중 104개가 Capture-42-1의 **공개 라벨상 정상** 창에서 발생했습니다. 그 파일의 정상 창 250개에 대한 오탐률은 각각 63.60%, 41.60%입니다. 새로운 수집 환경의 정상 동작으로 일반화하지 못했을 가능성을 보여 주지만, 원인을 확정하려면 원본 흐름과 라벨을 따로 검토해야 합니다. 시험 결과에 맞춰 이 표본을 제외하거나 공격으로 재라벨하지 않았습니다(`runs/analysis.json`의 `descriptive_subgroups`).

## 실시간 단건 재현 검증

{chr(10).join(ontable)}

처음 36개 사례를 추론 CLI로 확인했을 때 Qwen의 Torii 사례 1개가 배치와 다르게 판정됐습니다. 배치 점수 0.369230, 단건 점수 0.387218, 고정 문턱 0.387218이었습니다. BF16 배치 계산과 단건 계산의 점수 차이가 문턱 부근의 판단을 바꿀 수 있음을 확인해, 새 Laya 두 모델과 Qwen 각각의 최종 시험 2,324개 전체를 배치 1로 다시 재생했습니다. 가중치·온도·문턱을 바꾸지 않았습니다. 최초 불일치는 `runs/cli_smoke_batch_comparison.json`에 보존했습니다. 배치 결과를 단건 서비스 결과와 완전히 같다고 간주하지 않습니다.

최종 `runs/cli_smoke.json`은 같은 단건 조건으로 다시 로드한 CLI의 결과를 단건 전체 재생 결과와 대조한 검사입니다. 이 검증은 실제 네트워크 제어를 실행한 검증은 아닙니다.

## 실제로 바꾼 입력과 학습

공식 IoT-23 배포 서버의 11개 수집에서 `conn.log.labeled` 텍스트만 사용했습니다. 악성코드 바이너리나 패킷을 실행하지 않았습니다. 현재 입력은 실제 Zeek 연결 로그 시퀀스이며 DNS 질의·HTTP 내용·호스트 프로세스 로그까지 통합한 자료는 아닙니다.

5초의 현재 창과 그 앞 60초 문맥을 호스트별로 만듭니다. 현재/이전 구간의 연결 수·목적지 수·포트 수·송수신량·연결 실패 비율·반복 목적지 비율과, 각 구간에서 최대 3개씩 추출한 흐름 레코드를 제공합니다. 레코드에는 상대 시각·익명 목적지 식별자·프로토콜·서비스·연결 상태·지속 시간·송수신량이 있습니다. IP 원문, UID, 수집 파일명, 절대 시각과 공격 라벨은 입력에서 제거했습니다. 같은 목적지인지의 관계는 상태 안에서 유지합니다.

전체 학습은 {data['train']['cases']}개입니다. Laya 두 모델은 원본 가중치에서 출발해 **같은 최대 예산인 428회 업데이트**를 수행합니다. 준비 학습 1에포크에서 기준 모델은 공격 분류, 추가 모델은 정상성·관계 보조 과제를 학습하고, 이어 둘 다 같은 자료·표집 방식으로 공격 분류를 3에포크 학습합니다. 옵티마이저도 같은 시점에 재설정합니다. 검증 점수로 각각 체크포인트를 선택하므로 실제 평가 가중치의 누적 업데이트 횟수는 다를 수 있습니다. 입력 길이도 같지는 않으므로 정확히 같은 FLOP 예산은 아닙니다.

보조 과제는 현재와 과거 문맥 모두 공개 라벨상 정상인 학습 창 748개에서 만듭니다. (1) 마지막 흐름의 서비스·포트·프로토콜을 가리고 실제 값과 다른 정상 흐름의 값을 구분하는 복원 과제, (2) 실제 다음 흐름과 다른 호스트·시간 구간에서 가져온 정상 흐름을 구분하는 연결 과제입니다. 후자는 시간과 익명 목적지 표기를 맞춰 단순 식별자 차이만 보지 않게 했습니다. 교체 뒤 원본과 값이 완전히 같은 흐름은 후보에서 제외했고, 같은 입력에 상반된 정답이 붙지 않았음을 확인했습니다(`runs/auxiliary_quality.json`). 이 과제의 교체 표본은 **학습용 자기지도 정답**이며 실제 공격 라벨로 사용하지 않았습니다. 완전한 토큰 MLM이나 LogBERT 재현, 인과관계 학습의 입증은 아닙니다.

Laya는 인코더 학습률 8e-6, 판정 헤드 3e-5, 배치 8×누적 4를 사용했습니다. Qwen3는 r=16/alpha=32 LoRA, 학습률 1e-4, 배치 4×누적 8, 3에포크입니다. 학습은 BF16 계산으로 수행했고, 체크포인트는 검증 PR-AUC로 선택했습니다. 새 모델의 실제 체크포인트를 다시 로드한 뒤 벤치마크했습니다.

{chr(10).join(training)}

## 분리와 일반화의 범위

HideAndSeek와 Mirai 및 정상 장치 수집을 시간순 30분 블록으로 나눠 앞 60%는 학습, 다음 15%는 검증, 다음 10%는 보정, 마지막 15%는 기존 공격 시험에 사용했습니다. 첫 60초는 문맥 확보용으로만 쓰고 판정하지 않았으며 블록 경계마다 문맥을 초기화했습니다. 따라서 실제 배포 초기 60초 동안의 탐지 성능은 이 평가에 포함되지 않습니다.

Muhstik 수집 파일 전체는 가중치 학습에서 제외하고 별도 검증에만 사용했습니다. 일반 검증과 이 별도 검증의 PR-AUC 평균으로 체크포인트를 골랐습니다. 이 과정에 사용한 Muhstik을 최종 미학습 공격 성과로 세지 않습니다. Hakai·Torii·Trojan 수집 및 별도 정상 Somfy 수집은 학습·검증·보정에서 모두 제외했습니다. 정상 Somfy는 공개 폴더의 Somfy-01을 사용했습니다.

이 평가의 미학습은 **이번 도메인 학습에서 제외한 악성코드 계열 및 수집 파일**을 뜻합니다. C&C 같은 행동 유형은 학습의 Mirai 등에도 존재할 수 있습니다. 새로운 공격 기법 또는 기반 언어 모델이 사전학습에서 접하지 않은 제로데이를 검증한 뜻은 아닙니다. 알려진 공격 시험은 동일 수집 캠페인의 뒷부분이므로 완전히 독립된 캠페인 시험도 아닙니다.

수집·클래스별 표본 수를 제한했으므로 실제 운영의 공격 발생률과 다릅니다. 공개 라벨의 오류, IoT 장치·수집 환경 차이, 적은 공격 캠페인 수에 따른 편향이 남습니다. 정상성 보조 학습을 했더라도 최종 출력은 이진 분류 점수이며, 별도로 보정한 개방집합 탐지 헤드를 추가한 것은 아닙니다.

원본 이벤트 UID와 호스트·시간 그룹의 분할 중복을 검사했습니다. 별도로 정규화된 전체 입력의 정확한 중복을 확인했으며 미학습 시험 1,384개와 별도 Mirai 시험 200개는 학습 입력과 완전히 같은 상태가 없었습니다. 기존 공격 시험에는 학습과 같은 정상 상태 6개가 있었지만 같은 공격 상태는 없었습니다. 정확한 중복이 없다는 사실만으로 의미적으로 새로운 행동임을 보장하지는 않습니다(`runs/input_duplicate_diagnostics.json`).

## 이전 문맥을 제거한 민감도 시험

{chr(10).join(context)}

같은 모델과 문턱에서 이전 60초 집계와 오래된 이벤트만 제거했습니다. 현재 창의 집계와 이벤트는 남겼습니다. 이 결과는 문맥 변화에 대한 민감도이며, 문맥 없는 모델을 별도로 재학습한 통제 실험이나 단일 로그만으로 판단 불가능한 공격의 증명은 아닙니다.

## 지연과 부하

상주 모델에서 단건 판단 80회를 측정했습니다. 추가로 한 작업자·배치 1에 초당 10/25/50건을 각각 10초 동안 넣고, 예정 도착부터 판단 완료까지의 대기를 포함했습니다.

{chr(10).join(loadtable)}

실측의 200ms는 준비된 상태의 판단 기준입니다. 흐름 종료를 로그 관측 가능 시각의 하한으로 사용했으며 실제 Zeek 배출·전달 지연은 측정하지 않았습니다. 현재 5초 창이 닫히기를 기다리는 방식만으로는 공격 시작부터 200ms 대응을 달성할 수 없습니다. 긴 연결은 로그가 나오는 시점 자체가 늦어질 수 있으므로 배포에는 진행 중 흐름의 관측과 수시 갱신되는 상태가 필요합니다. 창 대기·장비 적용·네트워크 전송·설명 생성도 측정에 포함하지 않았습니다. 짧은 부하 시험의 결과를 장시간 처리량 보장으로 해석하지 않습니다. QoS 변경·차단·호스트 격리 등 실제 제어는 실행하지 않았습니다.

## 구현 판단과 다음 학습 우선순위

이번 결과는 정상·관계 보조 학습이 미학습 공격을 점수화하는 데 도움이 될 수 있다는 실험 결과입니다. 그러나 오탐 증가와 문턱 변화가 함께 발생했으므로 동일한 오탐 비용에서 우수함을 입증한 결과는 아닙니다. 현재 체크포인트를 자동 호스트 차단에 바로 적용할 근거도 부족합니다. 설명을 생성하지 않는 소형 Qwen도 단건 200ms 미만이므로, 그 속도가 Laya만의 장점이라는 가정은 이번 측정에서 성립하지 않습니다.

다음 실험은 정상 동작의 범위를 넓히는 데 우선순위를 둡니다. 서로 다른 장치·날짜·망의 실제 정상 Zeek 시퀀스와 오탐을 유발하는 정상 패턴을 수집하고 라벨을 검토한 뒤 보조 학습과 분류 학습에 넣어야 합니다. 이미 분석한 이번 시험을 개발 자료로 사용한다면, 다음 성능 주장은 새로 확보한 미사용 수집 자료에서 평가해야 합니다. 공격군 제외뿐 아니라 행동 유형 제외 평가와 여러 학습 시드도 필요합니다. 이는 이번 실패 양상에서 도출한 다음 실험 제안이며 예상 개선률을 근거 없이 제시하지 않습니다.

## 재현과 근거 파일

```bash
cd /home/keinus/laya-security-lab/v3
../.venv/bin/python download_sources.py
../.venv/bin/python prepare_data.py
bash run_training.sh
../.venv/bin/python diagnose_auxiliary.py
bash run_benchmark.sh
bash run_load.sh
../.venv/bin/python analyze_results.py
../.venv/bin/python diagnose_data.py
../.venv/bin/python benchmark_online.py
../.venv/bin/python write_report.py
../.venv/bin/python smoke_inference.py
../.venv/bin/python capture_runtime.py
../.venv/bin/python audit_v3.py
```

같은 폴더에서 재실행하면 해당 v3 산출물을 갱신합니다. 원본 Laya와 Qwen3 가중치, 상위 `model_io.py`, v2의 공통 측정 함수와 서버 `.venv`를 재사용합니다. 라이브러리 버전은 `runs/requirements-resolved.txt`, 기반 모델과 런타임 파일의 해시는 `runs/runtime_dependencies.json`에 보관합니다.

- `data/source_manifest.json`: 공식 다운로드 URL, 원본 파일 해시.
- `data/manifest.json`, `protocol.json`, `test_protocol.py`: 표본·분할·설계와 누출 검사.
- `runs/*_history.json`, `runs/*_complete.json`: 학습과 가중치 해시.
- `runs/comparison.json`, `runs/predictions_*.jsonl`, `runs/analysis.json`: 모든 모델의 예측·혼동표·신뢰구간.
- `runs/online_comparison.json`, `runs/online_predictions_*.jsonl`: 가중치와 문턱을 고정한 배치 1 전체 재생.
- `runs/source_label_diagnostics.json`, `runs/input_duplicate_diagnostics.json`: 시작 구간의 공격 누락과 정규화 입력 중복.
- `runs/load_comparison.json`, `runs/load_*.jsonl`: 서비스·대기·응답 지연 원시 자료.
- `runs/cli_smoke.json`: 저장한 모델을 추론 CLI로 다시 로드한 판단과 벤치마크의 일치 확인.
- `runs/audit.json`: 학습 완료, 데이터·예측·체크포인트 일치 검사.

`infer_v3.py`는 저장된 v3 상태 JSONL에서 점수·문턱·위협 여부만 출력합니다. 원시 스트림 파서와 제어기를 대체하는 배포 서비스는 아닙니다.

## 외부 근거

- [IoT-23 제공 기관](https://www.stratosphereips.org/datasets-iot23): 데이터·Zeek 로그·공개 라벨의 출처. 실제 성능 수치는 이 폴더의 실험 산출물에 근거합니다.
- [LogBERT](https://arxiv.org/abs/2103.04475): 정상 로그 시퀀스의 자기지도 학습이라는 설계 방향의 선행 근거. 이번 보조 과제는 별도 구현이며 해당 논문 수치를 우리 성능으로 인용하지 않습니다.
- [LogLLM](https://arxiv.org/abs/2411.08561): 로그 의미와 시퀀스 분류를 연결하는 선행 연구. 이번 실험은 그 전체 모델 재현이 아닙니다.
- [Laya](https://huggingface.co/convaiinnovations/laya), [Qwen3 0.6B](https://huggingface.co/Qwen/Qwen3-0.6B): 기반 모델. 이번 훈련은 기존에 고정한 원본 가중치를 사용했습니다.
'''
    (ROOT/'README.md').write_text(text,encoding='utf8')
if __name__=='__main__':main()
