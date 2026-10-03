# SNMP 수집과 알림 수신

SNMP 구현은 `domain/input/model/snmp`에 있습니다. 기존 `SnmpInputAdapter`는 GET polling을 유지하며, `SnmpNotificationInputAdapter`는 UDP Trap/Inform을 수신합니다. factory/API 타입 이름에는 패키지를 붙이지 않습니다.

| 입력 type | Alias | 지원 |
| --- | --- | --- |
| `SnmpInputAdapter` | `snmp` | v1/v2c/v3 GET polling |
| `SnmpNotificationInputAdapter` | `snmp_notification` | v1/v2c/v3 Trap, v2c/v3 Inform |

두 입력 모두 같은 `messagetype`의 `JsonParser`와 출력 어댑터를 연결합니다. GETNEXT/GETBULK/WALK와 인터페이스 자동 탐색은 제공하지 않습니다. Polling에는 인스턴스까지 포함한 숫자 OID를 지정하며, MIB 업로드로 OID 선택 목록과 structured transform 템플릿을 만들 수 있습니다.

## 워크벤치에서 추가하기

1. **Pipeline Studio**에서 Message type을 선택하거나 생성합니다.
2. **Input → + (Add input)**을 누르고 Input type에서 **SNMP poller** 또는 **SNMP notifications**를 선택합니다.
3. Poller는 **Polling**에서 Default version을 선택하고, **Targets**에서 장비와 OID, **Authentication**에서 v3 사용자·인증/암호화 방식·비밀번호 또는 환경변수를 입력합니다. 새 장비는 기본 버전과 인증정보를 상속하며 장비별로 재정의할 수 있습니다.
4. Notifications는 **Listener**에서 수신 주소·포트, **Security → Add user**에서 v3 사용자를 추가합니다. Inform용 사용자는 Sender engine ID를 비우고, Trap용 사용자는 송신 장비의 engine ID를 입력합니다. 두 방식이 필요하면 각각 사용자를 추가합니다.
5. **Save input**으로 저장합니다. 새 입력은 Disabled로 시작하므로 실제 수집을 시작할 때 Enabled를 켭니다. 저장한 입력을 Input 단계에서 다시 선택하면 같은 폼에서 수정할 수 있습니다.

JSON을 직접 작성할 필요가 없으며 폼이 `configParams`를 REST API 형식으로 직렬화합니다. 워크벤치의 **Test selected step**은 샘플 데이터 미리보기이며 실제 SNMP 접속이나 Trap/Inform 수신 검사가 아닙니다.

**Test pipeline → Sample data**에서 SNMP polling / Trap / Inform 예제를 선택할 수 있습니다. SNMP 입력 선택 시 해당 JSON 예제가 기본으로 표시되고, Parser 등 후속 단계를 선택해도 유지됩니다. 직접 편집한 샘플은 입력 타입을 바꾸어도 덮어쓰지 않습니다. 예제의 수치·장비 주소는 가상 데이터이며, 예제에 있는 OID가 실제 수집 설정에 자동 추가되지는 않습니다.

## 현재 수집할 수 있는 데이터

새 Poller의 기본 OID는 `sysUpTime.0` 하나입니다. 다른 항목은 **Targets → OIDs**에서 이름으로 선택하거나 숫자 OID를 직접 추가합니다. 장비가 해당 OID를 제공하고 설정한 사용자의 읽기 권한이 있어야 합니다.

| 구분 | 예시 | 숫자 OID 예시 |
| --- | --- | --- |
| 시스템 | 장비명, 설명, SNMP 관리 기능의 가동시간 | sysName `1.3.6.1.2.1.1.5.0`, sysDescr `1.3.6.1.2.1.1.1.0`, sysUpTime `1.3.6.1.2.1.1.3.0` |
| 인터페이스 | 운영 상태, 송수신 누적 바이트, 오류 카운터 | ifOperStatus `1.3.6.1.2.1.2.2.1.8.<ifIndex>`, ifHCInOctets `1.3.6.1.2.1.31.1.1.1.6.<ifIndex>`, ifHCOutOctets `1.3.6.1.2.1.31.1.1.1.10.<ifIndex>` |
| 호스트 자원 | CPU 사용률, 메모리·디스크의 전체/사용 할당 단위 수 | hrProcessorLoad `1.3.6.1.2.1.25.3.3.1.2.<index>`, hrStorageSize `1.3.6.1.2.1.25.2.3.1.5.<index>`, hrStorageUsed `1.3.6.1.2.1.25.2.3.1.6.<index>` |
| 장비별 항목 | 온도, 팬, 전원 등 장비가 노출하는 값 | 장비의 MIB 문서에 있는 OID를 직접 지정 |
| 알림 | linkDown/linkUp, coldStart/warmStart, 장비별 알림 | Trap/Inform의 trap_oid와 varbinds를 수신·보존 |

인터페이스·스토리지 인덱스는 직접 지정합니다. MIB는 정의를 해석하는 데 사용하며 WALK/GETBULK 기반 자동 탐색은 없습니다. 트래픽 카운터는 누적값이므로 bps는 시간에 따른 차이를 별도로 계산해야 하며, 메모리/디스크 바이트는 hrStorageAllocationUnits와 함께 계산해야 합니다. 제조사별 알림의 의미나 심각도를 자동 해석하지 않습니다.

표준 항목 정의: [SNMPv2-MIB / RFC 3418](https://www.rfc-editor.org/rfc/rfc3418.html), [IF-MIB / RFC 2863](https://www.rfc-editor.org/rfc/rfc2863.html), [HOST-RESOURCES-MIB / RFC 2790](https://www.rfc-editor.org/rfc/rfc2790.html).

## Agent와 공통 metric 저장

ClickHouse telemetry 출력은 지원하는 숫자 OID를 `SnmpHostMetricNormalizer`에서 해석하고 agent와 같은 이름·단위·리소스 label로 저장합니다. 수집 방식은 `source_type=SNMP`로 구분합니다. OID의 사용자 지정 이름을 바꿔도 OID 자체로 정규화합니다. 원래 OID, syntax, status, 원본 값은 raw 이벤트에 보존합니다.

| SNMP 원천 | 공통 metric | 변환 |
| --- | --- | --- |
| UCD `ssCpuRaw*`, `ssCpuNumCpus` | `cpu.usage`, `host.cpu.usage_percent`, `host.cpu.count` | 연속 poll의 CPU counter 차이, idle+iowait 제외. 첫 poll/재시작/카운터 감소는 사용률 생략 |
| UCD `memTotalReal`, `memSysAvail` | `host.memory.total_bytes`, `host.memory.available_bytes`, `memory.usage` | KiB→bytes, `(total-available)/total*100`. free인 `memAvailReal`을 available로 대체하지 않음 |
| UCD `laLoadInt` | `host.load.1/5/15`, `host.load.normalized_1` | 100으로 나누고 CPU 개수로 정규화 |
| IF-MIB `ifHCInOctets`, `ifHCOutOctets` | `host.network.rx_bytes`, `host.network.tx_bytes` | bytes 누적값, `interface`/`direction` label. 같은 인터페이스의 64비트 값을 32비트보다 우선 |
| UCD `dskPath/Device/Total/Avail/Used` | `host.disk.total_bytes/used_bytes/available_bytes/used_percent`, `disk.usage` | 64비트 high/low KiB→bytes. 사용률은 agent의 GNU df처럼 예약 공간을 제외한 used/(used+available)을 올림 |
| HOST-RESOURCES `hrStorage*` | `host.disk.total_bytes/used_bytes/free_bytes` | allocationUnits 곱하기. UCD 값이 있는 마운트는 중복 생성하지 않음. 예약 공간 정보가 없으므로 available/df 사용률은 만들어내지 않음 |
| UCD-DISKIO `diskIONReadX/WrittenX`, `diskIOReads/Writes/BusyTime` | `host.disk.read_bytes/write_bytes/read_ops/write_ops/io_time_ms` | `device` label, BusyTime의 μs→ms. 기존 Manager가 연속 누적값에서 Bps/IOPS/사용률 계산 |
| LM-SENSORS `lmTempSensorsValue` | `host.temperature.celsius` | milli-℃→℃, 센서 label |

CPU의 측정 구간은 SNMP poll 간격입니다. agent의 짧은 샘플 구간과 다를 수 있으므로 같은 시점의 숫자가 완전히 같다는 의미는 아닙니다. 오류·누락·NaN 값은 정상 0으로 저장하지 않습니다. 프로토콜 자체의 정보나 아직 매핑되지 않은 제조사 OID는 기존 `snmp.*` 이름을 유지하며, 공통 자원 지표로 중복 저장하지 않습니다. GET 인덱스 자동 탐색은 여전히 지원하지 않습니다.

## OID를 이름으로 추가하기

**SNMP poller → Targets → OIDs → Common OID**에서 자주 쓰는 시스템·인터페이스·CPU·스토리지 항목을 선택하고 **Add selected OID**를 누릅니다. Pipeline Studio와 Inputs 목록의 편집기에서 동일하게 사용할 수 있습니다.

- `sysName` 같은 시스템 항목은 `.0`이 포함된 전체 OID와 항목명이 자동 입력됩니다.
- `ifHCInOctets`를 선택하고 **Interface index (ifIndex)**에 `3`을 입력하면 `1.3.6.1.2.1.31.1.1.1.6.3`, 항목명 `ifHCInOctets_3`이 추가됩니다. 같은 종류의 다음 항목을 고르면 입력한 인덱스를 유지합니다.
- CPU에는 `hrDeviceIndex`, 스토리지에는 `hrStorageIndex`를 입력합니다. 장비에서 확인한 인덱스를 사용해야 하며 화면에서 자동 탐색하지 않습니다.
- 동일 OID는 중복 추가하지 않습니다. 기존 항목명과 충돌하면 새 항목명에 `_2` 등의 접미사를 붙입니다. 기존 설정은 보존하며, 추가된 이름과 OID는 아래 목록에서 수정할 수 있습니다.
- 제조사 전용 OID나 목록에 없는 항목은 **Add OID**로 직접 입력합니다. OID 목록은 해당 입력의 모든 대상 장비에 공통으로 적용됩니다.

Trap/Inform 수신에는 Polling OID를 지정할 필요가 없습니다. 송신 장비가 보낸 알림의 OID와 varbinds를 수신합니다.

## SNMPv3 Polling

### MIB 업로드와 structured transform 템플릿

1. **SNMP poller → Targets → Import MIB files**를 엽니다. Studio와 Inputs에서 같은 기능을 사용합니다.
2. **MIB files**에 SMIv1/SMIv2 텍스트 파일을 업로드합니다. 제조사 MIB가 다른 모듈을 `IMPORTS`하면 해당 파일도 함께 선택합니다. 최대 16개, 파일당 1 MiB, 합계 4 MiB입니다.
3. 읽기 가능한 `OBJECT-TYPE`의 이름, 숫자 OID, 자료형, 접근 권한, 설명, 단위를 확인합니다. 스칼라는 기본 선택되고 `.0`이 자동 추가됩니다. 테이블·행 자체, 쓰기 전용·알림 전용 객체, Trap/Notification 정의는 Polling 목록에서 제외됩니다.
4. 필요한 테이블 열을 선택하고 **Instance suffix**에 장비에서 확인한 숫자 인스턴스를 입력합니다. 같은 테이블 열끼리는 인덱스를 공유합니다. 복합 인덱스는 `3.7` 같은 완성된 숫자 suffix를 사용합니다. 문자열·IP 주소 인덱스도 SNMP 규칙에 따라 인코딩된 suffix를 입력해야 합니다. MIB에는 실제 장비의 행 번호가 들어 있지 않습니다.
5. **MIB template name**과 **Preview structured transform template**을 확인하고 **Add OIDs and template**을 누릅니다. 템플릿은 즉시 템플릿 저장소에 추가되고 OID는 입력 초안에 병합됩니다. 동일 OID는 기존 이름을 재사용하고 이름 충돌은 접미사로 구분합니다. 템플릿 이름 중복 등 저장 실패 시 OID 초안은 변경하지 않습니다.
6. **Save input**으로 OID 설정을 저장한 뒤 **Structured mapping / Schema Mapping → Mapping template**에서 생성된 템플릿을 선택해 **Apply template**으로 적용합니다. 기존 매핑은 이 적용 단계에서 교체됩니다. 입력 초안을 Discard해도 이미 생성한 템플릿은 남습니다.

템플릿은 관측시각·장비주소·수집상태를 공통 필드에, 선택한 `metrics.<이름>.value`를 `event_snmp`의 `metric_<이름>` 속성에 매핑합니다. 원래 OID·syntax·status·value를 가진 `metrics`도 보존합니다. 원본 JSON에 `JsonParser`를 연결해야 하며, Counter64 문자열과 숫자·문자열 값은 그대로 전달합니다. 단위 환산이나 상태 코드의 의미 해석은 자동 수행하지 않습니다. `event_snmp`는 구조화 JSON의 sub-domain 이름이며 물리 DB 테이블을 자동 생성하지 않습니다. ClickHouse telemetry 출력은 보존된 metrics를 사용합니다.

숫자 OID 이름에 점·슬래시가 포함될 수 있어 생성 템플릿의 sourceField는 JSON Pointer(`/metrics/portBytes_3/value`)를 사용합니다. 이후 OID의 Name을 바꾸면 템플릿의 sourceField도 맞춰 수정해야 합니다.

파싱은 `domain/input/model/snmp/SnmpMibParser`와 jsmiparser를 사용합니다. SNMPv2-SMI, SNMPv2-TC, RFC1155-SMI 및 기본 매크로를 내장하며 나머지 참조 모듈은 함께 업로드해야 합니다. 원본 업로드 파일은 저장하거나 실행하지 않습니다. 참조 파일을 서버·인터넷에서 자동 검색하지 않으며, 해석할 수 없는 참조나 문법 오류가 있으면 오류를 반환합니다. 현재 업로드 기능은 Polling 설정과 템플릿 생성용이며 수신 Trap/Inform의 MIB 이름 변환에는 적용되지 않습니다.

API: `POST /api/v1/snmp/mib/preview`, 본문 `{"files":[{"name":"EXAMPLE-MIB.mib","content":"...SMI text..."}]}`. 반환값은 `modules`, 읽기 가능한 `objects`, `warnings`이며 DB를 변경하지 않습니다. 템플릿은 기존 `POST /api/v1/structure/templates` API로 저장합니다. 예제 파일은 `src/test/resources/snmp/EXAMPLE-MIB.mib`입니다.

### Polling 설정 예시

UI에서 **SNMP poller**를 선택하고 다음 내용을 설정합니다. REST 요청의 `configParams`는 아래 객체를 **JSON 문자열로 직렬화한 값**이어야 합니다.

```json
{
  "version": "3",
  "intervalMs": 60000,
  "retries": 1,
  "securityName": "logparser",
  "securityLevel": "authPriv",
  "authProtocol": "SHA256",
  "authPassphraseEnv": "SNMP_AUTH_PASSPHRASE",
  "privProtocol": "AES128",
  "privPassphraseEnv": "SNMP_PRIV_PASSPHRASE",
  "targets": [{"name": "switch-01", "host": "192.0.2.10", "port": 161}],
  "oids": [{"name": "ifInOctets", "oid": "1.3.6.1.2.1.2.2.1.10.1"}]
}
```

각 장비는 독립 USM/session을 사용하므로 같은 사용자 이름에 서로 다른 비밀번호를 사용할 수 있습니다. 대상별 값이 공통값보다 우선합니다. 대상에 env 참조를 지정하면 공통 직접 비밀번호보다 그 env 참조가 우선합니다. 환경변수는 어댑터 시작 때 읽으며 변경 후 reload가 필요합니다.

인증 방식은 `MD5`, `SHA`/`SHA1`, `SHA224`, `SHA256`, `SHA384`, `SHA512`, 암호화 방식은 `DES`, `AES`/`AES128`, `AES192`, `AES256`입니다. 기본값은 `authPriv`, `SHA256`, `AES128`입니다. `authNoPriv`, `noAuthNoPriv`도 지원합니다. 인증/암호화 passphrase는 필요한 경우 최소 8바이트입니다.

Polling 결과의 `metrics.<name>`은 이제 문자열 대신 다음과 같은 객체입니다. 기존 `metrics.<name>`을 직접 읽는 mapping은 `metrics.<name>.value`를 참조하도록 수정합니다.

```json
{
  "protocol": "snmp",
  "operation": "poll",
  "observed_at": "2026-09-03T00:00:00Z",
  "poll_time": "2026-09-03T00:00:00Z",
  "target_host": "192.0.2.10",
  "poll_status": "success",
  "metrics": {
    "ifInOctets": {"oid": "1.3.6.1.2.1.2.2.1.10.1", "syntax": "Counter32", "status": "success", "value": 12345}
  }
}
```

`noSuchObject`/`noSuchInstance`는 해당 항목의 `status`에 저장하고 `value`를 생략합니다. 일부 OID만 실패하면 `poll_status=partial`, 전부 실패하면 `error`입니다. 통신/PDU 오류는 `error_message`를 갖는 error 이벤트가 됩니다. `Counter64`는 JSON 숫자 정밀도 손실을 막기 위해 10진 문자열과 syntax를 함께 저장하며, 다른 정수형은 숫자, OctetString은 표시 문자열과 `hex`를 함께 보존합니다. ClickHouse의 기존 수치 지표 테이블은 Float64를 사용하므로 큰 정수의 정확한 값은 raw 이벤트를 기준으로 합니다.

## Trap/Inform 수신

UI에서 **SNMP notifications**를 선택합니다. 예: `host=0.0.0.0`, `port=162`, `messagetype=snmp-notifications`, `queueSize=1000`.

```json
{
  "allowedHosts": ["192.0.2.10"],
  "communityEnvs": ["SNMP_COMMUNITY"],
  "engineStatePath": "D:/logparser/data/snmp-receiver.json",
  "users": [
    {
      "securityName": "logparser",
      "securityLevel": "authPriv",
      "authProtocol": "SHA256",
      "authPassphraseEnv": "SNMP_AUTH_PASSPHRASE",
      "privProtocol": "AES128",
      "privPassphraseEnv": "SNMP_PRIV_PASSPHRASE"
    },
    {
      "securityName": "logparser",
      "engineId": "8000000001020304",
      "securityLevel": "authPriv",
      "authProtocol": "SHA256",
      "authPassphraseEnv": "SNMP_AUTH_PASSPHRASE",
      "privProtocol": "AES128",
      "privPassphraseEnv": "SNMP_PRIV_PASSPHRASE"
    }
  ]
}
```

- **Inform**: `users[]`의 `engineId`를 생략합니다. 수신 서버의 engine ID에 인증키가 연결되고 송신 장비가 이를 탐색합니다.
- **Trap**: `users[].engineId`에 **송신 장비**의 실제 engine ID를 지정합니다. 위 hex 값은 예시입니다.
- 같은 엔진/사용자 조합은 중복 등록할 수 없습니다. 같은 사용자 이름을 여러 엔진에 등록할 때 `securityLevel`은 동일해야 합니다.
- `communities`와 `communityEnvs`를 모두 생략하면 v1/v2c 수신은 비활성화됩니다. `public`을 자동 허용하지 않습니다. v3만 사용하려면 예제에서 `communityEnvs`를 제거합니다.

| 필드 | 기본값/동작 |
| --- | --- |
| top-level `host` | `0.0.0.0` |
| top-level `port` | `162`; UDP, 1..65535 |
| top-level `queueSize` | 미지정이면 configParams.queueSize, 그것도 없으면 `1000` |
| `users` | v3 사용자 배열. securityName과 보안 수준에 필요한 인증정보 필수 |
| `communities` | 허용할 v1/v2c community 문자열 배열, 기본 빈 배열 |
| `communityEnvs` | community가 들어 있는 환경변수 이름 배열, 기본 빈 배열 |
| `allowedHosts` | 허용 송신 IP/호스트 배열. 시작 시 해석, 빈 배열은 인증을 통과한 모든 송신자 허용. CIDR 미지원 |
| `engineId` | 수신 서버 engine ID 지정. 생략하면 최초 시작 시 생성. 5..32바이트 hex |
| `engineStatePath` | `${user.home}/logparser/data/snmp/receiver-<adapterId>.json`. ID 없는 설정은 port를 사용 |
| `maxVarBindings` | `256`; 이를 초과하는 메시지는 수락하지 않음 |
| `dedupTtlMs` | `60000`; Inform 재전송 중복 확인 기간 |
| `dedupCacheSize` | `10000`; 넘으면 가장 오래된 중복 확인 항목 제거 |

engine 상태 파일은 v3 사용자가 있을 때 생성하며 engine ID와 boots만 저장합니다. 재시작 시 boots를 증가시키고, 사용 중 파일은 잠금으로 중복 사용을 막습니다. 손상된 파일이나 기존 파일과 다른 engine ID 설정은 시작 오류입니다. 컨테이너에서는 이 파일을 지속 볼륨에 보관합니다. 같은 host/port를 여러 listener가 동시에 바인딩할 수 없습니다.

## 응답과 전달 범위

**Inform 성공 응답은 메모리 큐 수락을 의미합니다. 최종 DB 저장이나 재시작 후 복구를 보장하지 않습니다.**

- 큐가 가득 차면 Inform을 저장하거나 성공 응답하지 않습니다. 송신 장비는 자신의 timeout/retry 설정으로 재전송할 수 있습니다.
- 수락했던 Inform이 제한된 중복 확인 기간/용량 안에 재전송되면 이벤트는 다시 만들지 않고 응답을 다시 보냅니다. 송신 주소/포트, 보안 주체, request ID를 포함한 PDU 내용으로 비교합니다.
- Trap에는 응답하지 않습니다. 수신 전 네트워크 유실, 큐 포화, 프로세스 종료에 따른 메모리 데이터 유실은 복구하지 않습니다.
- ACK 후 파이프라인 처리 또는 출력이 실패해도 Inform 응답을 취소할 수 없습니다.
- 수신 콜백은 프로토콜 해석과 큐 전달만 수행합니다. 별도 DB, 디스크 spool, Vault 클라이언트, 스케줄러 서비스는 추가하지 않습니다.

알림 JSON에는 `operation=trap|inform`, `received_at`, `observed_at`, `target_host`, `source_port`, `version`, `request_id`, `trap_oid`, `uptime_ticks`, `varbinds[]`가 포함됩니다. v3에서는 보안 수준/사용자와 context도 포함합니다. 인증 비밀번호와 community는 이벤트에 포함하지 않습니다. v1 enterprise-specific Trap은 enterprise OID와 generic/specific 번호도 보존합니다.

v3의 `context_engine_id`는 PDU의 context 값입니다. Inform에는 수신 서버의 authoritative engine ID를 `engine_id`로 추가합니다. SNMP4J가 Trap 콜백에는 이 응답 상태를 제공하지 않으므로 Trap에서 `engine_id`는 생략하며, context 값으로 추정하지 않습니다.

ClickHouse 출력의 telemetry 저장이 켜져 있으면 Polling 수치를 지표로 저장하고, 알림을 `snmp.trap`/`snmp.inform` 이벤트로 저장합니다. 알림의 초기 severity는 `INFO`이며 장비별 해석은 후속 처리 규칙으로 구성합니다.

## 검증

`SnmpTransportTest`는 loopback UDP로 v1/v2c/v3 Polling, v1 Trap, v2c/v3 Trap/Inform, v3 세 보안 수준, 잘못된 인증과 downgrade 차단, 큐 포화, Inform 재전송을 검증합니다. `SnmpMigrationTest`는 신규 DB와 V8에서 V9로의 타입 허용 변경을 검증합니다. 실제 장비별 engine ID/알고리즘 호환성은 운영 대상에서 추가 확인합니다.
