# Logparser parser/transform Processing Steps 구현 계획

> 구현 상태: 백엔드·프론트엔드·migration·테스트·문서 반영 완료. `.\gradlew test`와 `.\gradlew build` 통과. 브라우저 기반 UI smoke는 별도 실행하지 않음.

## 목표

Logparser의 parser와 transform을 하나의 processing step 목록으로 실행하고, 같은 `messagetype` 안에서 두 종류의 순서를 설정할 수 있게 한다. Parser는 기존처럼 기본적으로 `originalText`를 사용하되, 선택한 event field를 parser 입력으로 사용할 수 있게 한다.

구현 범위는 기존 Java/Spring Boot 백엔드와 현재 정적 Pipeline Studio UI로 한정한다. 별도 step 테이블, 범용 workflow 엔진, 새 프런트엔드 프레임워크는 도입하지 않는다.

## 현재 구현에서 유지할 계약

- `messagetype`은 input/parser/transform/output을 연결하는 키로 유지한다.
- `priority` 컬럼을 parser와 transform의 공통 step order로 재사용한다. 낮은 값이 먼저 실행된다.
- `enabled=false`인 구성은 저장하지만 런타임 step 목록에서는 제외한다.
- Structured Transform은 configurable processing step 뒤, output 앞에서 계속 실행한다.
- Parser의 `sourceField`가 비어 있으면 기존 parser 구현을 그대로 `originalText`에 적용한다.
- Transform이 `false`를 반환하면 이벤트를 drop한다.

### 의도적인 실행 의미 변경

기존 `ParseService`는 parser 후보 중 첫 성공 parser에서 종료했다. 통합 step 실행에서는 각 parser가 하나의 step이므로 성공해도 다음 step으로 진행한다. Parser 실패 시에는 기존 `continueOnFailure`를 적용한다.

- `true`: 다음 processing step으로 진행
- `false`: 해당 이벤트를 실패 처리하고 후속 step을 실행하지 않음

기존 설정을 최대한 보존하기 위해 migration에서 parser를 먼저, transform을 나중에 배치한다. 이후 사용자가 parser/transform을 교차 배치하면 새 순서가 적용된다.

## 데이터 및 순서 모델

별도 `pipeline_steps` 테이블은 만들지 않는다. 현재 `parsers.priority`와 `transforms.priority`를 공통 순서 값으로 사용한다.

Parser에 다음 필드를 추가한다.

```text
sourceField VARCHAR(255) NULL
```

`sourceField`는 현재 `LogEvent.fields`의 top-level key로 해석한다. 점 표기 nested path는 이번 범위에서 지원하지 않는다.

입력 값 변환 규칙:

- 문자열: 그대로 전달
- 숫자/boolean: 문자열로 변환
- Map/List: Jackson JSON 문자열로 직렬화
- 미존재/null: parser 실패

Parser 결과는 기존과 같이 event의 최상위 field map에 병합한다. 동일 key가 있으면 parser 결과가 덮어쓴다.

## 작업 순서

### Task 1. DB migration과 설정 모델 확장

**Files:**

- Create: `logparser/src/main/resources/db/migration/V8__Add_parser_source_field_and_processing_order.sql`
- Modify: `logparser/src/main/java/org/keinus/logparser/infrastructure/persistence/entity/ParserEntity.java`
- Modify: `logparser/src/main/java/org/keinus/logparser/domain/configuration/model/ParserAdapterConfig.java`
- Modify: `logparser/src/main/java/org/keinus/logparser/domain/configuration/service/DatabaseConfigLoader.java`
- Modify: `logparser/src/main/java/org/keinus/logparser/domain/configuration/service/ConfigManagementService.java`
- Modify: `logparser/src/main/java/org/keinus/logparser/domain/configuration/service/ConfigMetadataService.java`
- Modify: `logparser/src/main/java/org/keinus/logparser/domain/configuration/service/ConfigValidationService.java`

- [ ] `parsers.source_field` 컬럼을 nullable로 추가한다.
- [ ] parser entity/config/DB loader/conversion 경로에서 `sourceField`를 왕복 보존한다.
- [ ] parser metadata schema에 `sourceField`를 optional field로 추가한다.
- [ ] source field는 trim 후 blank를 null로 취급하고, 최대 길이와 허용 문자를 검증한다.
- [ ] parser priority의 음수만 거부하고 기존 100 상한은 제거한다. step 수가 늘어날 때 UI가 임의로 실패하지 않도록 한다.
- [ ] 기존 데이터의 message type별 priority를 migration에서 재번호화한다.
  - parser는 기존 priority/id 순서를 유지해 먼저 배치
  - transform은 기존 priority/id 순서를 유지해 parser 뒤에 배치
  - 순서는 `10, 20, 30...` 간격으로 저장
- [ ] `(messagetype, priority)` 조회 인덱스를 추가한다.

### Task 2. Parser field 입력 처리와 단일 step 실행 API 정리

**Files:**

- Modify: `logparser/src/main/java/org/keinus/logparser/domain/parse/service/ParseService.java`
- Modify: `logparser/src/main/java/org/keinus/logparser/domain/transformation/service/TransformService.java`
- Create or modify focused unit tests under `logparser/src/test/java/org/keinus/logparser/domain/parse/service/`

- [ ] parser 설정으로부터 초기화된 parser 인스턴스와 `sourceField`, `continueOnFailure`를 함께 보관한다.
- [ ] `sourceField`가 없을 때는 기존 `IParser.parse(LogEvent)`를 그대로 호출한다.
- [ ] `sourceField`가 있을 때는 field 값을 문자열화한 임시 `LogEvent`에 기존 parser를 실행하고, 성공 결과만 원래 event에 병합한다.
- [ ] field parser 실패가 `continueOnFailure=true`일 때 원래 event를 ERROR 상태로 오염시키지 않도록 한다.
- [ ] 기존 `parse(LogEvent)`와 `transform(LogEvent)`는 기존 테스트/호환 호출을 위해 유지하되, 통합 실행기가 사용할 수 있는 단일 binding 실행 메서드를 제공한다.
- [ ] 기존 parser 구현체 6개의 parsing contract 자체는 변경하지 않는다.

### Task 3. 공통 Processing Step 실행기 추가

**Files:**

- Create: `logparser/src/main/java/org/keinus/logparser/application/pipeline/ProcessingStepService.java`
- Modify: `logparser/src/main/java/org/keinus/logparser/application/pipeline/ProcessingDispatcher.java`
- Modify: `logparser/src/main/java/org/keinus/logparser/application/pipeline/MessageDispatcher.java` (생성자 의존성만 필요한 경우)
- Modify: `logparser/src/main/java/org/keinus/logparser/application/pipeline/PipelineReloadService.java`
- Modify: `logparser/src/main/java/org/keinus/logparser/application/pipeline/PipelineConfigEventListener.java`

- [ ] parser/transform 설정을 `messagetype`별 단일 immutable step chain으로 만든다.
- [ ] 정렬 순서는 `priority`, 중복 시 `kind(PARSER 우선)`, `id`로 고정한다.
- [ ] reload 시 parser와 transform 목록을 함께 읽고 하나의 snapshot으로 교체한다.
- [ ] parser/transform CRUD 이벤트가 발생하면 개별 서비스만 reload하지 않고 공통 chain을 한 번 reload한다.
- [ ] `ProcessingDispatcher`는 기존 `parse → transform` 두 호출 대신 통합 chain을 한 번 실행한다.
- [ ] 모든 step을 통과한 이벤트만 기존 Structured Transform과 output 단계로 전달한다.
- [ ] 처리 상태, drop/failed/processed metric, Live Tail 동작은 기존 의미를 유지한다.

### Task 4. 순서 변경 API 추가

**Files:**

- Modify: `logparser/src/main/java/org/keinus/logparser/interfaces/controller/PipelineController.java`
- Create: `logparser/src/main/java/org/keinus/logparser/interfaces/dto/request/ProcessingStepOrderRequest.java`
- Modify: `logparser/src/main/java/org/keinus/logparser/domain/configuration/service/ConfigManagementService.java`
- Modify: `logparser/src/main/java/org/keinus/logparser/interfaces/dto/response/PipelineTopologyDto.java` (step kind/source field 표시가 필요한 경우)

추가 API:

```http
PUT /api/v1/pipeline/{messageType}/processing-steps/order
```

요청 예:

```json
{
  "steps": [
    {"kind": "PARSER", "id": 3},
    {"kind": "TRANSFORM", "id": 7},
    {"kind": "PARSER", "id": 4}
  ]
}
```

- [ ] 요청 목록의 중복/누락/다른 message type step을 검증한다.
- [ ] parser와 transform 양쪽 priority를 하나의 transaction에서 재번호화한다.
- [ ] 성공 시 configuration change event를 한 번만 발행한다.
- [ ] 기존 개별 priority endpoint는 호환성을 위해 유지하되, UI에서는 새 batch API를 사용한다.
- [ ] 동시 수정으로 step 목록이 달라진 경우 409 응답과 함께 UI가 최신 설정을 다시 읽게 한다.

### Task 5. 백엔드 테스트

**Files:**

- Modify: `logparser/src/test/java/org/keinus/logparser/domain/parse/service/ParseServiceTest.java`
- Create: `logparser/src/test/java/org/keinus/logparser/application/pipeline/ProcessingStepServiceTest.java`
- Modify: `logparser/src/test/java/org/keinus/logparser/application/pipeline/ProcessingDispatcherTest.java`
- Modify or create controller/configuration tests under `logparser/src/test/java/org/keinus/logparser/`

- [ ] raw 기본 parser가 기존 결과를 유지하는지 검증한다.
- [ ] field 선택 parser가 문자열/숫자/Map/List를 올바르게 입력받는지 검증한다.
- [ ] parser → transform → parser(field) 순서가 실제 event field 변화에 반영되는지 검증한다.
- [ ] transform → parser 순서도 priority에 따라 실행되는지 검증한다.
- [ ] parser 실패와 `continueOnFailure` 조합을 검증한다.
- [ ] transform drop 뒤 후속 step과 output이 실행되지 않는지 검증한다.
- [ ] migration 후 기존 parser-first 순서와 sourceField null 기본값을 검증한다.
- [ ] batch reorder가 양쪽 테이블을 원자적으로 갱신하고 reload event를 한 번 발행하는지 검증한다.

### Task 6. Pipeline Studio UI 변경

**Files:**

- Modify: `logparser/src/main/resources/static/js/api.js`
- Modify: `logparser/src/main/resources/static/js/pipeline-studio.js`
- Modify: `logparser/src/main/resources/static/css/pipeline-studio.css`
- Modify: `logparser/src/main/resources/static/js/app.js` (legacy parser editor가 metadata를 표시하지 못하는 경우에만)

- [ ] parser와 transform을 하나의 `Processing Steps` rail/list로 표시한다.
- [ ] 각 카드에 parser/transform 종류, 현재 order, enabled 상태를 표시한다.
- [ ] parser와 transform 사이의 drag-and-drop을 허용한다.
- [ ] drag 결과는 새 batch order API 한 번으로 저장한다.
- [ ] drag 실패/409 시 서버 목록을 다시 읽고 사용자에게 오류를 표시한다.
- [ ] parser editor에 `Source field` optional combobox를 추가한다.
  - 기본 선택: `Raw event (originalText)`
  - sample 및 앞선 preview 결과의 top-level key를 선택지로 제공
  - 목록에 없는 field는 직접 입력 가능
- [ ] parser 카드 요약에 raw/field 입력 대상을 표시한다.
- [ ] step preview/simulation이 parser와 transform을 공통 order로 순회하도록 수정한다.
- [ ] Structured Transform과 Output은 기존 고정 위치를 유지한다.
- [ ] 별도 UI 상태 관리 라이브러리나 프런트엔드 빌드 체계는 추가하지 않는다.

### Task 7. 문서 및 최종 검증

**Files:**

- Modify: `logparser/README.md`
- Modify: `logparser/readme/logparser_schema.md`
- Modify: `logparser/readme/logparser-user-manual.md`

- [ ] pipeline 순서를 `Input → Processing Steps → Structured Transform → Output`으로 갱신한다.
- [ ] parser `sourceField` payload와 입력 변환 규칙을 문서화한다.
- [ ] `priority`가 parser/transform 공통 order라는 점과 batch reorder API를 문서화한다.
- [ ] `continueOnFailure`가 다음 parser가 아니라 다음 processing step으로 진행한다는 점을 명시한다.
- [ ] 다음 명령을 실행한다.

```powershell
cd D:\study\castrelyx\logparser
.\gradlew test
.\gradlew build
```

- [ ] 애플리케이션을 실행해 다음 UI smoke flow를 확인한다.
  1. message type 선택
  2. parser와 transform이 하나의 목록에 표시됨
  3. parser를 transform 앞/뒤로 이동
  4. 저장 후 새로고침해 순서 유지
  5. parser source field 저장 및 preview 확인
  6. 실제 event가 설정된 순서로 처리되는지 확인

## 완료 기준

- 같은 message type에서 parser와 transform을 원하는 순서로 배치할 수 있다.
- 저장한 순서가 DB reload와 애플리케이션 재시작 후에도 유지된다.
- sourceField 미설정 parser는 기존 raw parsing 결과와 동일하게 동작한다.
- sourceField 지정 parser는 앞선 step이 만든 field를 입력으로 사용할 수 있다.
- parser 실패, transform drop, output 전달의 기존 metric/상태 의미가 깨지지 않는다.
- 기존 설정 migration이 실패하지 않고 parser-first 초기 순서를 보존한다.
- 전체 Gradle test/build와 UI smoke 검증이 통과한다.

## 비범위

- 별도 workflow/step 테이블 및 버전별 step graph
- nested path 문법(JSONPath/SpEL)과 배열 요소별 parser 실행
- parser 결과를 source field 하위에 자동 중첩 저장하는 기능
- transform 자체의 field 선택 입력 기능
- parser/transform 전용 신규 테스트 서버 또는 프런트엔드 프레임워크 도입
