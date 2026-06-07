# Castrelyx logparser Manager TODO

## 목표

CastrelSign은 agent 인증서 발급과 갱신만 담당하고, agent telemetry는 logparser가 TCP/mTLS gzip input adapter로 직접 수신한다. logparser는 수신한 batch item을 내부 파이프라인에 넣고, 최종 이벤트 저장은 MariaDB output adapter로 수행한다.

## 확정된 설계

- CastrelSign 역할
  - agent enrollment, renew, client certificate 발급을 담당한다.
  - telemetry forwarding은 하지 않는다.
  - logparser 서버 인증서와 JVM truststore/keystore 갱신이 필요할 때 shared volume 갱신과 logparser 재시작만 담당한다.

- Agent ingest 역할 분리
  - `manager_url`은 CastrelSign enrollment/renew 전용으로 유지한다.
  - telemetry 전송은 별도 TCP/mTLS 설정으로 logparser에 직접 보낸다.
  - 기존 HTTPS sender는 유지하고, 새 TCP sender는 선택적으로 사용한다.

- TCP wire protocol
  - request frame: `4-byte big-endian length` + `gzip(JSON Batch)`.
  - response frame: newline-delimited JSON ACK/NACK.
  - ACK 예: `{"status":"accepted"}`.
  - NACK 예: `{"status":"error","code":"bad_frame","message":"invalid gzip payload"}`.
  - ACK 기준은 logparser 내부 큐 수락 완료다.
  - ACK는 최종 MariaDB 저장 완료를 의미하지 않는다.

- 저장소
  - logparser 설정 DB는 기존 SQLite를 유지한다.
  - MariaDB는 처리 결과 이벤트 저장 전용으로 사용한다.
  - MariaDB는 Docker 단일 service와 persistent volume으로 운영한다.

- logparser 사전 설정
  - template registry나 activate/deactivate API를 만들지 않는다.
  - 앱 시작 시 startup seed 서비스가 기본 설정 row를 logparser 설정 DB에 disabled 상태로 저장한다.
  - 기존 row가 있으면 운영자가 바꾼 값을 덮어쓰지 않는다.
  - on/off는 기존 enable/disable API 또는 UI를 사용한다.

## 구현 TODO

### 1. Agent 설정 확장

- [ ] `agent/internal/config/config.go`에 새 설정을 추가한다.
  - `ingest_transport`
    - 기본값: `https`
    - 허용값: `https`, `tcp_mtls`
  - `tcp_ingest_addr`
    - 예: `logparser.example.com:9443`
    - `ingest_transport=tcp_mtls`일 때 필수
  - `tcp_ingest_server_name`
    - logparser 서버 인증서 SAN 검증용
    - 비어 있으면 `tls_server_name`을 fallback으로 사용

- [ ] `agent/config.example.yaml`에 TCP/mTLS 예시를 추가한다.

```yaml
manager_url: https://castrelsign.example.com:8443
enrollment_token: replace-with-token
agent_id: host-001
tenant_id: default
cert_dir: ./certs
ca_cert_path: ./certs/ca.pem
tls_server_name: castrelsign.example.com

ingest_transport: tcp_mtls
tcp_ingest_addr: logparser.example.com:9443
tcp_ingest_server_name: logparser.example.com
```

- [ ] 설정 검증 규칙을 추가한다.
  - `manager_url`은 계속 HTTPS만 허용한다.
  - `ingest_transport=tcp_mtls`인데 `tcp_ingest_addr`가 비어 있으면 실패한다.
  - `ingest_transport=https`일 때는 기존 enrollment 응답의 `ingest_url`을 사용한다.

### 2. Agent TCP/mTLS sender

- [ ] 새 sender 구현을 추가한다.
  - 기존 sender interface인 `Send(context.Context, envelope.Batch) error`를 유지한다.
  - batch의 JSON encoding과 gzip 압축은 기존 HTTPS sender와 동일한 의미를 유지한다.
  - gzip payload 앞에 4바이트 big-endian unsigned length를 붙인다.
  - length는 gzip payload byte 수다.

- [ ] ACK/NACK 처리를 구현한다.
  - 서버 응답은 `\n`으로 끝나는 JSON line이다.
  - `status=accepted`면 성공으로 반환한다.
  - `status=error`면 `code`와 `message`를 포함한 error를 반환한다.
  - 응답이 JSON이 아니거나 timeout이면 error로 반환한다.

- [ ] spool 삭제 기준을 유지한다.
  - ACK를 받은 경우에만 성공 처리한다.
  - NACK, timeout, TLS 오류, connection 오류는 기존 실패 전송과 동일하게 spool retry 대상이다.

### 3. logparser TCP/mTLS gzip input adapter

- [ ] 새 input adapter 타입 `TcpMtlsGzipInputAdapter`를 추가한다.
  - Java package는 기존 input adapter와 같은 `org.keinus.logparser.domain.input.model` 하위에 둔다.
  - 설정 모델과 validator, metadata, DB trigger 허용 타입에 `TcpMtlsGzipInputAdapter`를 추가한다.

- [ ] mTLS server socket을 구현한다.
  - server certificate keystore를 로드한다.
  - CastrelSign root CA 기반 truststore를 로드한다.
  - client certificate를 필수로 요구한다.
  - TLS 1.2 이상만 허용한다.

- [ ] frame parsing을 구현한다.
  - connection에서 4바이트 big-endian length를 읽는다.
  - `maxFrameBytes`보다 크면 NACK 후 connection을 닫는다.
  - length만큼 gzip payload를 읽는다.
  - gzip 해제 후 JSON batch로 파싱한다.

- [ ] batch 검증을 구현한다.
  - top-level JSON object여야 한다.
  - `source_id`가 있어야 한다.
  - `items`가 있으면 array여야 한다.
  - client certificate subject CN과 `source_id`가 같아야 한다.

- [ ] event 변환을 구현한다.
  - 각 `items[]` entry를 개별 `LogEvent`로 만든다.
  - message type은 기본 `castrelyx-agent-item`으로 둔다.
  - fields에는 최소 다음 값을 포함한다.
    - `schema_version`
    - `source`
    - `source_id`
    - `tenant_id`
    - `observed_at`
    - `sent_at`
    - `item_kind`
    - `item_type`
    - `item_key`
    - `payload`
  - `originalText`에는 item 단위 JSON 문자열을 넣는다.

- [ ] ACK/NACK 반환을 구현한다.
  - 모든 item이 adapter 내부 큐에 수락되면 `{"status":"accepted"}`를 반환한다.
  - 검증 실패, gzip 실패, 큐 수락 실패는 `status=error`로 반환한다.
  - ACK 기준은 내부 큐 수락이며 MariaDB 최종 저장 완료가 아니다.

### 4. logparser MariaDB output adapter

- [ ] 새 output adapter 타입 `MariaDbOutputAdapter`를 추가한다.
  - Java package는 기존 output adapter와 같은 `org.keinus.logparser.domain.output.model` 하위에 둔다.
  - 설정 모델과 validator, metadata, DB trigger 허용 타입에 `MariaDbOutputAdapter`를 추가한다.

- [ ] 설정 항목을 지원한다.
  - `jdbcUrl`
  - `usernameEnv`
  - `passwordEnv`
  - `tableName`
  - `batchSize`
  - `flushIntervalMs`
  - `autoCreateSchema`

- [ ] 기본 테이블을 정의한다.

```sql
create table if not exists castrelyx_agent_events (
  id bigint primary key auto_increment,
  received_at timestamp not null default current_timestamp,
  agent_id varchar(255) not null,
  tenant_id varchar(255),
  source_id varchar(255) not null,
  item_kind varchar(100),
  item_type varchar(255),
  item_key varchar(500),
  event_json json not null,
  index idx_castrelyx_agent_events_source_id (source_id),
  index idx_castrelyx_agent_events_item_type (item_type),
  index idx_castrelyx_agent_events_received_at (received_at)
);
```

- [ ] 저장 동작을 구현한다.
  - `LogEvent.toOutputMap()` 결과를 JSON으로 저장한다.
  - `source_id`를 `agent_id`에도 복사한다.
  - `tenant_id`, `item_kind`, `item_type`, `item_key`는 fields에서 꺼내 별도 컬럼에 저장한다.
  - batch write를 지원하되 shutdown 시 flush한다.

### 5. logparser startup seed

- [ ] startup seed 서비스를 추가한다.
  - 앱 시작 시 `config_settings`에서 seed marker를 확인한다.
  - marker key: `castrelyx.seed.castrelyx-agent-mariadb.version`.
  - marker value: `1`.

- [ ] seed 대상 row를 생성한다.
  - input row가 없으면 `TcpMtlsGzipInputAdapter` row를 `enabled=false`로 생성한다.
  - output row가 없으면 `MariaDbOutputAdapter` row를 `enabled=false`로 생성한다.
  - parser row는 만들지 않는다.
  - transform row는 만들지 않는다.

- [ ] 기존 row 보호 규칙을 적용한다.
  - `messagetype=castrelyx-agent-item`과 해당 type의 row가 이미 있으면 변경하지 않는다.
  - 운영자가 수정한 `port`, `configParams`, DB 접속값을 덮어쓰지 않는다.
  - seed marker만 없고 row가 이미 있으면 marker를 기록하고 종료한다.

- [ ] on/off 운영은 기존 API/UI를 사용한다.
  - input enable: 기존 `/api/v1/input-adapters/{id}/enable`.
  - output enable: 기존 `/api/v1/output-adapters/{id}/enable`.
  - 별도 template registry, activate/deactivate API는 만들지 않는다.

### 6. logparser seed 기본 설정

아래 설정 의미를 startup seed row로 저장한다. 실제 DB row는 logparser의 `input_adapters`, `output_adapters`, `config_settings` 테이블에 저장한다.

```yaml
seed:
  id: castrelyx-agent-mariadb
  enabledByDefault: false
input:
  type: TcpMtlsGzipInputAdapter
  messagetype: castrelyx-agent-item
  port: 9443
  enabled: false
  configParams:
    keyStorePath: /app/certs/logparser-server.p12
    keyStorePasswordEnv: LOGPARSER_KEYSTORE_PASSWORD
    trustStorePath: /app/certs/agent-truststore.p12
    trustStorePasswordEnv: LOGPARSER_TRUSTSTORE_PASSWORD
    maxFrameBytes: 10485760
    ackMode: queueAccepted
output:
  type: MariaDbOutputAdapter
  messagetype: castrelyx-agent-item
  enabled: false
  configParams:
    jdbcUrl: jdbc:mariadb://mariadb:3306/castrelyx
    usernameEnv: CASTRELYX_DB_USER
    passwordEnv: CASTRELYX_DB_PASSWORD
    tableName: castrelyx_agent_events
    batchSize: 100
    flushIntervalMs: 5000
    autoCreateSchema: true
```

## CastrelSign 운영 TODO

- [ ] logparser용 server certificate를 CastrelSign CA 기준으로 발급한다.
- [ ] agent client certificate 검증용 truststore를 생성한다.
- [ ] shared volume에 다음 파일을 배치한다.
  - `/app/certs/logparser-server.p12`
  - `/app/certs/agent-truststore.p12`
- [ ] 인증서 변경 시 logparser 컨테이너를 재시작한다.
- [ ] CastrelSign은 logparser로 telemetry를 전달하지 않는다.
- [ ] CastrelSign enrollment 응답의 `ingest_url`은 `tcp_mtls` 모드에서 agent telemetry sender 선택에 사용하지 않는다.

## Docker 및 운영 TODO

### MariaDB service

- [ ] Docker Compose에 MariaDB service를 추가한다.

```yaml
services:
  mariadb:
    image: mariadb:11
    container_name: castrelyx-mariadb
    restart: unless-stopped
    environment:
      MARIADB_DATABASE: castrelyx
      MARIADB_USER: ${CASTRELYX_DB_USER}
      MARIADB_PASSWORD: ${CASTRELYX_DB_PASSWORD}
      MARIADB_ROOT_PASSWORD: ${CASTRELYX_DB_ROOT_PASSWORD}
    volumes:
      - mariadb-data:/var/lib/mysql
    healthcheck:
      test: ["CMD", "healthcheck.sh", "--connect", "--innodb_initialized"]
      interval: 10s
      timeout: 5s
      retries: 10

volumes:
  mariadb-data:
```

### logparser service

- [ ] logparser container에 TCP ingest port를 노출한다.
  - 기본 포트: `9443`.
- [ ] logparser container에 인증서 shared volume을 mount한다.
- [ ] logparser container에 MariaDB 접속 env를 추가한다.

```yaml
services:
  logparser:
    environment:
      SERVER_PORT: 8765
      LOGPARSER_CRYPTO_KEY: ${LOGPARSER_CRYPTO_KEY}
      LOGPARSER_CRYPTO_SALT: ${LOGPARSER_CRYPTO_SALT}
      LOGPARSER_KEYSTORE_PASSWORD: ${LOGPARSER_KEYSTORE_PASSWORD}
      LOGPARSER_TRUSTSTORE_PASSWORD: ${LOGPARSER_TRUSTSTORE_PASSWORD}
      CASTRELYX_DB_USER: ${CASTRELYX_DB_USER}
      CASTRELYX_DB_PASSWORD: ${CASTRELYX_DB_PASSWORD}
    ports:
      - "8765:8765"
      - "9443:9443"
    volumes:
      - ./certs:/app/certs:ro
```

### 운영 지표

- [ ] TCP accepted count.
- [ ] TCP NACK count by `code`.
- [ ] input queue depth.
- [ ] output adapter failure count.
- [ ] MariaDB inserted row count.
- [ ] agent spool backlog.
- [ ] logparser restart count after certificate renewal.

## 테스트 TODO

- [ ] Agent config test
  - 새 TCP 설정 키를 파싱한다.
  - 기본 `ingest_transport`는 `https`다.
  - `tcp_mtls`인데 `tcp_ingest_addr`가 없으면 실패한다.
  - 기존 HTTPS 설정은 기존 테스트와 동일하게 통과한다.

- [ ] Agent TCP sender test
  - test TLS server에 client certificate로 접속한다.
  - gzip length-prefixed frame을 전송한다.
  - `{"status":"accepted"}`를 받으면 성공한다.
  - `{"status":"error"}`를 받으면 error를 반환한다.

- [ ] logparser adapter test
  - valid client certificate와 matching `source_id`면 item event를 생성한다.
  - client certificate CN과 `source_id`가 다르면 NACK한다.
  - invalid length, oversized frame, invalid gzip, invalid JSON을 NACK한다.

- [ ] Startup seed test
  - 빈 설정 DB에는 disabled input/output row를 생성한다.
  - 기존 row가 있으면 값을 덮어쓰지 않는다.
  - seed marker를 기록한다.

- [ ] MariaDB output integration test
  - container MariaDB에 table을 생성한다.
  - `castrelyx-agent-item` event를 저장한다.
  - `source_id`, `item_type`, `event_json`이 기대값과 일치한다.

- [ ] End-to-end smoke
  - CastrelSign으로 agent certificate를 발급한다.
  - agent를 `ingest_transport=tcp_mtls`로 실행한다.
  - logparser input/output 설정을 enable한다.
  - agent `-once` 실행 후 MariaDB `castrelyx_agent_events` row 생성을 확인한다.

## 리스크 및 운영 주의

- ACK 기준이 queue accepted이므로 logparser가 ACK 후 장애가 나면 MariaDB 최종 저장 전 데이터가 유실될 수 있다.
- 유실 위험을 줄이려면 후속 개선으로 raw durable ACK 모드를 검토한다.
- MariaDB 접속 비밀번호는 logparser 설정 DB의 평문 필드에 저장하지 않고 env 참조 방식으로 운용한다.
- logparser 설정 DB는 SQLite이므로 설정 변경 빈도가 높은 운영 자동화는 피한다.
- 인증서 갱신 후 logparser 재시작 시 짧은 ingest 중단이 발생할 수 있다. agent spool retry로 흡수한다.
