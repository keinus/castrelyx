# LogParser 상세 사용 매뉴얼

이 문서는 현재 코드 기준의 Logparser 관리 UI와 REST API 사용 설명서입니다. adapter type, 필수 필드, `configParams` 예시는 `ConfigMetadataService`, `InputAdapterConfig`, `OutputAdapterConfig`, `ConfigValidationService`, 실제 adapter 구현을 기준으로 정리했습니다.

## 1. 접속과 기본 개념

기본 실행 주소는 다음과 같습니다.

| 항목 | 주소 |
| --- | --- |
| 관리 UI | `http://localhost:8765` |
| API base | `http://localhost:8765/api/v1` |
| Swagger UI | `http://localhost:8765/swagger-ui.html` |
| Live Tail WebSocket | `ws://localhost:8765/ws/tail` |

기본 흐름은 다음과 같습니다.

```text
Input Adapter -> MessageDispatcher -> ProcessingDispatcher -> Processing Steps (Parser/Transform) -> Structured Mapping -> Output Adapter
```

`messagetype`은 입력, processing step(parser/transform), output을 연결하는 키입니다. 출력 어댑터의 `messagetype`을 비우면 runtime 생성 시 `all`로 정규화되어 모든 message type을 받을 수 있습니다.

입력 또는 출력 어댑터가 초기화나 런타임 처리 중 예외를 발생시키면 Logparser는 그 어댑터만 자동으로 OFF(`enabled=false`) 처리합니다. 실패한 어댑터는 현재 런타임에서도 제거되며 다른 어댑터와 파이프라인은 계속 동작합니다. 설정이나 외부 연결 문제를 해결한 다음 Inputs 또는 Outputs 화면에서 다시 활성화합니다.

## 2. 화면 구성

모든 화면은 React/shadcn 기반의 동일한 사이드바와 헤더를 사용합니다. 좁은 화면에서는 헤더의 메뉴 버튼으로 사이드바를 열 수 있습니다. 선택한 Message type은 Studio와 Schema Mapping 사이에서 유지됩니다.

| 그룹 | 메뉴 | 용도 |
| --- | --- | --- |
| Workspace | Overview | 저장된 경로, 실행 상태, 큐, 출력 전송 지표와 worker thread를 확인합니다. |
| Workspace | Pipeline Studio | Input → Processing Steps → Structured Mapping → Output을 편집하고 테스트합니다. 기본 진입 화면입니다. |
| Workspace | Live Tail | dispatcher 이벤트를 WebSocket으로 확인합니다. |
| Configuration | Inputs / Outputs | 입력·출력 어댑터를 검색, 생성, 수정, 복제, 삭제합니다. |
| Configuration | Parsers / Transforms | 처리 단계의 속성을 편집합니다. 테스트는 Open pipeline으로 Studio에서 실행합니다. |
| Configuration | Schema Mapping | field mapping, 조건부 sub-table rule, 전역 template을 관리합니다. |
| System | Settings | 공통 설정과 reload/restart를 관리합니다. |
| System | Documentation | 사용자 매뉴얼, 설정 reference, Mermaid·StarUML 다이어그램을 읽습니다. |

이전 `markdown-viewer.html`, `transform.html`, `index_legacy.html` 주소도 공통 UI로 이동합니다. `readme/manual-assets/`의 이전 스크린샷과 메뉴 이름은 현재 화면과 다를 수 있습니다.

### 2.1 Pipeline Studio

상단에서 case-sensitive Message type을 선택합니다. + 버튼은 빈 작업 공간을 만들며, 실제 설정은 첫 component를 저장할 때 생성됩니다.

| 영역 | 동작 |
| --- | --- |
| Pipeline stages | Input부터 Output까지 활성 상태와 주요 설정을 표시합니다. Parser/Transform 옆 위·아래 버튼으로 공통 실행 순서를 저장합니다. |
| 속성 편집기 | Inputs/Outputs 목록과 같은 컴포넌트입니다. 연결·TLS·인증·큐·배치·DLQ를 탭별 개별 필드로 입력합니다. |
| Test pipeline | 선택한 현재 draft를 저장 전에도 테스트합니다. 직전 활성 processing step의 결과를 Source로 이어 받습니다. |

SNMP 대상과 OID는 항목별 행으로, HTTP 헤더와 변환 규칙은 key/value 입력으로 편집합니다. 알려지지 않은 기존 설정도 유지하며, Advanced의 Additional attributes에서 개별 속성을 추가할 수 있습니다. 잘못된 기존 JSON은 자동으로 빈 값으로 덮어쓰지 않고 오류로 표시합니다. REST API로 복구한 뒤 다시 여세요.

새 component와 복제본은 Disabled 상태로 시작합니다. 저장 전에 Enabled를 확인하세요. Output의 Message type을 `all`로 설정하면 모든 message type을 받으며 각 Studio 경로에도 표시됩니다. `Save input/output/parser/transform`과 `Save mapping`은 해당 설정을 저장하고 런타임 갱신을 요청합니다. 별도 Deploy 단계는 없습니다.

`tcp`, `http`, `clickhouse` 같은 입력·출력 별칭으로 저장한 설정도 동일한 속성 편집기를 사용하며, 편집 후 저장하면 canonical type 이름으로 정규화합니다. 같은 출력 어댑터의 활성화 요청을 반복해도 런타임 등록과 전송은 중복되지 않습니다.

`Validate`는 현재 draft의 필수 필드·범위와 활성 입출력 연결을 로컬에서 검사합니다. 네트워크 연결·인증서·실제 전송 성공을 보장하지 않습니다. `Reload configuration`은 저장된 설정으로 `/api/v1/pipeline/validate-and-reload`를 호출하며 실행 전에 확인창을 표시합니다. 저장되지 않은 변경을 두고 다른 화면이나 Message type으로 이동하면 폐기 여부를 확인합니다.

`Test selected step`은 현재 선택한 component만 테스트합니다.

- 첫 processing step은 Original sample event를 사용합니다. Input preview 실행을 먼저 요구하지 않습니다.
- 하위 step은 직전 활성 order의 성공한 결과를 사용하며 상위 step을 다시 실행하지 않습니다. Source 펼침 영역에서 해당 결과를 확인할 수 있습니다.
- Parser의 Source field에 필드를 지정하면 그 값을 파싱하고 해당 필드를 결과 객체로 교체합니다. 하위 테스트에서 이 필드를 비우면 직전 결과 전체를 파싱합니다. **운영 런타임에서 빈 sourceField는 originalText를 사용**하므로 테스트 화면에도 이 차이를 안내합니다.
- 상위 결과가 없거나 실패·drop 상태이면 하위 테스트를 차단합니다. 비활성 processing step은 상속 순서에서 제외합니다.
- Sample, 상위 설정, 활성 상태, Order 변경과 상위 테스트 재실행은 관련 하위 결과를 무효화합니다. 테스트한 동일 draft를 저장한 경우에는 새 ID와 시간 필드가 생겨도 결과를 유지합니다.
- Structured mapping은 마지막 처리 결과를, Output preview는 mapping 결과를 사용합니다. Input preview는 별도로 Sample만 사용합니다.
- 결과는 화면 메모리에만 보관하며 새로고침이나 Message type 변경 시 초기화됩니다.
- 매핑 변경을 폐기하고 다른 단계로 이동하면 마지막으로 저장한 매핑으로 복구하고 해당 매핑·하위 출력의 임시 테스트 결과를 무효화합니다. 저장하거나 template을 적용한 매핑은 유지됩니다.
- 파서가 sample에 일치하지 않으면 테스트 API는 `400`과 오류 메시지를 반환합니다. 빈 객체를 성공 결과로 취급하지 않습니다.

| 단계 | 테스트 범위 |
| --- | --- |
| Parser | 실제 `/api/v1/parsers/test`로 draft를 테스트합니다. |
| Structured mapping | 임시 mapping을 포함해 `/api/v1/structure/simulate`를 호출합니다. |
| Input | 로컬 payload 미리보기입니다. listener, TLS handshake, gzip 해제, 외부 연결을 테스트하지 않습니다. |
| Transform | Filter, AddProperty, RemoveProperty를 로컬에서 미리봅니다. |
| Output | 목적지와 event 미리보기입니다. 외부 전송·ACK·저장을 시도하지 않습니다. |

Parser와 transform은 공통 `priority` 순서로 교차 실행합니다. Parser 성공 후 다음 step이 계속 실행되며 실패 시 `continueOnFailure=true`이면 입력을 유지하고 다음 단계로 진행합니다. Filter가 이벤트를 drop하면 이후 단계와 output은 실행하지 않습니다.

## 3. Overview

Overview의 Pipelines 탭에서 저장된 message type 경로를 선택하면 Studio로 이동합니다. 기존 Pipeline View는 이 탭으로 통합했습니다. Output delivery와 Worker threads는 실제 서버 지표를 10초마다 갱신합니다. 서버에 연결할 수 없으면 unavailable 또는 오류를 표시하고 가짜 상태나 처리량을 표시하지 않습니다.

| 확인 대상 | 설명 |
| --- | --- |
| Inputs | 입력이 Enabled인지, port·TLS·broker 설정이 올바른지 확인합니다. |
| Parsers / Transforms | Message type과 공통 처리 순서를 확인합니다. |
| Outputs | 출력 활성 여부와 해당 Message type 또는 all 범위를 확인합니다. |
| Settings | 저장된 설정의 reload/restart가 필요한 경우 실행합니다. |

기존 topology API `/api/v1/pipeline/topology`도 유지됩니다.

## 4. Live Tail

Live Tail은 처리 중인 이벤트를 `/ws/tail` WebSocket으로 브로드캐스트합니다. 서비스 상태는 다음 API로 제어합니다.

| Method | Path | 설명 |
| --- | --- | --- |
| `GET` | `/api/v1/pipeline/livetail/status` | 활성 여부 조회 |
| `POST` | `/api/v1/pipeline/livetail/enable` | Live Tail 활성화 |
| `POST` | `/api/v1/pipeline/livetail/disable` | Live Tail 비활성화 |

Live Tail은 output adapter가 아니라 dispatcher 단계의 관찰 경로입니다. output이 실패해도 Live Tail에 이벤트가 보일 수 있고, 반대로 Live Tail이 꺼져 있어도 output은 계속 동작할 수 있습니다.

화면에는 최근 500개 이벤트만 유지합니다. 내용 검색과 Message type 필터, 이벤트 펼치기, 표시 중인 이벤트의 JSONL 내보내기를 지원합니다. Pause 동안 도착한 이벤트는 버리며 Resume은 이후 이벤트부터 표시합니다. 화면을 떠나면 이 브라우저의 연결만 닫습니다. Enable/Disable capture는 모든 뷰어에 영향을 주는 서버 설정이므로 확인 창을 거칩니다.

## 5. Inputs: 입력 어댑터

Inputs 화면 또는 `/api/v1/input-adapters` API에서 입력을 관리합니다.

### 공통 필드

| 필드 | 설명 |
| --- | --- |
| `type` | 입력 adapter type 또는 alias |
| `messagetype` | parser/output 연결 키 |
| `enabled` | 활성 여부 |
| `host` | 일부 네트워크 adapter의 bind 또는 접속 host |
| `port` | 일부 네트워크 adapter의 listen 또는 접속 port |
| `timeoutMs` | 연결/수신 timeout |
| `workerThreads` | 일부 adapter의 worker thread 수 |
| `queueSize` | 일부 adapter의 내부 queue 크기 |
| `configParams` | adapter별 JSON 설정 문자열 |

### 입력 타입별 필수 필드

| Adapter Type | Alias | 필수 필드 | 선택 필드 | 용도 |
| --- | --- | --- | --- | --- |
| `FileInputAdapter` | `file` | `path` | `isFromBeginning` | 파일 tail 방식 로그 수집 |
| `TcpInputAdapter` | `tcp` | `port` | `host`, `timeoutMs` | newline TCP 수신 |
| `TlsTcpInputAdapter` | `tls_tcp`, `tlstcp` | `port`, `configParams` | `host`, `timeoutMs` | newline TCP over TLS |
| `UdpInputAdapter` | `udp` | `port` | `host` | UDP datagram 수신 |
| `HttpInputAdapter` | `http` | `port` | `path_pattern`, `codec` | HTTP 요청 수신 |
| `HttpsInputAdapter` | `https` | `port`, `configParams` | `path_pattern`, `codec` | HTTP 요청 over TLS |
| `KafkaInputAdapter` | `kafka` | `bootstrapservers`, `topicid` | `groupId` | Kafka topic consume |
| `SnmpInputAdapter` | `snmp` | `configParams` | `timeoutMs`, `queueSize`, `workerThreads` | SNMP polling |
| `RabbitMqInputAdapter` | `rabbitmq` | `configParams.queue` | `host`, `port`, `timeoutMs` | RabbitMQ queue polling |
| `TlsRabbitMqInputAdapter` | `tls_rabbitmq`, `tlsrabbitmq` | `configParams.queue` | `host`, `port`, `timeoutMs` | RabbitMQ queue over TLS |
| `TcpMtlsGzipInputAdapter` | `tcp_mtls_gzip` | `port`, `configParams` | `timeoutMs`, `queueSize`, `workerThreads` | Castrelyx agent batch ingest |
| `FakeInputAdapter` | `fake` | 없음 | 없음 | 테스트 이벤트 생성 |

### TCP/TLS 입력 설정

`TlsTcpInputAdapter`는 `TcpInputAdapter`와 같은 newline-delimited TCP 프로토콜을 TLS 서버 소켓으로 받습니다.

```json
{
  "type": "TlsTcpInputAdapter",
  "messagetype": "secure-lines",
  "port": 6514,
  "enabled": true,
  "configParams": "{\"keyStorePath\":\"/app/certs/logparser-server.p12\",\"keyStorePasswordEnv\":\"LOGPARSER_KEYSTORE_PASSWORD\",\"clientAuth\":\"none\",\"enabledProtocols\":[\"TLSv1.3\",\"TLSv1.2\"]}"
}
```

`clientAuth`는 `none`, `want`, `need`를 지원합니다. `needClientAuth=true` 또는 `wantClientAuth=true`도 사용할 수 있습니다. `want` 또는 `need`를 쓰면 `trustStorePath`와 `trustStorePassword` 또는 `trustStorePasswordEnv`가 필요합니다.

### HTTPS 입력 설정

`HttpsInputAdapter`는 `HttpInputAdapter`와 같은 HTTP 요청 수신을 TLS로 처리합니다. 현재 구현은 HTTP 요청 전체를 원문 문자열로 읽어 `LogEvent`를 생성합니다.

```json
{
  "type": "HttpsInputAdapter",
  "messagetype": "webhook",
  "port": 8443,
  "path_pattern": "/events",
  "enabled": true,
  "configParams": "{\"keyStorePath\":\"/app/certs/logparser-server.p12\",\"keyStorePasswordEnv\":\"LOGPARSER_KEYSTORE_PASSWORD\",\"clientAuth\":\"need\",\"trustStorePath\":\"/app/certs/client-ca.p12\",\"trustStorePasswordEnv\":\"LOGPARSER_TRUSTSTORE_PASSWORD\"}"
}
```

### RabbitMQ 입력 설정

`RabbitMqInputAdapter`는 RabbitMQ Java client로 queue를 `basicGet` polling합니다. 기본값은 `autoAck=false`이며, 메시지를 읽어 `LogEvent`로 만들기 전에 ack를 보냅니다.

```json
{
  "queue": "logs.input",
  "username": "guest",
  "password": "guest",
  "virtualHost": "/",
  "autoAck": false,
  "prefetchCount": 10,
  "declareQueue": false
}
```

queue 선언과 binding이 필요하면 다음 필드를 함께 사용합니다.

```json
{
  "queue": "logs.input",
  "exchange": "logs.topic",
  "routingKey": "logs.#",
  "declareQueue": true,
  "durableQueue": true,
  "exclusiveQueue": false,
  "autoDeleteQueue": false,
  "bindQueue": true
}
```

TLS RabbitMQ는 두 가지 방식이 있습니다.

1. `RabbitMqInputAdapter`에 `tlsEnabled=true` 또는 `ssl=true` 지정
2. `TlsRabbitMqInputAdapter` 사용

`TlsRabbitMqInputAdapter`는 TLS가 기본값이며 port를 생략하면 `5671`을 사용합니다.

```json
{
  "queue": "logs.input",
  "username": "guest",
  "password": "guest",
  "virtualHost": "/",
  "hostnameVerification": true,
  "trustStorePath": "/app/certs/rabbitmq-truststore.p12",
  "trustStorePasswordEnv": "RABBITMQ_TRUSTSTORE_PASSWORD"
}
```

client certificate이 필요한 RabbitMQ 환경에서는 `keyStorePath`, `keyStorePassword` 또는 `keyStorePasswordEnv`, 필요 시 `keyPassword` 또는 `keyPasswordEnv`를 추가합니다.

### SNMP 입력 설정

`SnmpInputAdapter`는 `targets[]`와 `oids[]`가 필요합니다.

```json
{
  "intervalMs": 60000,
  "retries": 1,
  "targets": [
    {
      "name": "sw-core-01",
      "host": "192.0.2.10",
      "port": 161,
      "community": "public",
      "version": "2c"
    }
  ],
  "oids": [
    {
      "name": "sysName",
      "oid": "1.3.6.1.2.1.1.5.0"
    }
  ]
}
```

SNMPv3 `authPriv` 예시는 다음과 같습니다.

```json
{
  "intervalMs": 60000,
  "retries": 1,
  "targets": [
    {
      "name": "fw-edge-01",
      "host": "192.0.2.20",
      "port": 161,
      "version": "3",
      "securityName": "poller",
      "securityLevel": "authPriv",
      "authProtocol": "SHA256",
      "authPassphraseEnv": "SNMP_AUTH_PASSPHRASE",
      "privProtocol": "AES128",
      "privPassphraseEnv": "SNMP_PRIV_PASSPHRASE"
    }
  ],
  "oids": [
    {
      "name": "sysName",
      "oid": "1.3.6.1.2.1.1.5.0"
    }
  ]
}
```

대상 수와 OID 수가 많으면 한 polling 주기에 `target_count * oid_count` 만큼 요청이 생깁니다. `intervalMs`, `timeoutMs`, `retries`, `workerThreads`, `queueSize`를 함께 조정합니다.

### Castrelyx agent TCP mTLS gzip 입력

`TcpMtlsGzipInputAdapter`는 Castrelyx agent batch를 받는 전용 adapter입니다.

```json
{
  "keyStorePath": "/var/lib/castrelsign/certs/server.p12",
  "keyStorePasswordEnv": "CASTRELSIGN_KEYSTORE_PASSWORD",
  "trustStorePath": "/var/lib/castrelsign/certs/truststore.p12",
  "trustStorePasswordEnv": "CASTRELSIGN_TRUSTSTORE_PASSWORD",
  "maxFrameBytes": 10485760,
  "ackMode": "queueAccepted"
}
```

동작 규칙은 다음과 같습니다.

- TLS protocol은 `TLSv1.3`, `TLSv1.2`를 활성화합니다.
- client certificate이 필수입니다.
- certificate subject CN이 batch JSON의 `source_id`와 같아야 합니다.
- frame은 4-byte length와 gzip JSON payload로 구성됩니다.
- 각 `items[]` 원소가 하나의 `LogEvent`가 됩니다.
- 처리 성공 시 `{"status":"accepted"}`를 반환합니다.
- frame 오류, CN 불일치, queue full은 error response를 반환합니다.

## 6. Parsers

Parsers 화면 또는 `/api/v1/parsers` API에서 parser를 관리합니다.

| Parser Type | 필수 필드 | 용도 |
| --- | --- | --- |
| `JsonParser` | 없음 | JSON 원문을 field map으로 파싱 |
| `GrokParser` | `param` | Grok pattern 적용 |
| `RegexParser` | `param` | 이름 있는 그룹은 필드로 추출. 이름 없는 패턴은 group 1=key, group 2=value |
| `RFC3164SyslogParser` | 없음 | RFC3164 syslog 파싱 |
| `RFC5424SyslogParser` | 없음 | RFC5424 syslog 파싱 |
| `HttpParser` | 없음 | HTTP access log 파싱 |

`GrokParser`와 `RegexParser`는 `param`이 필요합니다. parser 테스트 API는 `/api/v1/parsers/test`입니다.

Parser의 선택 필드 `sourceField`를 지정하면 원문 대신 앞선 step이 만든 event field를 parser 입력으로 사용합니다. 빈 값은 `originalText`이며, 문자열은 그대로 전달하고 숫자/boolean은 문자열로, Map/List는 JSON 문자열로 변환합니다. 단, `RegexParser`는 List의 각 항목을 따로 파싱합니다. field가 없거나 null이면 parser step이 실패합니다. 지정한 `sourceField`의 파싱이 성공하면 기존 값을 삭제하고 같은 필드에 결과 Map을 저장합니다. Pipeline Studio의 parser 설정에서 `Source field`로 지정할 수 있습니다.

예를 들어 `Source field`가 `syslog_STRUCTURED_DATA`이면 `["[exampleSDID@32473 iut=\"3\"]"]`의
배열 전체가 아닌 `[exampleSDID@32473 iut="3"]`에 패턴을 적용합니다.
`^\[(?<sdid>\w+)@(?<id>\d+)\s+(?<attributes>.*)\]$`는 `sdid`, `id`, `attributes`를 추출하고,
`attributes` 내부의 `iut="3"`, `eventSource="Application"`도 각각 `iut`, `eventSource` key-value로 병합하며,
파싱에 성공하면 원래 `syslog_STRUCTURED_DATA` 배열을 삭제한 뒤 같은 필드에 결과 Map을 넣습니다. 여러 항목이 매칭되면 결과를 순서대로 병합하고,
같은 필드는 마지막으로 매칭된 값이 남습니다. 매칭되지 않은 항목은 건너뛰며, 빈 배열이나
모든 항목이 불일치하는 배열은 파싱 실패입니다. 테스트 API와 실제 pipeline에 동일하게 적용됩니다.

## 7. Event Rules: Transform

Event Rules 화면 또는 `/api/v1/transforms` API에서 transform을 관리합니다.

| Transform Type | 필수 필드 | 용도 |
| --- | --- | --- |
| `Filter` | `filterPass` 또는 `filterDrop` | 조건 기반 통과/제거 |
| `AddProperty` | `addProperties` | field 추가 |
| `RemoveProperty` | `removeProperties` | field 제거 |

Transform은 parser와 동일한 Processing Steps 목록에서 priority에 따라 실행됩니다. 입력 adapter 내부에서 parser나 transform을 대신 수행하지 않습니다.

Processing Steps의 전체 순서는 `PUT /api/v1/pipeline/{messageType}/processing-steps/order`에 다음처럼 저장합니다.

```json
{"steps":[{"kind":"PARSER","id":3},{"kind":"TRANSFORM","id":7}]}
```

현재 message type의 parser/transform을 모두 포함해야 하며, 서버가 양쪽 priority를 transaction으로 재번호화합니다. 목록이 동시에 변경되면 `409 Conflict`가 반환되므로 최신 pipeline을 다시 읽어야 합니다.

## 8. Schema Mapping

Schema Mapping은 `/api/v1/structure/*` API를 사용합니다.

| Method | Path | 설명 |
| --- | --- | --- |
| `GET` | `/api/v1/structure/schema` | target schema 조회 |
| `GET` | `/api/v1/structure/mapping/{messageType}` | message type별 mapping 조회 |
| `POST` | `/api/v1/structure/mapping` | mapping 저장 |
| `POST` | `/api/v1/structure/simulate` | mapping 시뮬레이션 |
| `GET` | `/api/v1/structure/templates` | mapping template 목록 조회 |
| `POST` | `/api/v1/structure/templates` | 현재 mapping을 재사용 가능한 template으로 저장 |
| `PUT` | `/api/v1/structure/templates/{id}` | template 수정 |
| `DELETE` | `/api/v1/structure/templates/{id}` | template 삭제 |
| `POST` | `/api/v1/structure/templates/{id}/apply?messageType=...` | template을 지정 message type mapping에 덮어쓰기 적용 |

Castrelyx agent 이벤트처럼 `source_id`, `tenant_id`, `item_kind`, `item_type`, `item_key`, `payload_*` field가 있는 이벤트는 output adapter가 조회용 컬럼을 자동 추출합니다. 별도 structured mapping을 적용하는 경우, mapping 결과가 `LogEvent.toOutputMap()`에 들어가는 구조를 확인합니다.

Schema Mapping의 `Mapping template` 선택 영역에서 전역 template을 관리할 수 있습니다. `Save as template`은 현재 화면의 mapping을 template으로 저장하고, `Apply`는 선택한 template을 현재 `messageType`의 mapping으로 덮어씁니다. 적용 전 확인 창을 표시하며 저장과 동시에 런타임에도 반영됩니다. Template 삭제는 저장된 template만 삭제하며 이미 적용된 message type mapping은 삭제하지 않습니다.

Template에는 `name`, `description`, `sourceMessageType`, `config`가 저장됩니다. `sourceMessageType`은 template을 만든 기준을 남기는 값이고, 실제 적용 대상은 화면의 Message Type 입력값 또는 apply API의 `messageType` query 값입니다. 같은 이름의 template은 중복 생성할 수 없습니다.

적용 동작은 overwrite 방식입니다. 예를 들어 `firewall` template을 `vpn` message type에 적용하면 `vpn`의 기존 structured mapping이 template 내용으로 교체되고, 이후 들어오는 `vpn` 이벤트는 새 mapping cache를 다시 구성해서 처리합니다. 원본 template과 `firewall` mapping은 변경되지 않습니다.

## 9. Outputs: 출력 어댑터

Outputs 화면 또는 `/api/v1/output-adapters` API에서 출력을 관리합니다.

### 공통 필드

| 필드 | 설명 |
| --- | --- |
| `type` | 출력 adapter type 또는 alias |
| `messagetype` | 처리할 message type. 비우면 `all` |
| `enabled` | 활성 여부 |
| `addOriginText` | 원문 text를 출력 JSON에 포함할지 여부 |
| `timeoutMs` | 출력 연결/request timeout |
| `batchSize` | 일부 batch 출력의 batch 크기 |
| `flushIntervalMs` | 일부 batch 출력의 주기 flush 간격 |
| `configParams` | adapter별 JSON 설정 문자열 |

### 출력 타입별 필수 필드

| Adapter Type | Alias | 필수 필드 | 선택 필드 | 용도 |
| --- | --- | --- | --- | --- |
| `ConsoleOutputAdapter` | `console` | 없음 | `addOriginText` | 서버 콘솔/로그 출력 |
| `TcpOutputAdapter` | `tcp` | `host`, `port` | `timeoutMs` | TCP 전송 |
| `HttpOutputAdapter` | `http` | `url` | `method`, `headers`, `timeoutMs` | HTTP 전송 |
| `KafkaOutputAdapter` | `kafka` | `bootstrapservers`, `topicid` | `key` | Kafka produce |
| `OpenSearchOutputAdapter` | `opensearch` | `url`, `index` | `osUsername`, `osPassword`, `action` | OpenSearch/Elasticsearch index |
| `RabbitMQAdapter` | `rabbitmq` | `host`, `exchange`, `routingkey` | `rmqPort`, `rmqUsername`, `rmqPassword`, `tagpass` | RabbitMQ publish |
| `MariaDbOutputAdapter` | `mariadb` | `configParams` | `batchSize`, `flushIntervalMs` inside config | MariaDB 저장 |
| `ClickHouseOutputAdapter` | `clickhouse` | `configParams` | `batchSize`, `flushIntervalMs` inside config | ClickHouse 저장 |
| `BenchmarkAdapter` | `benchmark` | 없음 | 없음 | 성능 측정 |

### MariaDB 출력

MariaDB 출력은 JDBC URL과 계정 환경 변수 이름을 `configParams`로 받습니다.

```json
{
  "jdbcUrl": "jdbc:mariadb://mariadb:3306/castrelyx",
  "usernameEnv": "CASTRELYX_DB_USER",
  "passwordEnv": "CASTRELYX_DB_PASSWORD",
  "tableName": "castrelyx_agent_events",
  "batchSize": 100,
  "flushIntervalMs": 5000,
  "autoCreateSchema": true
}
```

필드 설명:

| 필드 | 필수 | 설명 |
| --- | --- | --- |
| `jdbcUrl` | 예 | MariaDB JDBC URL |
| `usernameEnv` | 예 | DB username이 들어 있는 환경 변수 이름 |
| `passwordEnv` | 예 | DB password가 들어 있는 환경 변수 이름 |
| `tableName` | 아니오 | 기본값 `castrelyx_agent_events`; 영문/숫자/underscore만 허용 |
| `batchSize` | 아니오 | 기본값 `100`; 0보다 커야 함 |
| `flushIntervalMs` | 아니오 | 기본값 `5000`; 0보다 커야 함 |
| `autoCreateSchema` | 아니오 | true면 table을 자동 생성 |

저장 컬럼:

| 컬럼 | 설명 |
| --- | --- |
| `agent_id` | 현재 구현은 추출된 `source_id` 값을 사용 |
| `tenant_id` | event 또는 additional attributes에서 추출 |
| `source_id` | event `source_id`, additional `source_id`, common `srcHost`, source host 순으로 추출 |
| `item_kind` | event/additional `item_kind`, common `eventCategory` 순으로 추출 |
| `item_type` | event/additional `item_type`, common `eventType` 순으로 추출 |
| `item_key` | event/additional `item_key`, common `eventAction` 순으로 추출 |
| `event_json` | `LogEvent.toOutputJson(addOriginText)` 결과 |

API 생성 예시:

```powershell
curl -X POST http://localhost:8765/api/v1/output-adapters `
  -H 'Content-Type: application/json' `
  -d '{
    "type": "MariaDbOutputAdapter",
    "messagetype": "castrelyx-agent-item",
    "enabled": true,
    "configParams": "{\"jdbcUrl\":\"jdbc:mariadb://mariadb:3306/castrelyx\",\"usernameEnv\":\"CASTRELYX_DB_USER\",\"passwordEnv\":\"CASTRELYX_DB_PASSWORD\",\"tableName\":\"castrelyx_agent_events\",\"batchSize\":100,\"flushIntervalMs\":5000,\"autoCreateSchema\":true}"
  }'
```

### ClickHouse 출력

ClickHouse 출력은 HTTP API를 사용합니다.

```json
{
  "endpointUrl": "http://clickhouse:8123",
  "database": "castrelyx",
  "tableName": "castrelyx_agent_events",
  "usernameEnv": "CLICKHOUSE_USER",
  "passwordEnv": "CLICKHOUSE_PASSWORD",
  "batchSize": 100,
  "flushIntervalMs": 5000,
  "autoCreateSchema": true
}
```

필드 설명:

| 필드 | 필수 | 설명 |
| --- | --- | --- |
| `endpointUrl` | 예 | ClickHouse HTTP endpoint. `http` 또는 `https`만 허용 |
| `database` | 아니오 | 기본값 `default`; 영문/숫자/underscore만 허용 |
| `tableName` | 검증상 예 | table 이름; 영문/숫자/underscore만 허용 |
| `usernameEnv` | 아니오 | Basic auth username 환경 변수 이름 |
| `passwordEnv` | 아니오 | Basic auth password 환경 변수 이름 |
| `batchSize` | 아니오 | 기본값 `100`; 0보다 커야 함 |
| `flushIntervalMs` | 아니오 | 기본값 `5000`; 0보다 커야 함 |
| `autoCreateSchema` | 아니오 | true면 MergeTree table을 자동 생성 |

`usernameEnv`와 `passwordEnv`는 둘 다 지정하거나 둘 다 생략해야 합니다.

## 10. Metadata와 Validation API

서버는 다음 metadata API로 type 목록과 필드 schema를 제공합니다. 현재 UI는 `frontend/src/lib/adapter-definitions.ts`의 공통 필드 정의를 Studio와 목록 편집기에서 함께 사용합니다. 새 어댑터나 속성을 추가할 때 서버 metadata와 UI 정의를 함께 갱신해야 합니다.

| Method | Path | 설명 |
| --- | --- | --- |
| `GET` | `/api/v1/metadata/input-adapter-types` | 입력 type 목록 |
| `GET` | `/api/v1/metadata/input-adapter-schema/{type}` | 입력 type별 schema |
| `GET` | `/api/v1/metadata/parser-types` | parser type 목록 |
| `GET` | `/api/v1/metadata/parser-schema/{type}` | parser schema |
| `GET` | `/api/v1/metadata/transform-types` | transform type 목록 |
| `GET` | `/api/v1/metadata/transform-schema/{type}` | transform schema |
| `GET` | `/api/v1/metadata/output-adapter-types` | output type 목록 |
| `GET` | `/api/v1/metadata/output-adapter-schema/{type}` | output type별 schema |
| `GET` | `/api/v1/metadata/supported-codecs` | `plain`, `json`, `line` |
| `GET` | `/api/v1/metadata/supported-http-methods` | `POST`, `PUT`, `PATCH` |

검증 API는 다음과 같습니다.

| Method | Path | 설명 |
| --- | --- | --- |
| `GET` | `/api/v1/validate/pipeline` | 저장된 pipeline 무결성 검증 |
| `POST` | `/api/v1/validate/input` | 입력 설정 검증 |
| `POST` | `/api/v1/validate/parser` | parser 설정 검증 |
| `POST` | `/api/v1/validate/transform` | transform 설정 검증 |
| `POST` | `/api/v1/validate/output` | output 설정 검증 |
| `GET` | `/api/v1/validate/errors` | 누적 validation error 조회 |

## 11. Settings와 운영 제어

Pipeline 제어 API는 다음과 같습니다.

| Method | Path | 설명 |
| --- | --- | --- |
| `GET` | `/api/v1/pipeline/status` | 현재 상태 조회 |
| `GET` | `/api/v1/pipeline/output-metrics` | output metric 조회 |
| `POST` | `/api/v1/pipeline/reload` | 저장된 설정으로 pipeline reload |
| `POST` | `/api/v1/pipeline/validate-and-reload` | 검증 후 reload |
| `POST` | `/api/v1/pipeline/restart` | pipeline restart |
| `GET` | `/api/v1/pipeline/reload-progress` | reload 진행 상태 |
| `POST` | `/api/v1/pipeline/cancel-reload` | reload 취소 요청 |
| `GET` | `/api/v1/pipeline/threads` | thread detail 조회 |
| `PUT` | `/api/v1/pipeline/{messageType}/processing-steps/order` | parser/transform 공통 실행 순서 저장 |

어댑터와 처리 단계의 Save는 저장된 설정을 런타임에 반영합니다. Studio의 `Reload configuration`과 Settings의 Reload는 현재 저장된 설정을 다시 읽으며, 화면의 미저장 변경을 배포하는 동작이 아닙니다. Settings에서 검증 후 Reload와 Restart도 실행할 수 있으며, 실행 전 확인 창을 표시합니다.

Reload 취소는 즉시 진행 중 표시를 해제하지 않습니다. 입력을 중지하고 처리 중인 이벤트를 비운 안전한 단계에서 취소 요청을 확인한 뒤 이전 런타임 설정을 복구합니다. 복구가 끝날 때까지 다른 Reload/Restart는 거부됩니다. 마지막 취소 확인 단계를 지나 입력을 다시 시작하는 중이면 해당 Reload는 정상 완료될 수 있습니다.

## 12. 문서 API

문서 서비스는 다음 root만 허용합니다.

| 허용 경로 | 설명 |
| --- | --- |
| `README.md` | 빠른 참조 문서 |
| `AGENTS.md` | 내부 구현 가이드 |
| `readme/**` | 사용자 매뉴얼, 다이어그램, 이미지 |
| `docs/**` | 추가 문서 디렉터리 |

API:

| Method | Path | 설명 |
| --- | --- | --- |
| `GET` | `/api/v1/docs/content?path=README.md` | 텍스트 문서 읽기 |
| `GET` | `/api/v1/docs/raw?path=readme/manual-assets/01-overview.png` | 이미지 등 raw asset 읽기 |

텍스트 확장자는 `md`, `markdown`, `mdj`, `json`, `mmd`, `txt`, `html`입니다. raw asset은 이미지 확장자도 허용합니다.

## 13. 보안과 secret 관리

- MariaDB/ClickHouse 출력 인증은 환경 변수 참조만 지원합니다.
- TLS key/trust store password는 직접 값과 환경 변수 참조를 모두 지원하지만, 운영에서는 환경 변수 참조를 권장합니다.
- SNMPv3 passphrase는 `authPassphraseEnv`, `privPassphraseEnv`를 권장합니다.
- RabbitMQ 입력 password는 현재 `configParams.password` 직접 문자열입니다. DB 접근 권한과 백업 보관 정책으로 보호해야 합니다.
- OpenSearch와 RabbitMQ 출력 password 필드도 설정 DB에 남을 수 있으므로 운영 환경에서는 접근 권한을 제한합니다.

## 14. 문제 해결

| 증상 | 확인 지점 |
| --- | --- |
| 입력이 들어오지 않음 | Inputs enabled, port bind 실패, broker 연결 정보, TLS key/trust store 경로 |
| TLS 입력이 시작되지 않음 | `configParams` JSON, `keyStorePath`, password env, `clientAuth` 설정과 trust store |
| TLS RabbitMQ 연결 실패 | broker port, trust store, hostname verification, broker 인증서 SAN/CN |
| Castrelyx agent batch가 거부됨 | client certificate CN과 batch `source_id` 일치 여부, frame size, gzip JSON 형식 |
| parser 결과가 비어 있음 | `messagetype` 연결, parser type, `param` pattern |
| output이 없음 | Outputs enabled, output `messagetype`, target 연결 정보 |
| MariaDB output 실패 | `CASTRELYX_DB_USER`, `CASTRELYX_DB_PASSWORD`, JDBC URL, table 권한, `autoCreateSchema` |
| ClickHouse output 실패 | endpoint URL scheme, database/table identifier, Basic auth env, HTTP status response |
| 문서가 열리지 않음 | `/api/v1/docs/*` 허용 root와 확장자 확인 |

## 15. 설정 저장소와 migration

기본 설정 DB는 다음 위치에 생성됩니다.

```text
${user.home}/logparser/data/config.db
```

Flyway migration은 `src/main/resources/db/migration`에 있습니다. adapter type이나 alias가 추가되면 SQLite trigger가 새 type을 허용하는지 확인해야 합니다. 현재 입력 trigger는 TLS TCP, HTTPS, TLS RabbitMQ, TCP mTLS gzip을 포함하고, 출력 trigger는 MariaDB와 ClickHouse를 포함합니다.
