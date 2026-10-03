# Security Router 수집 운영

`감사/Zeek → rsyslog TLS 6514 → Logparser → ClickHouse → Manager 자산 로그`입니다. Agent mTLS gzip 9443과는 다른 wire format입니다.

## 활성화

Flyway V10이 security-router message type의 입력·출력(초기 비활성)과 parser 두 개를 생성합니다. RFC5424SyslogParser(priority 10) 다음에 JsonParser(sourceField=syslog_MESSAGE, priority 20)가 실행됩니다. 고정 router 규격으로 정규화하므로 이 message type에는 Schema Mapping을 적용하지 않습니다. 다른 파이프라인의 mapping은 유지합니다.

Manager에서 ROUTER 자산을 생성하고 실제 assetUid를 확인합니다. `POST /api/assets/{id}/router-source`에 `{"sourceId":"ssw-01"}`을 보내 등록하고 두 시스템의 source/asset 연결을 같게 설정합니다.

server.p12(서버 인증서/개인키), truststore.p12(router CA 또는 client 공개 인증서)를 보호 경로에 설치합니다. Compose에서 `ROUTER_CERT_DIR`, `ROUTER_KEYSTORE_PASSWORD`, `ROUTER_TRUSTSTORE_PASSWORD`를 지정합니다. 실제 비밀번호나 인증서를 저장소에 넣지 않습니다.

입력 configParams 예시:

```json
{
  "framing":"octet-counted",
  "maxFrameBytes":2097152,
  "clientAuth":"need",
  "keyStorePath":"/etc/logparser/router/server.p12",
  "keyStorePasswordEnv":"ROUTER_KEYSTORE_PASSWORD",
  "trustStorePath":"/etc/logparser/router/truststore.p12",
  "trustStorePasswordEnv":"ROUTER_TRUSTSTORE_PASSWORD",
  "inboxDirectory":"/root/logparser/data/router-inbox",
  "clientSourceBindings": {
    "router-client-certificate-cn": {
      "sourceId":"ssw-01",
      "assetUid":"manual-REPLACE-WITH-ACTUAL-ASSET-UID"
    }
  }
}
```

CN은 신뢰된 client 인증서에서 읽으며 미등록 CN은 연결을 종료합니다. 본문의 source_id가 등록 sourceId와 다르면 오류 보관함으로 보냅니다. assetUid는 본문이 아닌 수신기 설정에서 결정합니다. Manager에 존재하는 자산 UID를 지정해야 조회할 수 있습니다.

출력의 ClickHouse endpoint/database/credential 환경변수를 맞추고 autoCreateSchema=true, writeTelemetryTables=true를 유지합니다. **출력을 먼저 활성화한 뒤 입력을 활성화**합니다. 입력 포트는 6514입니다. 인증서 변경은 입력 비활성화/활성화로 적용하며 inbox는 삭제하지 않습니다.

## 저장과 복구

- 수신 프레임을 fsync한 후 처리합니다. 대기 및 오류 보관의 공유 한도는 256 MiB/10,000 files입니다. 한도에 도달하면 해당 연결의 읽기를 멈춰 backpressure를 적용합니다.
- raw `castrelyx_router_events`와 canonical `manager_events` insert가 모두 성공해야 inbox를 지웁니다. DB 실패/재시작 후 남은 기록은 재처리하며 실행 중 실패는 30초 후 재시도합니다.
- insert token은 source/event ID에 고정합니다. DB dedup window 밖의 중복도 자산 로그 API에서 같은 ID로 묶습니다. 물리 테이블의 중복 row가 전혀 없다고 보장하지 않습니다.
- source_type은 SECURITY_ROUTER이며 감사 해시·행위자·작업 ID와 Zeek 원본은 event_json.raw에 보존합니다. raw 기본 보관은 7일, canonical은 30일입니다.
- `GET /api/v1/router/status?assetUid=...`에서 저장 대기·오류 보관 건수 및 시각을 확인합니다. 건수는 재시작 시 disk에서 복구합니다. 저장 시각의 영구 기준은 ClickHouse입니다.
- 파싱/규격/식별 실패는 `inboxDirectory/dead/*.json`, 이유는 대응 `.reason`에 보관합니다. 원인을 수정한 뒤 입력을 중지하고 JSON을 inbox 최상위로 옮긴 후 다시 활성화하면 재처리합니다. 파일의 source/asset을 임의로 바꿔 인증 검사를 우회하지 않습니다.
- TCP 수신 성공은 DB commit ACK가 아닙니다. 수신 파일 fsync 이전의 강제 종료까지 무손실 또는 정확히 한 번 전송을 보장하지 않습니다.
- 같은 파일을 Agent 수집에도 추가하지 않습니다. 프레임 한도 초과/잘못된 길이는 연결 종료, 파싱/규격 오류는 원문 보관으로 처리합니다.

## Manager 조회

`GET /api/assets/by-uid/{assetUid}/logs`는 range(15m/30m/1h/6h/24h), severity, family(ALL/audit/security/zeek), query, limit(1..300), offset(0..100000)을 받습니다. 서버 검색 결과에 hasMore/nextOffset을 반환합니다. 새 이벤트가 유입되면 offset 페이지 경계는 바뀔 수 있습니다. 기존 `/api/agent/logs`는 호환성을 유지합니다.

자산 로그 탭에서 종류·심각도 필터, 검색, 페이지 이동, 원본·상세, 수신/저장 시각과 대기/오류 건수를 확인합니다. DB 장애는 503으로 표시하며 빈 결과로 숨기지 않습니다. 수신기 상태 조회 실패도 별도로 표시합니다.

## 검증

`gradlew test -PskipFrontend=true`는 byte framing, 실제 mTLS socket과 등록 CN, 정규화, DB 실패 후 inbox 복구를 검증합니다. Manager 테스트는 조회 조건/페이지/오류 분리를 확인합니다. 운영 장비의 실제 이벤트가 DB와 화면에 도달하는지는 배포 후 확인합니다.

임시 ClickHouse를 준비하고 `ROUTER_TEST_CLICKHOUSE_URL=http://127.0.0.1:18123`을 설정하면 두 모듈의 `RouterClickHouseIntegrationTest`도 실행합니다. 각 테스트는 임의 이름의 전용 DB를 만들고 정리합니다. Logparser는 실제 raw/canonical insert와 재전송 중복 처리를, Manager는 실제 SQL 검색·자산 분리·페이지 처리를 검증합니다. 운영 DB 대신 격리된 테스트 서버를 사용합니다.

inboxDirectory는 입력마다 다른 전용 경로를 사용합니다. Linux에서는 디렉터리 0700, 수신 파일 0600을 적용합니다. 정상적인 임시 파일은 재시작 시 복구하고 쓰다가 끊긴 임시 파일은 dead에 보존합니다.
