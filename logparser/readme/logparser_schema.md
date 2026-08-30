# Logparser configuration argument schema

이 문서는 Logparser의 input adapter, output adapter, parser, transform, structured transform을 REST API로 설정할 때 사용하는 argument의 기준 문서입니다. 설명은 현재 저장소의 설정 entity, 검증 서비스, factory, 실제 런타임 구현을 함께 확인한 결과를 기준으로 합니다.

## 1. 문서 적용 기준

관리 UI의 Pipeline Studio와 Inputs/Outputs는 같은 속성별 편집기를 사용합니다. TLS·인증·SNMP 대상/OID·배치·헤더 등은 각각의 입력 항목으로 편집하며, 내부 저장 시에만 기존 `configParams` JSON 문자열로 직렬화합니다. 화면 구조가 바뀌어도 아래 REST 필드명·인코딩·런타임 계약은 유지합니다. 공통 폼 정의는 `frontend/src/lib/adapter-definitions.ts`이며, 저장된 추가 속성은 폼 편집 후에도 보존합니다.

설정 동작이 서로 다르게 보일 때는 다음 우선순위로 해석합니다.

1. 실제 adapter/parser/transform 생성자와 런타임 서비스
2. `ConfigValidationService`의 REST CRUD 검증
3. persistence entity의 JSON property와 DB column
4. `ConfigMetadataService`가 UI에 제공하는 schema
5. 설정 model의 annotation 기본값

annotation의 `@Default`는 값을 자동 주입하지 않습니다. REST payload에서 생략한 값은 entity 기본값 또는 각 구현체의 런타임 기본값을 사용합니다.

### REST API와 JSON 인코딩

주요 생성 endpoint는 다음과 같습니다.

| 설정 | 생성 endpoint | 수정 endpoint |
| --- | --- | --- |
| Input adapter | `POST /api/v1/input-adapters` | `PUT /api/v1/input-adapters/{id}` |
| Parser | `POST /api/v1/parsers` | `PUT /api/v1/parsers/{id}` |
| Transform | `POST /api/v1/transforms` | `PUT /api/v1/transforms/{id}` |
| Output adapter | `POST /api/v1/output-adapters` | `PUT /api/v1/output-adapters/{id}` |
| Structured mapping | `POST /api/v1/structure/mapping` | 같은 endpoint에 같은 `messageType`으로 다시 저장 |

`PUT`은 부분 갱신이 아니라 전달한 entity 전체로 교체합니다. 수정 시 유지할 선택 필드도 payload에 다시 포함해야 합니다. `id`, `createdAt`, `updatedAt`, `version`은 서버 관리 필드이므로 생성 payload에서 생략합니다.

다음 필드는 JSON object/array가 아니라 **JSON으로 직렬화한 문자열**입니다.

| API 필드 | 문자열 내부 구조 |
| --- | --- |
| Input/output `configParams` | adapter별 JSON object |
| Output `headers` | `Map<String, String>` JSON object |
| Output `tagpass` | `Map<String, List<String>>` JSON object |
| Transform `filterPass`, `filterDrop` | `Map<String, String>` JSON object |
| Transform `addProperties` | `Map<String, List<String>>` JSON object |
| Transform `removeProperties` | `List<String>` JSON array |

예를 들어 RabbitMQ input의 `configParams`는 다음처럼 한 번 더 escape합니다.

```json
{
  "type": "RabbitMqInputAdapter",
  "messagetype": "rabbit-log",
  "enabled": true,
  "configParams": "{\"queue\":\"logs.input\",\"username\":\"guest\",\"password\":\"guest\"}"
}
```

## 2. 파이프라인 연결 규칙

파이프라인 순서는 다음과 같습니다.

```text
Input -> Processing Steps (Parser/Transform) -> Structured transform -> Output
```

- `messagetype`은 input, processing step(parser/transform), output을 연결하는 case-sensitive key입니다.
- Input, parser, transform은 정확히 같은 `messagetype`에만 적용됩니다.
- Output의 `messagetype`은 REST CRUD에서 필수입니다. 모든 이벤트를 받으려면 생략하거나 빈 값으로 두지 말고 `all`을 지정합니다.
- Parser와 transform은 하나의 processing step chain으로 합쳐져 `priority` 오름차순으로 실행됩니다. 같은 priority에서는 parser가 먼저이고, 그 다음 id 오름차순입니다. 기존 설정 migration은 parser를 먼저 배치합니다.
- Parser `continueOnFailure=true`는 실패 후 다음 parser가 아니라 다음 processing step으로 진행합니다. transform이 `false`를 반환하면 이벤트를 drop합니다.
- `enabled=false`인 input, parser, transform, output은 DB에는 남지만 런타임 설정에서 제외됩니다.
- Structured transform은 별도 `enabled` 없이 모든 이벤트에 중앙 적용됩니다. 저장 mapping이 없으면 기본 structured event를 만듭니다.

## 3. Input adapter

### 3.1 지원 type과 alias

REST 설정에는 canonical type 사용을 권장합니다.

| Canonical type | Factory alias | 비고 |
| --- | --- | --- |
| `FileInputAdapter` | `file` | canonical/alias 동작 |
| `TcpInputAdapter` | `tcp` | canonical/alias 동작 |
| `TlsTcpInputAdapter` | `tls_tcp`, `tlstcp` | canonical/alias 동작 |
| `UdpInputAdapter` | `udp` | canonical/alias 동작 |
| `HttpInputAdapter` | `http` | canonical/alias 동작 |
| `HttpsInputAdapter` | `https` | canonical/alias 동작 |
| `KafkaInputAdapter` | `kafka` | canonical/alias 동작 |
| `SnmpInputAdapter` | `snmp` | factory에는 alias가 있지만 현재 REST validator는 `snmp`를 거부하므로 canonical type 사용 |
| `RabbitMqInputAdapter` | `rabbitmq` | canonical/alias 동작 |
| `TlsRabbitMqInputAdapter` | `tls_rabbitmq`, `tlsrabbitmq` | canonical/alias 동작 |
| `TcpMtlsGzipInputAdapter` | `tcp_mtls_gzip` | canonical/alias 동작 |
| `FakeInputAdapter` | `fake` | canonical/alias 동작 |

### 3.2 Input 공통 REST 필드

| 필드 | 타입 | 필수 | 기본/제약 | 실제 사용 |
| --- | --- | --- | --- | --- |
| `type` | String | 예 | 위 canonical type 권장 | factory 선택 |
| `messagetype` | String | 예 | nonblank | 파이프라인 연결 key |
| `enabled` | Boolean | 아니오 | entity 기본 `true` | 런타임 등록 여부 |
| `host` | String | adapter별 | base source host 기본 `localhost` | RabbitMQ 접속 host에 사용. 일반 TCP/UDP/HTTP listener의 bind 주소로는 사용되지 않음 |
| `port` | Integer | adapter별 | 권장 `1..65535` | network adapter별 port |
| `path` | String | File만 예 | nonblank, directory 불가 | 파일 경로 |
| `topicid` | String | Kafka만 예 | non-null | Kafka topic |
| `bootstrapservers` | String | Kafka만 예 | non-null | Kafka broker list |
| `groupId` | String | 아니오 | Kafka 생략 시 random UUID | Kafka consumer group |
| `codec` | String | 아니오 | metadata상 `plain`, `json`, `multipart` 또는 supported-codecs API의 `plain`, `json`, `line` | 현재 HTTP/HTTPS 구현은 사용하지 않음 |
| `pathPattern` | String | 아니오 | metadata 이름 `path_pattern`과 다름 | 현재 HTTP/HTTPS 구현은 사용하지 않음 |
| `isFromBeginning` | Boolean | 아니오 | `false` | File 시작 위치 |
| `bufferSize` | Integer | 아니오 | annotation `1024..1048576` | 현재 input 구현은 사용하지 않음 |
| `timeoutMs` | Integer | adapter별 | adapter별 기본값 참조 | SNMP, RabbitMQ, TCP mTLS gzip에서 사용 |
| `workerThreads` | Integer | adapter별 | 양수 권장 | SNMP worker 또는 TCP mTLS 최대 연결 수 fallback |
| `queueSize` | Integer | adapter별 | 양수 권장 | SNMP/TCP mTLS 내부 queue |
| `configParams` | String(JSON) | adapter별 | valid JSON object | 복합/TLS 설정 |

`path_pattern`은 configuration model의 이름이고 REST entity property는 `pathPattern`입니다. 현재 런타임은 둘 다 HTTP route 제한에 사용하지 않으며 listener port의 모든 요청을 받습니다.

### 3.3 FileInputAdapter

| 필드 | 필수 | 기본 | 설명 |
| --- | --- | --- | --- |
| `path` | 예 | 없음 | UTF-8 파일을 line 단위로 tail. 존재하지 않으면 5초 간격으로 최대 12회 재시도 |
| `isFromBeginning` | 아니오 | `false` | `true`면 최초 open 시 처음부터, `false`면 당시 EOF부터 시작 |
| `host` | 아니오 | `localhost` | 생성되는 event의 `source_host` |

파일 크기가 줄면 rotation으로 판단해 새 파일의 처음부터 다시 읽습니다. 이때는 `isFromBeginning=false`여도 새 로그를 건너뛰지 않습니다. `bufferSize`는 사용하지 않습니다.

### 3.4 TcpInputAdapter / TlsTcpInputAdapter

| 필드 | TCP | TLS TCP | 기본/설명 |
| --- | --- | --- | --- |
| `port` | 필수 | 필수 | newline-delimited UTF-8 TCP listener |
| `configParams` | 사용 안 함 | 필수 | TLS server 설정은 3.10절 참조 |
| `host` | source host 기본값에만 저장 | 동일 | listener bind 주소를 제어하지 않음 |
| `timeoutMs` | 사용 안 함 | 사용 안 함 | client read timeout은 코드에 300,000ms로 고정 |
| `workerThreads` | 사용 안 함 | 사용 안 함 | 최대 client handler 100개로 고정 |
| `queueSize` | 사용 안 함 | 사용 안 함 | adapter event queue 10,000개로 고정 |

각 연결의 nonblank line이 한 event가 됩니다. 실제 `source_host`는 client socket 주소입니다.

### 3.5 UdpInputAdapter

| 필드 | 필수 | 기본/설명 |
| --- | --- | --- |
| `port` | 예 | UDP listener port |

한 datagram이 한 UTF-8 event입니다. 최대 packet buffer는 1,600 bytes, socket timeout은 5,000ms로 고정입니다. `host`, `timeoutMs`, `bufferSize`는 listener 동작을 바꾸지 않습니다.

### 3.6 HttpInputAdapter / HttpsInputAdapter

| 필드 | HTTP | HTTPS | 기본/설명 |
| --- | --- | --- | --- |
| `port` | 필수 | 필수 | HTTP listener port |
| `configParams` | 사용 안 함 | 필수 | TLS server 설정은 3.10절 참조 |
| `codec` | 현재 사용 안 함 | 현재 사용 안 함 | request body decoding mode를 바꾸지 않음 |
| `pathPattern` | 현재 사용 안 함 | 현재 사용 안 함 | route filtering을 하지 않음 |
| `host` | bind에 사용 안 함 | bind에 사용 안 함 | 모든 interface에 bind |
| `timeoutMs` | 현재 사용 안 함 | 현재 사용 안 함 | client read timeout은 30,000ms로 고정 |

request line, headers, blank line, `Content-Length` 바이트만큼의 body를 합친 전체 HTTP request 문자열이 한 event가 됩니다. body는 바이트 수만큼 읽은 뒤 UTF-8로 디코딩하므로 한글 등 다중 바이트 문자도 연결 종료 없이 처리합니다. body 상한은 10 MiB, request/header 한 줄의 상한은 64 KiB입니다. 종료 시 listener와 처리 중인 client 연결을 함께 닫습니다. 응답 작성 로직은 없으므로 일반 webhook server와 동일한 HTTP 응답 동작을 기대하면 안 됩니다.

### 3.7 KafkaInputAdapter

| 필드 | 필수 | 기본/설명 |
| --- | --- | --- |
| `bootstrapservers` | 예 | Kafka bootstrap server 목록 |
| `topicid` | 예 | consume할 topic |
| `groupId` | 아니오 | 생략 시 adapter 시작마다 random UUID |

key/value deserializer는 String이며 record value만 event 원문으로 사용합니다. consumer poll 간격은 1초입니다.

### 3.8 SnmpInputAdapter

Top-level에서 `configParams`가 필수이며 canonical type `SnmpInputAdapter`를 사용합니다.

#### SNMP `configParams` root

| 필드 | 타입 | 필수 | 기본/제약 |
| --- | --- | --- | --- |
| `targets` | Array | 예 | 최소 1개 |
| `oids` | Array | 예 | 최소 1개 |
| `community` | String | 아니오 | root 기본 `public`, target이 override |
| `version` | String | 아니오 | root 기본 `2c`; `1`, `v1`, `2`, `2c`, `v2c`, `3`, `v3` |
| `intervalMs` | Number | 아니오 | `60000`, 런타임 최소 `1000` |
| `timeoutMs` | Number | 아니오 | `5000`, 런타임 최소 `100`; top-level REST `timeoutMs`가 우선 |
| `retries` | Number | 아니오 | `0`, 런타임 최소 `0` |
| `workerThreads` | Number | 아니오 | `1`; top-level REST 값이 우선, 실제 값은 `1..targets.size()`로 제한 |
| `queueSize` | Number | 아니오 | `1000`; top-level REST 값이 우선, 런타임 최소 `1` |
| SNMPv3 공통 필드 | 아래 target 표와 동일 | 아니오 | 각 target의 fallback으로 사용 |

#### `targets[]`

| 필드 | 필수 | 기본/지원 값 |
| --- | --- | --- |
| `host` | 예 | target host/IP |
| `name` | 아니오 | `host` |
| `port` | 아니오 | `161` |
| `community` | v1/v2c 선택 | root 값 또는 `public` |
| `version` | 아니오 | root 값 또는 `2c` |
| `securityName` | v3 예 | root 값 fallback |
| `securityLevel` | v3 선택 | `authPriv`; `noAuthNoPriv`, `authNoPriv`, `authPriv` 및 underscore 표기 지원 |
| `authProtocol` | auth 사용 시 선택 | `SHA256`; `MD5`, `SHA`/`SHA1`, `SHA224`, `SHA256`, `SHA384`, `SHA512` |
| `authPassphrase` | auth 사용 시 조건부 | 직접 secret |
| `authPassphraseEnv` | auth 사용 시 조건부 | secret이 들어 있는 환경 변수 이름. direct 값이 우선 |
| `privProtocol` | privacy 사용 시 선택 | `AES128`; `DES`, `AES`/`AES128`, `AES192`, `AES256` |
| `privPassphrase` | `authPriv` 조건부 | 직접 secret |
| `privPassphraseEnv` | `authPriv` 조건부 | secret 환경 변수 이름. direct 값이 우선 |

`authNoPriv`는 auth passphrase/direct-env 중 하나가 필요하고, `authPriv`는 auth와 priv 양쪽 passphrase가 필요합니다. `noAuthNoPriv`는 passphrase가 필요 없습니다.

#### `oids[]`

OID는 문자열 또는 object로 지정합니다.

```json
{
  "targets": [
    {"name": "sw-core-01", "host": "192.0.2.10", "version": "2c", "community": "public"}
  ],
  "oids": [
    "1.3.6.1.2.1.1.3.0",
    {"name": "sysName", "oid": "1.3.6.1.2.1.1.5.0"}
  ],
  "intervalMs": 60000,
  "retries": 1
}
```

object의 `oid`는 필수이고 `name` 기본값은 OID 문자열입니다.

### 3.9 RabbitMqInputAdapter / TlsRabbitMqInputAdapter

Top-level `configParams`와 그 안의 `queue`가 필수입니다.

| `configParams` 필드 | 필수 | 기본/설명 |
| --- | --- | --- |
| `host` | 아니오 | `localhost`; top-level REST `host`가 우선 |
| `port` | 아니오 | plain `5672`, TLS `5671`; top-level `port` > `port` > `rmqPort` 순 |
| `rmqPort` | 아니오 | `port`의 legacy 대체 이름 |
| `username` | 아니오 | `guest`; `rmqUsername` fallback 지원 |
| `password` | 아니오 | `guest`; `rmqPassword` fallback 지원. env 참조는 구현되지 않음 |
| `virtualHost` | 아니오 | `/` |
| `queue` | 예 | consume queue. `queueName` fallback 지원 |
| `exchange` | 아니오 | queue binding에 사용 |
| `routingKey` | 아니오 | `""`; `routingkey` fallback 지원 |
| `autoAck` | 아니오 | `false`. false면 `basicGet` 뒤 명시적으로 ack |
| `declareQueue` | 아니오 | `false` |
| `durableQueue` | 아니오 | `true`; `durable` fallback 지원 |
| `exclusiveQueue` | 아니오 | `false`; `exclusive` fallback 지원 |
| `autoDeleteQueue` | 아니오 | `false`; `autoDelete` fallback 지원 |
| `bindQueue` | 아니오 | `declareQueue=true`이고 exchange가 있으면 기본 `true`, 아니면 `false` |
| `prefetchCount` | 아니오 | `1`, 최소 `1`; `autoAck=false`일 때 QoS에 사용 |
| `timeoutMs` | 아니오 | `5000`, 최소 `100`; top-level REST `timeoutMs`가 우선 |
| `charset` | 아니오 | `UTF-8`, Java가 지원하는 charset 이름 |
| `tlsEnabled` | 아니오 | plain adapter 기본 `false`, TLS adapter는 항상 `true` |
| `ssl` | 아니오 | `tlsEnabled`가 없을 때 사용하는 legacy boolean |
| `hostnameVerification` | 아니오 | `true` |
| TLS store 필드 | 아니오 | 3.10절의 client TLS 필드 사용 |

`TlsRabbitMqInputAdapter`에서 `tlsEnabled=false`를 명시하면 REST validation에 실패합니다. RabbitMQ password는 `configParams`에 평문으로 저장되므로 설정 DB와 backup 접근을 보호해야 합니다.

### 3.10 공용 TLS `configParams`

#### TlsTcpInputAdapter / HttpsInputAdapter server TLS

| 필드 | 필수 | 기본/설명 |
| --- | --- | --- |
| `keyStorePath` | 예 | server key store path |
| `keyStorePassword` | 조건부 | 직접 password |
| `keyStorePasswordEnv` | 조건부 | password 환경 변수 이름. direct 값이 우선 |
| `keyStoreType` | 아니오 | `PKCS12` |
| `keyPassword` | 아니오 | private key password. 생략 시 key store password |
| `keyPasswordEnv` | 아니오 | private key password 환경 변수 이름 |
| `trustStorePath` | client auth 시 예 | client CA trust store |
| `trustStorePassword` | trust store 사용 시 조건부 | 직접 password |
| `trustStorePasswordEnv` | trust store 사용 시 조건부 | password 환경 변수 이름 |
| `trustStoreType` | 아니오 | `PKCS12` |
| `tlsAlgorithm` | 아니오 | `TLS` |
| `enabledProtocols` | 아니오 | `TLSv1.3`, `TLSv1.2`; array, 단일 문자열, comma-separated 문자열 지원 |
| `clientAuth` | 아니오 | `none`; `none`, `want`, `need`와 동의어 `false`, `optional`, `required`, `true` |
| `needClientAuth` | 아니오 | `true`면 `clientAuth`보다 우선해 `need` |
| `wantClientAuth` | 아니오 | `needClientAuth`가 false이고 true면 `want` |

`clientAuth=want|need`이면 trust store path/password가 필수입니다.

#### RabbitMQ client TLS

RabbitMQ는 같은 key/trust store, type, password, `tlsAlgorithm` 필드를 사용합니다. key store는 client certificate이 필요할 때만, trust store는 custom CA가 필요할 때만 지정합니다. `enabledProtocols`와 `clientAuth`는 RabbitMQ client 연결에는 적용되지 않습니다.

### 3.11 TcpMtlsGzipInputAdapter

Castrelyx agent 전용 4-byte big-endian length + gzip JSON protocol입니다.

#### Top-level 필드

| 필드 | 필수 | 기본/제약 |
| --- | --- | --- |
| `port` | 예 | listener port |
| `configParams` | 예 | 아래 JSON object를 직렬화한 문자열 |
| `timeoutMs` | 아니오 | `30000`, client idle/read timeout, 양수 |
| `queueSize` | 아니오 | `10000`, 양수. batch 전체가 남은 용량에 들어갈 때만 enqueue |
| `workerThreads` | 아니오 | `maxConnections` 생략 시 fallback, 기본 `32` |

#### `configParams`

| 필드 | 필수 | 기본/제약 |
| --- | --- | --- |
| `keyStorePath` | 예 | PKCS12 server store |
| `keyStorePasswordEnv` | 예 | password 환경 변수 이름. 직접 password는 지원하지 않음 |
| `trustStorePath` | 예 | PKCS12 client trust store |
| `trustStorePasswordEnv` | 예 | password 환경 변수 이름. 직접 password는 지원하지 않음 |
| `maxFrameBytes` | 아니오 | `10485760` (10 MiB), 양수 |
| `maxConnections` | 아니오 | top-level `workerThreads`, 둘 다 없으면 `32`, 양수 |
| `tlsReloadIntervalMs` | 아니오 | `5000`, 양수 |
| `ackMode` | 아니오 | 지정한다면 `queueAccepted`만 허용. 런타임 ACK 의미는 항상 memory queue 수락 |

TLS protocol은 `TLSv1.3`/`TLSv1.2`, client auth는 `need`, store type은 PKCS12로 고정입니다. gzip 해제 상한 16 MiB, batch item 상한 5,000개는 설정 인자가 아닙니다. 인증서 subject CN과 batch `source_id`가 일치해야 합니다.

### 3.12 FakeInputAdapter

추가 argument가 없습니다. 매 호출에서 Suricata EVE와 유사한 JSON alert event를 즉시 생성합니다. 코드 주석에 언급된 `interval` argument는 현재 설정 model과 구현에 존재하지 않습니다.

## 4. Parser

### 4.1 Parser 공통 REST 필드

| 필드 | 타입 | 필수 | 기본/동작 |
| --- | --- | --- | --- |
| `type` | String | 예 | canonical parser class 이름 사용 |
| `messagetype` | String | 예 | input과 동일한 연결 key |
| `param` | String | Grok/Regex만 예 | parser pattern |
| `sourceField` | String | 아니오 | 비어 있으면 `originalText`, 지정하면 event field의 top-level 값을 parser 입력으로 사용 |
| `priority` | Integer | 아니오 | entity 기본 `0`; 낮을수록 먼저 실행 |
| `enabled` | Boolean | 아니오 | entity 기본 `true` |
| `continueOnFailure` | Boolean | 아니오 | `false`; 실패 시 다음 processing step으로 진행할지 여부 |

DB trigger와 validator는 `json`, `grok`, `regex`, `rfc3164`, `rfc5424`, `http` 같은 alias도 허용하지만 `ParseService`는 alias를 class 이름으로 정규화하지 않습니다. 정상 런타임 등록을 위해 아래 canonical type만 사용합니다.

### 4.2 Parser별 argument와 결과

| Type | `param` | 동작/생성 field |
| --- | --- | --- |
| `JsonParser` | 사용 안 함 | 원문 JSON object를 `Map<String,Object>`로 merge. top-level array는 실패 |
| `GrokParser` | 필수 Grok pattern | 기본 Grok pattern library를 등록하고 named capture를 field로 추가 |
| `RegexParser` | 필수 Java regex | 이름 있는 capture group은 이름을 key로 저장. 이름 있는 그룹이 없으면 group 1=key, group 2=value이며 최소 2개 capture group 필요 |
| `RFC3164SyslogParser` | 사용 안 함 | `FACILITY`, `SEVERITY`, `SEVERITY_TEXT`, `TIMESTAMP`, `HOST`, `TAG`, `MESSAGE`, `DECODE_ERRORS`; message의 `key=value`도 lowercase key로 추가 |
| `RFC5424SyslogParser` | 사용 안 함 | `syslog_FACILITY`, `syslog_SEVERITY`, `syslog_SEVERITY_TEXT`, `syslog_VERSION`, `syslog_TIMESTAMP`, `syslog_HOST`, `syslog_APP_NAME`, `syslog_PROCID`, `syslog_MSGID`, `syslog_STRUCTURED_DATA`, `syslog_MESSAGE`, `syslog_DECODE_ERRORS` |
| `HttpParser` | 사용 안 함 | request line은 버리고 `headers` map과 `body` string 생성. header key는 uppercase |

Parser test endpoint는 다음 object를 받습니다.

```json
{
  "type": "RegexParser",
  "param": "([A-Za-z_]+)=([^ ]+)",
  "sampleData": "status=ok host=web-01"
}
```

`POST /api/v1/parsers/test`는 저장하지 않고 즉시 parser를 초기화해 결과 field map을 반환합니다.

`RegexParser`의 `param`은 Java 정규식 문법을 사용합니다. Python의 `(?P<name>...)` 대신
`(?<name>...)`를 사용해야 합니다. 생성/수정 시 정규식을 컴파일해 검증하며, 문법이 틀리면
저장하거나 파이프라인을 재로딩하지 않고 HTTP 400과 오류 위치/수정 안내를 반환합니다.
이름 있는 그룹이 하나라도 있으면 이름 있는 그룹만 필드로 추출하고, 매칭되지 않은 optional
그룹은 생략합니다. `sampleData`는 기존 문자열 외에 배열도 받을 수 있으며, `RegexParser`는
각 배열 항목을 따로 파싱합니다. 예를 들어 다음 요청은 `sdid: exampleSDID`, `id: 32473`,
`attributes: iut="3"`를 반환하고, attributes 안의 `iut="3"` 같은 항목은 `iut: "3"` 개별 field로도 병합합니다.

```json
{
  "type": "RegexParser",
  "param": "^\\[(?<sdid>\\w+)@(?<id>\\d+)\\s+(?<attributes>.*)\\]$",
  "sampleData": ["[exampleSDID@32473 iut=\"3\"]"]
}
```

`sourceField` 입력 규칙은 다음과 같습니다.

- 미지정/blank: 기존처럼 `originalText` 사용
- String: 그대로 전달
- 숫자/boolean: 문자열로 변환
- Map/List: JSON 문자열로 직렬화. 단, `RegexParser`의 List는 각 항목을 따로 파싱
- 필드가 없거나 null: parser step 실패

`sourceField`가 없는 parser의 성공 결과는 원래 event의 top-level field map에 병합됩니다. `sourceField`가 있으면 원래 source field 값을 삭제하고 같은 키에 parser 결과 Map을 넣습니다. `attributes` named group에서 발견한 `key="value"` 항목도 결과 Map에 추가되며, 원본 `attributes` 문자열은 유지됩니다. nested path(JSONPath/SpEL)는 지원하지 않습니다.
`RegexParser`의 배열 입력은 하나 이상 매칭되면 성공하며, 결과는 항목 순서대로 병합합니다.
같은 필드는 마지막으로 매칭된 값이 남고, null/불일치 항목은 건너뜁니다. 성공 시 원본 source field 값은 결과 Map으로 교체됩니다.

## 5. Transform

### 5.1 Transform 공통 REST 필드

| 필드 | 타입 | 필수 | 기본/동작 |
| --- | --- | --- | --- |
| `type` | String | 예 | `Filter`, `AddProperty`, `RemoveProperty` 중 하나 |
| `messagetype` | String | 예 | parser/input과 같은 연결 key |
| `priority` | Integer | 아니오 | parser와 공유하는 processing step 순서; 낮을수록 먼저 실행 |
| `enabled` | Boolean | 아니오 | entity 기본 `true` |
| `filterPass` | String(JSON) | Filter 조건부 | `Map<String,String>` |
| `filterDrop` | String(JSON) | Filter 조건부 | `Map<String,String>` |
| `addProperties` | String(JSON) | AddProperty 예 | `Map<String,List<String>>` |
| `removeProperties` | String(JSON) | RemoveProperty 예 | `List<String>` |
| `configParams` | String(JSON) | 아니오 | 저장되지만 현재 `DatabaseConfigLoader`와 transform 구현은 사용하지 않음 |

DB trigger와 validator는 `filter`, `add_property`, `remove_property`도 허용하지만 `TransformService`는 alias를 class 이름으로 정규화하지 않습니다. canonical type만 사용합니다.

### 5.2 Processing step 순서 변경

Parser와 transform을 교차 배치하려면 다음 API에 현재 message type의 전체 step 목록을 전달합니다.

```http
PUT /api/v1/pipeline/{messageType}/processing-steps/order
Content-Type: application/json
```

```json
{
  "steps": [
    {"kind": "PARSER", "id": 3},
    {"kind": "TRANSFORM", "id": 7},
    {"kind": "PARSER", "id": 4}
  ]
}
```

목록은 중복 없이 해당 message type의 parser/transform을 모두 포함해야 하며, 서버는 양쪽 `priority`를 하나의 transaction에서 `10, 20, 30...`으로 재번호화합니다. 현재 목록이 변경된 경우 `409 Conflict`가 반환됩니다.

### 5.3 Filter

`filterPass` 또는 `filterDrop` 중 하나 이상이 필요합니다. 각 map value는 허용/차단 값의 comma-separated 문자열입니다.

```json
{
  "type": "Filter",
  "messagetype": "app-log",
  "priority": 10,
  "enabled": true,
  "filterPass": "{\"environment\":\"prod,stage\",\"status\":\"ok\"}",
  "filterDrop": "{\"severity\":\"debug,trace\"}"
}
```

- Drop 조건을 먼저 검사하며 어느 한 field라도 값이 일치하면 event를 제거합니다.
- Pass 조건은 모든 field가 존재하고 각각 허용 값에 포함되어야 합니다.
- 비교는 `toString()` 결과에 대한 exact, case-sensitive 비교입니다.

### 5.4 AddProperty

기존 flat field를 새 nested object 아래로 **이동**합니다.

```json
{
  "type": "AddProperty",
  "messagetype": "app-log",
  "priority": 20,
  "enabled": true,
  "addProperties": "{\"network\":[\"src_ip\",\"dst_ip\",\"dst_port\"]}"
}
```

위 설정은 `network` object를 만들고 지정 field를 그 아래에 넣은 뒤 원래 top-level field를 제거합니다. 같은 이름의 기존 target field는 덮어씁니다. source field가 없으면 nested value는 `null`입니다.

### 5.5 RemoveProperty

```json
{
  "type": "RemoveProperty",
  "messagetype": "app-log",
  "priority": 30,
  "enabled": true,
  "removeProperties": "[\"password\",\"token\",\"secret\"]"
}
```

지정한 top-level field를 제거합니다. nested path 표현식은 지원하지 않습니다.

## 6. Structured transform / Schema Map

Structured transform은 일반 transform 뒤, output 앞에서 항상 실행됩니다.

### 6.1 MappingConfiguration payload

```json
{
  "id": "web-access-v1",
  "messageType": "web-access",
  "commonMappings": [
    {"sourceField": "timestamp", "targetField": "event_time", "defaultValue": null},
    {"sourceField": "client_ip", "targetField": "src_ip", "defaultValue": null},
    {"sourceField": "status", "targetField": "event_result", "defaultValue": "unknown"}
  ],
  "subTableRules": [
    {
      "targetSubTable": "event_web",
      "conditionExpression": "http_method != null",
      "mappings": [
        {"sourceField": "http_method", "targetField": "http_method", "defaultValue": null},
        {"sourceField": "uri", "targetField": "uri_path", "defaultValue": "/"}
      ]
    }
  ]
}
```

| MappingConfiguration 필드 | 타입 | 필수/기본 | 설명 |
| --- | --- | --- | --- |
| `id` | String | 아니오 | 사용자 정의 식별자. 저장 key는 아님 |
| `messageType` | String | 사실상 필수 | mapping 저장/조회 primary key, event 연결 key |
| `commonMappings` | Array<FieldMapping> | 기본 `[]` | common field mapping |
| `subTableRules` | Array<SubTableRule> | 기본 `[]` | 순서대로 평가하는 sub-domain rule |

| FieldMapping 필드 | 타입 | 설명 |
| --- | --- | --- |
| `sourceField` | String | parser/transform 결과의 exact top-level key |
| `targetField` | String | common target 또는 sub field 이름 |
| `defaultValue` | String/null | source가 null일 때 사용할 문자열 기본값 |

| SubTableRule 필드 | 타입 | 설명 |
| --- | --- | --- |
| `targetSubTable` | String | 결과 `subDomainType` |
| `conditionExpression` | String | Spring SpEL. blank/null이면 항상 match |
| `mappings` | Array<FieldMapping> | match된 sub-domain field mapping |

rule은 배열 순서대로 평가하며 첫 match만 적용합니다. 조건식은 source map을 root로 사용하므로 `dst_port == 80`, `protocol == 'HTTP'`, `['field-name'] == 'x'` 같은 SpEL을 사용할 수 있습니다. 식 오류는 false로 처리됩니다.

### 6.2 Common target field

실제 런타임에서 처리하는 `targetField`는 다음과 같습니다.

| targetField | 결과 타입/처리 |
| --- | --- |
| `event_time` | `Instant`; ISO-8601, 일반 날짜 문자열, epoch seconds/millis 등 파싱 |
| `event_category` | String |
| `event_type` | String |
| `event_action` | String |
| `event_result` | String |
| `severity` | Integer |
| `src_ip` | canonical IPv4/IPv6 또는 null |
| `src_port` | Integer |
| `dst_ip` | canonical IPv4/IPv6 또는 null |
| `dst_port` | Integer |
| `protocol` | String |
| `src_host` | String |
| `dst_host` | String |
| `user_name` | String |
| `user_id` | String |
| `log_source` | String; 자동 설정된 message type을 override |

`ingest_time`, `raw_log`는 자동으로 설정됩니다. `/api/v1/structure/schema`가 표시하는 `event_id`, `ingest_time`, `raw_log`를 common mapping target으로 주면 현재 `applyCommonField()`는 처리하지 않습니다. 알 수 없는 common target도 무시됩니다.

### 6.3 Sub schema metadata

`GET /api/v1/structure/schema`는 다음 권장 sub schema를 제공합니다.

| Sub schema | field |
| --- | --- |
| `event_network` | `bytes_in`, `bytes_out`, `packets_in`, `packets_out`, `direction`, `session_id`, `duration_ms` |
| `event_web` | `http_method`, `uri_path`, `http_status`, `user_agent`, `referer`, `bytes` |
| `event_auth` | `auth_method`, `auth_protocol`, `failure_reason`, `mfa_used` |

현재 런타임은 sub field에 schema 기반 type conversion/validation을 하지 않습니다. `targetSubTable`과 sub `targetField`에 다른 문자열을 주어도 map에 그대로 저장합니다.

### 6.4 Structured 결과

결과는 다음 형태로 `LogEvent.fields`를 교체합니다.

```json
{
  "common": {
    "eventTime": "2026-08-24T00:00:00Z",
    "ingestTime": "2026-08-24T00:00:01Z",
    "srcIp": "192.0.2.10",
    "logSource": "web-access",
    "rawLog": "original log"
  },
  "subDomainType": "event_web",
  "subFields": {"http_method": "GET", "uri_path": "/"},
  "additionalAttributes": {"unmapped_field": "value"}
}
```

- mapping된 source field는 `additionalAttributes`에서 제외됩니다.
- mapping되지 않은 source field는 `additionalAttributes`에 보존됩니다.
- 저장 mapping이 없으면 `common.ingestTime`, `common.rawLog`, `common.logSource`만 만들고 모든 기존 field를 `additionalAttributes`에 보존합니다.
- common conversion이 실패하면 해당 target은 null/미설정이 될 수 있습니다.

### 6.5 Simulation

`POST /api/v1/structure/simulate` payload:

```json
{
  "messageType": "web-access",
  "sampleData": {"client_ip": "192.0.2.10", "http_method": "GET"},
  "temporaryConfig": null
}
```

| 필드 | 필수 | 설명 |
| --- | --- | --- |
| `messageType` | 예 | 저장 mapping 조회 key |
| `sampleData` | 아니오 | parser가 생성했다고 가정할 source field map |
| `temporaryConfig` | 아니오 | 지정하면 저장 mapping 대신 이 설정으로 시뮬레이션 |

### 6.6 Mapping template

Template 생성은 `POST /api/v1/structure/templates`, 수정은 `PUT /api/v1/structure/templates/{id}`를 사용합니다.

| 필드 | 생성 시 | 설명 |
| --- | --- | --- |
| `id` | 서버 생성 | UUID |
| `name` | 필수 | trim 후 nonblank, case-insensitive unique |
| `description` | 선택 | blank면 null |
| `sourceMessageType` | 선택 | template 출처 추적용 |
| `config` | 필수 | 전체 `MappingConfiguration` |
| `createdAt`, `updatedAt` | 서버 생성 | ISO instant |

`POST /api/v1/structure/templates/{id}/apply?messageType=target`은 config를 deep copy하고 `messageType`을 대상 값으로 바꿔 기존 mapping을 덮어씁니다.

## 7. Output adapter

### 7.1 지원 type과 alias

| Canonical type | Alias |
| --- | --- |
| `ConsoleOutputAdapter` | `console` |
| `TcpOutputAdapter` | `tcp` |
| `HttpOutputAdapter` | `http` |
| `KafkaOutputAdapter` | `kafka` |
| `OpenSearchOutputAdapter` | `opensearch` |
| `RabbitMQAdapter` | `rabbitmq` |
| `MariaDbOutputAdapter` | `mariadb` |
| `ClickHouseOutputAdapter` | `clickhouse` |
| `BenchmarkAdapter` | `benchmark` |

Canonical type과 alias 모두 REST validator와 factory에서 지원합니다.

### 7.2 Output 공통 REST 필드

| 필드 | 타입 | 필수 | 기본/실제 사용 |
| --- | --- | --- | --- |
| `type` | String | 예 | adapter 선택 |
| `messagetype` | String | 예 | exact key 또는 `all` |
| `enabled` | Boolean | 아니오 | entity 기본 `true` |
| `addOriginText` | Boolean | 아니오 | `false`; serialize하는 output에서 `origin_text` 포함 |
| `timeoutMs` | Integer | 아니오 | runtime 기본 `30000`, 양수 |
| `host`, `port` | adapter별 | TCP/RabbitMQ | destination |
| `url` | adapter별 | HTTP/OpenSearch | target URL |
| `method` | String | 아니오 | HTTP 기본 `POST`; `POST`, `PUT`, `PATCH` |
| `headers` | String(JSON) | 아니오 | HTTP header map |
| `topicid`, `bootstrapservers`, `key` | adapter별 | Kafka | topic, broker, record key |
| `indexTemplate` | String | OpenSearch 예 | REST entity의 실제 필드명. metadata가 표시하는 `index`와 다름 |
| `osUsername`, `osPassword` | String | 아니오 | OpenSearch Basic auth |
| `action` | String | 아니오 | 저장되지만 현재 OpenSearch 구현은 사용하지 않음 |
| `routingkey`, `exchange` | String | RabbitMQ 예 | publish routing |
| `rmqUsername`, `rmqPassword`, `rmqPort` | String/String/Integer | 아니오 | RabbitMQ 접속. port 기본 `5672` |
| `tagpass` | String(JSON) | 아니오 | 저장/변환되지만 현재 RabbitMQ output은 필터링에 사용하지 않음 |
| `batchSize`, `flushIntervalMs` | Integer | 아니오 | top-level 값은 현재 adapter가 사용하지 않음. MariaDB/ClickHouse는 `configParams` 내부 값을 사용 |
| `retryCount`, `retryDelayMs` | Integer | 아니오 | TCP/Kafka output에서 사용 |
| `configParams` | String(JSON) | MariaDB/ClickHouse 예 | DB output 복합 설정 |

### 7.3 ConsoleOutputAdapter

추가 필드는 없습니다. `addOriginText=true`이면 console JSON에 `origin_text`를 포함합니다.

### 7.4 BenchmarkAdapter

추가 필드는 없습니다. 외부 전송이나 JSON serialization 없이 1초 이상 경과할 때 처리량을 log로 기록합니다. 따라서 `addOriginText`는 결과에 영향을 주지 않습니다.

### 7.5 TcpOutputAdapter

| 필드 | 필수 | 기본/설명 |
| --- | --- | --- |
| `host` | 예 | destination host |
| `port` | 예 | destination port |
| `timeoutMs` | 아니오 | `30000`; connect/read timeout |
| `retryCount` | 아니오 | `3`; 양수가 아니면 기본값 |
| `retryDelayMs` | 아니오 | `1000`; 양수가 아니면 기본값 |

event마다 새 TCP connection을 열어 UTF-8 JSON bytes를 delimiter나 length prefix 없이 전송하고 닫습니다.

### 7.6 HttpOutputAdapter

| 필드 | 필수 | 기본/설명 |
| --- | --- | --- |
| `url` | 예 | `http`/`https`, host 필수 |
| `method` | 아니오 | `POST`; `POST`, `PUT`, `PATCH` |
| `headers` | 아니오 | JSON string 형태의 `Map<String,String>` |
| `timeoutMs` | 아니오 | `30000`; connect/request timeout |

`Content-Type`을 지정하지 않으면 `application/json`을 추가하고 `User-Agent: LogParser/1.0`을 설정합니다. redirect는 normal 정책으로 따라가며 2xx만 성공입니다.

```json
{
  "type": "HttpOutputAdapter",
  "messagetype": "all",
  "url": "https://collector.example/api/events",
  "method": "POST",
  "headers": "{\"Authorization\":\"Bearer replace-me\",\"X-Tenant\":\"ops\"}",
  "timeoutMs": 10000,
  "enabled": true
}
```

### 7.7 KafkaOutputAdapter

| 필드 | 필수 | 기본/설명 |
| --- | --- | --- |
| `bootstrapservers` | 예 | Kafka broker list |
| `topicid` | 예 | produce topic |
| `key` | 아니오 | record key, 기본 null |
| `timeoutMs` | 아니오 | `30000` |
| `retryCount` | 아니오 | runtime 기본 `0`, 음수는 `0` |
| `retryDelayMs` | 아니오 | runtime 기본 `250`, 양수가 아니면 기본값 |

producer는 `acks=all`, `compression.type=lz4`, `enable.idempotence=false`, buffer memory 8 MiB를 사용합니다. 각 send는 future 완료를 `timeoutMs`까지 기다립니다.

### 7.8 OpenSearchOutputAdapter

| 필드 | 필수 | 기본/설명 |
| --- | --- | --- |
| `url` | 예 | OpenSearch base URL |
| `indexTemplate` | 예 | index 이름 또는 `%{field}`/`%{datePattern}` template |
| `osUsername` | 아니오 | 지정하면 Basic auth 사용 |
| `osPassword` | 아니오 | username과 조합, null이면 빈 password |
| `timeoutMs` | 아니오 | `30000` |
| `action` | 아니오 | 현재 무시됨. 항상 `POST {url}/{index}/_doc` |

`%{yyMMdd}`처럼 `yy`로 시작하는 variable은 Java date pattern으로 현재 날짜를 사용하고, 나머지는 output map의 top-level field를 조회합니다. 값이 없으면 전송 실패입니다.

현재 구현은 self-signed leaf certificate을 trust하고 hostname verification을 비활성화한 HTTP client를 생성합니다. 별도의 TLS 검증 설정 argument는 없습니다.

### 7.9 RabbitMQAdapter

| 필드 | 필수 | 기본/설명 |
| --- | --- | --- |
| `host` | 예 | RabbitMQ host |
| `exchange` | 예 | TOPIC exchange. 시작 시 declare |
| `routingkey` | 예 | publish routing key |
| `rmqPort` | 아니오 | `5672` |
| `rmqUsername` | 아니오 | factory에 그대로 전달. 운영에서는 명시 권장 |
| `rmqPassword` | 아니오 | factory에 그대로 전달. DB에는 encrypted converter 적용 |
| `timeoutMs` | 아니오 | `30000`; publisher confirm timeout |
| `tagpass` | 아니오 | 현재 무시됨 |

connection/handshake timeout은 `timeoutMs`와 별개로 각각 10,000ms에 고정입니다. TLS output 설정은 지원하지 않습니다.

### 7.10 MariaDbOutputAdapter

Top-level `configParams`가 필수입니다.

| `configParams` 필드 | 필수 | 기본/제약 |
| --- | --- | --- |
| `jdbcUrl` | 예 | MariaDB JDBC URL |
| `usernameEnv` | 예 | username 환경 변수 이름, 실제 값이 nonblank여야 함 |
| `passwordEnv` | 예 | password 환경 변수 이름, 실제 값이 nonblank여야 함 |
| `tableName` | 아니오 | `castrelyx_agent_events`; `[A-Za-z0-9_]+` |
| `batchSize` | 아니오 | `100`, 양수 |
| `flushIntervalMs` | 아니오 | `5000`, 양수 |
| `autoCreateSchema` | 아니오 | `false` |

```json
{
  "type": "MariaDbOutputAdapter",
  "messagetype": "castrelyx-agent-item",
  "addOriginText": false,
  "enabled": true,
  "configParams": "{\"jdbcUrl\":\"jdbc:mariadb://mariadb:3306/castrelyx\",\"usernameEnv\":\"CASTRELYX_DB_USER\",\"passwordEnv\":\"CASTRELYX_DB_PASSWORD\",\"tableName\":\"castrelyx_agent_events\",\"batchSize\":100,\"flushIntervalMs\":5000,\"autoCreateSchema\":true}"
}
```

top-level `batchSize`/`flushIntervalMs`는 사용하지 않습니다. JDBC connection timeout argument도 별도로 적용하지 않으므로 top-level `timeoutMs`는 MariaDB 연결에 영향을 주지 않습니다.

### 7.11 ClickHouseOutputAdapter

Top-level `configParams`가 필수입니다.

| `configParams` 필드 | 필수 | 기본/제약 |
| --- | --- | --- |
| `endpointUrl` | 예 | `http` 또는 `https` ClickHouse endpoint |
| `database` | 아니오 | `default`; `[A-Za-z0-9_]+` |
| `tableName` | REST 검증상 예 | runtime 기본 `castrelyx_agent_events`; `[A-Za-z0-9_]+` |
| `metricTableName` | 아니오 | `manager_metric_samples`; identifier 제약 |
| `stateTableName` | 아니오 | `manager_state_snapshots`; identifier 제약 |
| `eventTableName` | 아니오 | `manager_events`; identifier 제약 |
| `usernameEnv` | 조건부 | Basic auth username 환경 변수 이름 |
| `passwordEnv` | 조건부 | Basic auth password 환경 변수 이름. username/password env는 함께 지정하거나 함께 생략 |
| `batchSize` | 아니오 | `100`, 양수. chunk metadata 없는 legacy event buffer |
| `flushIntervalMs` | 아니오 | `5000`, 양수 |
| `incompleteGroupTimeoutMs` | 아니오 | `30000`, 양수 |
| `maxPendingGroups` | 아니오 | `2048`, 양수 |
| `maxPendingItems` | 아니오 | `50000`, 양수 |
| `maxPendingBytes` | 아니오 | `67108864` (64 MiB), 양수 |
| `incompleteChunkDlqDir` | 아니오 | `${user.home}/logparser/data/incomplete-chunks`, nonblank valid path |
| `maxIncompleteChunkDlqBytes` | 아니오 | `134217728` (128 MiB), 양수 |
| `maxIncompleteChunkDlqRecords` | 아니오 | `1000`, 양수 |
| `autoCreateSchema` | 아니오 | `false` |
| `writeTelemetryTables` | 아니오 | `tableName`이 기본 raw table 이름이면 `true`, 아니면 `false` |

```json
{
  "type": "ClickHouseOutputAdapter",
  "messagetype": "castrelyx-agent-item",
  "timeoutMs": 30000,
  "enabled": true,
  "configParams": "{\"endpointUrl\":\"http://clickhouse:8123\",\"database\":\"castrelyx\",\"tableName\":\"castrelyx_agent_events\",\"metricTableName\":\"manager_metric_samples\",\"stateTableName\":\"manager_state_snapshots\",\"eventTableName\":\"manager_events\",\"usernameEnv\":\"CLICKHOUSE_USER\",\"passwordEnv\":\"CLICKHOUSE_PASSWORD\",\"batchSize\":100,\"flushIntervalMs\":5000,\"incompleteGroupTimeoutMs\":30000,\"maxPendingGroups\":2048,\"maxPendingItems\":50000,\"maxPendingBytes\":67108864,\"maxIncompleteChunkDlqBytes\":134217728,\"maxIncompleteChunkDlqRecords\":1000,\"writeTelemetryTables\":true,\"autoCreateSchema\":true}"
}
```

top-level `timeoutMs`는 HTTP connect/request timeout에 사용합니다. top-level `batchSize`/`flushIntervalMs`는 사용하지 않습니다.

schema 1.1 chunk event는 `source_id + batch_id + chunk_index`별로 `chunk_item_count`가 찰 때 sequence 순으로 flush합니다. 불완전 group은 timeout/상한/close 시 canonical telemetry table에 쓰지 않고 bounded durable DLQ에 저장한 뒤 raw table insert만 best effort로 시도합니다.

## 8. 현재 구현상 주의할 schema 차이

| 영역 | 현재 상태 | 설정 지침 |
| --- | --- | --- |
| Input HTTP | metadata에 `codec`, `path_pattern`이 있으나 runtime 미사용 | route/codec 제어 인자로 간주하지 않음 |
| Input network `host` | metadata는 bind host처럼 설명하지만 TCP/UDP/HTTP listener가 사용하지 않음 | bind 제한이 필요하면 코드 변경 필요 |
| Input `snmp` alias | factory/DB trigger에는 있으나 REST validator 누락 | `SnmpInputAdapter` 사용 |
| Parser alias | validator/DB에는 있으나 runtime reflection 정규화 없음 | canonical parser class 이름 사용 |
| Transform alias | validator/DB에는 있으나 runtime reflection 정규화 없음 | canonical transform class 이름 사용 |
| OpenSearch field | metadata는 `index`, REST entity는 `indexTemplate` | API payload에는 `indexTemplate` 사용 |
| OpenSearch `action` | 저장되지만 runtime 미사용 | 항상 `_doc` POST임을 전제로 설정 |
| RabbitMQ output `tagpass` | 저장/역직렬화되지만 runtime 미사용 | 필터링은 `Filter` transform으로 구현 |
| Output top-level batch fields | 저장되지만 MariaDB/ClickHouse가 읽지 않음 | 해당 `configParams` 내부에 지정 |
| Transform `configParams` | 저장되지만 loader가 읽지 않음 | type별 전용 JSON string field 사용 |
| Structured schema metadata | `event_id`, `ingest_time`, `raw_log`를 표시하지만 common mapper가 직접 처리하지 않음 | ingest/raw는 자동값 사용, event_id mapping 금지 |

## 9. 유지보수 체크리스트

새 argument나 type을 추가·변경할 때 이 문서를 같은 변경에서 갱신합니다.

### Input

- `InputAdapterConfig`, `InputAdapterEntity`
- `InputFactory.TYPE_ALIASES`
- 해당 `domain/input/model/*` 생성자와 `configParams` parser
- `ConfigMetadataService.getInputAdapter*`
- `ConfigValidationService.validateInputAdapter`
- Flyway input type trigger

### Output

- `OutputAdapterConfig`, `OutputAdapterEntity`
- `OutputFactory.TYPE_ALIASES`, `convertConfigToMap()`
- 해당 `domain/output/model/*` 생성자와 `configParams` parser
- `ConfigMetadataService.getOutputAdapter*`
- `ConfigValidationService.validateOutputAdapter`
- Flyway output type trigger

### Parser / Transform

- configuration model과 persistence entity
- `ParseService` 또는 `TransformService`의 runtime loading/ordering
- parser/transform 구현의 `init()` argument
- metadata와 validation service
- Flyway type trigger

### Structured transform

- `MappingConfiguration`, `FieldMapping`, `SubTableRule`, `MappingTemplate`
- `StructuredTransformService.applyCommonField()`와 `ConditionEvaluator`
- `SchemaDefinitionService`
- `StructuredTransformController` endpoint DTO
- mapping/template repository schema와 cache invalidation
