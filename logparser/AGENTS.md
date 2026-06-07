한국어로 작성해.

# Repository Guidelines

## 프로젝트 구조 및 모듈 구성
이 프로젝트는 Java 21과 Spring Boot 기반 로그 처리 파이프라인입니다. 핵심 코드는 `src/main/java/org/keinus/logparser/` 아래에 있습니다.

- `application/pipeline`: 입력 이벤트를 파싱, 변환, 구조화, 출력 단계로 전달하는 런타임 파이프라인입니다.
- `domain/input`: 입력 어댑터 모델입니다. 파일, UDP, TCP, HTTP, Kafka, SNMP, RabbitMQ 같은 수집 어댑터가 여기에 있습니다.
- `domain/parse`: 로그 파서 모델입니다. JSON, regex, delimiter, fixed length, key-value 파서를 다룹니다.
- `domain/transformation`: 필드 변환 규칙입니다.
- `domain/output`: 출력 어댑터 모델입니다. 파일, HTTP, OpenSearch, Kafka, 디버그 출력을 다룹니다.
- `domain/configuration`: 런타임 설정 모델과 검증 로직입니다.
- `infrastructure/persistence`: SQLite 기반 설정 저장소와 Flyway 마이그레이션입니다.
- `interfaces/controller`: REST API 컨트롤러입니다.
- `src/main/resources/static`: 브라우저에서 사용하는 정적 관리 UI입니다.
- `src/test`: JUnit 기반 단위/통합 테스트입니다.

생성 산출물인 `build/`, `.gradle/`, 런타임 데이터베이스, 로컬 로그 파일은 소스 변경 대상으로 보지 않습니다.

## 빌드, 테스트, 개발 명령
Windows PowerShell 기준으로 사용합니다.

- 로컬 실행: `.\gradlew bootRun`
- 전체 테스트: `.\gradlew test`
- 전체 빌드: `.\gradlew build`
- Docker 이미지 빌드: `docker build -t logparser .`
- Docker Compose 실행: `docker compose up --build`

Java 21이 필요합니다. 로컬 셸에서 Gradle이 Java를 찾지 못하면 `JAVA_HOME`을 Java 21 설치 경로로 지정한 뒤 명령을 다시 실행합니다.

## 코드 스타일 및 네이밍 규칙
기존 Java/Spring Boot 스타일을 따릅니다. 불필요한 리팩토링이나 포맷 변경은 하지 않습니다.

- Java 코드는 기존 4칸 들여쓰기 스타일을 유지합니다.
- 클래스와 인터페이스는 `PascalCase`, 메서드와 필드는 `camelCase`를 사용합니다.
- Spring 컴포넌트는 기존 패키지 경계에 맞춰 배치합니다.
- 설정 DTO와 도메인 모델은 검증 책임을 분리합니다. API 입력값 검증은 configuration model/service 쪽에서 처리합니다.
- 복잡한 어댑터별 설정은 `configParams` JSON을 사용하되, 가능한 한 명시적인 스키마와 테스트를 함께 추가합니다.

## 입력 어댑터 추가 지침
새 입력 어댑터를 추가할 때는 단순히 `InputAdapter` 하위 클래스만 만들지 말고 설정, 검증, 저장소 제약, 문서를 함께 맞춥니다.

필수 확인 항목:

1. `domain/input/model`에 `InputAdapter` 구현을 추가합니다.
2. 런타임 생성에 필요한 public `InputAdapterConfig` 생성자를 제공합니다.
3. `InputAdapterConfig`의 허용 타입과 필드 검증을 갱신합니다.
4. `ConfigMetadataService`에 UI/API 메타데이터와 `configParams` 스키마를 추가합니다.
5. `ConfigValidationService`에 타입별 상세 검증을 추가합니다.
6. SQLite trigger나 제약 조건이 타입을 제한한다면 Flyway migration을 추가합니다.
7. 설정 저장/로드 경로가 새 필드를 보존하는지 테스트합니다.
8. README에 사용 예시와 운영 주의사항을 추가합니다.

입력 어댑터는 `close()`에서 소켓, 스레드, executor, SNMP session 같은 외부 리소스를 확실히 정리해야 합니다. 주기 수집이나 네트워크 수집은 무한 스레드/무한 큐를 만들지 말고 timeout, queue size, worker count를 기존 설정 흐름에 맞춰 제한합니다.

## 파이프라인 지침
일반 흐름은 `InputAdapter` -> `MessageDispatcher` -> `ProcessingDispatcher` -> parser/transform/structured/output 단계입니다.

- 입력 단계는 `LogEvent`를 생성하고 dispatcher에 전달하는 책임만 가집니다.
- 파싱, 필드 변환, 출력 동작을 입력 어댑터 내부에 섞지 않습니다.
- 장애가 난 단일 이벤트나 대상 때문에 전체 파이프라인이 멈추지 않도록 합니다.
- 장시간 blocking 작업은 timeout과 종료 처리를 반드시 둡니다.

## 테스트 지침
이 저장소는 Gradle/JUnit 테스트를 중심으로 검증합니다. 동작을 바꾸면 변경 범위에 맞는 테스트를 추가하거나 갱신합니다.

- 어댑터 동작 변경: 해당 어댑터 단위 테스트를 추가합니다.
- 설정 모델/검증 변경: configuration model/service 테스트를 추가합니다.
- DB 스키마나 마이그레이션 변경: 저장/로드 경로 테스트를 추가합니다.
- 완료 전 최소 `.\gradlew test`를 실행합니다. 빌드 산출물까지 확인해야 하면 `.\gradlew build`를 실행합니다.

## 보안 및 설정 팁
런타임 설정 데이터베이스는 기본적으로 사용자 홈 아래 `logparser/data/config.db`에 생성됩니다. 저장소에 provider key, webhook secret, SNMP community, Kafka credential, OpenSearch password 같은 민감 값을 커밋하지 않습니다.

현재 출력 어댑터 password 류 필드는 암호화 변환을 거치지만, `configParams`에 넣은 값은 타입별 구현에 따라 평문으로 저장될 수 있습니다. 민감한 설정을 추가할 때는 저장 방식과 문서화를 함께 검토합니다.

## 작업 원칙
요청된 범위만 수정합니다. 주변 코드 정리, 임의 리팩토링, 생성 산출물 편집은 피합니다. 변경 전후 검증 가능한 목표를 세우고, 테스트 결과나 확인한 한계를 최종 답변에 명확히 적습니다.
