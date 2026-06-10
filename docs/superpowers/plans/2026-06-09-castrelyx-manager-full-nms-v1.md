# Castrelyx Manager Full NMS v1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 별도 `manager/` 서비스로 사용자용 NMS 콘솔, 자산/트래픽/Agent/SNMP 대시보드, CastrelSign 제어 UI, logparser 연동을 구현한다.

**Architecture:** `manager`는 NMS control plane이다. MariaDB는 사용자, 권한, 자산, 설정, 알림 상태를 저장하고, ClickHouse는 logparser raw telemetry와 manager canonical telemetry 테이블을 담당한다. CastrelSign은 agent 인증서와 enrollment token을 담당하고, logparser는 수집 파이프라인으로 유지한다.

**Tech Stack:** Java 21, Spring Boot 3.5, Gradle, MariaDB, ClickHouse HTTP API, React, Vite, TypeScript, Tailwind, shadcn/ui, Vitest, Playwright.

---

## 1. 범위와 기본 결정

- v1은 별도 `manager/` Spring Boot 서비스와 `manager/frontend/` React UI를 추가한다.
- v1 인증은 로컬 계정과 세션 cookie 기반이다. 추후 OAuth2 인증 서비스 분리를 고려해 `AuthProvider` 경계를 둔다.
- 역할은 `ADMIN`, `OPERATOR`, `VIEWER` 세 단계로 둔다.
- 첫 실행 시 admin 사용자가 없으면 setup wizard만 접근 가능하다.
- 비밀값은 `MANAGER_CRYPTO_KEY` 기반 AES-GCM 암호화 저장으로 처리한다.
- v1 트래픽 관리는 interface counter 기반 rate, utilization, errors, discards까지 구현한다.
- NetFlow, sFlow, IPFIX 기반 flow 분석은 v1 구현 범위가 아니라 roadmap으로 문서화한다.
- 알림은 v1에서 인앱 알림만 구현한다. webhook, email, SMS는 후속 범위다.
- logparser UI는 manager로 완전 흡수하지 않는다. manager는 공통 제어판과 deep link를 제공한다.

## 2. 파일 구조

구현 후 주요 구조는 다음 형태가 되어야 한다.

```text
castrelyx/
  docker-compose.yml
  .env.example
  manager/
    build.gradle
    settings.gradle
    src/main/java/org/castrelyx/manager/
      ManagerApplication.java
      auth/
      asset/
      alert/
      config/
      integration/
      secret/
      telemetry/
      user/
      web/
    src/main/resources/
      application.yml
      db/migration/
    src/test/java/org/castrelyx/manager/
    frontend/
      package.json
      vite.config.ts
      src/
        App.tsx
        lib/
        views/
        components/
        test/
    scripts/
      smoke-compose.ps1
```

패키지 책임은 다음과 같이 고정한다.

- `auth`: setup, login, session, `AuthProvider`, RBAC.
- `user`: local user persistence와 password hash.
- `secret`: 암호화, secret masking, encrypted config converter.
- `asset`: asset CRUD, source binding, merge candidate.
- `telemetry`: ClickHouse client, sync cursor, raw event normalization, canonical query.
- `alert`: alert rule, evaluator, instance lifecycle.
- `integration`: CastrelSign/logparser API client와 integration config.
- `web`: REST controller, DTO, exception handler.

## 3. 작업 순서

### Task 1: Manager 서비스 스캐폴드

**Files:**
- Create: `manager/settings.gradle`
- Create: `manager/build.gradle`
- Create: `manager/src/main/java/org/castrelyx/manager/ManagerApplication.java`
- Create: `manager/src/main/resources/application.yml`
- Create: `manager/frontend/package.json`
- Create: `manager/frontend/vite.config.ts`

- [ ] **Step 1: Gradle 프로젝트를 생성한다**

`manager/settings.gradle`:

```gradle
pluginManagement {
  repositories {
    gradlePluginPortal()
    mavenCentral()
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    mavenCentral()
  }
}

rootProject.name = 'castrelyx-manager'
```

`manager/build.gradle`은 Spring Boot 3.5.x, Java 21, web, validation, security, data-jpa, jdbc, flyway, mariadb, jackson, test, playwright smoke 실행에 필요한 기본 task를 포함한다. `logparser/build.gradle`의 frontend build task 패턴을 참고하되, 출력 경로는 `manager/src/main/resources/static`으로 둔다.

- [ ] **Step 2: application.yml을 작성한다**

필수 환경 변수:

```yaml
manager:
  crypto-key: ${MANAGER_CRYPTO_KEY:}
  public-base-url: ${MANAGER_PUBLIC_BASE_URL:http://localhost:8780}
  clickhouse:
    endpoint-url: ${MANAGER_CLICKHOUSE_ENDPOINT_URL:http://clickhouse:8123}
    database: ${MANAGER_CLICKHOUSE_DATABASE:castrelyx}
    username: ${MANAGER_CLICKHOUSE_USER:default}
    password: ${MANAGER_CLICKHOUSE_PASSWORD:}
    raw-table: ${MANAGER_CLICKHOUSE_RAW_TABLE:castrelyx_agent_events}
server:
  port: ${MANAGER_PORT:8780}
spring:
  datasource:
    url: ${MANAGER_DB_URL:jdbc:mariadb://localhost:3306/castrelyx_manager}
    username: ${MANAGER_DB_USER:castrelyx}
    password: ${MANAGER_DB_PASSWORD:castrelyx}
  flyway:
    enabled: true
```

- [ ] **Step 3: 기본 애플리케이션 테스트를 추가한다**

`manager/src/test/java/org/castrelyx/manager/ManagerApplicationTest.java`:

```java
package org.castrelyx.manager;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class ManagerApplicationTest {
  @Test
  void contextLoads() {
  }
}
```

- [ ] **Step 4: 검증한다**

Run:

```powershell
cd D:\Study\castrelyx\manager
.\gradlew test
```

Expected: `BUILD SUCCESSFUL`.

### Task 2: MariaDB 초기 스키마

**Files:**
- Create: `manager/src/main/resources/db/migration/V1__Initial_manager_schema.sql`
- Create: `manager/src/main/java/org/castrelyx/manager/user/UserAccount.java`
- Create: `manager/src/main/java/org/castrelyx/manager/asset/Asset.java`
- Create: `manager/src/main/java/org/castrelyx/manager/asset/AssetSourceBinding.java`
- Create: `manager/src/main/java/org/castrelyx/manager/alert/AlertRule.java`
- Create: `manager/src/main/java/org/castrelyx/manager/alert/AlertInstance.java`

- [ ] **Step 1: 스키마를 작성한다**

테이블은 최소 다음 컬럼을 포함한다.

```sql
create table users (
  id bigint primary key auto_increment,
  username varchar(120) not null unique,
  password_hash varchar(255) not null,
  display_name varchar(200),
  role varchar(40) not null,
  enabled boolean not null default true,
  created_at timestamp not null default current_timestamp,
  updated_at timestamp not null default current_timestamp
);

create table user_sessions (
  id bigint primary key auto_increment,
  session_token_hash varchar(255) not null unique,
  user_id bigint not null,
  expires_at timestamp not null,
  created_at timestamp not null default current_timestamp,
  foreign key (user_id) references users(id)
);

create table integration_configs (
  id bigint primary key auto_increment,
  service_name varchar(80) not null unique,
  base_url varchar(500) not null,
  encrypted_secret text,
  enabled boolean not null default true,
  created_at timestamp not null default current_timestamp,
  updated_at timestamp not null default current_timestamp
);

create table assets (
  id bigint primary key auto_increment,
  asset_uid varchar(120) not null unique,
  name varchar(255) not null,
  asset_type varchar(80) not null,
  management_ip varchar(80),
  description text,
  status varchar(40) not null default 'unknown',
  first_seen_at timestamp null,
  last_seen_at timestamp null,
  created_at timestamp not null default current_timestamp,
  updated_at timestamp not null default current_timestamp
);

create table asset_source_bindings (
  id bigint primary key auto_increment,
  asset_id bigint not null,
  source_type varchar(40) not null,
  source_id varchar(255) not null,
  source_key varchar(255),
  confidence int not null default 100,
  last_seen_at timestamp null,
  created_at timestamp not null default current_timestamp,
  unique key uk_asset_source (source_type, source_id, source_key),
  foreign key (asset_id) references assets(id)
);

create table asset_merge_candidates (
  id bigint primary key auto_increment,
  primary_asset_id bigint not null,
  candidate_asset_id bigint not null,
  reason varchar(500) not null,
  confidence int not null,
  status varchar(40) not null default 'pending',
  created_at timestamp not null default current_timestamp
);

create table snmp_credentials (
  id bigint primary key auto_increment,
  name varchar(200) not null,
  version varchar(20) not null,
  encrypted_params text not null,
  created_at timestamp not null default current_timestamp
);

create table snmp_targets (
  id bigint primary key auto_increment,
  name varchar(200) not null,
  host varchar(255) not null,
  port int not null default 161,
  credential_id bigint not null,
  enabled boolean not null default true,
  poll_interval_ms bigint not null default 60000,
  logparser_adapter_id bigint,
  created_at timestamp not null default current_timestamp,
  foreign key (credential_id) references snmp_credentials(id)
);

create table alert_rules (
  id bigint primary key auto_increment,
  name varchar(200) not null,
  rule_type varchar(80) not null,
  severity varchar(40) not null,
  expression_json text not null,
  enabled boolean not null default true,
  created_at timestamp not null default current_timestamp
);

create table alert_instances (
  id bigint primary key auto_increment,
  rule_id bigint not null,
  asset_id bigint,
  severity varchar(40) not null,
  status varchar(40) not null,
  title varchar(300) not null,
  detail text,
  first_seen_at timestamp not null,
  last_seen_at timestamp not null,
  acknowledged_at timestamp null,
  resolved_at timestamp null,
  foreign key (rule_id) references alert_rules(id),
  foreign key (asset_id) references assets(id)
);

create table sync_cursors (
  name varchar(120) primary key,
  cursor_value varchar(500) not null,
  updated_at timestamp not null default current_timestamp
);
```

- [ ] **Step 2: enum 값을 고정한다**

Java enum:

```java
public enum Role { ADMIN, OPERATOR, VIEWER }
public enum AssetType { LINUX_SERVER, WINDOWS_SERVER, WINDOWS_HOST, ROUTER, FIREWALL, NETWORK_DEVICE, UNKNOWN }
public enum SourceType { MANUAL, AGENT, SNMP }
public enum AlertStatus { ACTIVE, ACKNOWLEDGED, RESOLVED }
public enum Severity { INFO, WARNING, CRITICAL }
```

- [ ] **Step 3: 검증한다**

Run:

```powershell
cd D:\Study\castrelyx\manager
.\gradlew test
```

Expected: migration syntax error 없이 `BUILD SUCCESSFUL`.

### Task 3: Setup, AuthProvider, RBAC

**Files:**
- Create: `manager/src/main/java/org/castrelyx/manager/auth/AuthProvider.java`
- Create: `manager/src/main/java/org/castrelyx/manager/auth/LocalAuthProvider.java`
- Create: `manager/src/main/java/org/castrelyx/manager/auth/Role.java`
- Create: `manager/src/main/java/org/castrelyx/manager/web/SetupController.java`
- Create: `manager/src/main/java/org/castrelyx/manager/web/AuthController.java`
- Create: `manager/src/test/java/org/castrelyx/manager/auth/AuthProviderTest.java`
- Create: `manager/src/test/java/org/castrelyx/manager/web/SetupControllerTest.java`

- [ ] **Step 1: AuthProvider 인터페이스를 정의한다**

```java
package org.castrelyx.manager.auth;

public interface AuthProvider {
  AuthUser authenticate(String username, String password);
  AuthUser currentUser(String sessionToken);
  String createSession(AuthUser user);
  void revokeSession(String sessionToken);
}
```

`AuthUser`는 `id`, `username`, `displayName`, `role`을 가진 record로 둔다.

- [ ] **Step 2: setup 상태 API를 구현한다**

API:

- `GET /api/setup/status` -> `{ "required": true | false }`
- `POST /api/setup/admin` -> admin user 생성. admin 사용자가 이미 있으면 `409 Conflict`.

- [ ] **Step 3: login/logout/session API를 구현한다**

API:

- `POST /api/auth/login`
- `POST /api/auth/logout`
- `GET /api/auth/session`

session cookie 이름은 `CASTRELYX_MANAGER_SESSION`으로 고정한다.

- [ ] **Step 4: RBAC filter를 적용한다**

권한 규칙:

- `VIEWER`: GET만 허용.
- `OPERATOR`: 자산, SNMP target, alert acknowledge/resolve 가능. 사용자/연동 secret 변경 불가.
- `ADMIN`: 모든 기능 허용.

- [ ] **Step 5: 검증한다**

Run:

```powershell
cd D:\Study\castrelyx\manager
.\gradlew test --tests "*Auth*" --tests "*Setup*"
```

Expected: setup, login, role별 접근 테스트 통과.

### Task 4: Secret 암호화 저장

**Files:**
- Create: `manager/src/main/java/org/castrelyx/manager/secret/SecretCrypto.java`
- Create: `manager/src/main/java/org/castrelyx/manager/secret/SecretValue.java`
- Create: `manager/src/main/java/org/castrelyx/manager/secret/SecretMasker.java`
- Create: `manager/src/test/java/org/castrelyx/manager/secret/SecretCryptoTest.java`

- [ ] **Step 1: AES-GCM 암호화 구현을 추가한다**

`SecretCrypto` 동작:

- `MANAGER_CRYPTO_KEY`가 비어 있으면 시작 실패.
- key는 최소 32 bytes UTF-8 또는 base64 32 bytes를 허용한다.
- ciphertext format은 `v1:<base64-nonce>:<base64-ciphertext>`로 둔다.

- [ ] **Step 2: masking 규칙을 구현한다**

API 응답에서 secret은 다음 형태로만 반환한다.

```json
{
  "configured": true,
  "masked": "********"
}
```

- [ ] **Step 3: 검증한다**

Run:

```powershell
cd D:\Study\castrelyx\manager
.\gradlew test --tests "*Secret*"
```

Expected: round-trip, wrong key failure, response masking 테스트 통과.

### Task 5: CastrelSign 연동과 필요한 admin 조회 API

**Files:**
- Create: `manager/src/main/java/org/castrelyx/manager/integration/CastrelSignClient.java`
- Create: `manager/src/main/java/org/castrelyx/manager/web/CastrelSignIntegrationController.java`
- Modify: `CastrelSign/src/main/java/org/castrelyx/castrelsign/api/AdminEnrollmentTokenController.java`
- Create: `CastrelSign/src/main/java/org/castrelyx/castrelsign/api/AdminAgentController.java`
- Create: `CastrelSign/src/test/java/org/castrelyx/castrelsign/AdminAgentControllerTest.java`

- [ ] **Step 1: manager integration config API를 만든다**

API:

- `GET /api/integrations/castrelsign`
- `PUT /api/integrations/castrelsign`
- `POST /api/integrations/castrelsign/test`

`PUT` payload:

```json
{
  "baseUrl": "https://castrelsign:8443",
  "adminToken": "secret",
  "enabled": true
}
```

- [ ] **Step 2: CastrelSign client 기능을 구현한다**

manager에서 제공할 기능:

- enrollment token 생성.
- enrollment token 목록 조회.
- enrollment token revoke.
- agent 목록 조회.
- certificate 목록 조회.

- [ ] **Step 3: CastrelSign에 admin 조회 API가 부족하면 추가한다**

추가 API:

- `GET /api/admin/agents`
- `GET /api/admin/certificates`

두 API 모두 `Authorization: Bearer <CASTRELSIGN_ADMIN_TOKEN>`을 요구한다.

- [ ] **Step 4: 검증한다**

Run:

```powershell
cd D:\Study\castrelyx\CastrelSign
.\gradlew test
cd D:\Study\castrelyx\manager
.\gradlew test --tests "*CastrelSign*"
```

Expected: admin token 없을 때 401/403, 정상 token에서 agent/cert/token 응답 확인.

### Task 6: logparser 연동과 SNMP target 제어

**Files:**
- Create: `manager/src/main/java/org/castrelyx/manager/integration/LogparserClient.java`
- Create: `manager/src/main/java/org/castrelyx/manager/web/LogparserIntegrationController.java`
- Create: `manager/src/main/java/org/castrelyx/manager/snmp/SnmpTargetService.java`
- Create: `manager/src/main/java/org/castrelyx/manager/web/SnmpTargetController.java`

- [ ] **Step 1: logparser integration config API를 만든다**

API:

- `GET /api/integrations/logparser`
- `PUT /api/integrations/logparser`
- `POST /api/integrations/logparser/test`
- `GET /api/integrations/logparser/status`
- `GET /api/integrations/logparser/deep-links`

- [ ] **Step 2: SNMP target API를 만든다**

API:

- `GET /api/snmp/targets`
- `POST /api/snmp/targets`
- `PUT /api/snmp/targets/{id}`
- `POST /api/snmp/targets/{id}/enable`
- `POST /api/snmp/targets/{id}/disable`

- [ ] **Step 3: logparser SnmpInputAdapter 설정을 생성/갱신한다**

manager SNMP target 변경 시 logparser `/api/v1/input-adapters` API를 사용한다. `configParams`에는 현재 `SnmpInputAdapter`가 기대하는 `targets`, `oids`, `intervalMs`, `timeoutMs`, `retries`, `queueSize`, `workerThreads` 구조를 유지한다.

- [ ] **Step 4: 검증한다**

Run:

```powershell
cd D:\Study\castrelyx\manager
.\gradlew test --tests "*Logparser*" --tests "*Snmp*"
```

Expected: manager target 생성 시 logparser adapter payload가 정확히 생성된다.

### Task 7: ClickHouse canonical telemetry와 sync worker

**Files:**
- Create: `manager/src/main/java/org/castrelyx/manager/telemetry/ClickHouseClient.java`
- Create: `manager/src/main/java/org/castrelyx/manager/telemetry/TelemetrySyncWorker.java`
- Create: `manager/src/main/java/org/castrelyx/manager/telemetry/TelemetryNormalizer.java`
- Create: `manager/src/main/java/org/castrelyx/manager/telemetry/CanonicalTelemetrySchema.java`
- Create: `manager/src/test/java/org/castrelyx/manager/telemetry/TelemetryNormalizerTest.java`

- [ ] **Step 1: canonical ClickHouse 테이블을 만든다**

manager 시작 시 `CREATE TABLE IF NOT EXISTS`로 다음 테이블을 보장한다.

```sql
CREATE TABLE IF NOT EXISTS castrelyx.manager_metric_samples (
  observed_at DateTime64(3),
  asset_uid String,
  source_type String,
  source_id String,
  metric_name String,
  metric_value Float64,
  unit Nullable(String),
  labels_json String
) ENGINE = MergeTree
PARTITION BY toYYYYMM(observed_at)
ORDER BY (asset_uid, metric_name, observed_at);

CREATE TABLE IF NOT EXISTS castrelyx.manager_state_snapshots (
  observed_at DateTime64(3),
  asset_uid String,
  source_type String,
  source_id String,
  state_type String,
  state_key String,
  state_json String
) ENGINE = ReplacingMergeTree(observed_at)
ORDER BY (asset_uid, state_type, state_key);

CREATE TABLE IF NOT EXISTS castrelyx.manager_events (
  observed_at DateTime64(3),
  asset_uid String,
  source_type String,
  source_id String,
  event_type String,
  severity Nullable(String),
  event_json String
) ENGINE = MergeTree
PARTITION BY toYYYYMM(observed_at)
ORDER BY (asset_uid, event_type, observed_at);
```

- [ ] **Step 2: raw cursor sync를 구현한다**

Cursor 이름은 `clickhouse.raw.castrelyx_agent_events`로 둔다. cursor 값은 마지막 처리한 `(received_at, source_id, item_key)` 조합을 JSON 문자열로 저장한다.

- [ ] **Step 3: Agent item 정규화를 구현한다**

입력 raw는 logparser `ClickHouseOutputAdapter`의 `event_json`을 파싱한다.

규칙:

- `item_kind=asset`, `item_type=identity`: MariaDB `assets` upsert.
- `item_kind=metric`: `manager_metric_samples` insert.
- `item_kind=state`: `manager_state_snapshots` insert.
- `item_kind=event`: `manager_events` insert.
- `source_id`는 agent source binding으로 연결한다.

- [ ] **Step 4: SNMP payload 정규화를 구현한다**

SNMP raw payload에서 다음을 추출한다.

- `target_host`, `target_name`, `poll_status`, `metrics`.
- `poll_status=error`는 `manager_events`와 alert evaluator 입력으로 보낸다.
- IF-MIB counter는 interface metric으로 저장한다.

- [ ] **Step 5: 검증한다**

Run:

```powershell
cd D:\Study\castrelyx\manager
.\gradlew test --tests "*Telemetry*"
```

Expected: fixture raw event가 asset, metric, state, event canonical record로 변환된다.

### Task 8: 자산 API와 병합 후보

**Files:**
- Create: `manager/src/main/java/org/castrelyx/manager/asset/AssetService.java`
- Create: `manager/src/main/java/org/castrelyx/manager/web/AssetController.java`
- Create: `manager/src/test/java/org/castrelyx/manager/asset/AssetServiceTest.java`
- Create: `manager/src/test/java/org/castrelyx/manager/web/AssetControllerTest.java`

- [ ] **Step 1: 자산 CRUD API를 만든다**

API:

- `GET /api/assets`
- `POST /api/assets`
- `GET /api/assets/{id}`
- `PUT /api/assets/{id}`
- `GET /api/assets/{id}/sources`

- [ ] **Step 2: source binding 규칙을 구현한다**

자동 binding 우선순위:

1. agent `source_id`.
2. hostname + MAC address.
3. SNMP `sysObjectID` + `sysName` + management IP.

- [ ] **Step 3: merge candidate API를 만든다**

API:

- `GET /api/assets/merge-candidates`
- `POST /api/assets/merge-candidates/{id}/accept`
- `POST /api/assets/merge-candidates/{id}/reject`

자동 병합은 하지 않고 후보만 생성한다.

- [ ] **Step 4: 검증한다**

Run:

```powershell
cd D:\Study\castrelyx\manager
.\gradlew test --tests "*Asset*"
```

Expected: manual asset 생성, agent/SNMP source binding, merge candidate 생성/승인/거절 통과.

### Task 9: Traffic, Agent, SNMP Dashboard API

**Files:**
- Create: `manager/src/main/java/org/castrelyx/manager/web/TrafficController.java`
- Create: `manager/src/main/java/org/castrelyx/manager/web/DashboardController.java`
- Create: `manager/src/main/java/org/castrelyx/manager/telemetry/TrafficQueryService.java`
- Create: `manager/src/main/java/org/castrelyx/manager/telemetry/DashboardQueryService.java`

- [ ] **Step 1: traffic API를 만든다**

API:

- `GET /api/traffic/interfaces?range=1h`
- `GET /api/traffic/assets/{assetId}/interfaces?range=1h`

반환 필드:

```json
{
  "assetUid": "host-001",
  "interfaceName": "eth0",
  "inBps": 1200000,
  "outBps": 900000,
  "utilizationPct": 12.4,
  "errors": 0,
  "discards": 0,
  "status": "up"
}
```

- [ ] **Step 2: agent dashboard API를 만든다**

API:

- `GET /api/dashboards/agent`
- `GET /api/dashboards/agent/assets/{assetId}`

내용:

- heartbeat 상태.
- collector별 최근 수집 시각.
- CPU/memory/disk/network 요약.
- process/service/port/log event 요약.

- [ ] **Step 3: SNMP dashboard API를 만든다**

API:

- `GET /api/dashboards/snmp`
- `GET /api/dashboards/snmp/targets/{targetId}`

내용:

- poll success/failure.
- 장비 identity.
- interface status/traffic/errors/discards.
- routing/BGP/OSPF는 수집된 데이터가 있을 때만 표시한다.

- [ ] **Step 4: 검증한다**

Run:

```powershell
cd D:\Study\castrelyx\manager
.\gradlew test --tests "*Dashboard*" --tests "*Traffic*"
```

Expected: ClickHouse fixture에서 dashboard DTO가 계산된다.

### Task 10: Alert engine

**Files:**
- Create: `manager/src/main/java/org/castrelyx/manager/alert/AlertEvaluator.java`
- Create: `manager/src/main/java/org/castrelyx/manager/alert/DefaultAlertRuleSeeder.java`
- Create: `manager/src/main/java/org/castrelyx/manager/web/AlertController.java`
- Create: `manager/src/test/java/org/castrelyx/manager/alert/AlertEvaluatorTest.java`

- [ ] **Step 1: 기본 alert rule을 seed한다**

기본 규칙:

- agent heartbeat stale.
- CPU threshold exceeded.
- memory threshold exceeded.
- disk threshold exceeded.
- interface down.
- interface error/discard spike.
- SNMP poll failure.
- logparser output failure.

- [ ] **Step 2: alert instance lifecycle을 구현한다**

상태:

- `ACTIVE`
- `ACKNOWLEDGED`
- `RESOLVED`

동일 rule + asset + state key 조합은 기존 alert를 갱신하고 중복 생성하지 않는다.

- [ ] **Step 3: API를 만든다**

API:

- `GET /api/alerts`
- `POST /api/alerts/{id}/acknowledge`
- `POST /api/alerts/{id}/resolve`
- `GET /api/alert-rules`
- `PUT /api/alert-rules/{id}`

- [ ] **Step 4: 검증한다**

Run:

```powershell
cd D:\Study\castrelyx\manager
.\gradlew test --tests "*Alert*"
```

Expected: threshold 초과, stale heartbeat, acknowledge, resolve 테스트 통과.

### Task 11: Manager frontend

**Files:**
- Create: `manager/frontend/src/App.tsx`
- Create: `manager/frontend/src/lib/api.ts`
- Create: `manager/frontend/src/lib/types.ts`
- Create: `manager/frontend/src/views/SetupView.tsx`
- Create: `manager/frontend/src/views/LoginView.tsx`
- Create: `manager/frontend/src/views/OverviewView.tsx`
- Create: `manager/frontend/src/views/AssetsView.tsx`
- Create: `manager/frontend/src/views/TrafficView.tsx`
- Create: `manager/frontend/src/views/AgentDashboardView.tsx`
- Create: `manager/frontend/src/views/SnmpDashboardView.tsx`
- Create: `manager/frontend/src/views/AlertsView.tsx`
- Create: `manager/frontend/src/views/IntegrationsView.tsx`
- Create: `manager/frontend/src/views/SettingsView.tsx`

- [ ] **Step 1: setup/login shell을 구현한다**

흐름:

1. 앱 시작 시 `/api/setup/status` 조회.
2. setup 필요 시 setup wizard 표시.
3. setup 완료 후 login 표시.
4. session 유효 시 NMS 콘솔 표시.

- [ ] **Step 2: NMS 콘솔 IA를 구현한다**

좌측 메뉴:

- 개요
- 자산
- 트래픽
- Agent
- SNMP
- 알림
- 연동
- 설정

Viewer는 설정과 사용자 변경 action을 숨긴다.

- [ ] **Step 3: 운영 화면을 구현한다**

화면별 필수 요소:

- 개요: active assets, critical alerts, traffic top interfaces, agent health, SNMP poll health.
- 자산: 목록, 필터, 수동 등록, 상세 drawer, source bindings, merge candidates.
- 트래픽: interface table, rate chart, utilization, errors/discards.
- Agent: heartbeat, collector status, resource metric, process/service/port/log summary.
- SNMP: target list, poll status, interface traffic/status.
- 알림: severity/status filter, acknowledge, resolve.
- 연동: CastrelSign token 관리, logparser status/deep link.
- 설정: 사용자/역할 관리, OAuth2 future roadmap note.

- [ ] **Step 4: frontend unit test를 추가한다**

테스트 대상:

- setup/login route decision.
- role별 menu/action visibility.
- dashboard summary 계산.
- traffic formatter.
- alert severity/status filter.

- [ ] **Step 5: 검증한다**

Run:

```powershell
cd D:\Study\castrelyx\manager\frontend
npm install
npm run test
npm run build
```

Expected: test/build 성공.

### Task 12: Playwright E2E

**Files:**
- Create: `manager/frontend/playwright.config.ts`
- Create: `manager/frontend/tests/setup-login.spec.ts`
- Create: `manager/frontend/tests/assets.spec.ts`
- Create: `manager/frontend/tests/dashboards.spec.ts`
- Create: `manager/frontend/tests/integrations.spec.ts`

- [ ] **Step 1: E2E test fixture를 만든다**

테스트 서버는 manager local dev 또는 compose manager URL을 사용한다. 기본 URL은 `MANAGER_E2E_BASE_URL`로 받는다.

- [ ] **Step 2: 필수 flow를 검증한다**

Scenarios:

- setup wizard로 admin 생성.
- login/logout.
- role별 메뉴 visibility.
- asset 생성과 상세 조회.
- traffic dashboard 렌더링.
- alert acknowledge/resolve.
- CastrelSign integration 화면 접근.
- logparser deep link 표시.

- [ ] **Step 3: 검증한다**

Run:

```powershell
cd D:\Study\castrelyx\manager\frontend
npx playwright test
```

Expected: 모든 E2E 통과. 실패 시 screenshot/video artifact를 확인한다.

### Task 13: 루트 Docker Compose

**Files:**
- Create: `docker-compose.yml`
- Create: `.env.example`
- Create: `manager/scripts/smoke-compose.ps1`

- [ ] **Step 1: compose 서비스를 정의한다**

서비스:

- `mariadb`
- `clickhouse`
- `castrelsign`
- `logparser`
- `manager`

필수 volume:

- `mariadb-data`
- `clickhouse-data`
- `castrelsign-data`
- `logparser-data`

- [ ] **Step 2: manager env를 연결한다**

`.env.example`에 최소 다음 값을 둔다.

```dotenv
MANAGER_PORT=8780
MANAGER_DB_URL=jdbc:mariadb://mariadb:3306/castrelyx_manager
MANAGER_DB_USER=castrelyx
MANAGER_DB_PASSWORD=change-me
MANAGER_CRYPTO_KEY=replace-with-32-byte-minimum-secret
MANAGER_CLICKHOUSE_ENDPOINT_URL=http://clickhouse:8123
MANAGER_CLICKHOUSE_DATABASE=castrelyx
MANAGER_CLICKHOUSE_USER=default
MANAGER_CLICKHOUSE_PASSWORD=
CASTRELSIGN_ADMIN_TOKEN=replace-with-admin-token
LOGPARSER_CRYPTO_KEY=replace-with-logparser-key
LOGPARSER_CRYPTO_SALT=replace-with-logparser-salt
LOGPARSER_KEYSTORE_PASSWORD=changeit
LOGPARSER_TRUSTSTORE_PASSWORD=changeit
```

- [ ] **Step 3: smoke script를 작성한다**

`manager/scripts/smoke-compose.ps1` 동작:

1. `docker compose up -d --build`.
2. manager `/api/setup/status`가 응답할 때까지 대기.
3. ClickHouse raw table에 fixture telemetry insert.
4. manager sync endpoint 또는 scheduler tick 대기.
5. `/api/assets`, `/api/traffic/interfaces`, `/api/alerts` 조회.
6. 실패 시 container logs 출력 후 non-zero exit.

- [ ] **Step 4: 검증한다**

Run:

```powershell
cd D:\Study\castrelyx
docker compose up -d --build
.\manager\scripts\smoke-compose.ps1
```

Expected: manager API에서 asset, traffic, alert fixture가 조회된다.

## 4. 완료 기준

- `manager` backend test 통과.
- CastrelSign admin 조회 API를 수정했다면 `CastrelSign` test 통과.
- `manager/frontend` unit test와 build 통과.
- Playwright E2E 통과.
- 루트 compose smoke 통과.
- UI에서 setup, login, 자산, 트래픽, Agent, SNMP, 알림, CastrelSign, logparser 연동 화면이 접근 가능하다.
- secret이 API 응답과 로그에 평문으로 나오지 않는다.
- Flow 분석은 v1 비구현 roadmap으로 명시되어 있다.

## 5. 권장 구현 단위와 커밋

권장 커밋 순서:

1. `feat(manager): scaffold manager service`
2. `feat(manager): add auth and setup flow`
3. `feat(manager): add asset and secret persistence`
4. `feat(manager): add CastrelSign and logparser integrations`
5. `feat(manager): normalize telemetry into NMS tables`
6. `feat(manager): add traffic and dashboard APIs`
7. `feat(manager): add in-app alerts`
8. `feat(manager-ui): build NMS console`
9. `test(manager): add browser e2e coverage`
10. `chore: add root compose smoke`

각 커밋 전 최소 해당 task의 targeted test를 실행한다. 최종 통합 전에는 전체 검증 명령을 실행한다.

## 6. 전체 검증 명령

```powershell
cd D:\Study\castrelyx\manager
.\gradlew test

cd D:\Study\castrelyx\manager\frontend
npm run test
npm run build
npx playwright test

cd D:\Study\castrelyx\CastrelSign
.\gradlew test

cd D:\Study\castrelyx
docker compose up -d --build
.\manager\scripts\smoke-compose.ps1
```

## 7. 비범위

- OAuth2/OIDC 인증 서비스 구현.
- NetFlow/sFlow/IPFIX flow 수집과 top talker 분석.
- email, webhook, SMS 알림 전송.
- logparser UI 전체 재구현.
- SSH 원격 명령 수집, 장비 벤더 API 수집.
