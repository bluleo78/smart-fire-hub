# P3-b2 테넌트별 데이터 스키마 분리 — 운영자 런북 + 잔여 위험

**대상 밴드**: `2026-08-18-multi-tenancy-p3b2-schema-separation` (Task 1~6)
**작성 시점 기준**: 워크트리 `multi-tenancy-p3b2`, HEAD `4f113943`(Task 5 라운드 1). **아직 로컬
main 에 병합되지 않았고, prod 에는 배포되지 않았다.**
**경로**: 원래 `docs/superpowers/plans/`(gitignore 대상)에 `git add -f` 로 강제 추가했다가,
운영 런북은 잃으면 가장 곤란한 문서라 정상 추적되는 `docs/runbooks/` 로 옮겼다(라운드 1
리뷰). 파일명에 날짜 접두사를 붙이지 않은 이유는 이 디렉터리의 기존 관례
(`notification-outbox-rollout.md`)를 따른 것이다 — `docs/runbooks/` 의 파일은 "이 시점의
기록"이 아니라 "이 기능에 대한 살아있는 운영 문서"로 계속 참조될 것이라는 그 관례의 전제에
동의해서다.

이 문서는 코드가 아니라 **순전히 서술**이다. 이 밴드의 지배적 실패 유형이 "코드는 맞고 서술이
틀린 것"(R8·R11·R16·R18·R21, 다섯 번)이었으므로, 아래 모든 절에서 **확인한 것과 확인하지
않은 것을 문장 단위로 구분**했다. 실측한 것은 명령어와 원문 출력을 그대로 붙였다. 절대 단언
(`항상`, `전부`, `무관하게`)은 이 밴드에서 이미 두 번 거짓으로 드러난 전례가 있어, 쓰기 전에
실측으로 뒷받침되는지 확인했다 — 어떤 문장이 뒷받침되지 않는지는 리포트(`task-6-report.md`)
에 별도로 정리했다.

---

## 1. 신규 테넌트 프로비저닝 절차 (운영자 SQL)

**앱에는 테넌트를 생성하는 API 나 배치가 없다.** `TenantProvisioningService.provisionDefaults`
는 프로덕션 호출자가 0개임을 확인했다(실측):

```
$ grep -rln "TenantProvisioningService" src/main/java src/test/java | grep -v TenantProvisioningService.java
src/test/java/com/smartfirehub/tenant/TenantProvisioningServiceTest.java
src/test/java/com/smartfirehub/tenant/ReportTemplateTenantTest.java
```

→ 두 파일 다 `src/test/java` 다. `src/main/java` 안에서 이 서비스를 부르는 코드는 없다. 즉
`provisionDefaults(long tenantId)` 자체는 존재하고 테스트로 검증돼 있지만, 실제 테넌트 생성
경로는 **운영자가 직접 실행하는 SQL 절차**다(#383). 아래 순서는 이 밴드가 만든 스키마 분리
기계장치를 실측한 결과다.

### 1-1. 테넌트 행 생성

```sql
INSERT INTO tenant (slug, name, status) VALUES ('<slug>', '<name>', 'ACTIVE') RETURNING id;
```

`tenant` 테이블은 `public` 스키마의 전역 테이블이고 RLS 가 없다(V81) — 실측: 앞선 태스크들의
테스트 헬퍼(`TenantRlsTestSupport.insertActiveTenant`)가 컨텍스트 없이 직접 INSERT 한다. 반환된
`<id>` 를 이후 모든 단계에서 쓴다.

### 1-2. 기본 RBAC·양식 시드

```sql
SELECT provision_tenant_defaults(<id>);
```

`V98__provision_tenant_defaults_function.sql` 을 읽어 확인했다 — `SECURITY DEFINER` 함수로,
테넌트 1(원본)의 시스템 역할·역할-권한 매핑·내장 리포트 양식 3건을 대상 테넌트로 복제한다.
**멱등**이다(`NOT EXISTS` 가드로 재실행해도 중복 삽입되지 않음, 소스 주석 확인). `p_tenant_id`
가 원본 테넌트(1) 자신이면 즉시 반환하고 아무 것도 하지 않는다.

### 1-3. 파이프라인 실행 롤 생성

```sql
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'pipeline_executor_t<id>') THEN
    CREATE ROLE pipeline_executor_t<id> LOGIN PASSWORD '<임시_아무값>';
  END IF;
  EXECUTE format('GRANT CONNECT ON DATABASE %I TO pipeline_executor_t<id>', current_database());
END
$$;

GRANT USAGE ON SCHEMA data_t<id> TO pipeline_executor_t<id>;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA data_t<id> TO pipeline_executor_t<id>;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA data_t<id> TO pipeline_executor_t<id>;
ALTER DEFAULT PRIVILEGES FOR ROLE app_tenant IN SCHEMA data_t<id>
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO pipeline_executor_t<id>;
ALTER DEFAULT PRIVILEGES FOR ROLE app_tenant IN SCHEMA data_t<id>
  GRANT USAGE ON SEQUENCES TO pipeline_executor_t<id>;
REVOKE ALL ON SCHEMA public FROM pipeline_executor_t<id>;
```

`V111__tenant_pipeline_executor_role.sql`(테넌트 1 전용)을 읽어 확인한 grant 세트를 그대로
테넌트 `<id>` 형태로 옮긴 것이다 — **이 문서는 V111 을 편집하지 않았다**(적용된 마이그레이션
편집 금지). `FOR ROLE app_tenant` 로 거는 이유는 V111 주석이 밝힌 그대로다: 런타임 DDL 주체
(신규 데이터셋 테이블을 실제로 만드는 롤)가 `app` 이 아니라 `app_tenant` 이기 때문이다 —
`FOR ROLE app` 으로 걸면 새 데이터셋이 조용히 실행 롤에게 안 보인다.

**주의**: `data_t<id>` 스키마가 이 시점에는 **아직 존재하지 않는다**(1-6 참조). `GRANT ... ON
SCHEMA data_t<id>` 류 문장은 스키마가 없으면 실패한다. 따라서 이 단계는 **첫 데이터셋 생성
이후**(1-6)에 실행하거나, 스키마를 먼저 만들고(`CREATE SCHEMA IF NOT EXISTS data_t<id>`) 진행
해야 한다 — 이 순서 문제는 실측하지 않았고 **확인하지 않았다**(V111 은 테넌트 1 을 대상으로 하고
그 시점에 `data` 스키마가 이미 존재했으므로 이 문제를 겪지 않는다). 운영자는 스키마 존재를 먼저
확인하거나, `TenantSchemaProvisioner.ensureCurrentTenantSchema()` 가 첫 데이터셋 생성 시 이미
`GRANT USAGE/CREATE ON SCHEMA ... TO app_tenant` 와(롤이 있으면) executor 롤 grant 까지 자동으로
해 준다는 점을 활용해, **executor 롤을 첫 데이터셋 생성 이후에** 만드는 편이 더 간단하다
(`TenantSchemaProvisioner.ensureCurrentTenantSchema` 소스 확인: `roleExists(tx, executorRole)`
이 참이면 스키마 생성과 같은 트랜잭션에서 executor 롤에게 GRANT 세트를 전부 건다). 즉 실무적
권장 순서는 **"첫 데이터셋을 먼저 만들게 하고, 그다음 이 단계의 롤 생성 SQL 을 실행"** 이지만,
이 순서를 실제로 운영 환경에서 검증하지는 않았다 — **확인하지 않았다.**

### 1-4. search_path 를 DB 한정으로 고정

```sql
DO $$
BEGIN
  EXECUTE format(
    'ALTER ROLE pipeline_executor_t<id> IN DATABASE %I SET search_path TO %I',
    current_database(), 'data_t<id>');
END
$$;
```

**반드시 `IN DATABASE` 를 써야 한다.** V111 소스 주석을 그대로 인용한다:

> "`ALTER ROLE ... SET search_path` 를 DB 한정 없이 실행하면 `pg_db_role_setting.setdatabase`
> 가 0(= 전 DB) 으로 기록되어, 이 클러스터에 존재하는 다른 모든 DB 에도 같은 search_path 가
> 적용된다. 기존 `pipeline_executor` 롤이 정확히 이 상태다(V32:32, 무한정 `ALTER ROLE`)."

이 사실을 실측으로 재확인하지는 않았다(V111 코드 주석을 근거로 인용했을 뿐 `pg_db_role_setting`
을 직접 조회하지 않았다) — **확인하지 않았다.** 다만 V111 이 같은 실수를 피하려고 `IN DATABASE`
를 명시적으로 쓰고 있는 것은 소스로 확인했다.

### 1-5. 비밀번호 동기화

앱을 재기동(또는 Flyway `migrate` 를 다시 트리거)하면 `RolePasswordSyncCallback` 이 1-3 에서
임시로 넣은 비밀번호를 `TenantPipelineRole.password(<id>, secret)` 로 파생한 실제 값으로
덮어쓴다. 이 콜백은 `AFTER_MIGRATE` 이벤트에서 매 기동마다 실행되며(대기 중인 마이그레이션이
없어도), `FlywayCallbackConfig.resolveActiveTenantPipelineRolePasswords` 가 ACTIVE 테넌트를
순회해 존재하는 `pipeline_executor_t{id}` 롤만 골라 동기화 대상에 넣는다(소스 확인:
`roleExists` 로 존재 여부를 먼저 검사).

### 1-6. 물리 스키마 — 만들지 않는다

`data_t<id>` 스키마는 이 절차 어디서도 명시적으로 만들지 않는다. **첫 데이터셋 생성 시**
`DataTableService.createTable()` 의 첫 줄인 `schemaProvisioner.ensureCurrentTenantSchema()`
가 지연 생성한다(소스 확인, `DataTableService.java:55` 부근). 이 지연 생성 설계의 근거는
`TenantSchemaProvisioner` 클래스 Javadoc 에 있다: "공유 test DB 에 테넌트가 243개 쌓여 있고
테스트가 이를 정리하지 않는다. 테넌트마다 스키마를 선제 생성하면 스키마가 무한 누적된다."

---

## 2. 낡은 전제 정정 — "P3-b2 가 `data` 를 리네임한다"

V111 마이그레이션 파일 본문과 P3-b1 계획서 곳곳에 **"P3-b2 가 `data` 를 `data_t1` 등으로
리네임한다"** 는 전제가 적혀 있다. **이 전제는 폐기됐다.** V111 파일 자체는 적용된 마이그레이션
이라 편집하지 않았으므로, 이 정정을 여기 남긴다.

**실제로 채택된 방식**(Task 1, 커밋 `266c002b`): `DataSchema.current()` 가 테넌트 1 은
`LEGACY_SCHEMA_BY_TENANT` 맵을 통해 기존 `data` 스키마를 그대로 돌려주고, 그 외 테넌트만
`data_t{tenantId}` 접두사로 새 스키마를 받는다. **리네임이 아니라 신규 테넌트만 새 이름을 받는
분기다.**

이 전환에 따른 결과:

- **(a) `ALTER SCHEMA data RENAME TO ...` 는 어디에도 없다.** 리네임 마이그레이션 자체가
  존재하지 않는다(실측: `grep -rn "RENAME.*SCHEMA\|ALTER SCHEMA" src/main/resources/db/migration`
  실행 결과 이 밴드의 어떤 마이그레이션에서도 스키마 리네임 문장이 없음을 확인했다).
- **(b) 기존 테넌트(1) 데이터에 대한 DDL 이 없다.** `data` 스키마의 테이블·행은 이 밴드
  전체를 통틀어 손대지 않는다 — 접미사 방식을 택한 핵심 이유가 바로 이것이다(리네임은 기존
  데이터를 옮기거나 스키마명을 바꿔야 해서 위험이 크고, 접미사는 신규 테넌트만 새로 만들면
  된다).
- **(c) 레거시 `pipeline_executor` 롤 은퇴의 근거가 사라졌다.** V111 주석은 "그 롤의 은퇴는
  P3-b2 에서 물리 스키마 리네임과 같은 커밋으로 이뤄진다"고 적어 뒀는데, 리네임 자체가 없으므로
  "같은 커밋" 이라는 트리거가 없다. 은퇴는 여전히 필요하지만(레거시 롤이 `data` 스키마에 대해
  구식 grant 세트를 갖고 있다), 이 밴드가 그 트리거를 자동으로 만들지 않으므로 **#383 으로
  이연**한다(§6 백로그에도 기록).

---

## 3. `app` 슈퍼유저 노출 — 이 밴드에서 가장 무거운 잔여 위험

### 3-1. 무엇이 바뀌었나

계획 D4 결정(신규 스키마 생성 주체를 소유자 롤 `app` 으로 함)의 **의도치 않은 결과**다. 이전에는
`app` 자격증명이 Flyway 마이그레이션 실행 시점(앱 기동 초입)에만 잠깐 쓰이고 끝났다. 이 밴드가
`SchemaOwnerDataSourceConfig` 를 도입하면서, 이제 `app` 자격증명은 **런타임 내내 살아 있는
HikariCP 풀**(`schemaOwnerDataSource`, 빈 이름)이 됐다.

실측(소스 확인, `SchemaOwnerDataSourceConfig.java`): 이 빈은 `@Bean(name = "schemaOwnerDataSource")`
로 등록된 평범한 스프링 빈이다 — Java 접근 제한자로 주입 범위를 좁히는 장치가 없다. 실측
(`grep -rln "schemaOwnerDataSource" src/main/java`): 현재 **프로덕션 코드에서 이 빈을 주입받는
클래스는 `TenantSchemaProvisioner` 하나뿐**이다. 하지만 이것을 강제하는 규약 가드(누가 이 빈을
새로 주입받으면 실패하는 테스트)는 **없다** — 실측(`grep -rn "schemaOwnerDataSource 를 주입받는"
src/test/java` 류로 찾아봤으나 그런 가드 테스트를 찾지 못했다). 즉 오늘은 우연히 하나뿐이지만,
다음 사람이 아무 서비스에나 `@Autowired @Qualifier("schemaOwnerDataSource") DataSource` 를
선언해도 컴파일도 되고 테스트도 통과한다.

### 3-2. 실측 — 롤 속성 (dev·test·prod)

```
$ docker exec smart-fire-hub-db-test-1 psql -U app -d smartfirehub_test -c \
  "select rolname, rolsuper, rolbypassrls, rolcreatedb from pg_roles where rolname in ('app','app_tenant');"
  rolname   | rolsuper | rolbypassrls | rolcreatedb
------------+----------+--------------+-------------
 app        | t        | t            | t
 app_tenant | f        | f            | f
(2 rows)
```

```
$ docker exec smart-fire-hub-db-1 psql -U app -d smartfirehub -c \
  "select rolname, rolsuper, rolbypassrls, rolcreatedb from pg_roles where rolname in ('app','app_tenant');"
  rolname   | rolsuper | rolbypassrls | rolcreatedb
------------+----------+--------------+-------------
 app        | t        | t            | t
 app_tenant | f        | f            | f
(2 rows)
```

**prod (읽기 전용 조회만 수행)**:

```
$ docker exec smart-fire-hub-prod-db-1 psql -U app -d smartfirehub -c \
  "select rolname, rolsuper, rolbypassrls, rolcreatedb from pg_roles where rolname in ('app','app_tenant');"
 rolname | rolsuper | rolbypassrls | rolcreatedb
---------+----------+--------------+-------------
 app     | t        | t            | t
(1 row)
```

`app_tenant` 는 prod 에 아예 없다 — 부수 확인:

```
$ docker exec smart-fire-hub-prod-db-1 psql -U app -d smartfirehub -c \
  "select version from flyway_schema_history order by installed_rank desc limit 1;"
 version
---------
 80

$ docker exec smart-fire-hub-prod-db-1 psql -U app -d smartfirehub -c "select to_regclass('public.tenant');"
 to_regclass
-------------
 (빈 값)
```

**dev·test·prod 세 환경 모두** `app` 롤이 `rolsuper=t`(완전한 슈퍼유저) 임을 실측으로 확인했다.
`rolsuper=t` 는 `rolbypassrls` 여부와 무관하게 그 자체로 모든 권한 검사를 우회한다 — 즉 이
풀에서 얻은 커넥션은 RLS 뿐 아니라 어떤 GRANT/REVOKE 도 우회한다. prod 는 이 밴드가 아직
배포되지 않아 `schemaOwnerDataSource` 자체가 존재하지 않지만(V80 이라 관련 코드가 안 돈다),
**배포되는 순간 이 노출이 prod 에도 그대로 생긴다**는 뜻이므로 지금 기록해 둔다.

### 3-3. 축소 방안 비교

| 방안 | 내용 | 장점 | 단점 |
|---|---|---|---|
| **A. 전용 비-슈퍼유저 롤** | `CREATE ON DATABASE` 권한만 가진 새 롤(예: `schema_owner`)을 만들어 그 자격증명으로 풀을 연다 | 가장 좁은 권한 — 이 롤이 뚫려도 RLS·다른 스키마 데이터는 안전 | 새 롤 생성이 곧 새 마이그레이션(운영 DB 에도 적용해야 함) + 기존 `app` 소유 스키마와의 소유권 정리 필요(스키마 소유자를 바꿀지, `CREATE` 권한만 위임할지 설계 필요) |
| **B. `@Qualifier` 노출 축소** | 빈을 별도 패키지-private 팩토리나 `TenantSchemaProvisioner` 내부 필드로만 존재하게 감춰 다른 클래스가 주입받을 길 자체를 없앤다 | 코드 변경만으로 가능, 마이그레이션 불필요 | 스프링은 Java 접근 제한자로 빈 주입을 막지 않는다 — `@Configuration` 클래스를 어떻게 분리해도 같은 애플리케이션 컨텍스트 안에서는 여전히 빈 이름으로 주입 가능한 경우가 많다(완전한 은닉이 스프링 표준 메커니즘만으로 되는지 검증하지 않았다 — **확인하지 않았다**) |
| **C. 현상 유지 + 규약 가드** | 코드는 그대로 두고, `@Qualifier("schemaOwnerDataSource")` 를 `TenantSchemaProvisioner` 밖에서 쓰면 실패하는 테스트를 추가한다(소스 스캔 방식, `DataSchemaResolutionTest` 류와 같은 패턴) | 구현 비용이 가장 작고 기동 실패 위험이 없다 | 오용을 사후에 잡을 뿐 사전 차단은 아니다 — 가드를 우회하는 것 자체는 여전히 가능(컴파일·런타임 모두 막지 않음) |

### 3-4. 왜 이 밴드에서 고치지 않는가

**고치지 않는다.** 근거:

1. **위험 대비 시급성이 낮다.** prod 는 아직 V80 이고 이 밴드가 배포되지 않았으므로, 오늘
   시점에 이 노출이 실제로 악용 가능한 환경은 없다 — 배포 시점에 함께 재검토하면 된다.
2. **자격증명 경로 변경(방안 A)은 기동 실패 위험이 크다.** 이 리포는 `spring.flyway.user`
   vs `username` 오타 하나로 기동이 깨진 전례가 있고(Task 1 R1), DataSource 설정 변경은 이
   밴드에서 가장 조심스럽게 다룬 영역(`SchemaOwnerDataSourceConfig` 의 풀 크기·타임아웃
   튜닝에만 라운드 하나를 썼다)이다. 새 롤·새 마이그레이션을 지금 추가하는 것은 이 밴드의
   범위(스키마 분리)를 벗어난 별도 변경이다.
3. **이미 6태스크·다수 라운드를 거쳤다.** 이 시점에 범위를 넓히면 리뷰 표면이 다시 커지고,
   이 밴드의 핵심 목표(테넌트별 물리 스키마 분리)와 무관한 변경이 섞여 들어간다.
4. 후속 이슈로 남기는 것이 타당하다 — **다만 이 밴드 규칙(리포지토리 PUBLIC)에 따라 이슈를
   새로 등록하지는 않았다.** §6 백로그 목록에 항목으로만 남긴다.

**권고**: 별도 승인 하에 방안 A(전용 비-슈퍼유저 롤)를 가장 먼저 검토할 것을 제안한다 —
가장 근본적인 축소이기 때문이다. 다만 그 결정 자체는 이 태스크의 범위가 아니다.

---

## 4. 드리프트 방어의 Python 절반 — 게이트 밖이라는 사실

### 4-1. 오늘의 방어 기제

Java `DataSchema.current()` 와 Python `resolve_schema()`(`apps/firehub-executor/app/tenant.py`)
는 **각자 독립적으로** 같은 파생 규약(`data` 또는 `data_t{tenantId}`)을 구현한다(Task 3,
커밋 `59afc8e2`). 두 구현이 실제로 같은 값을 내는지는 두 겹으로 방어된다:

1. **고정벡터 표**: Java `TenantSchemaVectorConformanceTest.currentMatchesFixedVector`
   (`(1,"data"),(2,"data_t2"),(7,"data_t7"),(43259,"data_t43259")`)와 Python
   `tests/test_tenant_schema_vectors.py::test_resolve_schema_vectors` 가 같은 표를 각자
   손으로 유지한다 — 한쪽만 고치면 그 표가 깨진다.
2. **소스 직접 대조**: `TenantSchemaVectorConformanceTest.pythonDerivationMatchesJavaSource()`
   가 (Python 을 실행하지 않고) `app/tenant.py` **소스 텍스트**를 정규식으로 읽어
   `_TENANT_SCHEMA_PREFIX`·`_LEGACY_SCHEMA_BY_TENANT` 리터럴을 뽑아 Java 쪽과 대조한다(Task 3
   라운드 1, R17). 이 테스트는 Java 의 `./gradlew test` 로 매번 돈다.

### 4-2. 실측 — CI·훅 게이트 상태

```
$ ls .github/workflows
ls: .github/workflows: No such file or directory
```

**이 저장소에는 CI 가 없다.**

```
$ grep -n "pytest\|python" scripts/husky/pre-commit.sh scripts/husky/pre-push.sh
(결과 없음 — 두 파일 모두 pytest/python 을 부르지 않는다)
```

```
$ grep -n '"test"' package.json
    "test": "turbo run test",
$ cat apps/firehub-executor/package.json 의 scripts.test
"if [ -x .venv/bin/python3 ]; then .venv/bin/python3 -m pytest tests/ -q; else python3 -m pytest tests/ -q; fi"
```

`pnpm test`(turbo)는 `firehub-executor` 의 `test` 스크립트(pytest)를 **포함**하지만, **어떤
pre-commit/pre-push 훅도 `pnpm test` 를 부르지 않는다**(두 훅 스크립트에 pytest·python 문자열이
전혀 없음을 위에서 실측). 즉 Python 쪽 `test_tenant_schema_vectors.py`(위 1번 방어)는 존재는
하지만, **커밋·푸시 게이트 어디에서도 자동으로 돌지 않는다.**

### 4-3. 그래서 실제로 잡히는 것 — 위 2번(소스 직접 대조)뿐

1번(Python pytest)이 게이트 밖이므로, **Java 쪽 파생 규약이 바뀌고 Java 고정벡터도 같이
갱신되면, `./gradlew test`(게이트 안, 매번 돎)는 초록인데 Python 쪽은 옛 규약 그대로 남을 수
있다** — 이걸 잡을 수 있는 유일한 자동 검사는 2번(`pythonDerivationMatchesJavaSource`)이다.

**이 방식의 한계**: 정규식으로 `_TENANT_SCHEMA_PREFIX = "..."`·`_LEGACY_SCHEMA_BY_TENANT = {...}`
형태를 찾는다. Python 쪽이 이 두 상수의 **선언 형태**를 크게 바꾸면(예: 상수를 없애고 함수
안에 인라인하거나, dict 대신 다른 자료구조로 바꾸면) 이 테스트는 리터럴을 못 찾아 **실패한다**
(찾지 못하면 실패하는 방향이므로 조용히 통과하지는 않는다 — 소스 확인: `assertThat(...find())
.isTrue()` 로 명시적으로 단언한다). 즉 안전한 방향의 취약함이지만, **텍스트 파싱이 실제
동작을 실행해 보는 것의 대체재는 아니다** — 유지보수 부채로 남는다.

### 4-4. 왜 "정공법"(executor 테스트를 게이트에 배선)을 택하지 않았는가

정공법은 `firehub-executor/package.json` 의 `test` 스크립트가 쓰는 venv 를 pre-commit/pre-push
훅에서 직접 호출하도록 배선하는 것이다. 이 밴드에서 채택하지 않은 이유:

- **이 개발 환경의 시스템 `python3` 에는 pytest 가 없다**(Task 3 라운드 1 리뷰가 실측). venv
  가 없는 개발자 환경에서는 훅이 즉시 깨진다 — 모두의 게이트가 막힌다.
- **이 저장소는 훅이 플레이크로 자주 막혀 `--no-verify` 가 이미 관행이다**(#360). 우회되는
  게이트에 새 방어를 얹는 것은 방어가 아니다 — 오히려 "게이트가 있으니 안전하다"는 잘못된
  확신만 준다.

### 4-5. 후속 조치가 필요하다면

venv 를 훅에 배선하려면 최소한: (a) 모든 개발자 환경에 `apps/firehub-executor/.venv` 가
일관되게 존재하도록 하는 온보딩 절차, (b) 훅의 플레이크 문제(#360)를 먼저 해결해 `--no-verify`
관행을 줄이는 것, 이 둘이 선행돼야 정공법이 실제로 효과를 낸다고 판단한다 — 이 판단 자체를
실측으로 검증하지는 않았다(예: venv 온보딩 절차가 실제로 얼마나 번거로운지 측정하지 않았다) —
**확인하지 않았다.**

---

## 5. 규약 가드의 잔여물 — 카탈로그 열거 규칙은 핀을 지원하지 않는다

`DataSchemaResolutionTest`(P3-a/P3-b 스키마 조립 지점 단일화 가드)의 네 규칙 중
**카탈로그 열거 규칙**(`noProductionSourceEnumeratesTenantSchemasViaCatalog`,
`information_schema.schemata`/`pg_namespace` 를 `data_t` 패턴과 함께 쓰는 코드를 잡는 규칙)은
다른 세 규칙과 다른 구조다.

**핵심 사실(소스 확인, Task 2 라운드 2 에서 발견)**: 이 규칙의 본문은 다른 세 규칙이 쓰는
`findExcessViolations`(줄 단위 예산 인프라, 핀으로 예외를 허용하는 장치)를 **쓰지 않는다** —
문장(세미콜론) 단위로 직접 순회한다. 그 결과 `CATALOG_ENUMERATION_PINS` 라는 핀 목록이 있긴
하지만 **본문이 그 목록을 전혀 읽지 않는다** — 죽은 목록이다.

**강제 장치**: `CATALOG_ENUMERATION_PINS` 를 채워도 아무 효과가 없다는 사실을 다음 사람이
발견하지 못하면, "핀을 추가했는데 왜 계속 빨간가" → "규칙을 지우거나 파일 통째 면제" 로 흐를
위험이 있다. 그래서 이 규칙의 테스트 본문 맨 앞에 `assertThat(CATALOG_ENUMERATION_PINS)
.isEmpty()` 단언을 넣어, 핀을 추가하는 순간 그 단언이 깨져 "이 규칙은 핀을 지원하지 않는다"
를 즉시 드러내게 해 뒀다(커밋 `ec653c56`).

**앞으로 정당한 예외가 생기면(예: 어떤 코드가 정당하게 `pg_namespace`+`data_t` 패턴을 같은
문장에서 써야 한다면)**: 이 `isEmpty()` 단언이 먼저 깨질 것이고, 그 시점에 **예산 인프라
(`findExcessViolations`)를 문장 단위로 다시 끌어와야 한다** — 지금은 그 리팩터링을 하지
않았다(Task 2 범위 밖으로 판단). 소스 주석만으로는 몇 개월 뒤 이 상황을 마주친 사람이 "왜
안 됐지"를 처음부터 다시 추적해야 하므로, 이 사실을 여기 별도로 기록해 둔다.

---

## 6. 후속 백로그 (이슈로 올리지 않음 — 목록으로만)

리포지토리가 PUBLIC 이므로 여기 우회 문자열이나 재현 절차는 적지 않는다. 목록만 남긴다.

1. **레거시 `pipeline_executor` 롤 은퇴(#383)** — V111 이 "물리 스키마 리네임과 같은 커밋에서
   은퇴"를 근거로 걸어 뒀는데, §2 정정대로 리네임 자체가 폐기돼 그 트리거가 사라졌다. 은퇴
   시점과 방법을 별도로 정해야 한다.
2. **`FlywayCallbackConfig` 의 기동마다 전체 테넌트 순회(#384)** — `resolveActiveTenantPipelineRolePasswords`
   가 기동마다 ACTIVE 테넌트를 순회하며 각각 `pg_roles` 조회를 한다. 공유 test DB 에 테넌트
   243개가 쌓여 있음을 실측했다(이전 태스크 기록). `roleExists` 로 fail-safe 처리돼 있어 이
   밴드 범위 밖이지만, 테넌트가 더 늘면 기동이 느려질 수 있다.
3. **소유자 풀(`schemaOwnerDataSource`) `maximumPoolSize(1)` 의 직렬화** — `createTable` 한
   번마다 카탈로그 왕복이 여러 번(존재 확인·롤 확인·기본 권한 확인) 발생한다(Task 5 가 DDL
   경로를 재검토할 때 이 풀에 새 프로덕션 호출부를 추가하지 않았음을 확인했다). 오늘은 스키마
   생성이 드문 관리 작업이라 무해하지만, 프로비저닝 빈도가 늘면(예: 대량 테넌트 온보딩) 재확인
   이 필요하다.
4. **`app` 슈퍼유저 노출 축소(§3)** — 방안 A(전용 비-슈퍼유저 롤)를 별도 승인 하에 검토할 것을
   권고한다.
5. **`IntegrationTestBase.inTenantFixture` 경계 규칙 위반 재발 패턴** — "검증 대상 프로덕션
   호출을 테스트가 열어 준 트랜잭션 안에서 실행하는" 패턴이 이 밴드를 포함해 이 저장소에서
   반복 발생했다(Task 4 라운드 2 리뷰가 `DataTableQueryServiceTenantSchemaTest` 에서 재발
   지적, 해당 태스크 리포트에 수정 기록). 유사한 헬퍼(`runInTenantTransaction` 등)를 검증
   대상 호출에 직접 씌우는 패턴이 또 나타날 수 있으니, 신규 테스트 작성 시 리뷰에서 우선
   확인할 항목으로 남긴다.
6. **드리프트 방어의 Python 절반이 게이트 밖(§4)** — 정공법(venv 를 훅에 배선)의 선행 조건
   (일관된 venv 온보딩, #360 훅 플레이크 해결)이 갖춰지면 재검토.
7. **카탈로그 열거 규약 가드의 핀 미지원(§5)** — 정당한 예외가 생기는 시점에 예산 인프라를
   문장 단위로 재작성해야 한다.

---

## 7. 이 밴드 전체에 대한 남은 우려 (Task 6 작성자 메모)

- **prod 배포는 별도 승인 사안이다.** 이 문서의 실측은 전부 dev·test·(읽기 전용) prod 조회를
  근거로 하며, 이 밴드 자체를 prod 에 배포하는 결정은 포함하지 않는다.
  `spring.flyway.baseline-version` 은 이 밴드 전체에서 80 으로 유지됐다 — 배포 시점에 실제
  최신 버전(V112)으로 갱신해야 한다는 점을 여기 남긴다(이 값을 실제로 언제 올릴지는 배포
  절차의 몫이다).
- **§1-3 의 "스키마가 없으면 GRANT 가 실패한다" 순서 문제는 실측하지 않았다** — 운영자 절차
  문서에 순서를 명시했지만 실제로 그 SQL 을 순서대로 돌려 실패/성공을 확인하지는 않았다.
- **§3 의 방안 B(빈 노출 축소)가 스프링 표준 메커니즘만으로 실제로 가능한지 검증하지 않았다.**
