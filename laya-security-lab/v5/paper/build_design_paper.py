"""Create the design-inclusive manuscript from the verified empirical manuscript."""
from pathlib import Path
import re

here = Path(__file__).resolve().parent
source = (here/'build_paper.py').read_text(encoding='utf8')
source = source.replace('A-Za-zθ∑', 'A-Za-zθλ∑')
start = source.index("h('3 Laya 기반 위협탐지와 자동 대응 구조')")
end = source.index("h('4 공개 데이터 기반 학습과 평가 방법')")
source = source[:start] + "from design_content import add_design\nadd_design(globals())\n\n" + source[end:]
# Keep the original evidence and prose; move their section and table numbers.
source = re.sub(r"h\('([4-8])(?= )", lambda m: "h('"+str(int(m[1])+1), source)
source = re.sub(r"table\('표 ([2-5])(?= )", lambda m: "table('표 "+str(int(m[1])+6), source)
source = source.replace("Laya 아키텍처 기반 저지연 언어모델 위협탐지와 자동 대응 설계", "Laya 아키텍처를 이용한 로그 연관 위협탐지와 저지연 자동 대응 시스템 설계")
source = source.replace("Low Latency Language Model Threat Detection and Response with the Laya Architecture", "Design of a Low Latency Threat Detection and Response System with Laya")
source = source.replace("공개 네트워크 흐름 데이터 기반 탐색적 실증 연구", "이벤트 수집부터 정책 집행까지의 시스템 설계와 공개 네트워크 흐름 실증")
source = source.replace("핵심 관점은 언어 사전학습을 활용하는 판정 인터페이스를 유지하면서 응답 경로에서 순차적인 설명 생성을 제거하는 데 있다.", "설계는 관측 시각을 보존하는 이벤트 수집, 주체별 sliding window, 위협·위험 수준·조치 후보의 선택지 판정, 정책 검증과 만료 기반 복구, 비동기 설명으로 구성한다. 단일 인코딩에서 여러 선택지 그룹을 점수화하는 확장 모델과 단계별 학습 방법도 제안한다. 핵심 관점은 언어 사전학습을 활용하는 판정 인터페이스를 유지하면서 응답 경로에서 순차적인 설명 생성을 제거하는 데 있다.")
source = source.replace("본 연구의 기여는 공개 흐름 데이터에서 Laya를 학습한 재실행 결과, 같은 장치에서 측정한 입력 준비 포함 지연, 그리고 판정·정책 적용·설명을 분리한 방어 구조의 제안이다. 새로운 Transformer 연산이나 최초의 System One 보안 통합을 주장하지 않는다. 이미 유사한 보안 의사결정 계층이 논의됐으므로[6], 기여 범위는 방어용 흐름 분류에서의 확인 가능한 실측과 연관 탐지 검증 조건의 정리에 둔다.", "본 연구는 관계가 보존된 입력, Laya 기반 판정, 대응·복구 절차를 설계하고, 공개 흐름 데이터의 정확도와 추론 지연을 평가한다. 유사한 보안 의사결정 계층이 논의됐으므로[6], 기여는 방어용 시스템 설계와 확인 가능한 실측에 둔다. 설계 확장과 실험에 사용한 이진 모델은 구분한다.")
source = source.replace("h('5 2 학습 설정과 완료 범위',2)", """h('5 2 학습 설정과 완료 범위',2)
p('실험용 Laya 입력은 여덟 흐름의 전처리 특성과 열 이름, 마지막 행의 공격 여부를 묻는 영어 지시문이다. float32 값을 보존하도록 유효숫자 아홉 자리의 CSV로 직렬화했다. 최대 입력 길이는 8,192토큰으로 설정했고 두 완료 실행의 관측 최대 길이는 2,080토큰과 2,074토큰이었다. 입력을 잘라내는 표본은 거부했다. 이는 제3절의 Zeek 관계 window와 다중 선택지 확장을 사용한 실험이 아니다.')""")
source = source.replace("OUTPUT=HERE/'Laya_Threat_Detection_Paper.ko.docx'", "OUTPUT=HERE/'Laya_Threat_Detection_Design_Paper.ko.docx'")
source = source.replace("h('9 결론')", "from design_content import add_acceptance\nadd_acceptance(globals())\n\nh('9 결론')")
source = source.replace("(HERE/'paper_manifest.json')", "(HERE/'design_paper_manifest.json')")
source = source.replace("link(q,'원문',url)", "\n    if url:link(q,'원문',url)")
source = source.replace("def sha(p):", """REFERENCES.extend([
 ('Zeek Project. Protocol independent events. new_connection, connection_status_update, connection_state_remove. Book of Zeek. 열람 2026-10-07.', 'https://docs.zeek.org/en/current/scripts/base/bif/event.bif.zeek.html'),
 ('C. Guo, G. Pleiss, Y. Sun, and K. Q. Weinberger. On Calibration of Modern Neural Networks. Proceedings of ICML, PMLR 70:1321–1330, 2017.', 'https://proceedings.mlr.press/v70/guo17a.html'),
 ('Security Switch. 구현 문서와 소스 스냅샷. README, Zeek 운영 문서, telemetry 수집기, 정책·작업 API, 라우팅 ACL 및 자동 격리 구현. 2026-10-07 열람. 공개 문헌이 아닌 연동 대상의 로컬 소프트웨어 자료이며 위치와 해시는 부록 B 및 문서 manifest에 기록한다.', None),
])

def sha(p):""")
# Append the implementation source trace after the experimental provenance.
source = source.replace("doc.core_properties.title=", "from design_content import add_implementation_appendix\nadd_implementation_appendix(globals())\n\ndoc.core_properties.title=", 1)
source = source.replace("claims=[", "implementation_source_hashes={str(p):sha(p) for p in IMPLEMENTATION_SOURCES},\n    design_status='Proposed system and grouped-marker extension; only original binary Laya evaluated',\n    claims=[")
exec(compile(source, str(here/'build_paper.py'), 'exec'), {'__file__': str(here/'build_paper.py'), '__name__': '__main__'})
