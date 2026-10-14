"""Generate a code-adjacent experiment readout from measured artifacts only."""
import argparse,json,platform,sys
from pathlib import Path
from acquire import ROOT

def rate(value):return 'N/A' if value is None else f'{value*100:.2f}%'
def main():
    ap=argparse.ArgumentParser();ap.add_argument('--experiment',required=True);args=ap.parse_args()
    directory=ROOT/'runs'/args.experiment;manifest=json.loads((ROOT/'data/experiments'/args.experiment/'manifest.json').read_text())
    evaluations=[json.loads(p.read_text()) for p in sorted(directory.glob('*/*/seed-*/evaluation.json'))]
    if not evaluations:raise ValueError('No completed evaluations')
    lines=['# 공개 네트워크 데이터 실험 결과','',
        '**이 결과는 구현 검증용 파일럿입니다. 전체 데이터 재현 실험이나 미학습 공격 탐지 성능을 입증한 결과가 아닙니다.**' if manifest['pilot'] else '공개 네트워크 데이터 실험 결과입니다. 아래 범위와 표본 설계를 함께 해석해야 합니다.','',
        f"평가 단위는 현재 완료된 네트워크 흐름입니다. sequence는 이전 {manifest['context_seconds']:g}초의 통계와 최근 {manifest['context_events']}개 흐름을 추가하며, single은 현재 흐름만 봅니다. 호스트 프로세스·OSSEC 로그는 사용하지 않습니다.",'',
        '| 모델 | 입력 | seed | 같은 연도 재현율 | 같은 연도 오탐률 | 다음 연도 재현율 | 다음 연도 오탐률 | batch 1 p95 |',
        '|---|---|---:|---:|---:|---:|---:|---:|']
    for result in evaluations:
        known=result['tests']['known_test']['0.01'];external=result['tests'].get('external_test',{}).get('0.01',{})
        latency=result['latency'][0]['batch_p95_ms']
        lines.append(f"| {result['model']} | {result['view']} | {result['seed']} | {rate(known['recall'])} | {rate(known['fpr'])} | {rate(external.get('recall'))} | {rate(external.get('fpr'))} | {latency:.2f} ms |")
    lines+=['','위 표의 임계값은 보정 자료에서 관측 오탐률 1% 이하 조건으로 선택했습니다. 시험 오탐률 1%를 보장하지 않습니다. 0.1% 조건 결과와 원시 예측은 각 모델의 evaluation.json 및 *_predictions.jsonl에 있습니다.','',
        'Laya는 이진 판정 헤드를 학습했고, Qwen3-0.6B는 LoRA로 A/B 답변 토큰의 로짓을 학습했습니다. Qwen도 설명문을 생성하지 않으므로 아래 지연은 자유 생성 LLM과의 비교가 아닙니다. XGBoost·RF는 같은 측정값을 수치화하고 GRU는 동일한 순서 정보를 입력받습니다. 모델별 학습 예산과 선택 epoch는 training.json에 있으며 최적 튜닝을 마친 결과가 아닙니다.','',
        '## 관측과 판단','']
    singles={r['seed']:r for r in evaluations if r['model']=='laya' and r['view']=='single'}
    for result in evaluations:
        if result['model']!='laya' or result['view']!='sequence':continue
        known=result['tests']['known_test']['0.01'];external=result['tests'].get('external_test',{}).get('0.01')
        if result['seed'] in singles:
            before=singles[result['seed']]['tests']['known_test']['0.01']
            lines.append(f"- seed {result['seed']}에서 Laya의 같은 연도 재현율은 single {rate(before['recall'])} → sequence {rate(known['recall'])}, 오탐률은 {rate(before['fpr'])} → {rate(known['fpr'])}였습니다. 동일한 시험 흐름에서 이전 문맥을 추가한 구성의 결과가 개선됐습니다. 한 seed의 관측이며, sequence는 통계·개별 흐름·관계 ID를 함께 추가하므로 개선 원인을 연관 추론 하나로 분리해 입증하지는 못합니다.")
        lines.append(f"- Laya sequence의 같은 연도 혼동행렬은 TP={known['tp']}, FN={known['fn']}, FP={known['fp']}, TN={known['tn']}입니다. 원시 예측 및 모델 가중치 해시는 해당 구성 디렉터리에 보관했습니다.")
        if external:
            lines.append(f"- 다음 연도에서 같은 모델·보정 임계값의 재현율은 {rate(external['recall'])}({external['tp']}/{external['positive']}), 오탐률은 {rate(external['fpr'])}({external['fp']}/{external['fp']+external['tn']})였습니다. AP={external['ap']:.4f}, AUROC={external['auroc']:.4f}는 점수 순위의 지표이며, 이 임계값에서 실제로 놓친 공격을 상쇄하지 않습니다. 점수 분포와 운영 임계값의 연도 간 일반화 문제가 있다는 해석은 가능하지만 원인을 확정하려면 추가 대조 실험이 필요합니다.")
    all_negative=[f"{r['model']}/{r['view']}/seed-{r['seed']}" for r in evaluations if r['tests']['known_test']['0.01']['tp']==0 and r['tests']['known_test']['0.01']['fp']==0]
    if all_negative:lines+=['', '시험 흐름을 전부 정상으로 판정한 구성은 '+', '.join(all_negative)+'입니다. 이 구성의 오탐률 0%는 공격도 놓친 결과이므로 우수한 탐지 성능으로 해석할 수 없습니다. 각 보정 임계값과 AP를 함께 확인해야 합니다.']
    lines+=['','이 실행만으로 알려지지 않은 공격 탐지나 자동 차단의 유효성을 입증할 수 없습니다. 다음 본 실험은 연속된 전체 자료, 여러 학습 seed, 자연 비율의 보정·시험 표본, 공격 계열 전체 제외, 통계만/시간 제거/관계 제거 대조군을 포함해야 합니다. 다음 연도 시험 정답으로 임계값을 다시 맞추지 않았습니다. 새 보정 전략을 개발할 경우 별도의 개발 자료와 새로운 최종 시험을 고정해야 합니다.','',
        '## 원시 분모와 오탐 목표','',
        '| 모델 | 입력 | 시험 | TP | FN | FP | TN | AP | AUROC |','|---|---|---|---:|---:|---:|---:|---:|---:|']
    for result in evaluations:
        for split,policies in result['tests'].items():
            m=policies['0.01'];ap='N/A' if m['ap'] is None else f"{m['ap']:.4f}";auc='N/A' if m['auroc'] is None else f"{m['auroc']:.4f}"
            lines.append(f"| {result['model']} | {result['view']} | {split} | {m['tp']} | {m['fn']} | {m['fp']} | {m['tn']} | {ap} | {auc} |")
    normal_cal=manifest['counts']['calibration']['rows']-manifest['counts']['calibration']['positive']
    lines+=['','시간 구간을 재추출하는 bootstrap 구간은 evaluation.json에 기록합니다. 오탐이 0건이면 이 방식의 구간도 0으로 퇴화할 수 있으므로 무오탐 보장으로 해석하지 않습니다. audit.json에는 보조 Wilson 구간도 저장하지만 흐름 독립성 가정이 실제 시간 상관을 완전히 반영하지는 않습니다.',f'정상 보정 표본 {normal_cal:,}개에서 0.1% 목표의 관측 오탐 허용치는 {int(normal_cal*.001)}건입니다. 표본 크기와 시간 상관을 고려한 저오탐 검증이 필요합니다.','',
        '## 데이터 범위','',
        '| 구분 | 흐름 수 | 공격 흐름 수 | 시간 구간 수 |','|---|---:|---:|---:|']
    for split,counts in manifest['counts'].items():lines.append(f"| {split} | {counts['rows']} | {counts['positive']} | {counts['groups']} |")
    lines+=['',f"표본 방식: `{manifest['sampling']}`.",'',
        'LSPR23 파일 앞부분과 LSPR24의 지정한 Parquet row group 일부만 읽었습니다. 전체 망의 연속된 기록이 아니므로 문맥도 일부 누락됩니다. 이 표본의 AP·precision을 실제 망의 공격 발생 비율에 대한 성능으로 해석하면 안 됩니다.' if manifest['pilot'] and manifest['source']=='lspr23' else '원본 전체 로드 여부와 각 분할의 표본 크기는 데이터 manifest.json에 기록되어 있습니다.','',
        ('미학습 시험은 지도학습에서 제외한 공격 계열 '+manifest['heldout_family']+'의 평가입니다. 기초 모델 사전학습까지 미노출임을 보장하지 않습니다.') if manifest['heldout_family'] else '다음 연도 시험은 환경 변화에 대한 일반화 평가입니다. LSPR의 검증된 공격 계열 라벨이 없어 이 실행에서는 미학습 공격 수치를 산출하지 않았습니다. CIC IoT 2023 입력 어댑터와 공격 계열 제외 프로토콜은 별도로 구현되어 있습니다.','',
        '## 지연의 범위','',
        '측정 시간은 입력 직렬화·토큰화·모델 판정입니다. 흐름이 끝나기까지의 시간, 센서 로그 배출 지연, 라우터 적용 지연은 포함하지 않습니다. 200ms 이내 추론과 공격 시작 후 200ms 이내 대응은 서로 다른 지표입니다.','',
        '| 모델 | 입력 | 초당 요청 | 최대 배치 | 최대 배치 형성 대기 | 큐 포함 p95 | 200ms 이내 비율 |','|---|---|---:|---:|---:|---:|---:|']
    for result in evaluations:
        q=result.get('queue')
        if q:lines.append(f"| {result['model']} | {result['view']} | {q['offered_rate']} | {q['max_batch']} | {q['max_formation_wait_ms']} ms | {q['p95_ms']:.2f} ms | {rate(q['within_200ms_fraction'])} |")
    lines+=['','신경망은 서버의 RTX 4090에서 실행했으며 XGBoost·RF는 CPU 추론입니다. 단독 지연의 분위수는 배치별 warmup 4회 뒤 24회 측정한 값입니다. 큐 시험은 200개 실제 시험 입력을 일정 간격으로 도착시킨 짧은 부하 재생입니다. 모델마다 가변 배치에 따른 경계 판정 차이는 batch_stability에 별도로 기록됩니다. 공유 서버·배치 구성·입력 길이·요청 패턴의 영향을 포함하므로 이 짧은 측정으로 장기 SLA나 장치 간 성능을 보장할 수 없습니다.','']
    for result in evaluations:
        q=result.get('queue')
        if result['model']=='qwen' and q and q['p95_ms']<200:
            lines.append(f"Qwen의 제한된 A/B 판정도 큐 포함 p95 {q['p95_ms']:.2f}ms로 200ms 목표 아래였습니다. 따라서 자유로운 설명문 생성과 빠른 판정을 분리하는 설계 자체가 중요한 비교 조건입니다. 이번 결과는 Laya와 제한 출력 Qwen을 함께 후보로 두고 탐지율·운영 부하를 검증할 근거이며, 범용 LLM 전체가 느리다는 결론의 근거가 아닙니다.")
    lines+=['',
        '## 근거와 재현 범위','',
        '- [2026 LLM/ML/DL 비교 논문](https://link.springer.com/article/10.1007/s10462-025-11432-2): 비교 모델 구성 참고. 저자 분할 파일은 확보하지 않았으므로 수치 재현을 주장하지 않습니다.',
        '- [SAFE-NID, TMLR 2025](https://www.brianmatejek.com/assets/pdfs/research/publications/matejek2025safe.pdf): 공격 종류 제외 평가 원칙 참고. 본 구현은 패킷 payload 기반 SAFE-NID 모델 재현이 아닙니다.',
        '- [CyCon 2025](https://roland-meier.ch/files/2025_CyCon_AI.pdf), [LSPR23](https://zenodo.org/records/8042347), [LSPR24](https://zenodo.org/records/14900873): 공개 네트워크 자료 출처.',
        '- [2026 sequence construction 공개 초록](https://ssrn.com/abstract=6597680): 흐름 묶음과 연도 간 평가 설계 참고. 전체 논문 프로토콜은 확인하지 못했습니다.','',
        '동작별 최적 대응 정답과 대응 후 피해량은 이 자료에 없으므로 QoS·라우팅·차단 모델을 학습했다고 주장하지 않습니다. 인퍼런스 도구는 위협 판정만 출력하며 장비 설정을 바꾸지 않습니다.','']
    (directory/'RESULTS.md').write_text('\n'.join(lines),encoding='utf8')
    (directory/'comparison.json').write_text(json.dumps({'experiment':args.experiment,'manifest':manifest,'models':evaluations},indent=2))
    print(json.dumps({'models':len(evaluations),'report':str(directory/'RESULTS.md')}))
if __name__=='__main__':main()
