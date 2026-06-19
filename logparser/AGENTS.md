# Repository Guidelines

이 문서는 Logparser 내부 작업자를 위한 구현 가이드입니다. 사용자용 상세 절차는 `readme/logparser-user-manual.md`, 빠른 참조는 `README.md`를 기준으로 합니다.

## 프로젝트 구조

Logparser는 Java 21과 Spring Boot 기반 로그 처리 파이프라인입니다. 핵심 코드는 `src/main/java/org/keinus/logparser/` 아래에 있습니다.

- `application/pipeline`: `InputAdapter`에서 온 `LogEvent`를 dispatcher, parser, transform, structured mapping, output 단계로 연결합니다.
- `application/service`: Live Tail, 문서 제공, thread monitoring 같은 애플리케이션 서비스를 둡니다.
- `domain/input`: 입력 어댑터 모델입니다. File, TCP, TLS TCP, UDP, HTTP, HTTPS, Kafka, SNMP, RabbitMQ, TLS RabbitMQ, TCP mTLS gzip, Fake 입력이 있습니다.
- `domain/output`: 출력 어댑터 모델입니다. Console, TCP, HTTP, Kafka, OpenSearch, RabbitMQ, MariaDB, ClickHouse, Benchmark 출력이 있습니다.
- `domain/parse`: JSON, Grok, Regex, RFC3164, RFC5424, HTTP parser가 있습니다.
- `domain/transformation`: Filter, AddProperty, RemoveProperty와 structured mapping 로직이 있습니다.
- `domain/configuration`: 설정 DTO, 메타데이터, 검증, seed 로직입니다.
- `infrastructure/persistence`: SQLite entity/repository와 Flyway migration입니다.
- `interfaces/controller`: REST API controller입니다.
- `interfaces/websocket`: Live Tail WebSocket입니다.
- `src/main/resources/static`: 정적 관리 UI입니다.
- `readme`: 상세 매뉴얼, 다이어그램 샘플, 이미지 자산입니다.

생성 산출물인 `build/`, `.gradle/`, 로컬 DB, 로그 파일은 커밋하지 않습니다. 단, `.gitignore`가 특정 logparser source/test path를 막는 경우에는 필요한 예외를 명시적으로 추가합니다.

## 빌드와 테스트

Windows PowerShell 기준입니다.

```powershell
.\gradlew bootRun
.\gradlew test
.\gradlew build
docker build -t logparser .
docker compose up --build
```

Java 21이 필요합니다. Gradle이 Java를 찾지 못하면 `JAVA_HOME`을 Java 21 설치 경로로 지정한 뒤 다시 실행합니다.

```powershell
$env:JAVA_HOME='C:\Path\To\Java21'
.\gradlew test
```

## 코드 스타일

- 기존 Java/Spring Boot 스타일과 package 경계를 따릅니다.
- adapter 생성은 factory와 configuration service의 등록 흐름을 기준으로 맞춥니다.
- API 입력 검증은 configuration model/service에 둡니다.
- 복잡한 adapter 전용 설정은 `configParams` JSON을 사용하되, validation과 metadata schema를 함께 갱신합니다.
- 네트워크/주기 수집 adapter는 timeout, queue size, worker count, close 처리를 명확히 둡니다.
- 장시간 blocking 작업은 파이프라인 종료와 reload를 막지 않도록 중단 경로를 둡니다.

## 입력 어댑터 등록 체크리스트

새 입력 어댑터를 추가할 때는 구현체만 만들지 말고 다음 경로를 모두 맞춥니다.

1. `domain/input/model`에 `InputAdapter` 구현을 추가합니다.
2. `InputAdapterConfig`를 받는 public 생성자를 제공합니다.
3. `InputFactory.TYPE_ALIASES`에 alias가 필요하면 추가합니다.
4. `InputAdapterConfig.@Choice`와 `validate()`에 타입과 필수 필드를 추가합니다.
5. `ConfigMetadataService.getInputAdapterTypes()`와 `getInputAdapterSchema()`를 갱신합니다.
6. `ConfigValidationService.validateInputAdapter()`에 상세 검증을 추가합니다.
7. SQLite trigger가 type을 제한하면 `V1__Initial_schema.sql`과 신규 migration을 함께 갱신합니다.
8. 어댑터 단위 테스트, configuration model/service 테스트, factory 테스트가 필요하면 추가합니다.
9. `README.md`, `readme/logparser-user-manual.md`, `readme/diagram_samples.md`를 현재 동작과 맞춥니다.

현재 입력 type/alias는 다음을 기준으로 합니다.

| Canonical type | Alias |
| --- | --- |
| `FileInputAdapter` | `file` |
| `TcpInputAdapter` | `tcp` |
| `TlsTcpInputAdapter` | `tls_tcp`, `tlstcp` |
| `UdpInputAdapter` | `udp` |
| `HttpInputAdapter` | `http` |
| `HttpsInputAdapter` | `https` |
| `KafkaInputAdapter` | `kafka` |
| `SnmpInputAdapter` | `snmp` |
| `RabbitMqInputAdapter` | `rabbitmq` |
| `TlsRabbitMqInputAdapter` | `tls_rabbitmq`, `tlsrabbitmq` |
| `TcpMtlsGzipInputAdapter` | `tcp_mtls_gzip` |
| `FakeInputAdapter` | `fake` |

## 출력 어댑터 등록 체크리스트

새 출력 어댑터를 추가할 때는 다음 경로를 모두 맞춥니다.

1. `domain/output/model`에 `OutputAdapter` 구현을 추가합니다.
2. `Map<String, String>`을 받는 public 생성자를 제공합니다.
3. `OutputFactory.TYPE_ALIASES`와 `convertConfigToMap()`을 갱신합니다.
4. `OutputAdapterConfig.@Choice`와 `validate()`에 타입과 필수 필드를 추가합니다.
5. `ConfigMetadataService.getOutputAdapterTypes()`와 `getOutputAdapterSchema()`를 갱신합니다.
6. `ConfigValidationService.validateOutputAdapter()`에 상세 검증을 추가합니다.
7. SQLite trigger가 type을 제한하면 `V1__Initial_schema.sql`과 신규 migration을 함께 갱신합니다.
8. 출력 어댑터 단위 테스트, configuration model/service 테스트, factory 테스트를 추가합니다.
9. 사용자 문서와 다이어그램을 갱신합니다.

현재 출력 type/alias는 다음을 기준으로 합니다.

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

## TLS 구현 지침

- 서버 TLS 입력은 `TlsConfigSupport.createServerSocket()`를 우선 사용합니다.
- 서버 key store는 `keyStorePath`와 `keyStorePassword` 또는 `keyStorePasswordEnv`가 필요합니다.
- `clientAuth`가 `want` 또는 `need`이면 trust store path/password도 검증해야 합니다.
- RabbitMQ TLS 입력은 `RabbitMqInputAdapter`의 TLS 옵션을 공유합니다. `TlsRabbitMqInputAdapter`는 TLS를 기본값으로 취급하고 포트 생략 시 `5671`을 씁니다.
- Castrelyx agent 전용 `TcpMtlsGzipInputAdapter`는 별도 frame protocol과 CN/source_id 검증을 갖습니다. 일반 TLS TCP와 혼동하지 않습니다.

## 파이프라인 지침

일반 흐름은 `InputAdapter` -> `MessageDispatcher` -> `ProcessingDispatcher` -> parser -> transform -> structured mapping -> output입니다.

- 입력 단계는 원문을 `LogEvent`로 만들고 dispatcher에 전달하는 책임만 가집니다.
- parser, transform, output 동작을 입력 어댑터 내부에 넣지 않습니다.
- `messagetype`은 파이프라인 연결 키입니다. 새 흐름을 추가할 때 입력, parser, output의 message type 연결을 함께 검증합니다.
- Live Tail은 dispatcher 단계에서 브로드캐스트합니다. output adapter 구현에 Live Tail 전용 처리를 넣지 않습니다.

## Schema mapping template 지침

Schema Map의 template 기능은 message type별 mapping을 재사용하기 위한 전역 저장소입니다. 템플릿을 바꾸는 작업은 UI만 보지 말고 저장소, 서비스, cache invalidation, REST API를 함께 확인합니다.

- 모델은 `domain/model/mapping/MappingTemplate`입니다. `name`, `description`, `sourceMessageType`, `config`, `createdAt`, `updatedAt`를 유지합니다.
- 저장소는 `MappingTemplateRepository`와 `SqliteMappingTemplateRepository`입니다. SQLite table은 `mapping_templates`이고 `config_json`에 `MappingConfiguration` 전체를 저장합니다.
- 서비스는 `MappingTemplateService`입니다. 생성/수정 시 이름 중복과 빈 이름, 빈 config를 검증합니다.
- apply 동작은 선택한 template의 config를 deep copy한 뒤 대상 `messageType`으로 저장합니다. 기존 message type mapping은 덮어쓰며, template 자체는 변경하지 않습니다.
- apply 이후에는 `StructuredTransformService.invalidateCache(messageType)`를 호출해야 런타임 structured mapping cache가 오래된 설정을 쓰지 않습니다.
- REST API는 `StructuredTransformController`의 `/api/v1/structure/templates` 계열 endpoint에서 제공합니다.
- UI는 `static/js/api.js`, `static/js/app.js`, `static/index.html`의 Schema Map toolbar와 연결합니다. 사용자는 template 생성, 선택, 적용, 수정, 삭제를 같은 화면에서 수행할 수 있어야 합니다.
- 변경 시 `MappingTemplateServiceTest`, `MappingTemplateRepositoryTest`, `StructuredTransformControllerTest`를 함께 갱신합니다.
- 사용자 문서는 `README.md`, `readme/logparser-user-manual.md`, 흐름 다이어그램은 `readme/diagram_samples.md`에 반영합니다.

## 테스트 지침

- adapter 동작이 바뀌면 해당 adapter 단위 테스트를 추가하거나 갱신합니다.
- 설정 DTO와 검증 로직이 바뀌면 `domain/configuration` 테스트를 갱신합니다.
- factory alias나 reflection 생성 경로가 바뀌면 factory 테스트를 추가합니다.
- DB migration이나 trigger가 바뀌면 기존 DB와 신규 DB 양쪽을 고려합니다.
- 완료 전 최소 `.\gradlew test`를 실행합니다. 로컬에 Java가 없으면 정확한 실패 메시지를 남깁니다.

## 보안과 설정

기본 SQLite 설정 DB는 `${user.home}/logparser/data/config.db`에 생성됩니다. provider key, webhook secret, SNMP community, RabbitMQ password, OpenSearch password, TLS store password 같은 민감 값을 커밋하지 않습니다.

`configParams`에 직접 secret을 넣는 구현도 있으므로 운영 문서에는 가능한 env 참조 방식을 적고, env 참조가 실제로 구현된 필드에만 그렇게 안내합니다. 현재 MariaDB/ClickHouse 출력 인증은 env 참조만 지원합니다. RabbitMQ 입력 password는 직접 문자열 필드입니다.

## 작업 원칙

요청 범위에 맞는 파일만 수정합니다. broad cleanup, 임의 리팩터링, 생성 산출물 편집은 피합니다. 변경 후에는 어떤 검증을 실행했고 어떤 검증이 환경 때문에 막혔는지 명확히 남깁니다.
