# ADR 0001 — 플랫폼 DB Tibero 지원 방식

- 상태: **결정됨** (2026-11-05 — 1·5번은 사용자가 고르고, 나머지 제안은 사용자가 그대로 확정)
- 날짜: 2026-11-05
- 이슈: WD-489 (상위 WD-70), 근거 PoC: WD-487(MyBatis), WD-488(Flyway·JobRunr)
- 실측 상세: WD-70·WD-487·WD-488 이슈 코멘트

## 배경

플랫폼 자체 DB(메타데이터)로 PostgreSQL에 더해 Tibero도 설치할 때 고를 수 있게 한다. PostgreSQL을 버리지 않고 두 DB를 함께 지원한다.

2026-11-04에 이미 정한 것(WD-70 코멘트 「결정 사항」):

- 데이터 저장소는 Tibero 설치본에서도 다른 DB(PG 포함)를 고를 수 있다.
- 벡터·키워드 검색은 새 벡터 DB를 도입하기 전까지 Tibero 빌드에서 끈다.
- 접근 계층은 jOOQ 상용 방언이 아니라 MyBatis로 한다.
- 개발 Tibero는 트라이얼이 만료되는 2026-12-06까지 쓴다.

이 문서는 PoC 결과를 받아 나머지를 정한다.

현재 규모(main `d372e365` 기준):

- 플랫폼 리포지토리: 인터페이스 72개, jOOQ 구현 73개(약 15,600줄, 25개 도메인)
- 리포지토리 밖에서 `DSLContext` 를 이름으로라도 언급하는 클래스는 31개다. 그중 8개는 주석에만 나온다. 실제로 쓰는 23개의 분류는 아래 「리포지토리 밖 jOOQ 사용처」 표에 있다.
- Flyway 마이그레이션 155개(최신 V155)

## PoC 요약

| 항목 | 결과 |
|---|---|
| MyBatis 리포지토리 3개(UPSERT·잠금 큐·JSON·가시성 조건) | PG 계약 테스트 110건, Tibero 실서버 51건 통과(1건은 RLS 전제라 WD-492로 미룸) |
| SQL 공유율 | 약 30개 문장 중 **Tibero 전용은 5개**. 나머지는 `databaseId` 하나로 PG·Tibero가 함께 쓴다. |
| 작성량 | 리포지토리당 jOOQ의 1.3~1.7배(평균 1.5배). 한 번만 드는 공통 부분 349줄이 따로 있다. |
| Flyway | Oracle 모듈을 상속한 플러그인(클래스 4개)으로 migrate·info·validate·repair·baseline·PL/SQL 블록이 동작한다. clean은 Tibero 전용으로 바꿨다. 계정 생성·삭제는 막았다. |
| JobRunr | `OracleStorageProvider` 1회전 통과. 단 **JVM이 UTC일 때만** 그렇다. |
| 드라이버 공통 함정 | 시각의 오프셋을 버리거나 JVM 기본 시간대로 저장한다. UUID·`setNull(OTHER)` 은 지원하지 않는다. 생성 키로 ROWID 를 돌려준다. 따옴표 없는 별칭은 대문자로 온다. |
| 잠금 큐 | `FETCH FIRST … FOR UPDATE` 는 구문 오류다. `ROWNUM` 과 `SKIP LOCKED` 를 함께 쓰면 두 번째 워커가 한 건도 못 가져간다. 그래서 행 수 제한 없이 `SKIP LOCKED` 로 가져오고 앱에서 자른다. |

## 리포지토리 밖 jOOQ 사용처

리포지토리 구현 73개 말고도 jOOQ 를 직접 쓰는 곳이 있다. Tibero 설치본에서 어느 연결을 써야 하는지로 나누면 다음과 같다. 대부분은 **주 `@Primary` DSLContext(= 플랫폼 DB 연결)로 PG 데이터 스키마를 다루는** 코드다. 지금은 플랫폼 DB 와 데이터 저장소가 같은 PG 라서 맞게 동작한다.

| 묶음 | 클래스 | 실제 대상 | 맡는 이슈 |
|---|---|---|---|
| PG 데이터 저장소 | `PostgresDataTableStore`·`PostgresDataRowStore`·`DataTableQueryService`·`PgRowSearchIndex`·`SearchSourceReader`·`StagingTableCleanupService`·`AnalyticsQueryExecutionService`·`IncrementalCursorService`·`VerbatimSql`·`Adhoc*`·`TemporalSafeFetch` | PG `data` 스키마(사용자 테이블·사용자 SQL) | **WD-494 완료** — PG 데이터 저장소 전용 DSLContext(`@DataStoreDb`)로 옮겼다. Tibero 설치본에서 플랫폼 DSLContext 는 Tibero 를 가리키기 때문이다. |
| PG 롤·스키마 준비 | `TenantSchemaProvisioner`·`TenantPipelineRoleProvisioner`·`TenantPipelineRoleBootstrap`·`PipelineSandboxDataSourceConfig`·`TenantPipelineDataSourceRegistry` | PG 테넌트 스키마·파이프라인 롤(소유자 연결 포함) | **WD-494 완료** — 데이터 저장소 소유자 연결로 옮겼고, 테넌트 롤은 기동 치유가 만든다 |
| 플랫폼 + 데이터 저장소 혼합 | `PythonReadGrantSync`·`TiberoPythonReadGrantSync`·`PythonReadGrantBackend` | 플랫폼 메타 테이블(jOOQ 생성 테이블)을 읽고, 데이터 저장소에 GRANT 를 한다 | 메타 읽기는 **WD-494 완료**(`DatasetRepository.findSchemaMetas`·`findTableLevels`). staging 정리 게이트는 JobRunr 테이블을 직접 읽지 않고 JobRunr `StorageProvider.getJobStats()` 를 쓴다(Tibero 는 JobRunr Oracle 방언). 잠금: PG 백엔드는 데이터 저장소에서 잡는다(`lockTenantSync`). Tibero 데이터 저장소 백엔드는 `PlatformLocks` 로 플랫폼 DB 에서 잡고, Tibero 맞바꿈 경로는 JVM 안 잠금뿐이다(알려진 빈틈, 아래 별도 이슈 후보) — **WD-493 완료** |
| PG 전용 플랫폼 구현 | `PostgresPlatformLocks`·`PostgresPlatformTenantSession` | 플랫폼 DB(PG) | Tibero 짝: 잠금은 **WD-493 완료**(`TiberoPlatformLocks`), 테넌트 세션은 **WD-492 완료**(`TiberoPlatformTenantSession`, 결정 7) |

→ WD-491(리포지토리 73개)의 범위는 바뀌지 않는다. 다만 WD-494 는 "연결 분리" 만이 아니라 **위 18개 클래스의 DSLContext 를 바꿔 다는 일** 이다. 규모 M → L 로 본다.

## 결정

### 1. 접근 계층 — **A. 두 벌 유지** (사용자 결정)

PG는 jOOQ 구현을 그대로 쓰고, Tibero용 MyBatis 구현(`repository/mybatis/MyBatisX`)을 따로 둔다. 리포지토리 인터페이스(WD-485)는 하나이고, 설치본의 플랫폼 DB에 따라 구현 빈을 고른다.

검토한 대안:

| | A. 두 벌 유지 (채택) | B. MyBatis 한 벌(`databaseId`) |
|---|---|---|
| 내용 | PG는 jOOQ 그대로, Tibero만 MyBatis 구현 | PG도 MyBatis로 옮기고 다른 SQL만 `databaseId` 로 분기 |
| 처음 비용 | MyBatis 구현 73개(Tibero용) | 같은 73개 + PG 전환 검증 |
| 이후 비용 | 리포지토리를 고칠 때마다 두 벌 | 한 벌(다른 SQL만 문장 단위로 둘) |
| PG 운영 위험 | **없음** — 운영 중인 PG 경로를 바꾸지 않는다 | PG 운영 경로가 바뀐다, 컴파일 시 SQL 검사를 잃는다 |

**채택 이유**:

- 운영 중인 PG 경로와 jOOQ 의 타입 안전성을 건드리지 않는다.
- Tibero 는 고객 요구가 확정되지 않은 선택지다. 그 비용을 PG 쪽이 지지 않게 한다.

**감수하는 것**: 리포지토리를 바꿀 때마다 두 벌을 고쳐야 한다. 두 벌이 조용히 어긋날 위험이 있다.

**두 벌이 어긋나지 않게 하는 규칙**:

1. **계약 테스트 필수**
   - 리포지토리 인터페이스마다 추상 계약 테스트(`*ContractTest`) 하나를 둔다.
   - jOOQ 구현과 MyBatis 구현이 같은 테스트를 상속해 돈다(WD-485·WD-487 구조).
   - 계약 테스트가 없는 리포지토리는 MyBatis 구현을 만들기 전에 테스트부터 만든다.
2. **MyBatis 구현도 PG CI 에서 돈다**
   - MyBatis 매퍼는 `databaseId` 로 PG·Tibero 문장을 함께 갖는다. PoC 기준 약 83% 가 공유 문장이다.
   - 그래서 PG Testcontainers 에서도 MyBatis 계약 테스트가 돈다. Tibero 실서버가 없어도 매 PR 에서 두 구현의 동작 차이를 대부분 잡는다.
   - Tibero 전용 문장(PoC 기준 30개 중 5개)만 Tibero 실서버 검증에 기댄다.
   - 운영 PG 는 MyBatis 를 쓰지 않는다. 테스트 프로필에서만 배선을 켠다(지금 WD-487 방식).
3. **구현 짝 검사**(ArchUnit 또는 테스트)
   - 모든 플랫폼 리포지토리 인터페이스는 jOOQ 구현과 MyBatis 구현을 하나씩 가진다.
   - MyBatis 구현이 없는 인터페이스는 허용 목록에 이유와 함께 올린다. 2단계가 진행되는 동안 이 목록이 줄어든다.
4. **변경 규칙**: 리포지토리 인터페이스나 jOOQ 구현을 바꾸는 PR 은 MyBatis 구현과 매퍼 XML 도 함께 바꾼다. 검토 체크리스트에 넣는다.
5. **MyBatis 설정**
   - 지역 캐시는 문장 단위(`localCacheScope=STATEMENT`)로 둔다.
   - jOOQ 중첩 트랜잭션(savepoint)과 Spring `REQUIRED` 의 의미 차이는 구현마다 주석으로 남긴다(PoC `claimDue`).

**구현 선택·짝 검사·하네스(WD-706, WD-491 묶음 ①에서 정함)**:

- **배선(구현 선택).**
  - `MyBatisPlatformConfig` 는 `firehub.platform-db.mybatis.enabled=true`(테스트 프로필)이거나 `firehub.platform-db.type=tibero` 일 때 켜진다(`MyBatisPlatformCondition`).
  - MyBatis 구현은 `@Repository` 대신 표지 `@PlatformMyBatisRepository` 를 단다. 등록기(`PlatformMyBatisRepositoryRegistrar`)가 이를 스캔해 빈으로 올리고, 빈 이름은 클래스 이름을 소문자로 시작한 것이다.
  - Tibero 에서는 MyBatis 구현이 일반 주입 후보다. 그 밖(PG 테스트 프로필)에서는 `autowireCandidate=false` 라 계약 테스트만 이름으로 꺼낸다.
  - 짝이 생긴 jOOQ 구현은 `@ConditionalOnPostgresPlatformDb` 를 단다. 그래서 Tibero 에서 그 인터페이스의 후보는 MyBatis 하나뿐이다. PG 운영 컨텍스트에는 MyBatis 빈이 하나도 생기지 않는다(`MyBatisPlatformConfigGateTest`).
- **구현 짝 검사.**
  - `PlatformRepositoryPairTest` 는 jOOQ `@Repository` 가 구현하는 플랫폼 리포지토리 인터페이스마다 `@PlatformMyBatisRepository` 구현이 있는지 본다. 없으면 `PENDING`(인터페이스 → 묶음 번호)에 있어야 한다.
  - `PENDING` 에 있는데 짝이 생기면 실패한다. 그래서 목록이 줄어들 수밖에 없다.
  - 짝이 있는 jOOQ 구현에 `@ConditionalOnPostgresPlatformDb` 가 없어도 실패한다.
  - 묶음 ① 뒤에는 ②~⑦ 항목만 남았고, 묶음 ②·⑦(WD-707·712) 뒤에는 ③~⑥ 36개가 남는다.
- **Tibero 계약 테스트 하네스.**
  - 운영과 같은 두 계정 구조다(결정 7). 소유자 계정이 Flyway 를 적용하고 픽스처·정리를 맡는다. 리포지토리는 런타임 계정 풀(`CURRENT_SCHEMA=<소유자>`) 위에서 `TenantAwareTransactionManager` + `TiberoPlatformTenantSession` 으로 돈다.
  - 그래서 Tibero 계약 테스트도 DBMS_RLS 를 거친다. 소유자 계정은 정책 함수가 거르지 않으므로, 소유자로 돌리면 테넌트 차단 시험이 아무것도 보지 못한 채 통과한다.
  - 공유 하네스는 `TiberoPlatformSchema.shared()` 다. 런타임 풀은 2개다.
- **문장에 테넌트를 쓰지 않는다.**
  - Tibero 도 컨텍스트 DEFAULT 가 `tenant_id` 를 채우고 RLS 가 읽기·쓰기를 거른다. 그래서 MyBatis 문장은 PG·jOOQ 처럼 `tenant_id` 를 INSERT 값이나 WHERE 술어에 넣지 않는다.
  - 예외는 인터페이스가 테넌트를 인자로 받는 문장이다. `FH_TENANT.set_tenant`/`clear_tenant` 를 매퍼 XML 에서 부르는 것은 소스 검사가 막는다.
  - 결과로 쓰기는 트랜잭션을 시작할 때 심은 세션 테넌트에 적용된다. 트랜잭션 안에서 `TenantContext` 를 다른 테넌트로 바꿔 써서는 안 된다.
- **빈 문자열.**
  - Tibero 는 `''` 를 NULL 로 저장한다. 읽을 수 있는 열에는 대체 값(센티널)을 저장하지 않고 Tibero 고유 의미를 따른다. NULL 허용 열은 null 로 돌아오고, NOT NULL 열은 빈 값을 거부한다. 계약 테스트는 DB별 기대로 이 차이를 고정한다.
  - 조회에만 쓰는 인증 키는 예외로 `EmptyKeys` 의 센티널 하나를 쓴다.
  - 서비스 쪽 대응은 `smtp.*` 설정뿐이다. 빈 값은 행 삭제로 저장한다.

**묶음 ②·⑦ 에서 더한 규칙(WD-707·WD-712)**:

- **꺼진 기능 메서드.** 벡터·트라이그램 메서드 가운데 서비스가 `PlatformDbFeatures` 확인 뒤에서만 부르는 것은 MyBatis 구현이 `PlatformFeatureOff` 로 `UnsupportedOperationException` 을 던진다(빈 결과로 숨기지 않는다). 가드 없이 닿는 메서드는 Tibero 에 있는 테이블만으로 구현한다. 계약 테스트는 `supportsVectorSearch()`/`supportsTrigramSearch()` 훅이 false 면 시나리오 대신 예외를 단언한다.
- **잠금.** Tibero 에는 `FOR KEY SHARE`·`FOR SHARE`·`FOR NO KEY UPDATE` 가 없어 모두 `FOR UPDATE` 로 둔다(더 강한 잠금, 정확성 유지).
- **정책 없는 테이블.** `tenant_data_store`·`tenant_data_store_account`·`tenant` 는 RLS 가 거르지 않으므로 jOOQ 의 테넌트 조건을 문장에 그대로 둔다. `public.` 스키마 한정은 쓰지 않는다.
- **JSON 배열 포함.** 트리거 `datasetIds` 포함 판정은 Tibero 에서 `JSON_TABLE` 원소 비교로 한다(lax `JSON_EXISTS` 는 문자열 "5" 도 맞히고, 텍스트 비교는 길이 한도·부분 문자열 오탐이 있다). `JSON_TRANSFORM` 이 없어 배열 원소 제거는 잠근 뒤 자바에서 고쳐 쓴다(묶음 ③ `TriggerRepository`).
- **시각.** `TIMESTAMP` 는 UTC 벽시계로 저장하고 `Instant` 는 `UtcInstantTypeHandler` 로 바인딩한다. jOOQ 가 JVM 로컬 벽시계(`LocalDateTime`·`LOCALTIMESTAMP`)를 쓰는 열(`ai_session`, `document_file` 응답)은 MyBatis 도 같은 기준을 지킨다. 계약 테스트에 JVM 기본 시간대를 Asia/Seoul 로 바꾼 시나리오를 둔다.
- **바이트 한도.** Tibero 문자열 열은 `VARCHAR2(65532)` 바이트다. 예외 메시지처럼 길이가 정해지지 않은 값은 `TiberoTextLimits` 로 대리 쌍을 깨지 않고 자른다.
- **쿼리 시간 제한.** 매퍼 인자 `queryTimeoutSeconds` 를 `StatementTimeoutInterceptor` 가 `Statement.setQueryTimeout` 으로 건다(이미 걸린 더 짧은 트랜잭션 제한은 유지). Tibero 는 문장 취소 때 연결을 닫는다.
- **중복 키.** MERGE 중복 키는 사이트마다 1회 재시도가 기본이다. 임대(`EmbeddingReembedState`·`SearchIndexState.createIfAbsent`)는 중복 키를 "이미 있음/획득 실패"로 본다 — 대기 쪽은 승자 커밋 뒤에야 실패하므로 재시도해도 결과가 같다.
- **IDENTITY 다건 삽입.** Tibero `INSERT ALL` 은 IDENTITY 를 문장당 한 번만 매겨 중복 키가 난다. 다건 삽입은 행마다 INSERT 한다.
- **묶음 ⑥ 인계.** 데이터셋 가시성 SQL 일부는 아직 jOOQ `DatasetAccessRepository` 가 만든 문자열을 끼우고, AI 정책 허용 규칙은 `securitylevel/DatasetVisibility.xml` 조각 하나로 MyBatis 에 따로 있다. 묶음 ⑥ 이 `DatasetAccessRepository`·`EmbeddingAiPolicyRepository`·`SecurityLevelRepository` 를 옮길 때 합친다.

### 2. 마이그레이션 이원화 규칙

- **경로**:
  - PG 는 지금 그대로 `db/migration` 이다.
  - Tibero 는 `db/migration-tibero` 이다. 기준선은 **WD-490 을 시작할 때의 PG 최신 버전**에 해당하는 스키마 한 파일이다(지금은 V155).
  - `db/migration/tibero` 처럼 하위 폴더로 두지 않고 형제 폴더로 둔다. Flyway 는 위치를 재귀로 훑기 때문에, 하위 폴더에 두면 Tibero 파일이 PG migrate 와 jOOQ 코드젠에 섞여 들어간다. WD-70 본문의 `db/migration/{vendor}` 안을 따르지 않는 진짜 이유다.
  - Tibero 프로필은 `spring.flyway.locations` 를 **명시**하고 `fail-on-missing-locations=true` 를 둔다.
  - `{vendor}` 는 쓰지 않는다. Spring Boot 가 Tibero 를 몰라 글자 그대로 남고, 마이그레이션 0건으로 기동이 "성공"한다(WD-488 실측).
- **이후 스키마 변경**:
  - 기준선 뒤의 PG 마이그레이션 Vn 마다 Tibero 쪽에도 같은 버전 Vn 을 둔다.
  - Tibero 에 할 일이 없으면 주석만 있는 빈 파일을 둔다. Flyway 플러그인(Oracle 파서)이 이 파일을 성공으로 기록하는 것을 WD-490 에서 실서버로 확인했다(`TiberoBaselineSchemaLiveTest`).
  - 기준선은 스키마 `V155` 와 시드 `V155.1` 두 파일이다(DML 을 DDL 과 섞지 않는 규칙). 제외 목록·타입 대응·모양이 바뀐 인덱스는 `V155` 머리말에 적었다.
  - CI 테스트로 두 경로의 버전 집합이 같은지 검사한다(`MigrationParityTest`, WD-490).
- **Tibero 마이그레이션 작성 규칙**(DDL 이 암묵 커밋하므로):
  - 1파일 1DDL 이 기본이다.
  - 여러 DDL 이 필요하면 멱등 블록으로 쓴다(사전 뷰로 존재 확인 + `EXECUTE IMMEDIATE`).
  - DML 은 DDL 과 같은 파일에 섞지 않는다.
- **실패 복구**:
  - 설치 기준선이 실패하면 스키마를 비우고 처음부터 다시 적용한다.
  - 증분 마이그레이션이 실패하면 남은 객체를 확인하고, 파일을 고치거나 멱등화한 뒤 repair → migrate 한다.
  - repair 는 이력만 정리하고 반쯤 적용된 객체는 남긴다(실측).
- **타입 대응**(WD-490 에서 확정):
  - BOOLEAN → `NUMBER(1)`
  - TEXT → `VARCHAR(n)`, 큰 값은 CLOB(GROUP BY·DISTINCT 불가에 주의)
  - JSONB → `JSON`
  - TIMESTAMPTZ → UTC 를 담는 `TIMESTAMP`. TSTZ 컬럼은 쓰지 않는다(WD-260 결함).
  - `''` 는 NULL 로 바뀐다. `DEFAULT ''` 4곳과, 코드가 NOT NULL 컬럼에 `''` 를 쓰는 곳을 점검한다.
- **JobRunr 테이블**: Tibero 기준선 DDL 에 JobRunr Oracle 스키마를 넣는다. PG 가 V85 로 하는 방식과 같다. 런타임 롤에 DDL 권한을 주지 않기 위해서다.
- **Flyway 플러그인 유지**:
  - 플러그인은 Flyway 내부 API(패키지 전용 생성자)에 기댄다.
  - Flyway 를 올릴 때 `TiberoFlywayDatabaseTypeTest` 와 Tibero 실서버 PoC 를 다시 돌리는 것을 업그레이드 조건으로 둔다.

### 3. Tibero 빌드에서 끄는 기능

| 기능 | 이유 | 방식 |
|---|---|---|
| 벡터(의미) 검색 — AI 문서·데이터셋 검색 | Tibero 7.2 에 VECTOR 가 없다 | `PlatformDbFeature.VECTOR_SEARCH` 를 Tibero 기본값으로 끈다(WD-486 플래그) |
| 키워드(트라이그램) 검색 | pg_trgm 에 대응하는 기능이 없다 | `TRIGRAM_SEARCH` 를 끈다 |
| LISTEN/NOTIFY 즉시 깨우기 | 없다 | 끄지 않고 바꾼다. `DBMS_ALERT` 로 깨운다(WD-493 완료). 끄면 폴링만 쓰고 지연을 감수한다 |

화면 안내:

- API 가 설치본의 기능 플래그를 내려준다.
- 웹은 꺼진 기능의 입력을 숨기지 않고 비활성으로 두고, "이 설치본(Tibero)에서는 지원하지 않습니다"를 표시한다(WD-495).
- AI 에이전트 도구 목록에서도 빠지게 한다.
- 행 검색·공간 기능은 **데이터 저장소** 쪽 기능이라, 데이터 저장소가 PG 면 Tibero 설치본에서도 그대로 쓴다.

### 4. 플랫폼 DB × 데이터 저장소 조합

| 플랫폼 DB | 데이터 저장소 | 지원 |
|---|---|---|
| PG | PG(같은 DB) | 지금 그대로 |
| PG | Tibero 등 외부 | 지금 그대로(WD-69) |
| Tibero | Tibero | 지원 |
| Tibero | PG | 지원 — PG 데이터 저장소 연결을 플랫폼 DB 연결과 분리한다(WD-494) |

#### 분리 모드(WD-494)

`firehub.data-store.postgres.separate=true` 면 `data-store` Hikari 풀(`app_tenant` 롤)이 따로 생기고 데이터 저장소 쪽 jOOQ 는 이 풀을 쓴다. 기본값(false)에서는 플랫폼 풀과 같은 인스턴스라 PG 설치본은 달라지지 않는다. `application-tibero.yml` 이 이 값을 켜고, 접속 정보는 환경변수로 받는다.

- **두 DB 는 원자적이지 않다.** 스프링 트랜잭션 하나당 데이터 저장소 연결 하나를 열고 beforeCommit 에서 먼저 커밋한다. 그래서 데이터 저장소는 커밋됐는데 플랫폼 쪽이 롤백될 수 있다(이때 경고 로그를 남긴다). 데이터 저장소 연결에는 테넌트 GUC 를 걸지 않는다.
- **새 설치만 지원한다.** 데이터 저장소 Flyway 기준선(`db/datastore-postgres` V1)은 분리 모드에서만 돌고, 빈 DB 만 받는다. 데이터가 든 기존 DB 를 분리 모드로 옮기는 길은 없다.
- **소유자 계정**(`owner-url`·`owner-username`)에는 superuser(`CREATE EXTENSION postgis`), `pg_read_all_stats`(없으면 `fh_incremental_cursor_candidate` 가 오류 없이 틀린 값을 돌려주고, `fh_python_read_sessions_before` 가 모든 슬롯 세션을 미확인 표식 전으로 세어 표식 슬롯의 PYTHON 실행이 계속 거부된다 — 기동 때 WARN, WD-713. PG16 이상은 `WITH INHERIT TRUE` 로 준다. 점검·조치는 `.claude/docs/deploy.md` V159 절), `app_tenant` 에 대한 ADMIN OPTION 이 필요하다.
- **같은 클러스터면 `app_tenant` 비밀번호도 같아야 한다.** `app_tenant` 는 PG 클러스터 전역 롤이다. PG 플랫폼 DB 와 분리 모드 데이터 저장소가 한 클러스터에 있으면 플랫폼 Flyway(`spring.datasource.password`)와 데이터 저장소 Flyway(`firehub.data-store.postgres.password`)가 기동마다 서로 덮어써 플랫폼 풀 접속이 깨진다. 막지는 않고, 두 값이 다르면 기동 시 경고 로그를 남긴다.
- **테넌트 파이프라인 롤은 기동 치유(`TenantPipelineRoleBootstrap`)가 데이터 저장소에 만든다.** 플랫폼 마이그레이션이 만들어 주지 않는다.
- **배포 차단 사항 해제 — WD-493 완료.** 출력 테이블 잠금을 SQL REPLACE 출력 잠금과 같은 DB(데이터 저장소)에서 잡도록 옮겼다(이후 WD-690 에서 세션 잠금이 트랜잭션 범위 잠금으로 바뀌었다). 이제 분리 모드를 배포할 수 있다(남은 선행 이슈는 WD-491 — WD-492 테넌트 격리는 결정 7 로 끝났다).
- **WD-491 이 MyBatis 짝을 만들어야 하는 새 플랫폼 리포지토리 메서드:** `DatasetRepository.findSchemaMetas`, `DatasetRepository.findTableLevels`. (staging 정리 게이트는 JobRunr `StorageProvider` 를 써서 MyBatis 짝이 필요 없다.) 테넌트 격리(WD-492)에서 넘어온 WD-491 일은 결정 7 「넘겨받을 일」에 있다.
- **회귀:** Gradle 태스크 `separateDataStoreTest` 가 데이터 저장소 패키지를 분리 모드로 돌린다.

#### 잠금 배치 규칙(WD-493)

두 저장소를 가로지르는 직렬화는 아래 규칙으로 정리했다. 스펙 4절 표가 직렬화별 저장소를 정한다.

- 데이터 저장소를 지키는 잠금은 데이터 저장소에서 잡는다(출력 테이블 잠금(`OutputSwapLock`, 트랜잭션 범위), SQL REPLACE 출력 잠금, PG 백엔드의 Python 읽기 권한 동기화 `lockTenantSync`).
- Tibero 데이터 저장소 백엔드의 Python 읽기 권한 동기화(`TiberoPythonReadGrantSync`)는 `PlatformLocks` 로 플랫폼 DB 에서 잡는다. 그 짝이 될 Tibero 맞바꿈 경로는 JVM 안 잠금만 쓴다. 여러 API 인스턴스 사이에서는 서로 막지 못하는 알려진 빈틈이고, 아래 `TiberoDataTableStore` 별도 이슈 후보에 함께 넣는다.
- 플랫폼을 지키는 잠금은 플랫폼 DB 에서 잡는다(`PlatformLocks.xactLock`, `IncrementalStepLock`).
- 한 직렬화의 잠금은 모두 한 저장소에서 잡는다. 한 직렬화가 두 DB 에 갈라지면 서로 막지 못한다.
- 세션 잠금은 증분 스텝 잠금(`IncrementalStepLock`) 하나뿐이고 플랫폼 풀에서 `PlatformLocks.trySessionLock`/`releaseSessionLock` 으로 잡는다. 데이터 저장소에는 세션 잠금이 없다(WD-690·692).

**불변식: 플랫폼 잠금을 먼저, 데이터 저장소 잠금을 나중에.**

- 두 DB 사이의 교착은 어느 DB 도 탐지하지 못한다. 한 스레드가 A DB 잠금을 쥐고 B DB 잠금을 기다리고, 다른 스레드가 반대로 쥐고 있으면 둘 다 타임아웃 없이 멈춘다. 그래서 획득 순서를 한 방향으로 고정한다.
- 분리 모드에서 데이터 저장소 xact 잠금은 플랫폼 트랜잭션 커밋보다 먼저 풀린다(beforeCommit). 플랫폼 행을 지키는 잠금을 데이터 저장소에 두면 그 행의 커밋 전에 잠금이 풀리므로, 플랫폼 행을 지키는 잠금은 데이터 저장소에 두지 않는다.
- 점검 결과(WD-493 시점 기준): 데이터 저장소 잠금 지점 11곳과 플랫폼 잠금 진입점 6개를 양방향으로 확인했다. 앞쪽은 11곳 각각의 잠금 구간 안에서 플랫폼 잠금으로 가는 경로를 찾았고, 뒤쪽은 진입점 6개의 호출자를 거슬러 올라가 데이터 저장소 구간에 닿는지 봤다. "플랫폼 잠금 후 데이터 저장소 잠금" 순서를 어긴 곳은 0건이었다. 그 뒤 WD-690 이 세션 잠금이던 출력 잠금 한 곳을 맞바꿈 트랜잭션 안의 트랜잭션 범위 잠금(`OutputSwapLock`)으로 바꿨다 — 이 잠금은 플랫폼 쓰기 트랜잭션(데이터셋 KEY SHARE 행 잠금) 안에서 잡히므로 순서는 플랫폼 먼저, 데이터 저장소 나중이며 불변식을 어기지 않는다.
- 잠금 구간은 넓게 잡았다. 분리 모드에서 데이터 저장소 연결은 바깥 플랫폼 트랜잭션에 묶이므로, 데이터 저장소 xact 잠금의 구간은 잠금 문장부터 바깥 플랫폼 트랜잭션의 beforeCommit 까지다.
- 이 불변식의 범위는 **플랫폼 어드바이저리 잠금**(`PlatformLocks.xactLock`·`IncrementalStepLock`)이다. 데이터 저장소 DDL 뒤에 잡는 행 잠금(`FOR UPDATE`, 예: `DatasetService.updateColumn`)은 전수 점검하지 않았다.
- 데이터 저장소 잠금을 쥔 채 플랫폼 풀 커넥션을 따로 빌리는 곳이 있다(`isStopRequested`·`backendFor`·`syncTable`). 분리 모드에서는 플랫폼 풀 크기도 동시 실행 수에 맞춰야 한다.

**분리 모드 풀 크기:** `@DataStoreDb` 풀(기본 최대 10)을 추가로 점유하는 잠금은 이제 없다. 실행기를 끈 로컬 PYTHON 의 PG REPLACE 는 예전에 세션 잠금 연결을 쥔 채 안쪽 적재·맞바꿈이 풀에서 연결을 따로 빌려 한 건당 1개가 더 묶였고(동시에 몰리면 풀 상한에서 서로 막힘), 이 이중 점유는 WD-690 으로 해소됐다 — 출력 잠금(`OutputSwapLock`)을 맞바꿈 트랜잭션 안의 트랜잭션 범위 잠금으로 옮겨 스레드당 연결 하나만 쥔다. SQL REPLACE 의 출력 잠금(`OUTPUT_LOCK_SQL`)은 테넌트 파이프라인 전용 풀을 쓰므로 `@DataStoreDb` 풀을 더 쓰지 않는다. 위 불변식 항목처럼 데이터 저장소 잠금 보유자가 플랫폼 풀 커넥션도 빌리므로 `PLATFORM_DB_POOL_SIZE` 도 함께 본다.

**해소된 한계(분리 모드 풀 고갈, WD-690):** 실행기를 끈 로컬 PYTHON REPLACE 가 `@DataStoreDb` 잠금 연결과 적재·맞바꿈 연결을 함께 쥐어 풀 크기만큼 동시에 돌면 `connection-timeout` 까지 풀이 막히던 한계는, 출력 잠금을 맞바꿈 트랜잭션 안으로 옮겨(`OutputSwapLock`) 실행당 데이터 저장소 연결 하나만 쓰게 되면서 해소됐다. 남은 한계: 출력 잠금 대기가 플랫폼 쓰기 트랜잭션 안(데이터셋 KEY SHARE 행 잠금 뒤)에서 일어나, 같은 출력의 SQL 스텝이 끝날 때까지 기다리는 동안 플랫폼 커넥션과 행 잠금을 쥔다(그 출력의 데이터셋 삭제·필드 변경이 함께 기다림). 「플랫폼 잠금 먼저, 데이터 저장소 잠금 나중」 순서(WD-493)를 지키려는 선택이며 실행기를 끈 로컬 경로 전용이다(운영은 실행기를 켜고, 실행기는 Python 출력 잠금을 잡지 않는다). 같은 출력의 로컬 REPLACE 맞바꿈들이 `pg_advisory_xact_lock` 에서 플랫폼 커넥션을 쥔 채 기다리는 동안, 같은 출력 잠금을 쥔 SQL 스텝(실행기 끔)은 트랜잭션 밖에서 `cancel.checkpoint` 를 부르며 플랫폼 풀 커넥션이 필요하다 — 같은 출력을 기다리는 맞바꿈이 플랫폼 풀 크기만큼 쌓이면 그 SQL 스텝이 `connection-timeout` 까지 막혔다가 실패한다(실행기를 끈 개발 전용 경로이고 시간이 한정돼 영구 교착은 아니다).

#### Tibero 잠금·깨우기 구현(WD-493)

실서버로 확인한 것:

- `DBMS_LOCK` 잠금 id 범위는 `[1073741824, 1999999999]` 다.
- `ALLOCATE_UNIQUE` 는 호출한 트랜잭션을 커밋하므로 쓰지 않는다. 키는 Java 쪽에서 해시한다(정수 키는 앞 100만 구간, 문자열 키는 SHA-256).
- `DBMS_LOCK` 사용자 잠금 id 와 `DBMS_ALERT` 이름은 **인스턴스 전역**이다. 한 Tibero 인스턴스에 설치본·계정·실서버 테스트 계정이 여럿 있으면 같은 키가 같은 id·같은 알림 이름을 쓴다. 그래서 둘 다 플랫폼 계정의 현재 스키마로 이름공간을 나눈다. 스키마는 기동 때 `SYS_CONTEXT('USERENV','CURRENT_SCHEMA')` 로 한 번 읽는다(실서버 확인, 실패하면 `USER`). 문자열 키는 `스키마:키` 를 해시하고, 정수 키는 100만 구간 안에서 스키마 해시만큼 돌린다. 알림 이름은 `FH_OUTBOX_NEW_<스키마 SHA-256 앞 8자리 16진>`(22자, 30자 제한 안)이다.
- `unlock_all` 이 없어 키를 지정해 해제한다.
- Tibero 드라이버는 `ApplicationName` 데이터 소스 속성을 거부하지 않았다.
- `DBMS_ALERT.SIGNAL` 은 메시지로 `"1"` 을 보낸다. Tibero 는 `''` 를 NULL 로 보고 거부한다(JDBC-14208).

설치 전제 조건:

- `GRANT EXECUTE ON SYS.DBMS_LOCK TO <플랫폼 계정>` 이 필요하다. 없으면 기동 검사가 "플랫폼 계정에 GRANT EXECUTE ON SYS.DBMS_LOCK 이 필요합니다(ADR 0001)" 안내와 함께 멈춘다.
- 권한이 없을 때 오류 코드는 15146 이다(드라이버가 음수로 줄 수 있다).
- `DBMS_ALERT` 실행 권한은 기동 때 `TiberoOutboxWakeupChannel.probe()` 가 자기 알림 이름으로 `DBMS_ALERT.REMOVE` 를 한 번 실행해 확인한다. 오류 없음과 -14207(등록 안 된 이름, 권한이 있어도 난다 — 실측)은 통과, 15146 은 즉시 깨우기를 끄고 폴링으로만 동작(WARN 한 번), 그 밖의 오류는 WARN 만 남기고 채널을 유지한다. 결정은 기동 때 고정되므로 `GRANT EXECUTE ON SYS.DBMS_ALERT TO <플랫폼 계정>` 뒤에는 재기동해야 한다. `DBMS_LOCK` 과 달리 기동을 막지 않는다(폴링 폴백이 있다). 권한 없는 계정의 부정 실서버 테스트는 하지 않았다 — 공유 서버에서 `REVOKE ... FROM PUBLIC` 을 할 수 없어서다.
- 아웃박스 즉시 깨우기는 `DBMS_ALERT`(`FH_OUTBOX_NEW_<스키마 해시>`)로 한다. API 인스턴스마다 Tibero 세션 1개를 상시 점유한다. 끄려면 `notification.worker.listen_notify=false` 로 둔다(30초 폴링만 남는다).

스펙과 달라진 점:

- 알림 이름: 스펙 6절은 `FH_OUTBOX_NEW` 고정이었다. 인스턴스를 여러 설치본이 함께 쓰므로 스키마 해시를 붙였다(위 실측).
- 잠금 id: 스펙 5절은 키만으로 id 를 정했다. 같은 이유로 스키마를 섞는다.
- `SIGNAL` 메시지: 스펙은 `''` 였지만 Tibero 가 NULL 로 보고 거부해 `"1"` 을 보낸다.
- 트랜잭션 잠금 가드: 스펙 5절은 `isActualTransactionActive()` 였다. 지금은 플랫폼 DataSource 가 현재 트랜잭션에 묶였는지(`hasResource`)를 본다. 다른 DataSource 의 트랜잭션 안에서는 자동 커밋 커넥션에서 잡혀 곧바로 풀리기 때문이다.

다른 확인·후보:

- `FOR UPDATE SKIP LOCKED` 는 확인했다(WD-487). 운영 빈 전환은 WD-491 이다.
- 별도 이슈 후보: `TiberoDataTableStore` 의 JVM 안 잠금은 여러 API 인스턴스 사이를 막지 못한다. Tibero 맞바꿈과 Tibero Python 읽기 권한 동기화(플랫폼 `PlatformLocks`)가 서로 막지 못하는 것도 여기에 넣는다.
- 별도 이슈 후보: 분리 모드에서 실행기를 끈 로컬 PYTHON REPLACE 가 동시에 많으면 `@DataStoreDb` 풀이 고갈된다(위 풀 크기 항목).

### 5. 검증 환경 — **데모 라이선스 매달 갱신** (사용자 결정)

지금 쓰는 개발 Tibero 의 제약: 트라이얼이라 2026-12-06 에 만료되고, 세션이 20개이며, DCIM 팀과 함께 쓴다. K8S `tibero:8629` 도 같은 날 만료된다.

만료 뒤에는 공식 도커 이미지에 1개월 데모 라이선스를 매달 갱신해 쓴다.

- **라이선스 발급**: 데모 라이선스는 TmaxTech 사이트에서 받는다. 가입과 신청은 사람이 한다.
- **호스트명 고정**: 라이선스가 호스트명에 묶인다. 컨테이너·파드의 호스트명을 고정한다. K8S 인스턴스에는 이미 `wm-management` 로 고정한 선례가 있다.
- **갱신 절차·만료 확인**(WD-497/498)
  - 갱신 절차를 runbook 으로 남긴다.
  - 하네스가 시작할 때 `V$LICENSE` 의 만료일을 읽는다. 7일 안이면 경고하고, 지났으면 "라이선스 만료" 로 건너뛴다. 실패로 오인하지 않게 하기 위해서다.
- **데이터 유지**: 새 컨테이너나 파드에서 다시 설치되는 이미지 함정이 있다. 라이선스를 갱신할 때 DB 가 초기화될 수 있으므로, Tibero 검증 데이터는 하네스가 매번 만들고 지우는 것만 쓴다.
- **세션**: 데모 라이선스의 세션 한도에 맞춰 하네스 풀을 작게 둔다(지금 PoC 는 최대 2~3).

CI 에는 Tibero 공개 이미지가 없다. Tibero 실서버 테스트는 환경변수가 있을 때만 도는 지금 방식을 유지한다. PR 마다가 아니라 묶음(단계) 완료 시점에 돌린다. PR 마다는 1번의 PG 위 MyBatis 계약 테스트가 대신한다.

### 6. JVM 시간대 — UTC 필수

Tibero 드라이버는 시각을 JVM 기본 시간대로 저장한다. JVM 이 KST 면 JobRunr 서버가 20초 만에 자기 자신을 시간 초과로 지우고, 저장 시각이 9시간 어긋난다(WD-487·WD-488 실측).

- 운영 JVM 은 UTC 로 본다. 근거는 세 가지다.
  - api `Dockerfile` 과 배포 차트(`api-deployment.yaml`·values)에 `TZ`·`user.timezone` 설정이 없다. `JAVA_TOOL_OPTIONS` 는 메모리 옵션뿐이다.
  - 그래서 기반 이미지 eclipse-temurin 의 기본값(UTC)을 따른다.
  - `bootRun` 도 같은 이유로 UTC 를 고정한다(WD-476).
  - 실행 중인 파드에서 직접 확인하지는 않았다.
- Tibero 설치본은 `user.timezone=UTC` 를 **기동 시 검사로 강제**한다(WD-496).
- Tibero 테스트 하네스도 UTC 로 돈다(WD-497).

### 7. Tibero 테넌트 격리(WD-492)

PG 는 `app_tenant` 롤 + RLS 정책 + 트랜잭션-로컬 GUC(`app.tenant_id`)로 테넌트를 가른다. Tibero 는 같은 모델을 **애플리케이션 컨텍스트 + `DBMS_RLS` 정책**으로 옮겼다. 설계: `docs/superpowers/specs/2026-11-10-wd492-tibero-tenant-rls-design.md`. 스키마는 `db/migration-tibero/V156.1__tibero_tenant_rls.sql`(Tibero 전용 버전, PG 짝 없음)이 만든다.

#### 계정 두 개와 권한

| 계정 | 설정 | 권한 |
|---|---|---|
| 소유자 | `spring.flyway.user/password`, 스키마 이름은 `firehub.platform-db.tibero.owner-schema`(기본 Flyway 계정) | `CREATE SESSION`·`TABLE`·`SEQUENCE`·`VIEW`·`PROCEDURE`·`TRIGGER` + `CREATE ANY CONTEXT` + `GRANT EXECUTE ON SYS.DBMS_RLS`. `DROP ANY CONTEXT` 는 필요 없다(`CREATE OR REPLACE CONTEXT` 가 된다 — 실측) |
| 런타임 | `spring.datasource.username/password`(앱 풀) | `CREATE SESSION` + `GRANT EXECUTE ON SYS.DBMS_LOCK`(WD-493) + `GRANT EXECUTE ON SYS.DBMS_ALERT`(WD-691). 소유자 객체의 DML(모든 테이블)·SELECT(뷰·시퀀스)·`FH_TENANT` EXECUTE 는 Flyway `afterMigrate` 콜백(`TiberoRuntimeGrantCallback`)이 **기동마다** 준다 — 이후 마이그레이션이 테이블을 더해도 다음 기동에서 권한이 붙는다(PG `ALTER DEFAULT PRIVILEGES` 대신) |

- 앱 풀 초기화 문장이 `ALTER SESSION SET CURRENT_SCHEMA=<소유자>` 로 시작한다. 그래서 앱 SQL 은 한정 없는 이름을 그대로 쓰고, `DBMS_LOCK` id·`DBMS_ALERT` 이름의 이름공간(WD-493)도 소유자 스키마 기준 그대로다.
- **두 계정이 같으면(대소문자 무시) 단일 계정 모드다.** 기동은 되지만 앱이 소유자로 붙어 정책에 걸리지 않으므로 격리가 꺼진다. 기동 때 WARN 한 줄(「런타임 계정이 없어 테넌트 격리가 꺼져 있습니다」)을 남기고, 권한 콜백도 달지 않는다.
- **`owner-schema` 는 객체를 소유한 스키마이고 Flyway 계정과 같아야 한다.** 기본값은 `${spring.flyway.user:${spring.datasource.username}}` 다(Flyway 계정이 없으면 Boot 가 Flyway 를 datasource 계정으로 돌리므로 같은 순서로 대체한다). 권한 콜백은 Flyway 연결로 `USER_TABLES`·`USER_TAB_PRIVS` 같은 `USER_*` 뷰를 읽으므로, `spring.flyway.default-schema`/`schemas` 로 다른 스키마를 가리키는 설치는 지원하지 않는다. 값은 기동 때 Flyway 커스터마이저(`TiberoTenantMigration.configure`)가 식별자 모양(`[A-Za-z][A-Za-z0-9_$#]{0,127}`)을 검사해 어긋나면 기동을 멈춘다 — Flyway 계정과 같은지까지 대조하지는 않는다.
- 런타임 계정은 `DBMS_SESSION.SET_CONTEXT` 를 직접 부를 수 없다(-17004 실측). 컨텍스트는 `CREATE CONTEXT … USING FH_TENANT` 로 소유자 패키지에만 묶여 있어 `FH_TENANT.set_tenant`·`clear_tenant` 로만 바뀐다. 테넌트를 넘어 읽는 루틴 6개(아웃박스·선제 실행 테넌트 목록, 트리거·슬랙 워크스페이스 테넌트 해석, 테넌트 기본값 준비)도 이 패키지 안에서만 우회(`BYPASS`)를 켜고 끄며, 우회를 켜는 공개 루틴은 없다.
- 컨텍스트 이름은 인스턴스 전역이라 `FH_TENANT_<소유자 스키마 SHA-256 앞 8자리 16진>` 으로 설치본마다 다르게 만든다(WD-493 알림 이름과 같은 규칙).

#### 설치 전 점검(DBA 로)

```sql
-- 판(edition) — 격리는 Enterprise(개발 서버 값 enterprise)에서만 확인했다. 다른 판이면 아래 DBMS_RLS 확인과 실서버 시험을 먼저 돌린다
SELECT EDITION FROM V$LICENSE;
-- DBMS_RLS 패키지가 있고 유효한지 — VALID 여야 한다
SELECT STATUS FROM DBA_OBJECTS
 WHERE OWNER = 'SYS' AND OBJECT_NAME = 'DBMS_RLS' AND OBJECT_TYPE = 'PACKAGE';
```

두 쿼리는 실서버에서 돌려 열 이름·값을 확인했다(`TiberoPlatformTenantSessionLiveTest.설치_전_점검_쿼리가_돈다`, `V$LICENSE` 열은 `LICENSE_VERSION, LICENSEE, LICENSE_TYPE, PRODUCT_NAME, PRODUCT_VERSION, EDITION, ISSUE_DATE, EXPIRE_DATE, LIMIT_USER, LIMIT_CPU, HOST, OPTIONS`).

#### 단일 계정 설치본을 두 계정으로 옮기기

1. DBA 로 런타임 계정을 만든다: `CREATE USER <런타임> IDENTIFIED BY …; GRANT CREATE SESSION TO <런타임>;`
2. `GRANT EXECUTE ON SYS.DBMS_LOCK TO <런타임>; GRANT EXECUTE ON SYS.DBMS_ALERT TO <런타임>;`
3. 소유자 계정에 `CREATE ANY CONTEXT`·`EXECUTE ON SYS.DBMS_RLS` 가 없으면 준다(V156.1 을 적용하려면 필요하다).
4. 앱 설정에서 `spring.datasource.username/password` 를 런타임 계정으로 바꾼다. `spring.flyway.user/password` 는 소유자 그대로 둔다.
5. 재기동한다. Flyway 가 (아직이면) V156.1 을 적용하고, `afterMigrate` 콜백이 런타임 계정에 권한을 준다. 기동 로그에 단일 계정 WARN 이 없고 권한 부여 INFO 가 있는지 본다.

#### 컨텍스트는 세션 범위 — 트랜잭션 끝에 지우고, 못 지우면 버린다

- PG GUC 는 트랜잭션-로컬이지만 Tibero 컨텍스트는 **세션 범위**라 커밋·롤백 뒤에도 남는다(실측). 지우지 않은 커넥션이 풀로 돌아가면 다음 요청이 이전 테넌트를 물려받는다.
- 그래서 `TenantAwareTransactionManager` 가 트랜잭션 시작에 `FH_TENANT.set_tenant` 를 부르고(`TiberoPlatformTenantSession`), 커넥션을 풀에 돌려주기 직전(`doCleanupAfterCompletion`)에 `FH_TENANT.clear_tenant` 를 부른다. 새 트랜잭션만 지운다 — 참여한 안쪽 트랜잭션은 지우지 않고, `REQUIRES_NEW` 는 자기 커넥션만 지운다(바깥 컨텍스트는 남는다).
- 테넌트 없는 트랜잭션은 시작에도 `FH_TENANT.clear_tenant` 를 한 번 부른다 — 앞선 대여자의 끝 지우기·버리기가 모두 실패해 값이 남은 커넥션이 와도 물려받지 않게(fail-closed). 시작 지우기가 실패하면 트랜잭션을 열지 않고 그 커넥션을 버린다.
- 지우기에 실패하면 WARN 을 남기고 그 커넥션을 풀에서 버린다(Hikari `evictConnection`, 아니면 `abort`). 예외는 던지지 않는다 — 커밋이 이미 끝나 「커밋됐는데 예외」가 되기 때문이다. 실서버 시험이 풀 크기 1 에서 다음 트랜잭션이 다른 세션(`USERENV.SESSIONID`)을 받는 것으로 확인했다.
- 로그인처럼 트랜잭션이 열린 뒤 테넌트가 정해지는 경로(`applyToCurrentTransaction`)는 플랫폼 풀이 현재 트랜잭션에 묶여 있을 때만 심는다. 아니면 `IllegalStateException` — 트랜잭션 밖 커넥션에는 지워 줄 자리가 없다.
- `FH_TENANT.set_tenant` 를 `TiberoPlatformTenantSession` 밖에서 부르지 못하게 소스 검사(`PlatformDbPgSpecificSourceTest`)로 고정했다.

#### 정책·DEFAULT

- 정책은 PG 정책 61개에서 벡터 테이블 4개(Tibero 에 없음)를 뺀 **57개 테이블**에 PG 정책 이름 그대로 건다. `SELECT,INSERT,UPDATE,DELETE` + `update_check`(다른 테넌트 행 쓰기는 -10067). 정책 없이 두는 `tenant_id` 테이블은 PG 와 같은 4개(`membership`, `oauth_state`, `tenant_data_store`, `tenant_data_store_account`)이고, 카탈로그 시험이 「`TENANT_ID` 가 있는 테이블은 모두 정책이 있다」를 고정한다.
- 정책은 **동적**(`static_policy => FALSE`)이다. 술어가 세션 사용자(소유자는 걸러지지 않음)·`BYPASS` 에 따라 달라 캐시되면 안 되기 때문이다. 정책 함수가 문장마다 불리므로 성능 영향은 WD-498 에서 실측한다(필요하면 context-sensitive 로).
- `tenant_id` 열 DEFAULT 는 **58곳**이 `SYS_CONTEXT('<컨텍스트>','TENANT_ID')` 다(PG 62곳에서 벡터 4개를 뺀 수). NOT NULL 은 유지하므로 테넌트 없이 넣으면 NOT NULL 위반(-10005)으로 실패한다(fail-closed). `dataset.security_level_id`·`role.max_security_level_id` 의 PG DEFAULT(사용자 함수)는 BEFORE INSERT 트리거가 소유자 함수 `FH_DEFAULT_SECURITY_LEVEL`(런타임에 EXECUTE 없음)로 대신한다.
- `audit_log` 는 테넌트가 없으면 `tenant_id IS NULL` 행만 보인다(PG `IS NOT DISTINCT FROM` 과 같은 의미).

#### 넘겨받을 일(추적 목록)

**WD-491(MyBatis 구현)**
- Tibero 의 테넌트 해석 루틴(`FH_TENANT` 의 트리거·슬랙 워크스페이스 해석)은 **테넌트 id 만** 돌려준다. MyBatis 구현은 `TriggerRef.triggerId`·`SlackWorkspaceRef.workspaceId` 를 테넌트를 심은 뒤 RLS 아래에서 다시 읽어 채워야 한다(지금 호출처는 `ref.tenantId()` 만 쓴다).
- ~~`FH_TENANT.set_tenant` 소스 검사(`PlatformDbPgSpecificSourceTest`)를 `src/main/resources` 아래 MyBatis XML 까지 넓힌다.~~ **WD-706 완료.**
- ~~JobRunr 풀은 런타임 계정으로 붙고 `CURRENT_SCHEMA=<소유자>` 다. 두 계정 모드에서 Oracle 저장소 제공자가 JobRunr 테이블을 제대로 찾는지 확인한다.~~ **WD-706 완료.** 설정 변경 없이 동작한다(`TiberoJobRunrRuntimeAccountLiveTest`).

**WD-498(배포·운영)**
- 동적 정책 비용과 트랜잭션 시작·끝의 추가 지우기 왕복을 실측한다. 색인을 타도록 술어를 `tenant_id = TO_NUMBER(SYS_CONTEXT(...))` 로 바꾸는 것을 검토한다.

**강제하지 않는 것**
- `owner-schema` 가 Flyway 계정과 같은지는 강제하지 않는다 — 식별자 모양만 검사한다.

**운영 메모**
- 단일 계정 → 두 계정 전환: `spring.flyway.user` 를 따로 두지 않았다면 `spring.datasource.username` 을 바꾸기 **전에** `spring.flyway.user/password` 를 소유자로 명시한다. 그러지 않으면 둘 다 런타임 계정으로 대체된다.
- `CREATE ANY CONTEXT` 는 컨텍스트를 만들 때만 필요하다. V156.1 적용 뒤에는 회수해도 된다.
- 컨텍스트는 인스턴스 전역이고 `DROP USER … CASCADE` 로 지워지지 않는다. 설치본을 없앨 때는 컨텍스트(`FH_TENANT_<해시>`)를 먼저 지운다.
- 컨텍스트 이름 해시가 겹치는 두 번째 설치본을 만들면 컨텍스트가 그쪽 패키지로 다시 묶여 **기존** 설치본이 fail-closed(-17004)로 실패한다. 테넌트가 새지는 않는다.

## 2단계 순서와 규모

| 순서 | 이슈 | 내용 | 규모 |
|---|---|---|---|
| 1 | WD-496 | 부트스트랩: Tibero DataSource·프로필, UTC 검사, 예외 번역(PG SQLState → Tibero 오류 코드), pgjdbc 직접 의존 제거 | M |
| 1 | WD-497 | 테스트 하네스: Tibero 하네스 통합(WD-487·488 하네스 + `TiberoTestEnvironment`), UTC, 라이선스 만료 검사, 계약 테스트 Tibero 하위 클래스 규칙 | M |
| 2 | WD-490 | 기준선 DDL(V155 상당) + JobRunr 스키마, `MigrationParityTest`, 작성 규칙 | L |
| 3 | WD-494 | PG 데이터 저장소 연결을 플랫폼 DB 연결에서 분리하고, 데이터 저장소 경로 18개 클래스의 DSLContext 를 바꿔 단다(위 표) | L |
| 3 | WD-493 | 잠금(`DBMS_LOCK`)·알림(`DBMS_ALERT`/폴링), 두 저장소 직렬화 재설계 | M |
| 4 | WD-492 | 테넌트 격리 CONTEXT + DBMS_RLS(61개 테이블 — Tibero 는 57개, 벡터 4개 제외(결정 7), 소유자 필터 대응). PoC 에서 본 테넌트 조건 누락 7곳 포함 | L |
| 5 | WD-491 | Tibero 용 MyBatis 구현(73개) + 구현 짝 검사. 도메인 묶음으로 쪼갠다(아래) | XL |
| 6 | WD-495 | Tibero 빌드 기능 비활성 + 화면 안내 | S |
| 7 | WD-498 | 배포·운영: Helm 값, 백업(tbexport), 설치·복구 문서, 데모 라이선스 갱신 runbook | M |

**WD-491 쪼개기**(jOOQ 구현 수):

| 묶음 | 도메인 | jOOQ 구현 수 |
|---|---|---|
| ① 공통·인증 | auth·user·role·permission·tenant·settings·platform·audit·job | 15 (처음엔 14로 셌다. **WD-706 완료**) |
| ② 데이터셋 | dataset | 13 (짝 검사 대상 리포지토리 12 + `JooqDatasetMetaReader`. **WD-707 완료**) |
| ③ 파이프라인 | pipeline·mapping·apiconnection | 9 |
| ④ 알림·선제 메시지 | notification·proactive (PoC 2개 포함) | 13 |
| ⑤ 분석·대시보드 | analytics·dashboard | 7 |
| ⑥ 보안 등급·온톨로지·그래프 | securitylevel·ontology·graphreview·graphingest (PoC 1개 포함) | 11 |
| ⑦ AI·문서·파일 | ai·document·embedding·file | 6 (**WD-712 완료**) |

각 묶음은 "계약 테스트 보강 → MyBatis 구현(+Tibero 전용 문장) → PG CI 계약 테스트 통과 → Tibero 실서버 계약 테스트 통과 → 구현 짝 허용 목록에서 제거" 를 한 단위로 한다. PG 운영 빈은 바뀌지 않는다.

**규모 근거**:

- PoC 는 jOOQ 약 750줄분 리포지토리 3개를 이전했다. 인프라를 빼면 MyBatis 약 1,100줄이다.
- 전체 15,600줄은 그 약 21배다.
- 묶음 하나에 Tibero 실서버 검증과 리뷰까지 넣으면, WD-491 은 2단계에서 가장 크다. 나머지 이슈를 다 합친 것과 비슷하거나 더 크다.
- 날짜는 정하지 않는다. 묶음마다 Tibero 실서버 검증이 들어가므로, 일정은 데모 라이선스 갱신이 끊기지 않는 것을 전제로 한다.

## PoC 브랜치 처리 — 결정(2026-11-05)

| 브랜치 | 처리 |
|---|---|
| `worktree-wd487-mybatis-poc` | main 위로 다시 올려 WD-491 의 첫 단위로 병합한다. MyBatis 공통 인프라와 리포지토리 3개가 들어 있고, MyBatis 배선은 테스트 프로필에서만 켜진다. 짝 검사와 Tibero 설치본 배선은 WD-491/496 에서 붙인다. |
| `worktree-wd488-flyway-poc` | 병합해 WD-490/496 의 출발점으로 쓴다. 플러그인(본 코드)은 PG 에 영향이 없다. 등록·판별 테스트만 PG 회귀에서 돌고, 실서버 테스트는 환경변수가 있을 때만 돈다. |

병합하기 전에 API 전체 회귀를 돌린다. WD-487 은 테스트 프로필에서 MyBatis 배선을 켜서 모든 통합 테스트의 컨텍스트가 바뀌기 때문이다.

## 결과(감수하는 것)

- Tibero 설치본은 벡터·키워드 검색이 없다. 새 벡터 DB 를 도입하기 전까지 그렇다.
- 스키마를 바꿀 때마다 마이그레이션 두 벌을 쓰고, Tibero 작성 규칙을 지킨다.
- 플랫폼 리포지토리 변경이 두 벌이 된다. 계약 테스트·구현 짝 검사·PG 위 MyBatis 테스트로 어긋남을 잡는다.
- MyBatis 구현은 컴파일 시 SQL 검사가 없다. 계약 테스트가 그 역할을 한다.
- Tibero 검증은 데모 라이선스를 매달 갱신하는 실서버 환경에 기댄다. 갱신이 끊기면 Tibero 테스트는 건너뛰어지고, 그동안의 Tibero 전용 문장은 검증되지 않는다.
