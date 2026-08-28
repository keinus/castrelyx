# UI redesign — design QA

Date: 2026-08-28

final result: passed

## 범위와 시각적 기준

사용자가 선택한 6개 화면 시안을 기존 React/shadcn 콘솔에 구현했다. 새 모의 앱이 아니라 기존 Java API와 연결되는 배포 UI다. shadcn MCP에서 Field, Table 등 사용 컴포넌트와 audit checklist를 확인했다.

최종 실행: http://127.0.0.1:8765/#studio (별도 QA SQLite DB). 실제 JAR의 `assets/index-HWU6n2Hi.js` 로딩, 파싱 API, 매핑 시뮬레이션, TCP 입력 → WebSocket 수신을 확인했다.

| 화면 | 원본 시안 | JAR 렌더링 | 원본 + 구현 비교 |
| --- | --- | --- | --- |
| Studio | [원본](C:/Users/keinu/.codex/generated_images/01a0439a-0d76-7043-979c-7782cdc34ab1/exec-ecfe4ce7-12a2-4da9-942d-a4b4b64bda42.png) | [화면](C:/Users/keinu/.codex/visualizations/2026/08/27/01a0439a-0d76-7043-979c-7782cdc34ab1/logparser-ui-qa/redesign-studio-final.jpg) | [동일 보드 비교](C:/Users/keinu/.codex/visualizations/2026/08/27/01a0439a-0d76-7043-979c-7782cdc34ab1/logparser-ui-qa/comparison-studio.png) |
| Inputs | [원본](C:/Users/keinu/.codex/generated_images/01a0439a-0d76-7043-979c-7782cdc34ab1/exec-b4cab277-599f-4783-85fe-dd7987c69d0e.png) | [화면](C:/Users/keinu/.codex/visualizations/2026/08/27/01a0439a-0d76-7043-979c-7782cdc34ab1/logparser-ui-qa/redesign-inputs-final.jpg) | [동일 보드 비교](C:/Users/keinu/.codex/visualizations/2026/08/27/01a0439a-0d76-7043-979c-7782cdc34ab1/logparser-ui-qa/comparison-inputs.png) |
| Outputs | [원본](C:/Users/keinu/.codex/generated_images/01a0439a-0d76-7043-979c-7782cdc34ab1/exec-e212f266-9fed-4eb4-93ea-e712b9468de5.png) | [화면](C:/Users/keinu/.codex/visualizations/2026/08/27/01a0439a-0d76-7043-979c-7782cdc34ab1/logparser-ui-qa/redesign-outputs-final.jpg) | [동일 보드 비교](C:/Users/keinu/.codex/visualizations/2026/08/27/01a0439a-0d76-7043-979c-7782cdc34ab1/logparser-ui-qa/comparison-outputs.png) |
| Overview | [원본](C:/Users/keinu/.codex/generated_images/01a0439a-0d76-7043-979c-7782cdc34ab1/exec-8dd45832-3f39-4f28-a9d0-8041abeabc4a.png) | [화면](C:/Users/keinu/.codex/visualizations/2026/08/27/01a0439a-0d76-7043-979c-7782cdc34ab1/logparser-ui-qa/redesign-overview-v1.jpg) | [동일 보드 비교](C:/Users/keinu/.codex/visualizations/2026/08/27/01a0439a-0d76-7043-979c-7782cdc34ab1/logparser-ui-qa/comparison-overview.png) |
| Schema Mapping | [원본](C:/Users/keinu/.codex/generated_images/01a0439a-0d76-7043-979c-7782cdc34ab1/exec-db7d4d3d-345b-4e9a-b344-6fa4a6ea0bad.png) | [화면](C:/Users/keinu/.codex/visualizations/2026/08/27/01a0439a-0d76-7043-979c-7782cdc34ab1/logparser-ui-qa/redesign-mapping-final.jpg) | [동일 보드 비교](C:/Users/keinu/.codex/visualizations/2026/08/27/01a0439a-0d76-7043-979c-7782cdc34ab1/logparser-ui-qa/comparison-mapping.png) |
| Live Tail | [원본](C:/Users/keinu/.codex/generated_images/01a0439a-0d76-7043-979c-7782cdc34ab1/exec-630488bb-b140-4102-a4ec-d2828bf2cf05.png) | [화면](C:/Users/keinu/.codex/visualizations/2026/08/27/01a0439a-0d76-7043-979c-7782cdc34ab1/logparser-ui-qa/redesign-live-final.jpg) | [동일 보드 비교](C:/Users/keinu/.codex/visualizations/2026/08/27/01a0439a-0d76-7043-979c-7782cdc34ab1/logparser-ui-qa/comparison-live.png) |

## 비교 크기와 상태

- 원본 이미지: 모두 1487 × 1058 px. 이미지에서 별도 @2x 밀도는 지정되지 않았다.
- 최종 데스크톱 CSS viewport: 원본과 같은 1487 × 1058. 앞선 작업 중간 검증은 1440 × 1024.
- 구현 이미지: Inputs/Outputs/Overview/Live Tail은 1487 × 1058 px. 세로 스크롤이 있는 Studio/Mapping은 Browser 캡처 결과가 1472 × 1047 px. 이 캡처 차이를 디자인 불일치로 판단하지 않고 양쪽을 960 × 684로 정규화했다. 보드 왼쪽이 원본, 오른쪽이 실제 렌더링이다.
- 폰트 로딩 완료 확인. 이미지에 그림자나 배경을 추가하지 않고 비교용 크기만 정규화했다. 재생성 스크립트: [compare.ps1](C:/Users/keinu/.codex/visualizations/2026/08/27/01a0439a-0d76-7043-979c-7782cdc34ab1/logparser-ui-qa/compare.ps1).
- Studio: JSON parser 선택 및 서버 테스트 완료. Inputs: mTLS 탭. Outputs: Buffering 탭, batchSize 200. Mapping: 원본과 같은 4개 공통 항목 + 1개 하위 규칙 상태로 QA DB를 일시 변경해 비교하고, 12개 공통 항목 + 3개 규칙으로 복구했다.
- Overview 숫자와 Live Tail 내용은 실제 QA 서버 응답이다. 예시 시안의 가짜 처리량·연결 성공 상태·로그 내용을 넣지 않았다. Live Tail은 실제 TCP 전송 5개 이벤트 중 하나를 펼친 상태다.

## 수정 및 재비교 이력

| 심각도 | 발견 및 영향 | 수정 | 수정 후 근거 |
| --- | --- | --- | --- |
| P2, 해결 | Studio 단계 패널의 높이가 과도해 테스트 결과가 첫 화면에서 잘렸다. | 단계 연결선·번호 컴포넌트, 행 간격, 테스트 패널 높이/행간을 조정했다. | [초기](C:/Users/keinu/.codex/visualizations/2026/08/27/01a0439a-0d76-7043-979c-7782cdc34ab1/logparser-ui-qa/redesign-studio-v1.jpg), [중간 비교](C:/Users/keinu/.codex/visualizations/2026/08/27/01a0439a-0d76-7043-979c-7782cdc34ab1/logparser-ui-qa/comparison-studio-before-final.png), [최종 비교](C:/Users/keinu/.codex/visualizations/2026/08/27/01a0439a-0d76-7043-979c-7782cdc34ab1/logparser-ui-qa/comparison-studio.png). 결과 JSON이 첫 화면에 표시된다. |
| P2, 해결 | 매핑 행의 반복 카드·라벨과 규칙 도구의 여백 때문에 동일한 4개 행 상태도 지나치게 길었다. | 열 머리글과 정렬된 행, 헤더 템플릿 도구, 규칙 내 항목 추가 버튼, compact Card를 적용했다. | [초기 4개 행](C:/Users/keinu/.codex/visualizations/2026/08/27/01a0439a-0d76-7043-979c-7782cdc34ab1/logparser-ui-qa/redesign-mapping-v1.jpg), [중간 비교](C:/Users/keinu/.codex/visualizations/2026/08/27/01a0439a-0d76-7043-979c-7782cdc34ab1/logparser-ui-qa/comparison-mapping-before-final.png), [최종 비교](C:/Users/keinu/.codex/visualizations/2026/08/27/01a0439a-0d76-7043-979c-7782cdc34ab1/logparser-ui-qa/comparison-mapping.png). |
| P2, 해결 | 이벤트 상세가 Event 셀 안에 한정되고 긴 JSON에 밝은 스크롤바가 표시됐다. | 별도 colSpan=3 행, 공유 코드 패널, 긴 문자열 줄바꿈, dark color-scheme 및 스크롤바를 적용했다. | [초기](C:/Users/keinu/.codex/visualizations/2026/08/27/01a0439a-0d76-7043-979c-7782cdc34ab1/logparser-ui-qa/redesign-live-v1.jpg), [최종 비교](C:/Users/keinu/.codex/visualizations/2026/08/27/01a0439a-0d76-7043-979c-7782cdc34ab1/logparser-ui-qa/comparison-live.png), [모바일](C:/Users/keinu/.codex/visualizations/2026/08/27/01a0439a-0d76-7043-979c-7782cdc34ab1/logparser-ui-qa/redesign-live-mobile.jpg). |
| P2, 해결 | 입력 편집기에서 인증서 설정과 부가 설정이 섞여 Transport security 구역과 하단 요약을 밀어냈다. | TLS reload interval은 Advanced에 유지하고 mTLS는 인증서별 입력 및 읽기 전용 보안 정보로 정리했다. | [초기](C:/Users/keinu/.codex/visualizations/2026/08/27/01a0439a-0d76-7043-979c-7782cdc34ab1/logparser-ui-qa/redesign-inputs-v1.jpg), [최종 비교](C:/Users/keinu/.codex/visualizations/2026/08/27/01a0439a-0d76-7043-979c-7782cdc34ab1/logparser-ui-qa/comparison-inputs.png). |

최종 비교에서 추가 조치가 필요한 P0/P1/P2는 발견하지 않았다.

## 필수 시각 품질 점검

- **폰트/타이포그래피:** Inter UI와 JetBrains Mono JSON. 제목·라벨·보조 설명의 위계, 줄바꿈 및 축약을 확인했다. 원본 생성 이미지의 화면별 글자 크기 차이는 공통 토큰으로 통일했다.
- **간격/레이아웃:** 모든 화면은 232px 공통 사이드바, 64px 헤더와 같은 페이지 여백을 사용한다. 어댑터 폼은 넓은 2열, 작은 화면에서는 1열이다. 원본마다 다른 사이드바 폭과 탭 스타일은 사용자의 통합 요구에 따라 하나로 맞췄다.
- **색상/토큰:** 중성 남색 배경, 밝은 본문, 파란 선택/주요 동작, 녹색 실제 연결 상태를 공통 semantic token으로 적용했다. 포커스 표시와 비활성 상태도 확인했다.
- **이미지/아이콘:** 이 시안은 사진·일러스트 없이 구성된 관리 UI다. Lucide 아이콘과 기존 Layers 로고를 사용한다. 전체 화면을 이미지로 대체하거나 임의의 장식 자산을 넣지 않았다.
- **문구/실제 내용:** 필수 여부, Enabled, 연결·큐·배치 값은 실제 설정을 따른다. Source field는 직접 입력과 제안을 모두 지원한다. Advanced와 도움말을 유지했으며, 보안 설정을 확인하지 않고 인증 성공이라고 표시하지 않는다. Live Tail의 축약 행과 상세 내용은 동일 이벤트다.

작은 글자와 속성 정렬은 전체 보드 외에 확대 비교로 확인했다: [Studio 필드](C:/Users/keinu/.codex/visualizations/2026/08/27/01a0439a-0d76-7043-979c-7782cdc34ab1/logparser-ui-qa/focus-studio-fields.png), [매핑 필드](C:/Users/keinu/.codex/visualizations/2026/08/27/01a0439a-0d76-7043-979c-7782cdc34ab1/logparser-ui-qa/focus-mapping-fields.png), [mTLS 필드](C:/Users/keinu/.codex/visualizations/2026/08/27/01a0439a-0d76-7043-979c-7782cdc34ab1/logparser-ui-qa/focus-input-fields.png). 코드 색상과 아이콘은 확대 Studio 및 실제 Live Tail 캡처를 함께 확인했다.

## 동작·반응형·접근성 검증

- 실제 API: ClickHouse batchSize를 UI에서 201로 저장하고 API 응답으로 확인한 다음 200으로 복구. JSON parser 서버 테스트와 매핑 `bytes_in=128` 시뮬레이션 확인.
- 실제 TCP → 최종 JAR → WebSocket 수신, JSON 펼침/접힘, 필터의 빈 결과, Pause/Resume, Clear 확인. reconnect/invalid frame/paused frame/cleanup은 회귀 테스트로 추가 확인.
- 공통 모바일 Sheet 열기 → Inputs 이동 시 닫힘, mTLS 개별 필드, 매핑 라벨, Live Tail 전체 너비 상세 확인.
- 390 × 844, 820 × 1180, 1440 × 1024 및 최종 1487 × 1058 검증. DOM scrollWidth가 viewport를 넘지 않음. [최종 JAR 모바일](C:/Users/keinu/.codex/visualizations/2026/08/27/01a0439a-0d76-7043-979c-7782cdc34ab1/logparser-ui-qa/redesign-studio-mobile-final.jpg), [태블릿](C:/Users/keinu/.codex/visualizations/2026/08/27/01a0439a-0d76-7043-979c-7782cdc34ab1/logparser-ui-qa/redesign-studio-tablet.jpg), [메뉴](C:/Users/keinu/.codex/visualizations/2026/08/27/01a0439a-0d76-7043-979c-7782cdc34ab1/logparser-ui-qa/redesign-sidebar-mobile.jpg), [입력](C:/Users/keinu/.codex/visualizations/2026/08/27/01a0439a-0d76-7043-979c-7782cdc34ab1/logparser-ui-qa/redesign-inputs-mobile.jpg), [매핑](C:/Users/keinu/.codex/visualizations/2026/08/27/01a0439a-0d76-7043-979c-7782cdc34ab1/logparser-ui-qa/redesign-mapping-mobile.jpg).
- 필드와 버튼 이름, main 1개, aria-expanded/aria-controls, 키보드 포커스, JSON을 HTML로 해석하지 않는 처리를 확인했다. 수동 포커스 표시가 캡처 일부에 남아 있는 것은 정상 동작이다.
- 최종 JAR 브라우저 console error/warn: 0.

## 자동 검증과 산출물

- `npm test`: 30/30 통과.
- 기존 JavaScript 회귀: 33/33 통과.
- `.\\gradlew test -PskipFrontend=true --rerun-tasks`: Java 446/446 통과, 실패/오류/skip 0.
- `.\\gradlew test bootJar` 및 최종 간격 변경 후 `.\\gradlew bootJar`: 성공. TypeScript/Vite 빌드 포함.
- 최종 JAR: [logparser-0.3.1.jar](D:/study/castrelyx/logparser/build/libs/logparser-0.3.1.jar). 새 bundle 포함, 기존 static/js 엔트리 0개 확인.
- 테스트 입력 19888은 삭제, capture는 비활성화, 매핑 12개/규칙 3개 및 batchSize 200 복구. 운영 DB는 변경하지 않았다.

## 남은 검증 범위 / P3

- 원본 이미지의 서로 다른 메뉴 폭·탭 장식 대신 공통 shadcn 스타일을 사용한다. 정상 운영 설정에서는 행 수에 따라 세로 스크롤이 생긴다.
- Vite의 500kB 초과 경고가 남는다(main 약 507kB, Mermaid 관련 지연 로딩 chunk 약 662kB). 기능 오류는 아니며 추가 분할은 성능 개선 작업으로 남긴다.
- Docker CLI가 없어 컨테이너 실행은 검증하지 않았다. 실제 인증서 mTLS handshake 및 외부 ClickHouse/Kafka 등 서비스 전송은 이번 UI 검증 범위에 포함하지 않았다.
