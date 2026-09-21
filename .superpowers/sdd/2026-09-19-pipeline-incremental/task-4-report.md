# Task 4 리포트 — SQL 스텝 REPLACE 원자화 (API)

## 요약

REPLACE 전략의 SQL 자동 적재(SELECT → INSERT INTO ... SELECT)에서 출력 비우기(DELETE)와
INSERT 를 Task 3 이 만든 `preStatements` 로 같은 executor 요청/트랜잭션에 실어 보내도록 바꿨다.
실행기 꺼진 경로(`SqlScriptExecutor`)도 같은 계약을 따른다. 실측 결과 `DataSchema.qualify()` 는
executor 의 선행 문장 화이트리스트가 요구하는 형태(스키마·테이블 양쪽 인용)를 만들지 않아서,
`OutputClearStatement` 는 `qualify()` 를 거치지 않고 직접 인용해 조립하도록 구현했다.

## `DataSchema.qualify` 실측 결과

`qualify()` 는 `current() + "." + "\"" + identifier(이스케이프) + "\""` — 즉 **스키마 부분은
인용하지 않고**(`data`+점+인용된 테이블명) 테이블명만 인용한다. 이는
`DataSchemaResolutionTest.qualify_escapesEmbeddedDoubleQuote` 가 이미 그 정확한 형태(스키마
미인용)를 계약으로 고정하고 있어 확인했다(`apps/firehub-api/src/test/java/com/smartfirehub/tenant/DataSchemaResolutionTest.java:357-361`).

firehub-executor 의 선행 문장 화이트리스트(`apps/firehub-executor/app/validators/sql_validator.py:53`)는
`^DELETE FROM "(data|data_t\d+)"\."[a-z0-9_]+"$` 로 **스키마·테이블 양쪽 모두** 인용을 요구한다 —
`qualify()` 의 출력과 일치하지 않는다(`data."tbl"` vs `"data"."tbl"`).

**결론(인용 정정 — Fix round 1, 리뷰 지적 4)**: 브리프(task-4-brief.md:89)가 실제로 지시한 것은
그 반대다 — "`DataSchema.qualify` 의 출력이 `"data"."t"` 형태(양쪽 인용)인지 확인한다. 다르면
Task 3 의 파이썬 정규식을 그 형태에 맞춘다", 즉 불일치 시 **executor 쪽 정규식을 고치라**는
지시였다. 내가 처음 요약에 "executor 가 아니라 builder 를 고쳐라"라고 적은 것은 이 문장을 거꾸로
옮긴 것이다.

실제로는 코디네이터가 부여한 상위 계약(작업 브리핑의 "Things the brief cannot know" 절)이 브리프의
이 문장을 덮어썼다: "`OutputClearStatement.deleteAll` 이 만드는 문자열이 executor 화이트리스트와
정확히 일치해야 하고, 불일치하면 **builder(실행기가 아니라)를 고쳐라**"라는 것이 그 상위 지시였다.
그래서 내 선택(빌더를 고친다)은 그 구속력 있는 계약 아래서는 옳았지만, 그 근거를 브리프 자체의
문장으로 잘못 인용했다. 이번에 인용을 바로잡는다.

`DataSchema.qualify()` 자체를 고치지 않은 이유는 그대로다 — 그 출력 형태를 계약으로 고정한 기존
테스트(`DataSchemaResolutionTest` 의 두 단언, 그리고 `qualify()` 를 쓰는 다른 모든 SQL 조립 지점의
눈에 보이는 리터럴 형태)가 전부 깨진다. `OutputClearStatement.deleteAll()` 은 `DataSchema.current()`
로 스키마 식별자만 얻고(스키마명을 "유일한 지점에서 얻는다"는 규약은 유지), 스키마·테이블 양쪽을
직접 인용해 조립한다.

## 임시 데이터셋(temp dataset) truncate 결정

`PipelineAsyncRunner` 의 SQL 분기가 SELECT 이고 `outputDatasetId` 가 없을 때 자동 생성/재사용하는
"임시 데이터셋"(`ptmp_*`, `origin_type=TEMP`)의 truncate 호출(원래 라인 377)도 **DELETE 선행 문장으로
바꿨다** — truncate 로 남겨두지 않았다.

판단 기준: "실패 시 사용자에게 보이는 데이터셋이 빈 채로 남는가?" 임시 데이터셋은 매 실행마다
새로 만들어지는 게 아니라 `TempDatasetService.findExistingTempDataset` 로 **재사용**된다(스키마가
바뀔 때만 재생성). 즉 안정된 식별자를 가진 실제 물리 테이블이고, `origin_type=TEMP` 라는 태그가
붙을 뿐 데이터셋 목록·상세·다른 스텝의 `{{#N}}` 참조에서 여전히 조회 가능하다 — 일반 출력
데이터셋과 같은 원자성 문제(비우기 커밋 → INSERT 실패 → 빈 테이블)를 그대로 겪는다. 그래서
"버려지는 임시물이라 보장이 필요 없다"는 예외에 해당하지 않는다고 판단했다.

반면 API_CALL/AI_CLASSIFY 스텝의 임시 데이터셋 truncate(현재 파일 기준 `PipelineAsyncRunner.java:633`,
`:758`)는 **손대지 않았다** — 이 스텝들은 `executorClient.executeApiCall`/`aiClassifyExecutor.execute`
로 실행되고, Task 3 의 `preStatements` 는 `/execute/sql` 전용이라 이 경로에는 애초에 실어 보낼 곳이
없다. 이 Task 의 파일 범위(`:299-322`, `:325-462`, `:382`)에도 SQL 분기만 포함돼 있어 범위 밖이다.

**정정(Fix round 1, 리뷰 지적 3) — "이미 원자적"이라는 근거가 절반 틀렸다.** 애초 리포트는 이
truncate 를 그냥 두는 근거로 "temp-table swap 방식으로 이미 원자성이 있다"고 적었는데, 이건
사실이 아니다.

- **API_CALL**(`PipelineAsyncRunner.java:633`): `outputDatasetId` 가 없어 임시 데이터셋을 새로
  만들거나 재사용한 **직후, 스왑 로직보다 먼저** truncate 가 무조건 실행된다. `executorEnabled`
  경로의 REPLACE 스왑(`:649-666` — `createTempTable` → executor 호출 → 성공 시 `finishReplace`,
  실패 시 `dropTempTable`)은 그 뒤에 별도의 임시 테이블(`{table}_tmp`)을 만들어 작업하고 성공할
  때만 원본과 맞바꾸므로 스왑 자체는 맞지만, **그 스왑이 시작되기도 전에 원본이 이미 비어 있다** —
  API 호출이 실패하면(또는 `executorEnabled=false` 라 스왑 경로 자체를 안 타는 `apiCallExecutor.execute`
  로 빠지는 경우도) 원본 임시 데이터셋은 truncate 된 채로 남는다.
- **AI_CLASSIFY**(`PipelineAsyncRunner.java:758`): 이쪽은 애초에 이 파일 안에 스왑 로직 자체가
  없다 — truncate 직후 곧바로 `aiClassifyExecutor.execute(...)` 에 전권을 넘긴다("AI_CLASSIFY는
  자체적으로 출력 행 수를 관리"). 그 실행기 내부가 원자적인지는 이 Task 의 조사 범위 밖이지만,
  적어도 `PipelineAsyncRunner` 레벨에서는 truncate 와 실제 적재 사이에 원자성을 보장하는 장치가
  전혀 없다 — API_CALL 과 같은 "먼저 비우고 나중에 실패할 수 있다" 형태다.

두 경우 모두 Task 4 가 SQL 스텝에서 고친 것과 같은 계열의 결함이다. API_CALL 의 truncate 는
스왑이 성공 시 원본을 통째로 대체하므로 결과적으로 **불필요해 보이기까지 한다** — 스왑 전에
비울 이유가 없다.

맞는 절반은 유지한다: `/execute/api-call`·`/execute/python` 엔드포인트는 Task 3 의
`preStatements` 를 받지 않으므로 이 Task 의 방식(DELETE 선행 문장)을 그대로 이식할 수는 없다.

**결정은 바꾸지 않는다** — 이 truncate 를 고치는 것은 이번 Task 의 파일 범위 밖이라 손대지
않았고, 위 결함은 후속 이슈로 남긴다(아래 "남은 우려 사항" 참조).

## 파일별 변경 근거

- **`OutputClearStatement.java`(신규)** — 브리프 명세대로 `deleteAll(tableName)` 을 제공하되, 위
  실측에 따라 `DataSchema.qualify()` 를 거치지 않고 스키마·테이블을 직접 인용해 조립한다. Javadoc
  에 `data."` 리터럴을 그대로 적으면 `DataSchemaResolutionTest.noProductionSourceOutsideDataSchemaHoldsTheLiteral`
  가드(프로덕션 소스에 물리 스키마 리터럴이 있으면 실패)가 주석까지 스캔해 걸리므로, 예시 문자열을
  풀어 써서 피했다(실제로 한 번 걸렸다가 고침 — 아래 테스트 증거 참조).
- **`ExecutorClient.java`** — `executeSql(String, List<String>)` 추가, HTTP 바디에
  `"preStatements"` 키(camelCase, executor 쪽 Pydantic alias)로 실어 보낸다. 기존
  `executeSql(String)` 은 `executeSql(query, List.of())` 위임으로 바꿨다.
- **`SqlScriptExecutor.java`** — `execute(List<String>, String)` 추가: `sqlValidator.validate`
  이후 각 선행 문장이 `DELETE FROM ` 로 시작하고 세미콜론이 없는지 방어 검증한 뒤, 같은
  `dsl.transaction` 안에서 `SET LOCAL search_path` → 선행 문장들 → 본 스크립트 순서로 실행한다.
  기존 `execute(String)` 은 `execute(List.of(), script)` 위임으로 바꿨다.
- **`PipelineAsyncRunner.java`**
  - `:301`(현재 라인 304) 조건에 `&& !"SQL".equals(step.scriptType())` 추가 — SQL 스텝의 비우기
    결정은 SQL 분기(isSelect 판단 이후)로 전부 옮겼다.
  - SQL 분기 시작에 `List<String> preStatements = new ArrayList<>();` 선언.
  - 임시 데이터셋 생성 블록의 즉시 truncate 호출을 제거하고(위 "temp dataset" 절 참조), 그 직후
    outputTableName 이 확정된 지점에 "출력 비우기 결정" 블록을 하나로 모았다: REPLACE + SELECT →
    `preStatements.add(OutputClearStatement.deleteAll(outputTableName))`, REPLACE + 비SELECT(사용자
    DML) → 기존처럼 `dataTableRowService.truncateTable(outputTableName)` 즉시 호출(사용자가 직접
    쓴 INSERT/UPDATE/DELETE 는 기존 동작 유지 — 브리프 명시).
  - 두 실행 호출 지점(`:443-450`)을 `executorClient.executeSql(sql, preStatements)` /
    `sqlExecutor.execute(preStatements, sql)` 로 바꿨다(SELECT 래핑 경로·비SELECT 경로 둘 다,
    비SELECT 경로는 preStatements 가 항상 빈 목록이지만 시그니처를 맞추기 위해 그대로 전달).
- **`PipelineAsyncRunnerTest.java`** — 기존 테스트는 전부 `sqlExecutor.execute(String)` 1-인자
  목(mock) 스텁·검증이었는데, 프로덕션 호출부가 2-인자로 바뀌면서 그 스텁들이 더 이상 트리거되지
  않아(mock 은 실제 위임 로직을 타지 않는다) **전부 2-인자 형태로 고쳐야 했다** — 브리프의
  "기존 호출부를 업데이트하지 않는다"는 지시는 "이 Task 가 안 건드리는 다른 모듈의 호출부"를 뜻하는
  것으로 해석했다(`PipelineAsyncRunner` 자체의 두 호출부는 브리프가 명시적으로 바꾸라고 한
  지점이라 그 파급이 테스트에도 미친다). 새 테스트 3개를 브리프 Step 1 명세대로 추가했다.
- **`SqlScriptExecutorSandboxTest.java`** — 브리프 Step 1 의 DB 원자성 테스트를 추가했다. 이
  클래스는 파일 관례상 클래스 레벨 `@Transactional` 을 쓰지 않으므로(=이미 롤백 없는 자동 커밋
  경로), 별도로 `NOT_SUPPORTED` 를 붙일 필요가 없었다 — 기존 1행은 `dataTableService.createTable`
  + `dsl.execute(INSERT...)` 로 즉시 커밋되는 경로로 심고, `finally` 로 테이블을 정리한다.

## 테스트 증거

- `./gradlew cleanTest test --tests 'com.smartfirehub.pipeline.service.PipelineAsyncRunnerTest' --tests 'com.smartfirehub.pipeline.service.SqlScriptExecutorSandboxTest'`
  → `PipelineAsyncRunnerTest`: 30/30 통과, `SqlScriptExecutorSandboxTest`: 13/13 통과.
- 전체 백엔드 스위트: `./gradlew cleanTest test` → **2744 테스트, 0 실패, 1 skip**(스킵은
  `ApiConnectionServiceExtTest` 의 기존 스킵 케이스로 이 Task 와 무관).
- 도중 `DataSchemaResolutionTest.noProductionSourceOutsideDataSchemaHoldsTheLiteral` 이
  `OutputClearStatement.java` 의 Javadoc 예시 문자열(`data."tbl"`) 때문에 한 번 실패했다 —
  물리 스키마 리터럴을 프로덕션 코드(주석 포함)에서 금지하는 가드다. Javadoc 을 리터럴 없는
  설명으로 바꿔 해결했고, 재실행으로 초록 확인.

## 변이(mutation) 테스트 — 비공허성 증명

### Mutation A — `PipelineAsyncRunner` 를 옛 2-요청 동작(즉시 truncate + 1-인자 execute)으로 되돌림

`git show HEAD:.../PipelineAsyncRunner.java` (Task 4 이전 커밋)로 되돌려 새 테스트 3개만 포함한
테스트 파일과 함께 실행:

```
30 tests completed, 15 failed
```

실패 목록에 새로 추가한 3개 테스트가 모두 포함됨:
- `REPLACE_SELECT_SQL은_truncate하지_않고_DELETE를_선행문장으로_같은_요청에_보낸다()`
- `APPEND_SELECT_SQL은_선행문장이_없다()`
- `REPLACE_SELECT_SQL_실행기_꺼진_경로도_DELETE를_선행문장으로_같은_요청에_보낸다()`

(나머지 12개는 프로덕션 호출부가 1-인자로 되돌아가면서 2-인자로 고친 다른 기존 테스트들의 스텁이
트리거되지 않아 같이 빨개진 것 — 이 또한 그 테스트들이 실제로 프로덕션 호출부를 검증하고 있다는
증거다.)

파일을 새 버전으로 복원 후 재실행 → 30/30 통과 확인.

### Mutation B — `SqlScriptExecutor.execute(List, String)` 에서 선행 문장을 트랜잭션 밖(autocommit)으로 먼저 실행하도록 되돌림

```java
// MUTATION: 선행 문장을 본 트랜잭션 밖에서 먼저 실행 — 원래 결함(비우기 커밋 → INSERT 별도) 재현
for (String preStatement : preStatements) {
  tenantDsl.execute("SET search_path = '" + DataSchema.current() + "'");
  tenantDsl.execute(preStatement);
}
tenantDsl.transaction(cfg -> {
  cfg.dsl().execute("SET LOCAL search_path = '" + DataSchema.current() + "'");
  cfg.dsl().execute(scriptContent);
});
```

실행 결과:

```
13 tests completed, 1 failed
선행_DELETE_후_INSERT가_실패하면_기존_행이_남는다()
org.opentest4j.AssertionFailedError:
expected: 1
 but was: 0
```

정확히 새로 추가한 DB 원자성 테스트만 실패했고, 실패 양상(0행)도 원래 결함이 재현될 때 예상되는
그대로다. 파일을 원래 버전으로 복원 후 재실행 → 13/13 통과 확인.

## 이 Task 가 브리프 파일 목록 밖에서 건드린 것

- `OutputClearStatement.java` 의 Javadoc — `DataSchemaResolutionTest` 가드 대응(위 참조). 프로덕션
  로직 변경 아님.
- `DataSchemaResolutionTest.java`(`HAND_ASSEMBLY_PINS`) — Fix round 1 에서 `SqlScriptExecutor` 에
  선행 문장 검증용 접두어 조립을 추가하면서 "스키마명을 손으로 조립하는 곳" 가드가 새로 걸렸다
  (아래 Fix round 1 절 참조). 그 가드에 정당한 예외 사이트를 하나 더 등록했다 — 규칙 자체나
  기존 핀은 건드리지 않았다.
- 그 외에는 브리프가 지정한 파일만 수정했다. `DataSchema.java` 자체는 건드리지 않았다(위 설계
  결정 참조).

## Fix round 1 (코드리뷰 반영)

1. **`PipelineAsyncRunner.java` — 알 수 없는 `loadStrategy` 회귀.** Task 4 이전에는 상단 switch 의
   `default` 분기가 REPLACE 도 APPEND 도 아닌 값을 경고 로그와 함께 REPLACE 로 폴백해 truncate
   했다. SQL 스텝을 그 switch 밖으로 빼면서 새 판단 블록이 `"REPLACE".equalsIgnoreCase(loadStrategy)`
   만 보게 됐는데, `loadStrategy` 컬럼에는 서버 쪽 enum·체크 제약이 없다(`PipelineStepRepository`
   는 null 만 "REPLACE" 로 매핑) — 즉 임의 문자열이 실제로 들어올 수 있고, 그 경우 아무것도
   비우지 않은 채 매 실행마다 행이 쌓이는 조용한 회귀였다. **"APPEND 가 아니면 REPLACE"** 로
   판단을 바꾸고(알 수 없는 값은 경고 로그 유지), 회귀 테스트
   `알수없는_loadStrategy의_SQL_SELECT_스텝도_REPLACE로_취급해_DELETE_선행문장을_보낸다` 를
   추가했다.
2. **`SqlScriptExecutor.java` — 선행 문장 검증 강화.** 기존 검증(`DELETE FROM ` 접두어 + 세미콜론
   없음)은 executor 의 화이트리스트보다 느슨해, 이 경로에 두 번째 호출부가 생기면 임의의
   WHERE 절 붙은 DELETE 를 테넌트 파이프라인 롤 권한으로 실행할 길이 열려 있었다. 접두어를
   `"DELETE FROM \"" + DataSchema.current() + "\".\""` 로 직접 조립해 스키마까지 고정하고(파이썬
   정규식 리터럴을 그대로 옮기면 `noProductionSourceOutsideDataSchemaHoldsTheLiteral` 가드에
   걸리므로 지시대로 회피), 나머지 부분은 `[a-z0-9_]+"` 전체 일치로 제한했다 — `DataTableService.validateName`
   이 기존 `truncateTable` 경로에서 하던 문자셋 방어를 이 경로에도 되살린 것이다. 이 조립이
   `DataSchemaResolutionTest` 의 "손으로 조립 금지" 가드에 새로 걸려, 정당한 예외로 핀 하나를
   추가했다(위 참조). 회귀 테스트 `형식에_맞지_않는_선행_문장은_본_스크립트_실행_전에_거부된다`
   (WHERE 절 붙은 문장이 거부되고, 본 INSERT 도 전혀 실행되지 않았음을 행 수 0 으로 확인)를
   `SqlScriptExecutorSandboxTest` 에 추가했다.
3. **리포트 정정 — API_CALL/AI_CLASSIFY "이미 원자적" 주장.** 위 "임시 데이터셋 truncate 결정"
   절에 정정 내용을 추가했다 — 실제로는 두 스텝 타입 모두 truncate 가 스왑/실행보다 먼저 커밋돼
   실패 시 임시 데이터셋이 빈 채로 남을 수 있다. 고치지는 않았고(범위 밖) 후속 이슈로 남긴다.
4. **리포트 정정 — 브리프 인용 방향.** 위 "`DataSchema.qualify` 실측 결과" 절의 결론 문단을
   정정했다 — 브리프(line 89)는 "불일치 시 executor 정규식을 고쳐라"였고, "builder 를 고쳐라"는
   내가 잘못 인용한 것이었다. 실제 근거는 코디네이터가 부여한 상위 계약이었다.
- 선택 항목(비용이 낮아 반영): `OutputClearStatement.deleteAll` 이 접미사 붙은 테넌트 스키마
  (`data_t12`)에서도 양쪽을 인용하는 정확한 문자열을 `DataSchemaTenantResolutionTest` 에
  `deleteAll은_접미사_붙은_테넌트_스키마도_양쪽_인용한다` 로 못박았다 — 이전에는 손으로만
  확인하고 테스트로 남기지 않았다.

## 남은 우려 사항 / 후속 고려

- **[신규 후속 이슈 후보]** API_CALL/AI_CLASSIFY 임시 데이터셋(`PipelineAsyncRunner.java:633`,
  `:758`)의 truncate 가 실제 적재/스왑보다 먼저 커밋된다 — 호출이 실패하면 임시 데이터셋이 빈
  채로 남는, Task 4 가 SQL 스텝에서 고친 것과 같은 결함이다. 이 Task 의 범위 밖이라 고치지
  않았다(위 "Fix round 1" 3번 참조). `/execute/api-call`·`/execute/python` 은 `preStatements` 를
  받지 않으므로 같은 방식(DELETE 선행 문장)을 그대로 옮길 수 없고, 별도 설계(예: API_CALL 처럼
  아예 truncate 를 없애고 스왑 전용으로 가거나, AI_CLASSIFY 실행기 내부에 원자성을 위임)가
  필요하다.
- `SqlScriptExecutor.execute(List<String>, String)` 의 선행 문장 검증은 이제 executor 의 화이트리스트와
  같은 엄격도(스키마 고정 + `[a-z0-9_]+` 테이블명 전체 일치)를 갖췄다(Fix round 1, 위 참조).
