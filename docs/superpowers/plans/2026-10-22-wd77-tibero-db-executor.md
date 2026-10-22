# Tibero DB 실행기(WD-77 1단계) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Tibero 테넌트의 파이프라인 SQL 단계(출력 데이터셋이 있는 SELECT 의 REPLACE·APPEND·MERGE, 사용자 DML)를 새 Java 서비스 `firehub-db-executor` 가 Tibero JDBC 로 실행하고, api 는 문장 생성·오케스트레이션만 한다.

**Architecture:** api `PipelineAsyncRunner` 가 테넌트 백엔드(`DataStoreResolver`)를 보고 TIBERO 면 새 `DbExecutorClient` 로 `/execute/sql`·`/execute/probe` 를 부른다. db-executor 는 api 내부 엔드포인트(WD-75)로 저장소 기술자를 받아 테넌트별 Hikari 풀로 Tibero OWNER 계정에 붙고, 다시 검증한 뒤 한 트랜잭션에서 `LOCK TABLE`→`DELETE`→본문→`COMMIT` 한다. 검증 규칙(Tibero 어휘·위험 함수)은 api 에 한 벌 만들고 db-executor 에 옮겨 쓰되 공용 고정값 파일로 같은 결과를 강제한다.

**Tech Stack:** Spring Boot 3.4.1 · Java 21 · HikariCP · Tibero JDBC(`libs/tibero*.jar`, 커밋 안 함) · JUnit 5 · Mockito · AssertJ · JSqlParser(api 기존)

**Spec:** `docs/superpowers/specs/2026-10-22-tibero-db-executor-design.md` — 모든 태스크 구현자는 먼저 읽는다.

## Global Constraints

- 한국어 주석 필수(클래스·메서드·주요 로직에 무엇을·왜). 사용자에게 보이는 오류 문구는 한국어.
- PG 테넌트 동작은 한 줄도 바뀌면 안 된다. PG 경로 기존 테스트는 전부 그대로 통과해야 한다.
- Tibero 테넌트 SQL 은 어떤 경로로도 PG 에서 실행되지 않는다(`app.executor.enabled=false` 로컬 분기 포함). db-executor 주소 미설정·불통이면 실패(fail-closed), PG 폴백 없음.
- db-executor 는 메타데이터 PG 에 접속하지 않는다.
- Spring Boot 3.4.1, Java 21, spotless google-java-format 1.34.1 — api 와 같은 버전. **프로젝트 전체 `spotlessApply` 금지** — 바꾼 파일만 포맷한다(`./gradlew spotlessApply -PspotlessFiles=<정규식>` 또는 해당 파일만).
- 내부 인증: `Authorization: Internal <token>`, 상수 시간 비교. 토큰은 api 의 `app.executor.internal-token` 과 같은 값(`EXECUTOR_INTERNAL_TOKEN`).
- db-executor 응답 오류 관례: HTTP 200 + `success=false`. 인증 실패만 401.
- 시간 제한: db-executor 문장 `setQueryTimeout(50)`, `/execute/sql` 전체 55초 초과 시 cancel→ROLLBACK, probe 문장 30초. api `DbExecutorClient` `/execute/sql` 60초, `/execute/probe` 35초.
- 풀: 테넌트별 최대 4 커넥션, 풀 최대 8개(LRU 로 닫음). 기술자 캐시 60초(성공만), 테넌트별 단일 조회.
- db-executor 포트 8090, 기본 api 주소 `http://api:8080`. api 의 기본 db-executor 주소 `http://db-executor:8090`, 로컬 프로필 `http://localhost:8090`.
- Tibero 스키마 = `DataStoreAccountRole.OWNER.usernameFor(tenantId)` (`GENIA_T{id}`), 물리 테이블·컬럼명 = `TiberoNames.physical(metadataName)`(대문자).
- Tibero JDBC jar·자격 증명은 커밋하지 않는다. 자격 증명 값은 출력·로그·커밋 금지(`~/.tibero/credentials` → `scripts/tibero-test.sh` 가 `TIBERO_TEST_*` 로 넘김). 공유 서버의 `DCIM_*` 계정은 건드리지 않는다.
- 워크트리에는 jar 가 없다. 실서버 테스트 전 메인 체크아웃 `/Users/bluleo78/git/smart-fire-hub/apps/firehub-api/libs/tibero7-jdbc-17.jar` 를 워크트리의 `apps/firehub-api/libs/`·`apps/firehub-db-executor/libs/` 에 복사한다(커밋 안 함).
- 워크트리에는 node_modules 가 없어 pre-commit 훅(turbo/playwright)이 실패한다 — 각 태스크가 자기 테스트를 직접 돌려 통과를 확인한 뒤 `git commit --no-verify` 한다.
- `docs/superpowers` 는 gitignore 대상이지만 기존 문서처럼 `git add -f` 로 추적한다.
- 커밋 메시지 끝: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` 와 `Claude-Session: https://claude.ai/code/session_01PBRb3qA4B9kiPnZ4C46SGX`.

## Review Focus

1. 사용자 SQL 이 `;` 나 `-- 주석` 으로 끝날 때 — 래핑(`FROM (<sql>\n)`)이 깨지지 않아야 한다(PG 도 같은 결함을 겪었다). → Task 3 테스트 `insertSelect_userSqlEndingWithLineComment_keepsClosingParen`, `..._trailingSemicolonStripped`.
2. 로컬 프로필(`app.executor.enabled=false`)의 Tibero 테넌트 — `SqlScriptExecutor`·`ExecutorClient` 로 새면 안 된다. → Task 6 테스트 `tiberoSqlStep_localMode_neverTouchesPgExecutors`.
3. Tibero 가 대문자로 돌려주는 결과 컬럼명(`AMOUNT`)과 데이터셋 컬럼(`amount`)의 대응 — 이름 정규화 누락 시 "일치 컬럼 없음"으로 실패한다. → Task 6 테스트 `tiberoSqlStep_upperCaseProbeColumns_matchLowerCaseDatasetColumns`.
4. 기술자 조회가 409(`DATA_STORE_NOT_READY`)·503(기본 토큰)·401 일 때 — 풀을 만들지 않고 한국어 오류로 끝나야 한다. → Task 4 테스트 `descriptor_nonOk_failsWithKoreanMessage_noPool`.
5. 같은 출력에 REPLACE 두 개가 겹칠 때 중복 없음, 실행 중 시간 초과면 기존 행 보존. → Task 5 실서버 테스트 `live_concurrentReplace_noDuplicates`, `live_timeout_rollsBackKeepsOldRows`.

---

### Task 1: Tibero SQL 어휘 분석기 · 문장 가드 · 고정값 (api)

**Files:**
- Create: `apps/firehub-api/src/main/java/com/smartfirehub/dataset/storage/tibero/TiberoSqlLexer.java`
- Create: `apps/firehub-api/src/main/java/com/smartfirehub/dataset/storage/tibero/TiberoSqlGuard.java`
- Create: `apps/firehub-api/src/test/resources/fixtures/tibero-sql-lexical-vectors.json`
- Test: `apps/firehub-api/src/test/java/com/smartfirehub/dataset/storage/tibero/TiberoSqlLexerVectorsTest.java`, `TiberoSqlGuardTest.java`

**Interfaces:**
- Produces:
  - `public final class TiberoSqlLexer` — `public static String stripTrailingSemicolon(String sql)`(따옴표·주석 밖 끝의 `;` 하나와 뒤 공백만 제거), `public static int countTopLevelSemicolons(String sql)`(따옴표·주석 밖), `public static String firstKeyword(String sql)`(주석·공백·여는 괄호 건너뛴 첫 단어, 대문자), `public static String maskLiteralsAndComments(String sql)`(문자열 리터럴·주석을 같은 길이 공백으로 — 식별자 `"..."` 는 유지), `public static boolean hasAlternativeQuoting(String sql)`(`q'`/`Q'`/`nq'` 대체 따옴표 존재).
  - 어휘 규칙: `--` 줄 주석, 중첩 없는 `/* */`, `'..'`(`''` 이스케이프), `"..."` 식별자(`""` 이스케이프), `q'<delim>..<delim>'`(`[]`,`{}`,`()`,`<>` 짝, 그 외 같은 문자). 닫히지 않은 리터럴·주석은 `IllegalArgumentException`.
  - `public final class TiberoSqlGuard` — `public static void requireAllowedStatement(String sql)` 위반 시 `TiberoSqlGuard.Rejected extends RuntimeException`(한국어 메시지). 규칙(스펙 §4.3·§5.4):
    1. 끝 `;` 하나 제거 후 최상위 `;` 가 있으면 거부: 「Tibero 실행 SQL 은 한 문장만 허용됩니다.」
    2. 첫 키워드가 `SELECT|WITH|INSERT|UPDATE|DELETE|MERGE` 가 아니면 거부: 「Tibero 실행 SQL 은 SELECT·WITH·INSERT·UPDATE·DELETE·MERGE 로 시작해야 합니다.」
    3. `WITH` 다음 최상위 첫 단어가 `FUNCTION`/`PROCEDURE` 면 거부(PL/SQL 선언).
    4. 마스킹한 본문에서 단어 경계로 위험 이름을 찾으면 거부: 접두 `DBMS_`·`UTL_`·`OWA_`, 단어 `HTP`·`HTF`·`XMLQUERY`·`XMLTABLE`·`XMLEXISTS`·`DBURITYPE`·`HTTPURITYPE`·`XDBURITYPE`, `SYS.`(점 포함), `CURSOR` 뒤 `(`. 대소문자 무관, 따옴표 식별자 안(`"DBMS_X"`)도 거부. 메시지: 「Tibero 에서 허용하지 않는 함수·패키지(<이름>)가 포함되어 있습니다.」
  - `public static void requireNoForeignSchemaCalls(String sql, String tenantSchema)` — `<식별자>.<식별자>(` 꼴 호출의 앞 식별자가 `tenantSchema`(대소문자 무관)가 아니고 2단계 호출이면 거부. 단, `<별칭>.<컬럼>` 은 뒤에 `(` 가 없으므로 해당 없음.
- 고정값 JSON 구조(배열 항목):
  ```json
  {"_설명": ["Tibero(Oracle) 어휘 규칙 ..."],
   "lexical": [{"name": "...", "sql": "...", "topLevelSemicolons": 0, "firstKeyword": "SELECT", "stripped": "...", "alternativeQuoting": false}],
   "guard":   [{"name": "...", "sql": "...", "tenantSchema": "GENIA_T5", "allowed": true}]}
  ```

- [ ] **Step 1: 고정값 작성** — `lexical` 최소 14건: `--` 안 `;`, `/* ; */`, `/* /* */` 비중첩(닫힘 뒤 텍스트는 코드), `'it''s;'`, `"a;b"` 식별자, `q'[a;b]'`, `q'{x}'`, `Q'!a;b!'`, 끝 `;` 제거(`SELECT 1 ;  ` → `SELECT 1`), 끝 `-- c` 뒤 `;` 없음, `(SELECT 1)` 첫 키워드 SELECT, 주석 뒤 `WITH`, `$$` 는 일반 문자(PG 와 다름), `E'..'` 는 식별자 E + 리터럴. `guard` 최소 20건: 허용(SELECT/WITH/INSERT…SELECT/UPDATE/DELETE/MERGE, `DUAL`, 리터럴 안 `'DBMS_X'`, 컬럼명 `dbms_flag` 는 접두 `DBMS_` 라 **거부**임을 명시), 거부(`DROP TABLE`, `TRUNCATE`, `BEGIN NULL; END;`, `DECLARE`, `CALL p()`, `SELECT 1; SELECT 2`, `WITH FUNCTION f ...`, `DBMS_XMLGEN.getxml('…')`, `utl_http.request`, `SYS.DBMS_X`, `XMLTABLE(…)`, `HTTPURITYPE(…)`, `CURSOR(SELECT …)`, `"DBMS_X".f()`, `LOCK TABLE`, `COMMIT`, `ALTER SESSION`, `other_schema.fn(1)`).
- [ ] **Step 2: 실패 테스트** — `TiberoSqlLexerVectorsTest` 가 `lexical` 각 항목에 대해 네 함수 결과를 `assertThat(...).as(name).isEqualTo(...)` 로, `TiberoSqlGuardTest.vectors` 가 `guard` 항목의 허용/거부를 단언. 고정값 파일 읽기는 기존 `TiberoExecutorVectorsTest` 방식을 따른다. 추가 단위 테스트: `unterminatedLiteral_throws`, `rejectionMessage_isKorean_namesFunction`.
- [ ] **Step 3: 실행해 실패 확인** — `cd apps/firehub-api && ./gradlew test --tests '*TiberoSql*'` → 컴파일 실패.
- [ ] **Step 4: 구현** — 한 번 훑는 상태 기계(문자 단위), 정규식으로 따옴표를 처리하지 않는다.
- [ ] **Step 5: 통과 확인** — 같은 명령 PASS.
- [ ] **Step 6: 커밋** — `feat(api): Tibero SQL 어휘 분석기·문장 가드와 공용 고정값 (WD-77)`.

### Task 2: 파이프라인 SQL 의 백엔드별 스키마 · 검증 (api)

**Files:**
- Create: `apps/firehub-api/src/main/java/com/smartfirehub/pipeline/service/PipelineDataSchema.java`
- Modify: `apps/firehub-api/src/main/java/com/smartfirehub/pipeline/service/validator/SqlValidator.java` (Tibero 모드 팩토리)
- Modify: `apps/firehub-api/src/main/java/com/smartfirehub/pipeline/service/PipelineService.java:~313-440`(저장 시점 검증·더미 참조 `DataSchema.qualify("step_ref_"…)`)
- Modify: `apps/firehub-api/src/main/java/com/smartfirehub/pipeline/service/PipelineAsyncRunner.java:1375-1442`(`resolveStepReferences`), `:412-420`(실행 직전 재검증)
- Modify: `apps/firehub-api/src/main/java/com/smartfirehub/securitylevel/access/DatasetAccessGuard.java:~600-640`(`judgeSql` 의 `PgLexicalAmbiguityCheck`·`DataSchema.current()`)
- Test: `.../pipeline/service/PipelineDataSchemaTest.java`, `.../pipeline/service/validator/SqlValidatorTiberoTest.java`, 기존 `DatasetAccessGuard` 테스트 파일에 Tibero 케이스 추가

**Interfaces:**
- Consumes: `DataStoreResolver.currentBackend()`, `DataStoreAccountRole.OWNER.usernameFor(long)`, `TiberoNames.physical/quote`, `TenantContext`, Task 1 `TiberoSqlGuard`, `TiberoSqlLexer.hasAlternativeQuoting`.
- Produces:
  - `@Component public class PipelineDataSchema` — `public boolean isTibero()`, `public String current()`(PG: `DataSchema.current()`, Tibero: `GENIA_T{id}`), `public String qualify(String metadataTableName)`(PG: `DataSchema.qualify(..)`, Tibero: `"GENIA_T5"."ORDERS"` — 스키마도 따옴표), `public SqlValidator validator()`(PG: 기존 주입된 기본 `SqlValidator` 빈, Tibero: `SqlValidator.forTiberoSchema(current())`).
  - `public static SqlValidator forTiberoSchema(String tiberoSchema)` — 허용 스키마 = 인자(대소문자 무관 비교는 기존 `equalsIgnoreCase` 유지), 미한정 참조 거부, 단 미한정 `DUAL` 하나만 허용. PG 전용 카탈로그·함수 차단 목록은 그대로 두고, `validate()` 진입 시 `TiberoSqlGuard.requireAllowedStatement` 와 `requireNoForeignSchemaCalls(sql, schema)` 를 먼저 부른다(거부는 기존 검증 예외 타입으로 감싸 같은 400 경로를 탄다).
  - `DatasetAccessGuard.judgeSql` — 백엔드가 Tibero 면 `PgLexicalAmbiguityCheck` 대신 「`TiberoSqlLexer.hasAlternativeQuoting` 이면 거부(한국어: 「Tibero 대체 따옴표(q'..')는 지원하지 않습니다. 작은따옴표 두 번('')으로 써 주세요.」)」, 스키마는 `PipelineDataSchema.current()`.
- [ ] **Step 1: JSqlParser 실측 테스트 먼저** — `SqlValidatorTiberoTest` 에 다음 SQL 이 `forTiberoSchema("GENIA_T5").validate(..)` 에서 통과하는지 실제로 돌려 보고, **나온 결과를 그대로 단언으로 고정**한다(통과 못 하는 문법은 거부 단언 + 한국어 메시지가 나오는지): `SELECT ROWNUM rn, a FROM genia_t5.orders WHERE ROWNUM <= 10`, `SELECT NVL(a,0) FROM genia_t5.orders`, `SELECT DECODE(a,1,'y','n') FROM genia_t5.orders`, `SELECT * FROM genia_t5.a, genia_t5.b WHERE a.id = b.id(+)`, `SELECT id FROM genia_t5.t START WITH pid IS NULL CONNECT BY PRIOR id = pid`, `SELECT SYSDATE FROM DUAL`. 결과를 커밋 메시지·보고서에 표로 남긴다.
- [ ] **Step 2: 스키마 판정 테스트** — `genia_t5.orders`·`GENIA_T5.ORDERS`·`"GENIA_T5"."ORDERS"` 허용, `data_t5.orders`·`genia_t6.orders`·미한정 `orders`·`all_tables`·`sys.dual` 거부, `DUAL` 허용, `dbms_xmlgen.getxml('select 1 from dual')` 거부. `PipelineDataSchemaTest`: Tibero 테넌트 5 → `current()=="GENIA_T5"`, `qualify("orders")=="\"GENIA_T5\".\"ORDERS\""`; PG 테넌트는 `DataSchema` 와 같은 값.
- [ ] **Step 3: 실패 확인** — `./gradlew test --tests '*SqlValidatorTiberoTest' --tests '*PipelineDataSchemaTest'`.
- [ ] **Step 4: 구현** — `PipelineService`(저장 시점)·`PipelineAsyncRunner`(재검증·`resolveStepReferences` 의 `DataSchema.qualify` 자리)·`DatasetAccessGuard` 가 `PipelineDataSchema` 를 쓰게 바꾼다. PG 테넌트 결과 문자열은 기존과 **완전히 같아야** 한다.
- [ ] **Step 5: 회귀** — `./gradlew test --tests '*SqlValidator*' --tests '*DatasetAccessGuard*' --tests '*PipelineService*' --tests '*PipelineAsyncRunner*'` 전부 PASS.
- [ ] **Step 6: 커밋** — `feat(api): Tibero 테넌트 파이프라인 SQL 의 스키마 판정·검증 (WD-77)`.

### Task 3: Tibero 파이프라인 문장 생성기 (api)

**Files:**
- Create: `apps/firehub-api/src/main/java/com/smartfirehub/dataset/storage/tibero/TiberoPipelineSql.java`
- Modify: `apps/firehub-api/src/main/java/com/smartfirehub/dataset/storage/tibero/TiberoDmlSql.java`(`mergeCore` 에 선택적 "바뀐 행만" 조건과 모든 컬럼이 키일 때 `WHEN MATCHED` 생략 — 기존 호출 결과는 그대로)
- Test: `.../dataset/storage/tibero/TiberoPipelineSqlTest.java`

**Interfaces:**
- Consumes: `TiberoNames`, `TiberoDmlSql.mergeCore`, `TiberoSqlLexer.stripTrailingSemicolon`.
- Produces (모두 `public static String`, 인자 이름은 메타데이터 이름 — 내부에서 `TiberoNames.physical` 로 바꾼다):
  - `deleteAll(String schema, String table)` → `DELETE FROM "GENIA_T5"."ORDERS"` (db-executor 선행문 정규식과 정확히 일치해야 함).
  - `insertSelect(String schema, String table, List<String> columns, String userSql)` → `INSERT INTO "S"."T" ("ID", "C1", …, "_UPDATED_AT") SELECT "S"."<seq>".NEXTVAL, src."C1", …, SYSTIMESTAMP FROM (\n<userSql 끝 ; 제거>\n) src` — 시퀀스명은 `TiberoNames.sequenceName(schema, table)`. 사용자 SQL 결과 컬럼은 대문자 물리명으로 참조(`src."C1"`) — 따라서 Task 6 은 matched 컬럼을 사용자 SQL 결과 컬럼명(probe) 기준으로 넘긴다.
  - `mergeSelect(String schema, String table, List<String> columns, List<String> pkColumns, String userSql)` → `MERGE INTO "S"."T" t USING (\n<userSql>\n) s ON (t."K" = s."K" …) WHEN MATCHED THEN UPDATE SET t."C" = s."C", …, t."_UPDATED_AT" = SYSTIMESTAMP WHERE (DECODE(t."C", s."C", 0, 1) = 1 OR …) WHEN NOT MATCHED THEN INSERT ("ID", …, "_UPDATED_AT") VALUES ("S"."<seq>".NEXTVAL, s."…", …, SYSTIMESTAMP)`; 모든 컬럼이 PK 면 `WHEN MATCHED` 절 전체 생략.
  - `probeWrap` 은 만들지 않는다(probe 래핑은 db-executor 몫).
- [ ] **Step 1: 실패 테스트** — 위 세 함수의 정확한 문자열 단언(스키마 `GENIA_T5`, 테이블 `orders`, 컬럼 `[amount, name]`, PK `[id_key]`) + Review Focus 1: `insertSelect_userSqlEndingWithLineComment_keepsClosingParen`(`SELECT 1 a FROM DUAL -- c` → 결과에 `-- c\n) src` 포함), `insertSelect_trailingSemicolonStripped`, `mergeSelect_allColumnsArePk_omitsWhenMatched`, `mergeCore_existingCallers_unchanged`(기존 `merge(...)` 출력 문자열을 변경 전 값으로 고정).
- [ ] **Step 2: 실패 확인** — `./gradlew test --tests '*TiberoPipelineSqlTest' --tests '*TiberoDmlSql*'`.
- [ ] **Step 3: 구현.**
- [ ] **Step 4: 통과 확인** — 같은 명령 + `--tests '*Tibero*'` 전체 PASS(실서버 테스트는 env 없으면 건너뜀).
- [ ] **Step 5: 커밋** — `feat(api): Tibero 파이프라인 적재 문장 생성(INSERT…SELECT·MERGE·비우기) (WD-77)`.

### Task 4: db-executor 서비스 골격 · 인증 · 기술자 · 풀 · 이미지

**Files:**
- Create: `apps/firehub-db-executor/` — `build.gradle.kts`, `settings.gradle.kts`(`rootProject.name = "firehub-db-executor"`), `gradlew`·`gradle/wrapper/*`(api 것 복사), `package.json`(api 와 같은 모양, 이름 `@smart-fire-hub/firehub-db-executor`, dev 는 `./gradlew bootRun --args='--spring.profiles.active=local'`), `Dockerfile`, `.dockerignore`, `.gitignore`(`build/`, `.gradle/`), `libs/.gitkeep`
- Create (패키지 `com.smartfirehub.dbexecutor`): `DbExecutorApplication.java`, `config/DbExecutorProperties.java`, `security/InternalTokenFilter.java`, `store/StoreDescriptor.java`, `store/StoreDescriptorClient.java`, `store/TenantPools.java`, `store/TiberoSession.java`, `health/TiberoDriverHealthIndicator.java`, `src/main/resources/application.yml`, `application-local.yml`
- Test: `src/test/java/com/smartfirehub/dbexecutor/...` 각 클래스 테스트

**Interfaces:**
- Produces:
  - 설정(`app.*`): `app.internal-token`(env `EXECUTOR_INTERNAL_TOKEN`, 비면 기동 실패), `app.api-base-url`(env `DB_EXECUTOR_API_BASE_URL`, 기본 `http://api:8080`; local `http://localhost:5010`), `app.descriptor-ttl-seconds=60`, `app.pool.max-pools=8`, `app.pool.max-size=4`, `server.port=8090`. `spring.autoconfigure.exclude` 로 DataSource 자동 구성 끔.
  - `InternalTokenFilter` — `/execute/**` 에 `Authorization: Internal <token>` 상수 시간 비교(`MessageDigest.isEqual`), 실패 401 JSON `{"success":false,"error":"인증되지 않은 요청입니다."}`. `/actuator/health` 는 통과.
  - `record StoreDescriptor(long tenantId, String backend, String host, Integer port, String database, String schema, Account owner)`, `record Account(String username, String password)` — `toString` 은 비밀번호 마스킹.
  - `StoreDescriptorClient` — `StoreDescriptor get(long tenantId)`; GET `{api}/internal/executor/tenants/{id}/data-store`, 같은 토큰 헤더. 200 이고 backend=`TIBERO` 일 때만 성공·캐시. 그 외(`POSTGRESQL`, 401/409/503/연결 실패)는 `StoreUnavailableException`(한국어: 「테넌트 저장소 정보를 가져오지 못했습니다(<상태>).」 — 비밀번호·URL 쿼리 넣지 않음). 실패는 캐시 안 함. 테넌트별 단일 조회(`ConcurrentHashMap<Long, CompletableFuture>`).
  - `TenantPools` — `Connection borrow(StoreDescriptor d)`; 키 = tenantId, 접속값(host/port/db/username/password 의 해시)이 바뀌면 옛 풀 닫고 새로 만듦. LRU 8개 초과 시 가장 오래 안 쓴 풀 닫음. Hikari: maxPoolSize 4, `connectionInitSql` = 세션 NLS 초기화, autoCommit false, JDBC URL 형식은 api `ExternalStorePoolFactory` 와 같게.
  - `TiberoSession` — `public static final String SESSION_INIT_SQL`(api `TiberoDialect` 와 같은 문장), `DRIVER_CLASS = "com.tmax.tibero.jdbc.TbDriver"`.
  - `TiberoDriverHealthIndicator` — `Class.forName(DRIVER_CLASS)` 실패면 DOWN(`detail driver=missing`), 기동 시 ERROR 로그 1회.
  - Dockerfile: 빌드 `eclipse-temurin:21-jdk`(`--platform=$BUILDPLATFORM`), `COPY gradle/ gradlew build.gradle.kts settings.gradle.kts libs/ src/`, `./gradlew bootJar -x test`; 런타임 `eclipse-temurin:21-jre`, `EXPOSE 8090`, `java -XX:MaxRAMPercentage=75.0 -jar app.jar`.
- [ ] **Step 1: 실패 테스트** — `InternalTokenFilterTest`(헤더 없음/틀림 401, 맞음 통과, health 통과), `StoreDescriptorClientTest`(MockRestServiceServer 또는 `com.sun.net.httpserver` 로: 200 TIBERO 성공·60초 안 재조회 없음, 동시 10건 → 원격 1회, **Review Focus 4** `descriptor_nonOk_failsWithKoreanMessage_noPool` — 409/503/401/POSTGRESQL 각각 예외·메시지에 비밀번호 없음·`TenantPools` 미호출), `TenantPoolsTest`(같은 접속값 재사용, 비밀번호 변경 시 새 풀·옛 풀 close, 9번째 테넌트에서 LRU 1개 close — Hikari 생성은 팩토리 함수 주입으로 가짜 DataSource), `TiberoSessionTest`(SESSION_INIT_SQL 이 `apps/firehub-api/src/test/resources/fixtures/tibero-executor-vectors.json` 의 `sessionInitSql` 과 같음 — 상대 경로 `../firehub-api/...`), `TiberoDriverHealthIndicatorTest`.
- [ ] **Step 2: 실패 확인** — `cd apps/firehub-db-executor && ./gradlew test`.
- [ ] **Step 3: 구현.**
- [ ] **Step 4: 통과 확인** — `./gradlew test` PASS, `./gradlew spotlessCheck` PASS. jar 없이 `docker build -t firehub-db-executor:wd77 apps/firehub-db-executor` 성공(health DOWN 이 정상).
- [ ] **Step 5: 커밋** — `feat(db-executor): Tibero DB 실행기 골격 — 내부 인증·저장소 기술자·테넌트 풀·드라이버 상태 (WD-77)`.

### Task 5: db-executor `/execute/sql` · `/execute/probe` + 실서버 테스트

**Files:**
- Create: `apps/firehub-db-executor/src/main/java/com/smartfirehub/dbexecutor/sql/{TiberoSqlLexer.java, TiberoSqlGuard.java}`(Task 1 에서 복사, 패키지만 변경), `sql/PreStatementRule.java`, `sql/SqlRunner.java`, `sql/ProbeRunner.java`, `sql/ValueConverter.java`, `web/ExecuteController.java`, `web/dto/*.java`
- Modify: `apps/firehub-api/src/test/resources/fixtures/tibero-executor-vectors.json`(값 변환·컬럼명 벡터 추가), 그에 맞춰 api `TiberoExecutorVectorsTest`·Python `test_tibero_vectors.py` 가 새 키를 무시하거나 검증하도록 확인
- Modify: `scripts/tibero-test.sh`(db-executor 실서버 테스트 추가 실행·요약)
- Test: `src/test/java/.../sql/*Test.java`, `src/test/java/.../live/TiberoLiveSqlTest.java`

**Interfaces:**
- Consumes: Task 4 `StoreDescriptorClient.get`, `TenantPools.borrow`, Task 1 고정값 파일.
- Produces (HTTP, JSON 은 snake_case — api `SqlExecuteResult` 와 같은 이름):
  - `POST /execute/sql` 요청 `{"tenantId": 5, "query": "...", "preStatements": ["DELETE FROM \"GENIA_T5\".\"ORDERS\""]}` → `{"success", "rows", "columns", "row_count", "execution_log", "error", "db_error": {"code": -10007, "message": "..."}}`.
  - `POST /execute/probe` 요청 `{"tenantId", "query"}` → `{"success", "columns": [{"name","jdbc_type","type_name","precision","scale"}], "error", "db_error"}`; 실행 문장 `SELECT * FROM (\n<query 끝 ; 제거>\n) WHERE 1 = 0`, 30초.
  - `PreStatementRule.lockFor(String deleteStmt, String tenantSchema)` — 정규식 `^DELETE FROM "([A-Z0-9_]+)"\."([A-Z0-9_$#]+)"$` 와 스키마 일치 확인 후 `LOCK TABLE "S"."T" IN EXCLUSIVE MODE` 반환, 아니면 거부(「허용되지 않은 선행 문장입니다.」).
  - 실행 순서(스펙 §4.3): 검증(`TiberoSqlGuard.requireAllowedStatement` + `requireNoForeignSchemaCalls` + 선행문 규칙) → 각 선행문마다 LOCK(별도 문장)→DELETE → 본문 → COMMIT; 예외·시간 초과 시 ROLLBACK. 문장마다 `setQueryTimeout(50)`, 전체 55초 넘으면 `Statement.cancel()`→ROLLBACK→`success=false`(「실행 시간이 55초를 넘어 취소했습니다.」). rows/columns 는 선행문 없고 첫 키워드 SELECT/WITH 일 때만.
  - `ValueConverter` — 컬럼명: 전부 대문자면 소문자로, 아니면 그대로. NUMBER: scale>0 이면 `BigDecimal.setScale(scale)`, scale==0 이면 정수(`long` 범위 밖이면 `BigDecimal`), 맨 NUMBER(scale -127)는 `BigDecimal.stripTrailingZeros().toPlainString()` 값. DATE/TIMESTAMP 는 ISO-8601 문자열(`yyyy-MM-dd'T'HH:mm:ss[.SSSSSS]`, 시간대 없음). CLOB 은 문자열. 이 규칙은 고정값 `valueConversions`·`columnNames` 배열로 pin 한다(WD-75 Python 백엔드와 같은 결과).
- [ ] **Step 1: 단위 실패 테스트** — `TiberoSqlVectorsParityTest`(api 의 `tibero-sql-lexical-vectors.json` 을 읽어 복사본이 같은 결과), `PreStatementRuleTest`(정상·소문자·다른 스키마·`WHERE` 붙음·세미콜론 → 거부), `SqlRunnerTest`(모의 JDBC: 순서 LOCK→DELETE→본문→COMMIT, 본문 실패 시 ROLLBACK·`db_error.code` 음수 전달, 55초 초과 시 cancel+ROLLBACK, 선행문 있으면 rows null), `ValueConverterTest`(고정값), `ExecuteControllerTest`(MockMvc: 401, 기술자 실패 → 200+success=false 한국어).
- [ ] **Step 2: 실패 확인 → 구현 → 통과** — `cd apps/firehub-db-executor && ./gradlew test`.
- [ ] **Step 3: 실서버 테스트 작성** — `TiberoLiveSqlTest`(`TIBERO_TEST_HOST` 등 없으면 `Assumptions.assumeTrue(false)`). 테스트용 스키마·계정은 api 의 기존 실서버 계약 테스트가 쓰는 방식(전용 테스트 계정 생성·정리)을 그대로 따른다 — 남의 계정 건드리지 않음. 기술자 클라이언트는 테스트 더블로 바꿔 env 값으로 만든 `StoreDescriptor` 를 준다. 케이스:
  - `live_replace_append_merge` — 출력 테이블(Task 3 의 `TiberoPipelineSql` 결과 문자열을 고정 문자열로 가져와 사용)에 REPLACE 두 번(마지막 결과만 남음), APPEND(누적), MERGE(갱신·삽입, 바뀌지 않은 행 `_UPDATED_AT` 불변 — **바뀐 행만** 확인).
  - **Review Focus 5** `live_concurrentReplace_noDuplicates` — 같은 출력에 REPLACE 두 개를 스레드 둘로 동시에 → 최종 행 수 = 한 실행 분량.
  - **Review Focus 5** `live_timeout_rollsBackKeepsOldRows` — 시간 제한을 테스트용으로 2초로 줄인 실행기에서 느린 본문(예: 큰 `CONNECT BY LEVEL` 카운트) → success=false, 기존 행 그대로.
  - `live_rejects_ddl_plsql_multi_dangerous` — `DROP`, `BEGIN NULL; END;`, 두 문장, `DBMS_XMLGEN` 이 실행 전에 거부되고 테이블 무사.
  - `live_probe_columns` — `SELECT 1 AS a, 'x' b, SYSDATE d FROM DUAL` → 이름 `a,b,d`, 타입 정보 채워짐.
  - `live_nlsDateFormat` — 세션의 `NLS_DATE_FORMAT` 이 초기화 값.
  - `live_mergeSourceDuplicateKey_code` — 원본에 같은 키 두 줄인 MERGE → 실패 코드를 **보고서에 기록**(Task 6 이 상수로 씀). 단언은 `success=false` 와 code<0 만.
- [ ] **Step 4: 실서버 실행** — jar 를 `apps/firehub-db-executor/libs/` 에 복사(Global Constraints) 후 `scripts/tibero-test.sh` (db-executor 단계 포함) — 전부 PASS, 남은 객체 없음 확인. 측정한 MERGE 원본 중복 코드를 보고서에 적는다.
- [ ] **Step 5: 커밋** — `feat(db-executor): Tibero SQL 실행·컬럼 탐지 — 재검증·출력 테이블 잠금·시간 제한·실서버 테스트 (WD-77)`.

### Task 6: api 라우팅 · 오류 번역 · 미지원 단계 차단

**Files:**
- Create: `apps/firehub-api/src/main/java/com/smartfirehub/pipeline/service/executor/DbExecutorClient.java`
- Modify: `.../pipeline/service/executor/ExecutorClient.java:213-238`(`SqlExecuteResult` 에 `@JsonProperty("db_error") DbError dbError` 추가, `record DbError(int code, String message)`) — 기존 생성자 호출부 전부 갱신
- Modify: `.../pipeline/service/PipelineAsyncRunner.java`(SQL 단계 Tibero 분기, 단계 종류별 차단)
- Modify: `.../pipeline/service/PipelineSqlErrorMessages.java`(Tibero 분기)
- Modify: `.../dataset/storage/tibero/TiberoDialect.java`(Task 5 에서 측정한 MERGE 원본 중복 코드 추가 — 측정 못 했으면 추가하지 않고 일반 문구)
- Modify: `apps/firehub-api/src/main/resources/application.yml`(`app.db-executor.base-url: ${DB_EXECUTOR_BASE_URL:http://db-executor:8090}`), `application-local.yml`(`http://localhost:8090`), `application-test.yml`
- Test: `.../pipeline/service/PipelineAsyncRunnerTiberoTest.java`, `.../pipeline/service/executor/DbExecutorClientTest.java`, `PipelineSqlErrorMessagesTest` 에 Tibero 케이스

**Interfaces:**
- Consumes: Task 2 `PipelineDataSchema`, Task 3 `TiberoPipelineSql`, Task 5 HTTP 계약.
- Produces:
  - `@Service public class DbExecutorClient` — `SqlExecuteResult executeSql(String query, List<String> preStatements)`(60s), `ProbeResult probe(String query)`(35s), `record ProbeResult(boolean success, List<ProbeColumn> columns, String error, DbError dbError)`, `record ProbeColumn(String name, @JsonProperty("jdbc_type") int jdbcType, @JsonProperty("type_name") String typeName, Integer precision, Integer scale)`. 본문에 `tenantId` 는 `TenantContext.require()` 로. 주소 미설정·연결 실패·타임아웃 → `ScriptExecutionException`(한국어 「Tibero 실행기에 연결하지 못했습니다.」).
  - `PipelineSqlErrorMessages.describeTiberoError(String prefix, DbError err, String error)`, `describeTiberoMergeError(DbError err, String rawMessage, List<String> pkColumns)` — 코드 집합은 `TiberoDialect` 의 `UNIQUE_VIOLATION`·`NOT_NULL_INSERT`·`VALUE_TOO_LARGE`·`NotFound.TABLE` 등, 문구는 PG 판과 같은 뜻.
- 동작(스펙 §5.1):
  - Tibero 테넌트 SQL 단계: 증분이면 거부(「Tibero 저장소 테넌트에서는 아직 증분 SQL 단계를 실행할 수 없습니다.」), 출력 데이터셋 없는 SELECT 면 거부(「Tibero 저장소 테넌트의 SQL 단계는 출력 데이터셋을 지정해야 합니다.」). SELECT 면 `dbExecutorClient.probe(sql)` 결과 컬럼명을 PG 경로와 같은 정규화·matched 계산에 넣고, REPLACE 면 선행문 `TiberoPipelineSql.deleteAll`, MERGE 면 `mergeSelect`, 그 외 `insertSelect`. 비 SELECT(DML) 는 sql 그대로. 실행은 `dbExecutorClient.executeSql` 만 — `executorEnabled` 무관.
  - Tibero 테넌트의 PYTHON·API_CALL·AI_CLASSIFY 단계: 단계 시작 즉시 실패(「Tibero 저장소 테넌트에서는 아직 Python 단계를 실행할 수 없습니다.」 / 「…API 수집 단계…」 / 「…AI 분류 단계…」).
- [ ] **Step 1: 실패 테스트** — `PipelineAsyncRunnerTiberoTest`(기존 `PipelineAsyncRunner` 단위 테스트 구성 방식 재사용, `DataStoreResolver` 를 TIBERO 로): REPLACE/APPEND/MERGE 각각 `dbExecutorClient.executeSql` 에 넘긴 문자열·선행문 단언, **Review Focus 2** `tiberoSqlStep_localMode_neverTouchesPgExecutors`(executorEnabled=false 에서도 `ExecutorClient`·`SqlScriptExecutor`·`SqlColumnProbe` 상호작용 0), **Review Focus 3** `tiberoSqlStep_upperCaseProbeColumns_matchLowerCaseDatasetColumns`(probe `AMOUNT`,`NAME` ↔ 데이터셋 `amount`,`name`), 미지원 단계 4종·증분·출력 없는 SELECT 한국어 실패, db-executor 실패 응답 → 단계 FAILED + 번역 문구. `DbExecutorClientTest`(헤더·tenantId·타임아웃·연결 실패 문구). `PipelineSqlErrorMessagesTest` Tibero 코드별 문구. PG 경로 테스트 무변경 통과.
- [ ] **Step 2: 실패 확인 → 구현 → 통과** — `./gradlew test --tests '*PipelineAsyncRunner*' --tests '*DbExecutorClient*' --tests '*PipelineSqlErrorMessages*' --tests '*ExecutorClient*'`.
- [ ] **Step 3: api 전체 테스트** — `./gradlew test` (알려진 환경 고장은 메모리 `local-api-test-env-broken` 우회 절차대로) PASS.
- [ ] **Step 4: 커밋** — `feat(api): Tibero 테넌트 SQL 단계를 db-executor 로 보내고 미지원 단계를 막는다 (WD-77)`.

### Task 7: 이미지 jar 반입 · 배포 구성 · 문서

**Files:**
- Modify: `apps/firehub-api/Dockerfile`(`COPY libs/ libs/` 를 `bootJar` 전에), Create: `apps/firehub-api/libs/.gitkeep`(`.gitignore` 의 `*.jar` 는 유지 — `.gitkeep` 만 추적되는지 `git check-ignore` 로 확인)
- Create: `apps/firehub-api/src/main/java/com/smartfirehub/dataset/storage/tibero/TiberoDriverStartupCheck.java` — `app.data-store.tibero-enabled=true` 인데 `TiberoDialect.DRIVER_CLASS` 로드 실패면 기동 시 ERROR 로그 1회(기동은 계속)
- Modify: `docker-compose.prod.yml`(`db-executor` 서비스: context `./apps/firehub-db-executor`, env `EXECUTOR_INTERNAL_TOKEN: ${EXECUTOR_INTERNAL_TOKEN:-executor-token}`, `DB_EXECUTOR_API_BASE_URL: http://api:8080`, network `firehub-net`, 포트 미공개; api env `DB_EXECUTOR_BASE_URL: http://db-executor:8090`)
- Modify: `scripts/deploy.sh`(`db-executor` 대상 — 기존 `executor)` 블록과 같은 모양, 이미지명 `firehub-db-executor`)
- Modify: `.claude/docs/deploy.md`(새 절 「Tibero DB 실행기(WD-77)」: 배포 순서 api→db-executor, 토큰 동일, jar 를 빌드 머신의 `apps/firehub-api/libs/`·`apps/firehub-db-executor/libs/` 에 둘 것과 확인 명령 `docker run --rm <img> sh -c 'ls /app/BOOT-INF/lib | grep -i tibero'` 류, k8s 에 Deployment·Service `db-executor:8090` 필요(차트는 사용자 승인 후), health DOWN 의미)
- Test: `TiberoDriverStartupCheckTest`

- [ ] **Step 1: 실패 테스트** — `TiberoDriverStartupCheckTest`: 드라이버 클래스명을 없는 이름으로 주입 + 기능 켜짐 → ERROR 로그 1건(로그 캡처), 기능 꺼짐 → 로그 없음.
- [ ] **Step 2: 구현 → 통과** — `./gradlew test --tests '*TiberoDriverStartupCheck*'`.
- [ ] **Step 3: 이미지 확인** — jar 를 두 `libs/` 에 복사한 상태에서 `docker build -t firehub-api:wd77 apps/firehub-api` 와 `docker build -t firehub-db-executor:wd77 apps/firehub-db-executor` → 각 이미지 jar 안에 tibero jar 포함 확인. jar 를 치운 상태에서도 두 빌드 성공(빈 `libs/`).
- [ ] **Step 4: compose 문법** — `docker compose -f docker-compose.prod.yml config -q` 성공.
- [ ] **Step 5: 커밋** — `feat: Tibero JDBC 를 api·db-executor 이미지에 넣는 경로와 db-executor 배포 구성 (WD-77)`.

### Task 8: 끝-끝 실서버 확인 (컨트롤러가 직접)

- [ ] 맥에서 db-executor 를 `local` 프로필로 띄우고(jar 포함, `EXECUTOR_INTERNAL_TOKEN=dev-executor-token`), api 쪽 `DbExecutorClient` 를 실서버 테스트 계정 기술자로 직접 호출하는 짧은 확인(또는 Task 5 실서버 테스트를 HTTP 경유로 1건) — `/execute/sql` REPLACE 한 번, `/execute/probe` 한 번 성공, 응답 형태가 api 레코드로 역직렬화됨.
- [ ] 결과를 원장에 기록. 실패 시 해당 태스크로 되돌린다.
