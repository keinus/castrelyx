# LogParser Pipeline Studio UI/UX 설계

> 상태: 디자인 검토용  
> 기준일: 2026-08-24  
> 설정 기준: [`logparser_schema.md`](./logparser_schema.md)  
> 범위: 하나의 `message type`에 연결된 Input → Parser → Transform → Structured Transform → Output의 생성·수정·삭제·정렬·검증·테스트 경험

## 1. 목표

Pipeline Studio는 분리된 설정 목록을 오가며 `messagetype`을 수동으로 맞추는 기존 방식을 대체한다. 사용자는 한 화면에서 하나의 `message type`을 선택하고, 연결된 전체 처리 흐름과 현재 선택한 컴포넌트의 실제 설정을 함께 관리한다.

핵심 목표는 다음과 같다.

1. 파이프라인의 연결 관계와 실행 순서를 항상 보이게 한다.
2. 설정 폼에 충분한 공간을 제공한다.
3. `configParams`와 JSON 문자열 필드를 사람이 편집하기 쉬운 구조화된 폼으로 바꾼다.
4. 각 단계의 설정 결과를 저장·배포 전에 테스트한다.
5. 런타임에서 사용하지 않는 필드는 기본 화면에 노출하지 않는다.
6. canonical type과 실제 REST property를 사용해 저장 결과와 런타임 동작의 차이를 줄인다.

## 2. 범위와 비범위

### 포함

- `message type`별 파이프라인 선택과 생성
- Input, Parser, Transform, Structured Transform, Output CRUD
- Parser와 Transform의 우선순위 변경
- 컴포넌트 활성화/비활성화
- 타입별 argument 입력과 조건부 검증
- Structured Mapping과 Rule 편집
- 현재 draft를 사용한 단계별 테스트 결과
- 저장, 검증, 배포 상태 구분

### 제외

- 대시보드 지표와 장기 추세 차트
- Live Tail 자체의 재설계
- 문서에 없는 새로운 adapter argument
- 런타임에서 사용하지 않는 값을 동작하는 설정처럼 표현하는 것
- API 응답이나 백엔드 기능이 없는 테스트를 이미 동작하는 것처럼 표현하는 것

## 3. 승인된 화면 구조

```text
┌──────────┬─────────────────────────────────────────────────────────────────┐
│ App nav  │ Pipeline Studio · Message type · Draft state · Validate/Deploy │
├──────────┼──────────────────────┬──────────────────────────────────────────┤
│          │ 1 Input              │ 선택 컴포넌트 설정                       │
│          │   └ TCP mTLS + gzip  │                                          │
│          │          ↓           │ 넓은 2열 폼                              │
│          │ 2 Parser             │ - 기본 설정                              │
│          │   └ JSON parser      │ - 타입별 설정                            │
│          │          ↓           │ - 보안/성능/고급 설정                    │
│          │ 3 Transform          │                                          │
│          │   ├ Normalize        │ Delete · Discard · Save                  │
│          │   └ Remove fields    ├──────────────────────────────────────────┤
│          │          ↓           │ Test current draft                       │
│          │ 4 Structured         │ Sample input │ Result after selected stage│
│          │          ↓           │ Stage selector · Run test · result status│
│          │ 5 Output             │                                          │
└──────────┴──────────────────────┴──────────────────────────────────────────┘
```

- 전체 작업영역에서 파이프라인 열은 약 30%, 설정/테스트 열은 약 70%를 사용한다.
- 파이프라인은 좌측 위에서 아래로 흐른다.
- 설정은 우측 상단 약 60%, 테스트는 우측 하단 약 40%를 사용한다.
- 설정 영역은 모달이나 좁은 drawer가 아니라 화면의 주 작업영역이다.
- 모든 단계는 한 viewport에서 식별할 수 있어야 한다. 내용이 많으면 좌측 파이프라인 열만 독립 스크롤한다.

## 4. 정보 모델

### 4.1 `message type`이 화면의 최상위 컨텍스트다

- 상단 `Message type` selector가 현재 작업 대상을 결정한다.
- Input, Parser, Transform, Structured Mapping, Output은 선택된 값으로 필터링한다.
- 입력·파서·변환·현재 파이프라인 전용 출력의 `messagetype`은 설정 폼에서 잠긴 값으로 표시한다.
- Output은 현재 `message type`과 `all` 중 하나를 Scope로 선택할 수 있다. 기본값은 현재 `message type`이다.
- 값은 case-sensitive임을 생성/변경 화면에서 명시한다.
- `message type` 변경은 단순 텍스트 수정이 아니라 별도 Rename 동작으로 처리한다. 연결된 모든 entity와 mapping을 함께 변경할 수 있는 백엔드 동작이 없으면 Rename은 제공하지 않는다.

### 4.2 실행 순서

```text
Input → Parser(priority ASC, id ASC) → Transform(priority ASC, id ASC)
      → Structured Transform → Output
```

- Parser와 Transform 노드는 drag handle로 순서를 변경한다.
- 드래그 완료 후 UI가 연속적인 `priority` 값을 다시 계산한다.
- 동일 priority가 허용되더라도 UI는 모호한 순서를 만들지 않는다.
- Structured Transform은 `enabled` toggle을 제공하지 않는다. 저장 mapping이 없어도 기본 structured event가 생성되기 때문이다.

## 5. 파이프라인 열 디자인

### 5.1 단계 헤더

각 단계 헤더는 다음 정보를 갖는다.

- 단계 번호와 이름
- 현재 활성 컴포넌트 수
- `+ Add` 동작
- 단계 수준 오류나 경고 수

Input은 일반적으로 하나의 pipeline source를 대표하지만 데이터 모델상 복수 entity를 막지 않는다. UI는 복수 Input을 허용하되, 두 개 이상이면 “같은 message type으로 합류”한다는 설명을 표시한다.

### 5.2 컴포넌트 노드

모든 노드는 동일한 골격을 사용한다.

| 영역 | 내용 |
| --- | --- |
| 왼쪽 | drag handle 또는 단계 아이콘 |
| 본문 1행 | 사용자 친화적인 type 이름 |
| 본문 2행 | 핵심 endpoint, pattern, mapping 수 등 한 줄 요약 |
| 상태 | Enabled toggle 또는 Structured 고정 실행 표시 |
| 빠른 동작 | Edit, overflow menu |
| overflow | Duplicate, Disable/Enable, Delete |

선택 노드는 cobalt 1px border와 왼쪽 accent line으로 표시한다. 비활성 노드는 opacity를 낮추되 텍스트 대비를 유지한다.

### 5.3 단계별 노드 요약

| 단계 | 2행 요약 |
| --- | --- |
| Input | port, file path, topic, queue, target 수 등 실제 수신 지점 |
| Parser | pattern 요약 또는 생성되는 대표 field |
| Transform | 조건 수, 이동 field 수, 삭제 field 수 |
| Structured | common mapping 수, rule 수, unmapped 수 |
| Output | host/URL/topic/table, 최근 전송 상태가 있으면 별도 보조 정보 |

## 6. CRUD와 상태 흐름

### 6.1 생성

1. 단계의 `+ Add`를 누른다.
2. 우측 설정 영역이 `Create input/parser/transform/output` 상태로 바뀐다.
3. Type combobox에서 타입을 선택한다.
4. 선택한 타입에 필요한 필드만 표시한다.
5. `Create` 후 좌측 단계에 노드가 추가되고 자동 선택된다.

Type 선택기는 큰 card grid 대신 검색 가능한 listbox를 사용한다. 사용자용 이름, canonical type, 한 줄 설명을 제공한다.

### 6.2 수정

- 노드 선택 즉시 우측에 전체 entity 값을 로드한다.
- `PUT`이 전체 교체이므로 화면에 보이지 않는 유지 필드도 편집 state에 보존한다.
- 변경이 시작되면 상단 상태가 `Unsaved changes`로 바뀐다.
- 다른 노드나 `message type`으로 이동할 때 Save/Discard 확인을 제공한다.

### 6.3 삭제

- 설정 footer 왼쪽에 destructive action으로 배치한다.
- 확인 문구에는 type, endpoint, pipeline 영향 범위를 표시한다.
- 마지막 활성 Input 또는 Output 삭제 시 “현재 pipeline을 배포할 수 없음” 경고를 포함한다.
- 삭제 성공 후 같은 단계의 다음 노드를 선택한다. 노드가 없으면 생성 empty state를 표시한다.

### 6.4 활성화

- node와 설정 header 양쪽 toggle은 같은 state를 공유한다.
- 변경은 즉시 저장하지 않고 draft 변경으로 취급한다.
- Structured Transform에는 toggle을 표시하지 않는다.

### 6.5 복제

- 같은 `message type`과 type/argument를 복제한다.
- Parser/Transform priority는 선택 노드 바로 다음 순서로 배정한다.
- secret은 백엔드가 원문을 반환하지 않는 경우 복제하지 않고 재입력을 요구한다.

## 7. 설정 폼 공통 규칙

### 7.1 폼 구조

설정 수에 따라 다음 section을 사용한다.

1. General
2. Connection 또는 Source/Destination
3. Parsing/Rules/Mapping
4. Authentication/TLS
5. Reliability/Batching
6. Advanced

- 8개 이하 필드는 한 화면의 2열 폼을 사용한다.
- 반복 데이터는 table 또는 row editor를 사용한다.
- JSON object/map은 key-value row editor로 바꾼다.
- JSON array는 tag 또는 reorderable list로 바꾼다.
- 원본 REST payload는 Advanced의 read-only preview에서만 확인한다.
- 사용자가 raw JSON을 직접 편집하는 기능은 별도 Expert mode로 분리하며 기본값은 꺼져 있다.

### 7.2 필드 표현

| 데이터 | 컨트롤 |
| --- | --- |
| host, path, URL, topic | text input, monospace value |
| port, timeout, size, count | number input + 단위 suffix |
| enum | select 또는 짧은 segmented control |
| boolean | switch |
| map | key-value row editor |
| array | tag/list editor |
| secret | Password / Environment variable mode selector |
| condition/pattern | code editor + syntax 도움말 |

### 7.3 검증

- required 오류는 저장 시점이 아니라 blur와 test 시점에 표시한다.
- number 범위와 identifier 정규식은 client에서 먼저 검증한다.
- 조건부 필드는 원인이 되는 field 바로 아래에 나타난다.
- backend 오류는 관련 field에 연결하고, 연결할 수 없으면 form 상단 summary에 표시한다.
- alias 대신 canonical type을 저장한다. canonical 이름은 사용자용 이름 아래 muted monospace로 표시한다.

### 7.4 런타임 미사용 필드

다음 필드는 기본 폼에서 숨긴다.

- HTTP/HTTPS Input의 `codec`, `pathPattern`
- 일반 network Input의 bind address 의미로 오해할 수 있는 `host`
- 사용되지 않는 Input `bufferSize`
- OpenSearch Output의 `action`
- RabbitMQ Output의 `tagpass`
- MariaDB/ClickHouse Output의 top-level `batchSize`, `flushIntervalMs`
- Transform의 `configParams`
- Structured common mapping의 `event_id`, `ingest_time`, `raw_log`

Advanced의 “Runtime limitations”에 숨긴 이유를 설명한다. 저장된 기존 값이 있으면 read-only legacy value로 보여주되 수정 컨트롤은 제공하지 않는다.

## 8. 테스트 영역

### 8.1 공통 구조

- 제목: `Test current draft`
- 설명: `Runs in memory · Nothing is deployed`
- 왼쪽: Sample input
- 오른쪽: 선택한 단계 이후의 Result
- 상단: Stage result selector, Run test, 결과 건수, latency
- 결과 selector: Raw input, 각 Parser, 각 Transform, Structured Transform, 각 Output serialization

### 8.2 단계별 결과

| 선택 단계 | 결과 표현 |
| --- | --- |
| Input | 수신 원문과 source metadata |
| Parser | 생성/변경된 field map, decode error |
| Filter | Passed 또는 Dropped, 일치한 조건 |
| AddProperty | Before/After diff, 이동된 field |
| RemoveProperty | 제거된 field 목록과 After JSON |
| Structured | common, subDomainType, subFields, additionalAttributes |
| Output | 실제 전송 직전 serialized payload와 destination summary |

### 8.3 현재 API 지원과 구현 의존성

| 기능 | 현재 API | 디자인 처리 |
| --- | --- | --- |
| Parser 단독 테스트 | `POST /api/v1/parsers/test` | 바로 제공 가능 |
| Structured simulation | `POST /api/v1/structure/simulate` | 바로 제공 가능 |
| Input 연결/수신 테스트 | 전용 API 없음 | UI는 설계하되 구현 시 API 추가 필요 |
| Transform 단독 테스트 | 전용 API 없음 | 전체 pipeline simulation API 필요 |
| Output 연결/전송 테스트 | 전용 API 없음 | connection-only 또는 명시적 test delivery API 필요 |
| Draft 전체 단계별 결과 | 전용 API 없음 | stage stop을 지원하는 simulation API 필요 |

권장 백엔드 계약은 저장하지 않은 temporary configuration과 `stopAfterStage`를 받는 pipeline simulation이다. Output은 기본적으로 외부 전송을 하지 않고 serialization까지만 보여준다. 실제 test delivery는 별도 확인을 거친다.

## 9. Input Adapter 디자인

### 9.1 공통

모든 Input 폼에 다음을 표시한다.

| UI label | REST field | 규칙 |
| --- | --- | --- |
| Adapter type | `type` | canonical type 저장 |
| Message type | `messagetype` | 현재 pipeline 값으로 잠금 |
| Enabled | `enabled` | 기본 true |

### 9.2 File Input (`FileInputAdapter`)

| UI section | UI label | REST field | 필수/기본 | 디자인 동작 |
| --- | --- | --- | --- | --- |
| Source | File path | `path` | 필수 | file input + 경로 형식 검증 |
| Start position | Read from beginning | `isFromBeginning` | false | switch, 설명에 최초 open 기준 명시 |
| Metadata | Source host fallback | `host` | localhost | Advanced에 배치 |

Test 영역은 파일 존재 여부, regular file 여부, UTF-8 sample line을 보여주는 디자인을 사용한다. 전용 API가 필요하다.

### 9.3 TCP Input (`TcpInputAdapter`)

| UI label | REST field | 필수/기본 | 디자인 동작 |
| --- | --- | --- | --- |
| Listen port | `port` | 필수 | 1..65535 |

- “All interfaces”를 read-only runtime behavior로 표시한다.
- newline-delimited UTF-8임을 설명한다.
- `host`, `timeoutMs`, `workerThreads`, `queueSize`는 동작 설정처럼 노출하지 않는다.

### 9.4 TLS TCP Input (`TlsTcpInputAdapter`)

Connection section:

- Listen port → `port`

TLS section:

| UI label | `configParams` key | 조건 |
| --- | --- | --- |
| Key store path | `keyStorePath` | 필수 |
| Key store password | `keyStorePassword` 또는 `keyStorePasswordEnv` | 둘 중 하나 |
| Key store type | `keyStoreType` | 기본 PKCS12 |
| Private key password | `keyPassword` 또는 `keyPasswordEnv` | 선택 |
| Client authentication | `clientAuth` | none/want/need |
| Trust store path | `trustStorePath` | clientAuth가 want/need일 때 필수 |
| Trust store password | `trustStorePassword` 또는 `trustStorePasswordEnv` | trust store 사용 시 필수 |
| Trust store type | `trustStoreType` | 기본 PKCS12 |
| Enabled protocols | `enabledProtocols` | TLSv1.3, TLSv1.2 |
| TLS algorithm | `tlsAlgorithm` | Advanced, 기본 TLS |

Password와 environment variable은 mode selector로 하나만 활성화한다. `clientAuth`가 want/need로 바뀌면 trust store section을 펼친다.

### 9.5 UDP Input (`UdpInputAdapter`)

| UI label | REST field | 필수 |
| --- | --- | --- |
| Listen port | `port` | 필수 |

Runtime behavior에 “1 datagram = 1 event”, 최대 1,600 bytes를 표시한다. `host`, `timeoutMs`, `bufferSize`는 숨긴다.

### 9.6 HTTP Input (`HttpInputAdapter`)

| UI label | REST field | 필수 |
| --- | --- | --- |
| Listen port | `port` | 필수 |

- 모든 path를 수신하며 request line, headers, body 전체를 한 event로 만든다고 설명한다.
- `codec`, `pathPattern`, bind `host`는 현재 runtime 미사용이므로 숨긴다.
- 일반 webhook server와 같은 HTTP response를 보장하지 않는다는 warning을 표시한다.

### 9.7 HTTPS Input (`HttpsInputAdapter`)

- HTTP Input의 Listen port를 사용한다.
- TLS TCP Input과 동일한 server TLS section을 사용한다.
- HTTP의 runtime limitations를 함께 표시한다.

### 9.8 Kafka Input (`KafkaInputAdapter`)

| UI section | UI label | REST field | 필수/기본 |
| --- | --- | --- | --- |
| Connection | Bootstrap servers | `bootstrapservers` | 필수 |
| Subscription | Topic | `topicid` | 필수 |
| Consumer | Consumer group | `groupId` | 선택, 비우면 시작마다 UUID |

Bootstrap servers는 comma-separated token input을 사용한다. Test는 broker 연결, topic metadata, consume 권한을 분리해 결과를 보여주는 디자인을 사용한다.

### 9.9 SNMP Input (`SnmpInputAdapter`)

Top-level 설정:

| UI label | 저장 위치 | 기본/조건 |
| --- | --- | --- |
| Default version | `configParams.version` | 2c |
| Default community | `configParams.community` | public |
| Poll interval | `configParams.intervalMs` | 60,000ms, 최소 1,000 |
| Timeout | top-level `timeoutMs` | 5,000ms, 최소 100 |
| Retries | `configParams.retries` | 0 |
| Worker threads | top-level `workerThreads` | target 수 이하 |
| Queue size | top-level `queueSize` | 1 이상 |

Targets는 반복 table로 편집한다.

| Target field | 조건부 동작 |
| --- | --- |
| name, host, port, version | 항상 표시 |
| community | v1/v2c에서 표시 |
| securityName, securityLevel | v3에서 표시 |
| authProtocol, authPassphrase/authPassphraseEnv | authNoPriv/authPriv에서 표시 |
| privProtocol, privPassphrase/privPassphraseEnv | authPriv에서 표시 |

OIDs는 `Name`, `OID` row editor로 표시한다. 문자열 OID도 로드할 때 Name을 비운 row로 정규화한다. 최소 target 1개, OID 1개를 저장 전에 검증한다.

### 9.10 RabbitMQ Input (`RabbitMqInputAdapter`)

Connection:

| UI label | 저장 위치 | 기본 |
| --- | --- | --- |
| Host | top-level `host` | localhost |
| Port | top-level `port` | 5672 |
| Username | `configParams.username` | guest |
| Password | `configParams.password` | guest |
| Virtual host | `configParams.virtualHost` | `/` |
| Timeout | top-level `timeoutMs` | 5,000ms |
| Charset | `configParams.charset` | UTF-8 |

Subscription:

| UI label | `configParams` key | 기본/조건 |
| --- | --- | --- |
| Queue | `queue` | 필수 |
| Auto acknowledge | `autoAck` | false |
| Prefetch count | `prefetchCount` | 1, autoAck=false일 때 표시 |
| Declare queue | `declareQueue` | false |
| Durable | `durableQueue` | true, declareQueue=true일 때 표시 |
| Exclusive | `exclusiveQueue` | false, declareQueue=true일 때 표시 |
| Auto delete | `autoDeleteQueue` | false, declareQueue=true일 때 표시 |
| Exchange | `exchange` | 선택 |
| Routing key | `routingKey` | 기본 빈 문자열 |
| Bind queue | `bindQueue` | declareQueue와 exchange 사용 시 표시 |

현재 password env 참조가 구현되지 않았으므로 직접 secret 입력만 제공하고 “DB backup 접근 보호 필요” 경고를 표시한다.

### 9.11 TLS RabbitMQ Input (`TlsRabbitMqInputAdapter`)

- RabbitMQ Input의 모든 필드를 사용한다.
- Port 기본값은 5671이다.
- TLS Enabled는 true로 고정하고 toggle로 제공하지 않는다.
- Client TLS section은 key/trust store path, password/direct-env, store type, TLS algorithm, hostname verification을 제공한다.
- `enabledProtocols`와 `clientAuth`는 RabbitMQ client에 적용되지 않으므로 숨긴다.

### 9.12 TCP mTLS + gzip Input (`TcpMtlsGzipInputAdapter`)

Connection/Capacity:

| UI label | 저장 위치 | 기본/조건 |
| --- | --- | --- |
| Listen port | top-level `port` | 필수 |
| Idle timeout | top-level `timeoutMs` | 30,000ms |
| Queue size | top-level `queueSize` | 10,000 |
| Worker threads | top-level `workerThreads` | maxConnections fallback, 기본 32 |
| Maximum frame size | `configParams.maxFrameBytes` | 10 MiB |
| Maximum connections | `configParams.maxConnections` | 32 |

mTLS:

| UI label | `configParams` key | 필수/기본 |
| --- | --- | --- |
| Key store path | `keyStorePath` | 필수 |
| Key store password env | `keyStorePasswordEnv` | 필수 |
| Trust store path | `trustStorePath` | 필수 |
| Trust store password env | `trustStorePasswordEnv` | 필수 |
| TLS reload interval | `tlsReloadIntervalMs` | 5,000ms |
| Acknowledge mode | `ackMode` | queueAccepted 고정 |

TLSv1.3/TLSv1.2, client auth required, PKCS12, gzip 해제 16 MiB 상한, batch item 5,000개 상한은 read-only runtime behavior로 표시한다. 직접 password 입력은 제공하지 않는다.

저장 시 위의 점 표기 UI path를 실제 `configParams` object의 `maxFrameBytes`, `maxConnections` key로 직렬화한다.

### 9.13 Fake Input (`FakeInputAdapter`)

추가 argument가 없다. 폼에는 type, message type, enabled만 표시하고 생성되는 sample event 예시를 Test 영역에 보여준다. 존재하지 않는 interval 설정을 만들지 않는다.

## 10. Parser 디자인

### 10.1 공통 필드

| UI label | REST field | 동작 |
| --- | --- | --- |
| Parser type | `type` | canonical class 저장 |
| Message type | `messagetype` | 잠금 |
| Enabled | `enabled` | 기본 true |
| Order | `priority` | drag 순서와 동기화 |
| Continue on failure | `continueOnFailure` | 기본 false |

Parser Test는 저장 전 draft `type`, `param`, sample raw log를 `/parsers/test`에 전달한다.

### 10.2 타입별 폼

| Parser | 추가 입력 | Test 결과 디자인 |
| --- | --- | --- |
| JSON (`JsonParser`) | 없음 | parsed field tree, top-level array 오류 |
| Grok (`GrokParser`) | Grok pattern → `param` | named capture table, unmatched 구간 |
| Regex (`RegexParser`) | Java regex → `param` | match별 group 1 key/group 2 value, 최소 group 수 오류 |
| RFC3164 (`RFC3164SyslogParser`) | 없음 | FACILITY, SEVERITY, TIMESTAMP, HOST, TAG, MESSAGE |
| RFC5424 (`RFC5424SyslogParser`) | 없음 | syslog_* field group과 structured data |
| HTTP (`HttpParser`) | 없음 | headers map과 body preview |

Pattern parser는 code editor, pattern library/help, sample input, 결과 field table을 한 흐름으로 배치한다. alias는 선택기에 노출하지 않는다.

## 11. Transform 디자인

### 11.1 공통 필드

| UI label | REST field | 동작 |
| --- | --- | --- |
| Transform type | `type` | Filter/AddProperty/RemoveProperty |
| Message type | `messagetype` | 잠금 |
| Enabled | `enabled` | 기본 true |
| Order | `priority` | drag 순서와 동기화 |

### 11.2 Filter (`Filter`)

Raw JSON 대신 두 개의 rule builder를 제공한다.

1. Drop when any condition matches
2. Pass only when all conditions match

각 row는 `Field`, `Operator = equals one of`, `Values`로 구성한다. Values는 comma-separated runtime 형식으로 serialize한다. exact, case-sensitive 비교임을 표시한다.

- Drop rule을 먼저 보여주고 실행 우선순위를 설명한다.
- `filterPass` 또는 `filterDrop` 중 최소 하나가 필요하다.
- Test 결과는 Passed/Dropped와 일치한 field/value를 표시한다.

### 11.3 AddProperty (`AddProperty`)

이 동작은 값을 추가하는 것이 아니라 flat field를 nested object로 이동한다. UI label은 오해를 줄이기 위해 `Group fields`로 표시하고 canonical type을 보조 표기한다.

- Target object name input
- Source fields multi-select/reorder list
- 여러 target object row 추가
- Before/After tree diff
- 기존 target overwrite와 missing source → null 경고

저장 시 `addProperties`의 `Map<String,List<String>>` JSON 문자열로 변환한다.

### 11.4 RemoveProperty (`RemoveProperty`)

- top-level source field tag selector
- custom field 추가
- 중복 제거
- nested path 미지원 안내
- Test 결과에서 제거된 field를 red diff로 표시

저장 시 `removeProperties` JSON array 문자열로 변환한다.

## 12. Structured Transform 디자인

Structured Transform은 일반 폼이 아니라 mapping workspace를 사용하되, 전체 우측 영역 안에서 동작한다.

화면 state와 저장 payload의 대응은 다음과 같다.

| UI state | Payload field | 설명 |
| --- | --- | --- |
| Mapping ID | `id` | 선택, 사용자 정의 식별자 |
| Current pipeline | `messageType` | 현재 message type으로 잠금 |
| Common mapping rows | `commonMappings` | `FieldMapping[]` |
| Ordered rule list | `subTableRules` | `SubTableRule[]` |
| Mapping source | `sourceField` | exact top-level source key |
| Mapping target | `targetField` | common 또는 sub target key |
| Fallback value | `defaultValue` | source가 null일 때 사용 |
| Rule destination | `targetSubTable` | 결과 `subDomainType` |
| Rule condition | `conditionExpression` | Spring SpEL |
| Rule mapping rows | `mappings` | 해당 rule의 `FieldMapping[]` |

### 12.1 상단 도구

- Message type read-only
- Template select
- Apply template
- Save as template
- Auto map
- Reset draft
- Save mapping

Template 적용은 기존 mapping을 덮어쓴다는 확인을 제공한다.

### 12.2 Common mapping

3열 mapping table을 사용한다.

| Source field | Target field | Default value |
| --- | --- | --- |
| parser/transform 결과 | 지원 common target select | source null fallback |

지원 target은 `event_time`, `event_category`, `event_type`, `event_action`, `event_result`, `severity`, `src_ip`, `src_port`, `dst_ip`, `dst_port`, `protocol`, `src_host`, `dst_host`, `user_name`, `user_id`, `log_source`로 제한한다.

- source field는 sample/test 결과에서 자동 수집한다.
- target 중복 mapping은 저장 전에 오류 처리한다.
- type conversion 가능성을 target 옆에 표시한다.
- `event_id`, `ingest_time`, `raw_log`는 선택기에 포함하지 않는다.

### 12.3 Sub-table rule

Rule list와 선택 rule editor를 함께 사용한다.

- rule 순서 drag
- Target sub-table: event_network/event_web/event_auth 또는 custom
- SpEL condition editor
- condition sample evaluation
- rule별 mapping table
- 첫 match만 적용된다는 실행 설명

SpEL 오류는 false가 되므로 저장 전 syntax check와 sample evaluation 결과를 명확히 보여준다.

### 12.4 Simulation 결과

`/structure/simulate` 결과를 다음 4개 section으로 나눈다.

1. Common
2. Sub-domain type
3. Sub fields
4. Additional attributes

mapping된 source와 보존된 unmapped source를 색상과 아이콘으로 구분한다. conversion 실패 target은 null/미설정 warning으로 표시한다.

## 13. Output Adapter 디자인

### 13.1 공통

| UI label | REST field | 규칙 |
| --- | --- | --- |
| Adapter type | `type` | canonical type 저장 |
| Scope | `messagetype` | Current message type / All |
| Enabled | `enabled` | 기본 true |
| Include original text | `addOriginText` | 기본 false, 적용되는 output만 표시 |
| Timeout | `timeoutMs` | 사용하는 adapter에서만 표시 |

### 13.2 Console Output (`ConsoleOutputAdapter`)

- 추가 destination field 없음
- Include original text 제공
- Test 결과는 최종 console JSON serialization을 보여준다.

### 13.3 Benchmark Output (`BenchmarkAdapter`)

- 추가 field 없음
- Include original text를 숨긴다.
- 외부 전송이 없고 처리량을 log로 기록한다는 설명만 표시한다.

### 13.4 TCP Output (`TcpOutputAdapter`)

| UI label | REST field | 기본/필수 |
| --- | --- | --- |
| Destination host | `host` | 필수 |
| Destination port | `port` | 필수 |
| Timeout | `timeoutMs` | 30,000ms |
| Retry count | `retryCount` | 3 |
| Retry delay | `retryDelayMs` | 1,000ms |

Runtime behavior에 event마다 새 connection, delimiter/length prefix 없음으로 표시한다.

### 13.5 HTTP Output (`HttpOutputAdapter`)

| UI label | REST field | 기본/필수 |
| --- | --- | --- |
| Endpoint URL | `url` | 필수 http/https URL |
| Method | `method` | POST/PUT/PATCH, 기본 POST |
| Headers | `headers` | key-value row editor |
| Timeout | `timeoutMs` | 30,000ms |

Authorization header는 secret value로 마스킹한다. 자동 `Content-Type`과 `User-Agent`를 read-only generated headers로 표시한다. 2xx만 성공임을 Test 결과에 사용한다.

### 13.6 Kafka Output (`KafkaOutputAdapter`)

| UI label | REST field | 기본/필수 |
| --- | --- | --- |
| Bootstrap servers | `bootstrapservers` | 필수 |
| Topic | `topicid` | 필수 |
| Record key | `key` | 선택 |
| Timeout | `timeoutMs` | 30,000ms |
| Retry count | `retryCount` | runtime 기본 0 |
| Retry delay | `retryDelayMs` | runtime 기본 250ms |

Runtime defaults `acks=all`, `compression=lz4`, idempotence disabled를 Advanced summary로 표시한다.

### 13.7 OpenSearch Output (`OpenSearchOutputAdapter`)

| UI label | REST field | 기본/필수 |
| --- | --- | --- |
| Base URL | `url` | 필수 |
| Index template | `indexTemplate` | 필수 |
| Username | `osUsername` | 선택 |
| Password | `osPassword` | username과 함께 사용 |
| Timeout | `timeoutMs` | 30,000ms |

- index template preview를 제공한다.
- 저장 field는 metadata의 `index`가 아니라 실제 `indexTemplate`을 사용한다.
- `action`은 항상 `_doc` POST로 동작하므로 입력을 제공하지 않는다.
- hostname verification 비활성화라는 현재 runtime 보안 동작을 warning으로 표시한다.

### 13.8 RabbitMQ Output (`RabbitMQAdapter`)

| UI label | REST field | 기본/필수 |
| --- | --- | --- |
| Host | `host` | 필수 |
| Port | `rmqPort` | 5672 |
| Exchange | `exchange` | 필수 |
| Routing key | `routingkey` | 필수 |
| Username | `rmqUsername` | 운영 환경 명시 권장 |
| Password | `rmqPassword` | encrypted converter 적용 |
| Publisher confirm timeout | `timeoutMs` | 30,000ms |

- exchange type이 TOPIC으로 declare됨을 표시한다.
- TLS output 미지원 warning을 표시한다.
- 사용되지 않는 `tagpass`는 숨긴다.

### 13.9 MariaDB Output (`MariaDbOutputAdapter`)

Connection:

| UI label | `configParams` key | 필수/기본 |
| --- | --- | --- |
| JDBC URL | `jdbcUrl` | 필수 |
| Username environment variable | `usernameEnv` | 필수 |
| Password environment variable | `passwordEnv` | 필수 |
| Table name | `tableName` | castrelyx_agent_events |

Batching:

| UI label | `configParams` key | 기본 |
| --- | --- | --- |
| Batch size | `batchSize` | 100 |
| Flush interval | `flushIntervalMs` | 5,000ms |
| Auto-create schema | `autoCreateSchema` | false |

identifier는 `[A-Za-z0-9_]+`로 검증한다. top-level batch/flush/timeout을 별도 입력으로 제공하지 않는다.

### 13.10 ClickHouse Output (`ClickHouseOutputAdapter`)

설정이 많으므로 section navigation을 사용한다.

Connection:

- Endpoint URL → `endpointUrl`
- Database → `database`
- Username env / Password env → 함께 지정하거나 함께 비움
- Timeout → top-level `timeoutMs`

Tables:

- Raw table → `tableName`, 필수
- Metric table → `metricTableName`
- State table → `stateTableName`
- Event table → `eventTableName`
- Write telemetry tables → `writeTelemetryTables`
- Auto-create schema → `autoCreateSchema`

Buffering:

- Batch size → `batchSize`
- Flush interval → `flushIntervalMs`
- Incomplete group timeout → `incompleteGroupTimeoutMs`
- Max pending groups/items/bytes

DLQ:

- Directory → `incompleteChunkDlqDir`
- Max bytes → `maxIncompleteChunkDlqBytes`
- Max records → `maxIncompleteChunkDlqRecords`

Pending byte 값은 사람이 읽는 MiB 입력과 raw byte preview를 함께 제공한다. chunk group과 DLQ 동작을 Advanced 설명에 표시한다. top-level batch/flush field는 표시하지 않는다.

## 14. 설정 Footer와 상단 명령

### 설정 Footer

- 왼쪽: Delete
- 오른쪽: Discard changes, Save component
- 생성 상태: Cancel, Create component
- mapping 상태: Reset draft, Save mapping

### 상단 명령

| 동작 | 의미 |
| --- | --- |
| Validate | 현재 화면의 전체 draft 무결성 검사, 배포하지 않음 |
| Save changes | 저장되지 않은 component/mapping 저장 |
| Deploy | 저장 상태 검증 후 pipeline reload |

현재 API의 `/pipeline/validate-and-reload`는 Validate와 Deploy를 분리하지 않는다. 화면 의미를 그대로 구현하려면 validation-only API가 필요하다. API 추가 전에는 버튼을 합쳐 `Validate & reload`로 표시해야 한다.

## 15. 상태와 오류 디자인

| 상태 | 표현 |
| --- | --- |
| Loading | 설정 skeleton, 기존 pipeline rail 유지 |
| Empty pipeline | 각 단계의 Add 동작 강조, 설명 최소화 |
| Unsaved | 상단 Draft 상태와 Save 활성화 |
| Invalid | 단계 header/노드/field에 동일 error 연결 |
| Save success | 상태를 Saved로 변경, 짧은 toast |
| Deploying | 진행 상태와 cancel 가능 여부 표시 |
| Disabled | 노드 opacity 감소, status text 유지 |
| Test running | Run button progress, 이전 결과 유지 |
| Test failed | field validation과 runtime error를 분리 |

## 16. 반응형과 접근성

- 1440px 이상: 승인된 30/70 split.
- 1024~1439px: 좌측 pipeline 320px 고정, 우측 form 1열 전환 가능.
- 1024px 미만: pipeline을 상단 accordion으로 접고 설정 → 테스트 순서로 세로 배치.
- stage 순서와 drag는 키보드 Move up/Move down 명령도 제공한다.
- toggle, icon-only button, overflow menu에 accessible name을 제공한다.
- error는 색상만으로 표현하지 않고 icon과 text를 함께 사용한다.
- code editor와 JSON result는 가로 스크롤을 허용하고 줄바꿈 옵션을 제공한다.
- focus ring은 cobalt 2px, destructive focus는 red 계열을 사용한다.

## 17. 구현 전 확인이 필요한 기능 의존성

다음 항목은 디자인상 필요하지만 현재 API만으로 완전하게 구현할 수 없다.

1. 저장하지 않은 전체 pipeline draft simulation과 단계별 결과
2. Input adapter 연결/수신 테스트
3. Transform 단독 simulation
4. Output connection-only 및 test delivery
5. Validate와 Deploy의 분리
6. `message type` 안전한 rename
7. Parser/Transform drag reorder를 한 번에 저장하는 batch priority endpoint

이 기능은 UI에서 가짜 성공 상태로 대체하지 않는다. 구현 범위를 정할 때 API 추가 또는 제한된 UI 상태 중 하나를 명시적으로 선택한다.

## 18. 디자인 완료 조건

- 모든 canonical Input/Parser/Transform/Output type이 올바른 argument를 표시한다.
- `configParams`와 JSON 문자열 field를 구조화된 UI로 편집하고 저장 시 올바르게 직렬화한다.
- runtime 미사용 field를 동작 설정처럼 노출하지 않는다.
- `PUT` 전체 교체에 필요한 값을 잃지 않는다.
- pipeline rail의 순서와 실제 priority가 일치한다.
- 테스트 결과가 선택 단계와 현재 draft를 명확히 가리킨다.
- 지원되지 않는 테스트/검증은 구현 의존성으로 표시한다.
- 삭제, 비활성화, 배포가 pipeline 무결성에 미치는 영향을 사전에 보여준다.
