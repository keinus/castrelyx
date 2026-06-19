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
Input Adapter -> MessageDispatcher -> ProcessingDispatcher -> Parser -> Transform -> Structured Mapping -> Output Adapter
```

`messagetype`은 입력, parser, transform, output을 연결하는 키입니다. 출력 어댑터의 `messagetype`을 비우면 runtime 생성 시 `all`로 정규화되어 모든 message type을 받을 수 있습니다.

## 2. 화면 구성

| 메뉴 | 용도 |
| --- | --- |
| Overview | pipeline 상태와 처리량을 확인합니다. |
| Pipeline View | 입력, parser, transform, output 연결 상태를 봅니다. |
| Live Tail | 처리 중 이벤트를 WebSocket으로 확인합니다. |
| Sources | 입력 어댑터를 생성, 수정, 삭제, 활성화합니다. |
| Parsers | parser를 생성하고 테스트합니다. |
| Event Rules | transform rule을 관리합니다. |
| Schema Map | structured field mapping을 관리합니다. |
| Destinations | 출력 어댑터를 생성, 수정, 삭제, 활성화합니다. |
| Configuration | 공통 설정을 관리합니다. |
| Actions | reload, validate, restart 같은 pipeline 작업을 실행합니다. |

스크린샷 자산은 `readme/manual-assets/` 아래에 있으며, 화면의 예시 값은 실제 운영 설정과 다를 수 있습니다.

## 3. Overview와 Pipeline View

Overview는 파이프라인 구동 상태, 활성 구성 수, 처리량, 최근 오류를 보는 화면입니다. 처리량이 0이면 다음을 먼저 확인합니다.

| 확인 대상 | 설명 |
| --- | --- |
| Sources | 입력 어댑터가 enabled인지 확인합니다. |
| Parsers | 해당 `messagetype`에 parser가 없으면 원문 이벤트가 그대로 통과할 수 있습니다. |
| Destinations | output이 enabled이고 `messagetype`이 맞는지 확인합니다. |
| Actions | 설정 변경 후 pipeline reload가 되었는지 확인합니다. |

Pipeline View는 저장된 설정을 기준으로 topology를 보여줍니다. 실제 topology API는 `/api/v1/pipeline/topology`입니다.

## 4. Live Tail

Live Tail은 처리 중인 이벤트를 `/ws/tail` WebSocket으로 브로드캐스트합니다. 서비스 상태는 다음 API로 제어합니다.

| Method | Path | 설명 |
| --- | --- | --- |
| `GET` | `/api/v1/pipeline/livetail/status` | 활성 여부 조회 |
| `POST` | `/api/v1/pipeline/livetail/enable` | Live Tail 활성화 |
| `POST` | `/api/v1/pipeline/livetail/disable` | Live Tail 비활성화 |

Live Tail은 output adapter가 아니라 dispatcher 단계의 관찰 경로입니다. output이 실패해도 Live Tail에 이벤트가 보일 수 있고, 반대로 Live Tail이 꺼져 있어도 output은 계속 동작할 수 있습니다.

## 5. Sources: 입력 어댑터

Sources 화면 또는 `/api/v1/input-adapters` API에서 입력을 관리합니다.

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
| `RegexParser` | `param` | 정규식 capture group 적용 |
| `RFC3164SyslogParser` | 없음 | RFC3164 syslog 파싱 |
| `RFC5424SyslogParser` | 없음 | RFC5424 syslog 파싱 |
| `HttpParser` | 없음 | HTTP access log 파싱 |

`GrokParser`와 `RegexParser`는 `param`이 필요합니다. parser 테스트 API는 `/api/v1/parsers/test`입니다.

## 7. Event Rules: Transform

Event Rules 화면 또는 `/api/v1/transforms` API에서 transform을 관리합니다.

| Transform Type | 필수 필드 | 용도 |
| --- | --- | --- |
| `Filter` | `filterPass` 또는 `filterDrop` | 조건 기반 통과/제거 |
| `AddProperty` | `addProperties` | field 추가 |
| `RemoveProperty` | `removeProperties` | field 제거 |

Transform은 parser 뒤에 적용됩니다. 입력 adapter 내부에서 parser나 transform을 대신 수행하지 않습니다.

## 8. Schema Map

Schema Map은 `/api/v1/structure/*` API를 사용합니다.

| Method | Path | 설명 |
| --- | --- | --- |
| `GET` | `/api/v1/structure/schema` | target schema 조회 |
| `GET` | `/api/v1/structure/mapping/{messageType}` | message type별 mapping 조회 |
| `POST` | `/api/v1/structure/mapping` | mapping 저장 |
| `POST` | `/api/v1/structure/simulate` | mapping 시뮬레이션 |

Castrelyx agent 이벤트처럼 `source_id`, `tenant_id`, `item_kind`, `item_type`, `item_key`, `payload_*` field가 있는 이벤트는 output adapter가 조회용 컬럼을 자동 추출합니다. 별도 structured mapping을 적용하는 경우, mapping 결과가 `LogEvent.toOutputMap()`에 들어가는 구조를 확인합니다.

## 9. Destinations: 출력 어댑터

Destinations 화면 또는 `/api/v1/output-adapters` API에서 출력을 관리합니다.

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

UI는 metadata API를 사용해 type 목록과 필드 schema를 가져옵니다.

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

## 11. Actions와 운영 제어

Pipeline 제어 API는 다음과 같습니다.

| Method | Path | 설명 |
| --- | --- | --- |
| `GET` | `/api/v1/pipeline/status` | 현재 상태 조회 |
| `GET` | `/api/v1/pipeline/output-metrics` | output metric 조회 |
| `POST` | `/api/v1/pipeline/reload` | 저장된 설정으로 pipeline reload |
| `POST` | `/api/v1/pipeline/validate-and-reload` | 검증 후 reload |
| `POST` | `/api/v1/pipeline/restart` | pipeline restart |
| `GET` | `/api/v1/pipeline/reload-progress` | reload 진행 상태 |
| `POST` | `/api/v1/pipeline/cancel-reload` | reload 취소 |
| `GET` | `/api/v1/pipeline/threads` | thread detail 조회 |

설정을 추가하거나 수정한 뒤 `auto-reload`가 켜져 있으면 일정 지연 후 자동 reload가 실행됩니다. 즉시 반영하려면 Actions에서 reload를 실행합니다.

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
| 입력이 들어오지 않음 | Sources enabled, port bind 실패, broker 연결 정보, TLS key/trust store 경로 |
| TLS 입력이 시작되지 않음 | `configParams` JSON, `keyStorePath`, password env, `clientAuth` 설정과 trust store |
| TLS RabbitMQ 연결 실패 | broker port, trust store, hostname verification, broker 인증서 SAN/CN |
| Castrelyx agent batch가 거부됨 | client certificate CN과 batch `source_id` 일치 여부, frame size, gzip JSON 형식 |
| parser 결과가 비어 있음 | `messagetype` 연결, parser type, `param` pattern |
| output이 없음 | Destinations enabled, output `messagetype`, target 연결 정보 |
| MariaDB output 실패 | `CASTRELYX_DB_USER`, `CASTRELYX_DB_PASSWORD`, JDBC URL, table 권한, `autoCreateSchema` |
| ClickHouse output 실패 | endpoint URL scheme, database/table identifier, Basic auth env, HTTP status response |
| 문서가 열리지 않음 | `/api/v1/docs/*` 허용 root와 확장자 확인 |

## 15. 설정 저장소와 migration

기본 설정 DB는 다음 위치에 생성됩니다.

```text
${user.home}/logparser/data/config.db
```

Flyway migration은 `src/main/resources/db/migration`에 있습니다. adapter type이나 alias가 추가되면 SQLite trigger가 새 type을 허용하는지 확인해야 합니다. 현재 입력 trigger는 TLS TCP, HTTPS, TLS RabbitMQ, TCP mTLS gzip을 포함하고, 출력 trigger는 MariaDB와 ClickHouse를 포함합니다.
