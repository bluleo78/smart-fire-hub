# Tibero DB 실행기(firehub-db-executor) 설계 — WD-77 1단계

- 작성일: 2026-10-22
- 관련: 에픽 WD-69(외부 저장소 지원), WD-77(파이프라인), WD-74(Tibero 데이터셋 저장소), WD-75(executor Tibero 연결 기반)
- 마감: 에픽 2026-10-23 (변경하지 않음)

## 1. 왜 바꾸나

WD-75 는 Python executor 가 pyodbc + Tibero ODBC 드라이버(`libtbodbc.so`)로 Tibero 에 직접 붙는 방향이었다. 사용자와 검토한 결과 이 방향을 접는다.

- 상용 네이티브 `.so` 를 별도로 확보·반입해야 한다. 이 드라이버가 Python 워커 프로세스 안에서 죽으면 워커 전체가 내려간다.
- api(JDBC)와 executor(ODBC)가 같은 DB 에 서로 다른 드라이버를 쓰게 된다. 그러면 형 변환·날짜·오류 코드 차이를 두 번 검증해야 한다(JDBC 실측에서 오류 코드가 문서와 전부 달랐다).
- 그렇다고 Tibero 실행을 api 에 넣는 것도 사용자가 원하지 않는다(api 에 실행 부하를 주지 않는다).

결정: **Tibero 테넌트의 파이프라인 DB 작업만 전담하는 Java 서비스(`apps/firehub-db-executor`)를 새로 만든다.** 드라이버는 이미 실서버로 검증한 Tibero JDBC 하나로 통일한다.

## 2. 목표 · 비목표 · 실패 조건

### 목표(1단계, 10-23까지)
- Tibero 테넌트의 **SQL 단계**가 Tibero 에서 실행된다. 대상은 출력 데이터셋이 있는 SELECT 의 REPLACE·APPEND·MERGE 적재와 사용자 DML 이다.
- 사용자 SQL 표기는 PG 테넌트와 같은 원칙을 따른다: **그 테넌트의 실제 데이터 스키마를 붙여 쓴다**(§5.0).
- api 는 문장을 만들고 순서를 정하는 오케스트레이션만 한다. 사용자 SQL 은 api 에서 Tibero 로 실행되지 않는다.
- 맥에서 개발 Tibero 서버(실서버)에 붙여 검증한다.

### 비목표(1단계에서 하지 않음 — Tibero 테넌트에는 한국어 오류로 막는다)
- 증분(incremental) SQL 단계: 커서 후보가 PG 시계와 PG 트리거(`_updated_at`)에 기대고 있다. Tibero 의 `_UPDATED_AT` 은 `SYSTIMESTAMP` 라 그대로 쓰면 누락·중복 위험이 있다.
- 출력 데이터셋이 없는 SELECT(임시 데이터셋 자동 생성): 2단계에서 한다. 두 가지가 먼저 정해져야 한다.
  - 임시 데이터셋 이름: 지금 규칙(`ptmp_<파이프라인>_<단계명 최대 30자>_<4자>`)은 Tibero 의 30자 한도를 자주 넘는다.
  - probe 결과를 앱 컬럼 타입으로 되돌리는 매핑: NUMBER(1)·맨 NUMBER·DATE 를 어떤 타입으로 볼지 정해야 한다.
- API 수집·Python 단계의 Tibero 적재: 2단계(`/execute/load`)에서 한다.
- AI 분류 단계: Tibero 저장소의 임시 테이블 교체(`createTempTable`/`swapTable`)가 아직 미구현이다(호출하면 예외가 난다). 2단계에서 한다.
- 분석 쿼리·차트(`/execute/query`): WD-76 에서 한다.
- 공용 Gradle 모듈 분리: 2단계에서 한다(§7 참고).
- WD-75 ODBC 경로 제거: 2단계에서 원격 적재로 대체한 뒤 정리한다. 지금은 손대지 않는다. Python executor 의 `store_gate` 는 Tibero 테넌트를 막는 안전망으로 그대로 둔다.
- 운영 배포·k8s 차트 수정: 차트는 리포 밖(`~/k8s/smart-fire-hub`)에 있어 사용자 승인이 필요하다. 이 머신은 푸시까지만 한다.

### 실패 조건(동작해도 실패로 본다)
- Tibero 테넌트의 SQL 이 어떤 경로로든 PG 에서 실행된다. 로컬 모드(`app.executor.enabled=false`)의 `SqlScriptExecutor` 경유도 포함한다.
- 같은 출력에 REPLACE 두 실행이 겹쳐 행이 중복된다(#731 재발).
- 실행기가 DDL·PL/SQL 을 실행해 테넌트 테이블을 바꾸거나, 자동 커밋 DDL 때문에 "비우기+쓰기" 원자성이 깨진다.
- 이미지에 JDBC 드라이버가 빠졌는데 조용히 기동해 실행 때에야 실패한다.
- PG 테넌트의 동작이 조금이라도 바뀐다.

## 3. 구조

```
api(PipelineAsyncRunner)
 ├─ PG 테넌트     → ExecutorClient   → Python executor(/execute/sql …)   [변경 없음]
 └─ Tibero 테넌트 → DbExecutorClient → firehub-db-executor(/execute/sql, /execute/probe)
                                          ├─ api 내부 엔드포인트로 저장소 기술자 조회(WD-75 것 재사용)
                                          └─ 테넌트별 Hikari 풀 → Tibero(OWNER 계정)
```

- 분기 기준: `DataStoreResolver.backendFor(tenantId)` (이미 테넌트별로 영구 캐시됨, 생성 후 불변).
- Tibero 분기는 `app.executor.enabled` 값과 무관하다. 로컬 모드에서도 Tibero 는 db-executor 로만 간다. db-executor 주소가 없거나 닿지 않으면 실패로 끝난다(fail-closed). PG 로 폴백하지 않는다.
- 메타데이터 PG(실행 기록·커서·보안 게이트·데이터셋 메타)는 지금처럼 api 가 다룬다. db-executor 는 메타데이터 PG 에 접속하지 않는다.
- 데이터셋 테이블 DDL(임시 데이터셋 생성 등)은 WD-74 대로 api 의 Tibero 저장소가 한다. 짧은 메타 작업이라 실행 부하로 보지 않는다.

## 4. db-executor 서비스

### 4.1 스택
- Spring Boot 3.4.1, Java 21, spotless(google-java-format 1.34.1) — api 와 같은 버전.
- 의존: `spring-boot-starter-web`, `spring-boot-starter-actuator`, HikariCP. jOOQ·Flyway·Security 는 쓰지 않는다. DataSource 자동 구성도 끈다(메타 DB 가 없다).
- Tibero JDBC: api 와 같이 `libs/` 의 `tibero*.jar` 를 `fileTree` 로 넣는다. jar 는 커밋하지 않는다.
- 포트 8090. 외부(ingress·nginx)에 노출하지 않는다.

### 4.2 API 계약
모든 요청은 `Authorization: Internal <token>` 을 쓴다. 토큰은 api↔executor 와 같은 `EXECUTOR_INTERNAL_TOKEN` 이고, 비교는 상수 시간이다. 응답은 Python executor 관례대로 **HTTP 200 + `success=false`** 로 오류를 싣는다. 인증 실패만 401 이다.

`POST /execute/sql`
- 요청: `{ tenantId, query, preStatements[] }`
- 응답: `{ success, rows, columns, row_count, execution_log, error, db_error{ code, message } }`
  - `db_error.code` 는 Tibero 의 부호 있는 자체 코드(예: -10007)다. `pg_error` 는 싣지 않는다.
  - `rows`/`columns` 는 선행 문장이 없고 SELECT 일 때만 채운다. 컬럼명은 전부 대문자일 때만 소문자로 바꾼다. NUMBER 는 자릿수(scale)에 맞춰 돌려준다. 이 두 규칙은 WD-75 Python 백엔드와 같다.

`POST /execute/probe`
- 요청: `{ tenantId, query }`
- 실행: `SELECT * FROM (\n<query>\n) WHERE 1 = 0` 을 돌려 `ResultSetMetaData` 만 읽는다. 행은 읽지 않는다.
- 응답: `{ success, columns[{ name, jdbc_type, type_name, precision, scale }], error, db_error }`.
- 1단계에서 api 는 이 결과의 **컬럼 이름만** 쓴다. 출력 데이터셋 컬럼과 맞추는 matchedColumns 계산이다.
- 이름은 **바꾸지 않고 드라이버가 준 그대로** 돌려준다(`/execute/sql` 의 rows·columns 소문자 규칙과 다르다). 따옴표 없는 별칭은 Tibero 가 대문자로 접어 `AMOUNT`, 따옴표 소문자 별칭은 `amount` 로 온다. 실행기가 소문자로 바꾸면 api 가 `AS "amount"` 와 `AS amount` 를 가를 수 없어 적재 문장이 없는 이름(`src."AMOUNT"`)을 참조한다.
  - api 의 정규화: 전부 대문자인 이름만 소문자로 바꿔 맞춘다. 그 밖(따옴표 소문자·대소문자 섞임)은 맞춤에 쓰지 않고, 그 이름을 소문자로 바꾼 것이 출력 컬럼과 같으면(출력 컬럼을 노린 별칭) 조용히 NULL 로 적재하지 않도록 단계를 「Tibero 결과 컬럼 이름은 따옴표 없는 별칭으로 써 주세요(<이름>).」로 실패시킨다.
- 타입 정보는 2단계 임시 데이터셋 생성 때 쓴다. 기존 `TiberoColumnType` 은 앱 타입 → Tibero DDL 방향뿐이라 역방향 매핑을 새로 정해야 한다.

`GET /actuator/health`
- JDBC 드라이버 클래스(`com.tmax.tibero.jdbc.TbDriver`)가 없으면 DOWN 이다. 기동 로그에도 ERROR 를 남긴다.

### 4.3 실행 규칙(`/execute/sql`)
1. **검증(실행기 자체, fail-closed)** — api 를 믿지 않고 다시 검사한다.
   - 선행 문장은 정확히 `DELETE FROM "<스키마>"."<테이블>"` 형태만 허용한다. 스키마는 그 테넌트 기술자의 schema 여야 한다.
   - 본문은 단일 문장이어야 한다. 따옴표·주석 밖의 `;` 는 거부한다(끝의 `;` 하나는 떼어 낸다).
   - 첫 키워드는 `SELECT`·`WITH`·`INSERT`·`UPDATE`·`DELETE`·`MERGE` 중 하나여야 한다.
   - DDL·제어 키워드가 문장 첫머리에 오면 거부한다: `CREATE`·`ALTER`·`DROP`·`TRUNCATE`·`RENAME`·`GRANT`·`REVOKE`·`COMMENT`·`BEGIN`·`DECLARE`·`CALL`·`EXEC`·`COMMIT`·`ROLLBACK`·`SAVEPOINT`·`SET`·`LOCK`.
   - 위험 함수 거부 목록(§5.4 와 같은 목록)에 걸리면 거부한다.
   - 어휘 분석기(따옴표·주석 인식)는 **Tibero(Oracle) 규칙**을 따른다. 기존 `sql-lexical` 벡터는 PG 규칙(중첩 주석, `$$`, `E'..'`)이라 쓰지 않는다.
     - Tibero 규칙: `--` 한 줄 주석, 중첩 없는 `/* */`, `'..'`(`''` 이스케이프), `"..."` 식별자, `q'[..]'` 류 대체 따옴표.
     - 이 규칙의 새 고정값 파일(`tibero-sql-lexical-vectors.json`)을 만들어 api·db-executor 가 함께 읽는다.
2. **트랜잭션 하나**: autoCommit 을 끈다. 세션 NLS 초기화 SQL(TiberoDialect 와 같은 문장)은 커넥션을 만들 때 실행한다.
3. **출력 잠금**: 선행 문장마다 그 DELETE **직전에, 별도 문장으로** `LOCK TABLE "<스키마>"."<테이블>" IN EXCLUSIVE MODE` 를 실행한다. 그다음 DELETE → 본문 → COMMIT 순서다. 실패하면 ROLLBACK 한다.
   - 이것은 Python executor 의 `pg_advisory_xact_lock` 과 같은 역할이다. 트랜잭션에 묶여 있어 COMMIT·ROLLBACK 때 풀린다. HTTP 호출이 시간 초과돼도 실행이 끝날 때까지 잠금이 남는다.
   - 대기가 끝난 뒤 시작하는 DELETE 는 별도 문장이라 앞 실행이 커밋한 결과를 본다. 그래서 중복이 생기지 않는다.
4. **시간 제한**: 문장마다 `setQueryTimeout(50s)` 를 건다. 전체가 55초를 넘기면 `cancel` → ROLLBACK → `success=false`. api 의 호출 시간 제한(60s)보다 반드시 짧아야 한다.
   - 55초(probe 30초)는 **요청이 실행기에 도착한 시점부터** 잰다. 기술자 조회(남은 시간까지만 기다림)·풀 대여·실행이 모두 이 안이다. 기한이 지나면 커넥션을 빌리지 않거나 실행하지 않고, 커밋 직전에 다시 확인해 지났으면 ROLLBACK 한다.
5. **오류**: `SQLException.getErrorCode()` 의 부호 있는 코드와 메시지를 `db_error` 로 돌려준다. 접속 정보·비밀번호는 메시지에 넣지 않는다.

### 4.4 접속
- 저장소 기술자: `GET {api}/internal/executor/tenants/{id}/data-store` (WD-75 엔드포인트를 그대로 쓴다). 성공만 60초 캐시한다. 테넌트별 단일 조회(single-flight)로 막는다. 조회가 실패하면 실행도 실패한다.
  - 커넥션 대여(로그인)가 실패하면 그 테넌트의 기술자 캐시를 지우고 그 요청은 실패로 끝낸다. 비밀번호가 바뀌었으면 다음 요청이 새 기술자로 풀을 다시 만든다.
- 풀: 테넌트별 Hikari 풀이다. 최대 4 커넥션, 풀 수 최대 8 이고 넘으면 LRU 로 닫는다(api `ExternalStoreConnections` 와 같은 값). 기술자의 계정·비밀번호가 바뀌면 풀을 새로 만든다.
- api 주소 기본값은 `http://api:8080` 이다(compose·k8s 공통 서비스명).

## 5. api 쪽 변경

### 5.0 사용자 SQL 의 스키마 표기 — PG 와 같은 원칙
- PG 에서는 테넌트마다 실제 스키마가 다르다(1번 테넌트는 `data`, 나머지는 `data_t{id}`). 사용자는 그 실제 이름을 붙여 쓰고(`data_t5.orders`), 이름 치환은 없다. 검증기는 `DataSchema.current()` 로 받은 실제 스키마로 한정된 참조만 허용한다.
- Tibero 테넌트도 같다. 실제 스키마 `GENIA_T{id}` 를 붙여 쓴다(`genia_t5.orders`). 따옴표 없는 식별자는 Tibero 처럼 대문자로 접어 비교한다. 물리 테이블명은 메타데이터 이름의 대문자다(`TiberoNames.physical`).
- 그래서 "현재 테넌트의 데이터 스키마"를 테넌트 백엔드에 따라 고르는 지점을 하나 둔다. PG 면 `DataSchema.current()`, Tibero 면 `GENIA_T{id}` 다(`DataStoreAccountRole` 규칙). 이 지점을 다음 넷이 함께 쓴다.
  - `SqlValidator`
  - 보안 게이트(`DatasetAccessGuard`)
  - `{{#N}}` 참조 치환(`PipelineAsyncRunner.resolveStepReferences`). Tibero 에서는 `GENIA_T5."ORDERS"` 꼴로 바꾼다.
  - 저장 시점 검증(`PipelineService`)
- 예외로 Tibero 테넌트는 한정 없는 `DUAL` 하나만 허용한다. 그 밖의 다른 스키마·사전 뷰(`ALL_*`, `DBA_*`, `USER_*`, `SYS.*`)는 PG 와 같이 거부한다.

### 5.1 라우팅
- `DbExecutorClient`(새로 만듦): `app.db-executor.base-url`(기본 `http://db-executor:8090`), 같은 내부 토큰, `/execute/sql` 60s, `/execute/probe` 35s.
- `PipelineAsyncRunner` 의 SQL 단계는 테넌트 백엔드가 TIBERO 면 `DbExecutorClient` 를 쓰고, 컬럼 탐지도 `/execute/probe` 로 보낸다. `executorEnabled` 분기를 타지 않는다.
- 다음은 Tibero 테넌트일 때 단계 시작 시 한국어 오류로 실패시킨다.
  - PYTHON·API_CALL·AI_CLASSIFY 단계
  - 증분 SQL 단계
  - 출력 데이터셋이 없는 SELECT 단계
  - 사용자가 직접 쓴 INSERT — Tibero 출력 테이블은 ID 기본값이 없어 실패한다. 「Tibero 저장소 테넌트에서는 INSERT 를 직접 쓸 수 없습니다. SELECT 결과를 출력 데이터셋에 적재해 주세요.」 UPDATE·DELETE 는 그대로 보낸다(사용자 MERGE 는 검증기가 거부). UPDATE 가 `_UPDATED_AT` 을 움직이지 않는 점은 2단계(증분) 과제다.
  - 예: 「Tibero 저장소 테넌트에서는 아직 Python 단계를 실행할 수 없습니다」
- 저장 시점에도 같은 조건을 알려 주면 좋지만, 1단계에서는 실행 시점 거부만 필수다.

### 5.2 Tibero 문장 생성(새 클래스 묶음, 예: `TiberoPipelineSql`)
PG 전용인 `OutputClearStatement`·`MergeSqlBuilder`·INSERT 래퍼·probe 래퍼의 Tibero 판이다. 이름 한정·대문자 물리명은 `TiberoNames`, 시스템 컬럼 규칙은 `TiberoDmlSql` 을 따른다.
- 비우기: `DELETE FROM "<SCHEMA>"."<TABLE>"`
- 추가/덮어쓰기 적재: `INSERT INTO t ("ID", <cols>, "_UPDATED_AT") SELECT <seq>.NEXTVAL, <cols>, SYSTIMESTAMP FROM (<사용자 SQL>)`. Tibero 테이블은 ID 기본값·트리거가 없어 시퀀스로 채워야 한다. 컬럼 대응(matchedColumns)은 PG 래퍼와 같은 규칙이다.
- 병합: `MERGE INTO t USING (<사용자 SQL>) s ON (<PK 일치>) WHEN MATCHED THEN UPDATE … WHEN NOT MATCHED THEN INSERT ("ID", …) VALUES (<seq>.NEXTVAL, …)`.
  - 기존 `TiberoDmlSql.mergeCore` 는 아무 USING 소스나 받는다(`promoteUpsertMerge` 가 이미 SELECT 소스를 넘긴다). 이것을 재사용한다.
  - PG(`MergeSqlBuilder`)와 같이 **바뀐 행만 갱신**한다: `UPDATE … WHERE` 에 NULL 을 같게 보는 비교(`DECODE(t.c, s.c, 0, 1) = 1` 의 OR)를 건다. 바뀐 행만 `_UPDATED_AT` 이 움직인다. 2단계 증분 읽기가 바뀌지 않은 행을 다시 읽지 않게 하려는 것이다.
  - 모든 컬럼이 키면 `WHEN MATCHED` 절을 빼고 INSERT 만 한다. PG 의 `DO NOTHING` 에 해당한다.
- 탐지: §4.2 의 `WHERE 1 = 0` 래핑은 실행기가 한다. api 는 결과 컬럼 이름 정규화와 예약어 검사(Tibero 예약어 목록)를 PG 경로와 같은 자리에서 적용한다.

### 5.3 오류 번역
- `SqlExecuteResult` 에 `db_error{code, message}` 를 더한다. PG 경로에서는 null 이다.
- `PipelineSqlErrorMessages` 에 Tibero 분기를 둔다. 코드 집합은 WD-74 `TiberoDialect` 의 것을 쓴다(중복 키, NOT NULL, 값 너무 큼, 테이블 없음 …). 번역된 한국어 문구는 PG 경로와 같은 뜻이 되게 맞춘다.
- `describeMergeError` 의 "같은 키가 원본에 두 번" 문구에 쓸 Tibero 코드(Oracle 의 ORA-30926 에 해당)는 아직 모른다. 구현 때 실서버에서 재현해 코드를 확인한 뒤 `TiberoDialect` 에 더한다.

### 5.4 SQL 검증기·보안 게이트
- `SqlValidator`(JSqlParser)와 보안 게이트는 읽는 테이블을 판정한다. Tibero 테넌트도 그대로 거친다(스키마는 §5.0).
- 구현 첫 작업으로 Tibero 문법 몇 가지를 실측한다: `ROWNUM`, `NVL`, `DECODE`, `(+)` 조인, `CONNECT BY`, `q'[..]'`.
  - 파싱이 안 되는 문법은 **거부한다**(fail-closed, 한국어 안내).
  - 어떤 문법이 통과하는지는 테스트로 고정한다.
- 게이트의 어휘 모호성 검사(`PgLexicalAmbiguityCheck`)는 PG 규칙이다. Tibero 테넌트에는 §4.3 의 Tibero 어휘 규칙으로 같은 검사를 한다.
  - 단 Tibero 규칙(Tibero 어휘 검사·`GENIA_T{id}` 스키마·DUAL 예외)은 **Tibero 에서 실행될 SQL**, 즉 파이프라인 SQL 단계 판정(`PIPELINE_SAVE`·`PIPELINE_RUN`)에만 쓴다. 대화형·분석·지표 SQL(`INTERACTIVE`)은 Tibero 테넌트여도 아직 PG 에서 실행되므로 PG 규칙(PG 어휘 검사·PG 데이터 스키마)으로 판정한다.
- **위험 함수 거부 목록(Tibero)**. 실행 계정이 테넌트 OWNER 라 테넌트의 모든 테이블을 읽을 수 있다. 그래서 문자열 안의 SQL 을 실행하는 함수로 게이트의 테이블 판정을 우회하면 보안 등급이 뚫린다. 다음은 대소문자 무관으로 거부한다. api 검증기와 db-executor 양쪽에서 거부한다.
  - `DBMS_`·`UTL_`·`OWA_` 로 시작하는 이름
  - 정확히 `HTP`·`HTF` 인 식별자(뒤에 식별자 문자가 오지 않음). Oracle 웹 툴킷 패키지 이름이 정확히 이것이라 막는 대상은 같고, 접두로 막으면 `HTF_RATE`·`HTPRICE` 같은 컬럼까지 거부한다.
  - `SYS.` 한정 호출
  - `XMLQUERY`·`XMLTABLE`·`XMLEXISTS`·`DBURITYPE`·`HTTPURITYPE`·`XDBURITYPE`
  - `CURSOR(` 식
  - 테넌트 스키마가 아닌 스키마로 한정된 함수 호출. 점 앞뒤의 공백·주석(`OTHER . FN(1)`, `OTHER /*c*/ . FN(1)`)도 한정 호출로 본다. 스키마 비교는 Tibero 식별자 규칙 그대로다 — 따옴표 없는 이름은 대문자로 접고, 따옴표 이름은 대소문자 그대로 비교한다(`"genia_t5"` 는 `GENIA_T5` 가 아니다). 검증기의 테이블 스키마 비교도 같은 규칙이다.
  - PG 의 기존 목록(`query_to_xml` 등)과 같은 취지다. 목록은 테스트로 고정한다.

### 5.5 JDBC jar 를 이미지에 넣는 경로(api 포함)
- 확인된 결함: api Dockerfile 이 `libs/` 를 복사하지 않는다. 그래서 **지금 운영 api 이미지에는 Tibero JDBC 가 없다.** WD-74 는 맥에서만 실서버 검증됐다.
- api·db-executor Dockerfile 모두 `COPY libs/ libs/` 로 바꾼다. 빈 폴더라도 복사되도록 `libs/.gitkeep` 를 커밋한다(`*.jar` 는 계속 무시한다).
- api 는 `app.data-store.tibero-enabled=true` 인데 드라이버가 없으면 기동 로그에 ERROR 를 남긴다. db-executor 는 §4.2 대로 health 를 DOWN 으로 둔다.
- 배포 문서에 "이미지를 빌드하는 컴퓨터의 `apps/firehub-api/libs/`, `apps/firehub-db-executor/libs/` 에 공식 jar 를 둔다"와 확인 명령을 적는다. 재배포 라이선스는 사용자가 확인한다(설계 범위 밖).

## 6. 배포·개발 환경
- `docker-compose.prod.yml` 에 `db-executor` 서비스를 추가한다. 토큰은 `EXECUTOR_INTERNAL_TOKEN`, api 주소는 `http://api:8080` 이다. api 에는 `DB_EXECUTOR_BASE_URL` 을 준다.
- `scripts/deploy.sh` 에 `db-executor` 대상을 추가한다.
- `apps/firehub-db-executor/package.json` 은 api 처럼 gradle 스크립트를 감싼다(turbo 의 test·build 에 포함).
- 로컬: `pnpm --filter firehub-db-executor dev`(포트 8090). api `application-local.yml` 의 `app.db-executor.base-url` 은 `http://localhost:8090` 이다.
- `.claude/docs/deploy.md` 에 db-executor 절을 쓴다. 내용은 배포 순서(api 먼저), 토큰, jar 반입, k8s 에 Deployment·Service(`db-executor:8090`)가 필요하다는 점이다. 차트 수정은 사용자 승인 뒤에 한다.

## 7. 코드 공유 결정
1단계는 공용 Gradle 모듈을 만들지 않는다.
- 리포에 Gradle 멀티 프로젝트가 없다. api `generateJooq` 가 상대 경로에 기대고 있다. Dockerfile 빌드 컨텍스트도 바뀌어야 한다. 마감 안에 감당할 위험이 아니다.
- db-executor 가 필요한 Tibero 코드는 작다. 세션 초기화 SQL, 오류 코드 꺼내기, 값 변환(NUMBER·날짜), 어휘 분석기, 위험 함수 목록 정도다. 이것만 옮겨 쓴다.
- 같은 결과를 내는지는 고정값 파일을 api·db-executor 가 함께 읽는 테스트로 묶는다.
  - 기존 `tibero-executor-vectors.json` 에는 세션 초기화 SQL·계정명·시퀀스명만 있다. 값 변환(NUMBER 자릿수·날짜)과 컬럼명 대소문자 규칙 벡터를 이 파일에 새로 더한다.
  - 어휘 규칙과 위험 함수는 새 `tibero-sql-lexical-vectors.json` 에 둔다.
- 2단계에서 `/execute/load`(INSERT 빌더·값 변환이 커짐)를 만들 때 공용 모듈로 뺀다. 이것은 /simplify·리뷰에서 "중복" 지적이 나와도 1단계에서는 의도된 선택으로 본다.

## 8. 테스트
- **db-executor 단위 테스트**: 검증 규칙(허용·거부 문장 목록, 고정값 벡터), 기술자 클라이언트(캐시·단일 조회·실패 시 실패), 풀 교체, 시간 초과 → 취소·롤백(모의 JDBC), 오류 응답 형태, 인증(401·상수 시간).
- **db-executor 실서버 테스트**(`TIBERO_TEST_*` 가 없으면 건너뜀, `scripts/tibero-test.sh` 에 추가):
  - REPLACE·APPEND·MERGE 결과
  - 같은 출력에 REPLACE 두 개를 동시에 돌려도 중복이 없음
  - 실행 중 시간 초과 시 롤백돼 기존 행이 남음
  - DDL·PL/SQL·다중 문장·위험 함수 거부
  - probe 컬럼 메타
  - NLS 날짜 형식
  - MERGE 원본 키 중복 시 오류 코드(§5.3 확인용)
  - 바뀐 행만 `_UPDATED_AT` 이 움직임
- **api 테스트**:
  - Tibero 테넌트 SQL 단계가 `DbExecutorClient` 로만 간다(`ExecutorClient`·`SqlScriptExecutor` 는 호출되지 않음, 로컬 모드 포함).
  - db-executor 주소가 없거나 실패하면 단계가 실패한다.
  - Tibero 문장 생성 결과
  - 오류 번역
  - Python·API_CALL·AI_CLASSIFY·증분·출력 없는 SELECT 단계 거부
  - 스키마 판정: `genia_t5.orders` 허용, `data_t5.orders`·다른 테넌트 스키마·사전 뷰 거부, `DUAL` 허용
  - `{{#N}}` 치환 결과
  - 위험 함수 거부
  - PG 테넌트 기존 테스트 전부 그대로 통과
- **끝-끝 확인**: 맥에서 db-executor 를 띄우고 실서버로 api 단위 수준의 연결 확인까지 한다. 로컬 api 테스트 환경은 알려진 고장(pgvector 핀·호스트명)이 있어, 전체 UI 흐름 확인은 WD-81 로 넘긴다.

## 9. 2단계 이후(참고, 이번 범위 아님)
- 출력 없는 SELECT 의 임시 데이터셋: Tibero 30자 이름 규칙, probe 타입 → 앱 타입 역매핑(안정적이어야 `TempDatasetService` 가 매번 재생성하지 않는다).
- `/execute/load`: Python executor 가 API 수집·Python 결과를 Tibero 테넌트일 때 db-executor 로 나눠 보낸다(api 경유 없음). WD-75 의 `DbBackend` 자리에 원격 적재 백엔드를 끼운다.
- Tibero 임시 테이블 교체(`createTempTable`/`swapTable`) 구현 → API 수집·Python·AI 분류의 REPLACE.
- Tibero 증분 커서(Tibero 시계 기준).
- 공용 Gradle 모듈, WD-75 ODBC 경로 제거, db-executor 전용 토큰(기술자 비밀번호를 Python executor 가 받을 수 없게).
- `/execute/query`(WD-76 분석 쿼리), Python 입력 읽기(WD-78).

## 10. 구현자에게 맡기는 것
- 패키지 구조·클래스 이름(위 이름은 예시)
- 어휘 분석기 구현 방식(고정값 벡터만 맞으면 된다)
- Hikari 세부 설정 중 위에 없는 값
- 한국어 오류 문구의 정확한 표현(뜻은 PG 경로와 같게)

## 11. 설계자가 정한 것(사용자 확인 필요 항목 표시)
- **[확인 필요] 출력 잠금을 메타데이터 PG advisory 잠금이 아니라 Tibero `LOCK TABLE … IN EXCLUSIVE MODE` 로 한다.**
  - WD-77 본문은 "advisory lock 은 메타데이터 PG 에서 유지"라고 적었다. 그런데 api 가 PG 세션 잠금을 쥐고 HTTP 로 실행기를 부르면, 60초 시간 초과 때 잠금이 먼저 풀린다. 그동안 Tibero 쓰기는 아직 진행 중일 수 있어 #731 중복이 재발한다.
  - Tibero 테이블 잠금은 쓰기 트랜잭션에 묶여 이 구멍이 없다.
  - 대가: 그 테이블에 대한 다른 DML(가져오기 등)이 단계가 끝날 때까지 기다린다. SELECT 는 막지 않는다.
- 사용자 SQL 표기: PG 와 같이 실제 스키마를 붙여 쓴다(`genia_t5.orders`, 사용자 확인 2026-10-22).
- 출력 없는 SELECT(임시 데이터셋)는 2단계로 미룬다. 마감 안에 이름 규칙·타입 역매핑까지 정하기 어렵기 때문이다.
- 위험 함수는 거부 목록으로 막는다(§5.4). 목록에 없는 우회 함수가 발견되면 목록에 더한다. 허용 목록 방식은 Tibero 내장 함수가 많아 1단계에서 하지 않는다.
- 서비스 이름 `firehub-db-executor`: MySQL(WD-82)도 같은 JDBC 실행기로 받을 수 있게 저장소 종류를 이름에 넣지 않았다.
- 포트 8090, 시간 제한 50s/55s/60s.
- 1단계는 공용 모듈 없이 고정값 벡터로 같은 동작을 묶는다(§7).
