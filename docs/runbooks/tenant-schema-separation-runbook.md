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

> **P7-a 이후 정정 (2026-08-19).** 이제 **테넌트 생성 API 가 있다** —
> `POST /api/platform/tenants` (§6 참고). 아래 SQL 절차는 여전히 유효하고 멱등하므로 API 를 쓸 수
> 없는 상황(운영자 계정 없음, 앱 미배포)의 폴백으로 남겨 둔다. 다만 아래 "호출자가 0개다" 라는
> 실측은 P7-a 에서 낡았다 — `PlatformTenantService.create` 가 `provisionDefaults` 를 부른다.
> **API 를 쓰면 1-1·1-2 를 대신하며, 한 트랜잭션이라 좀비 테넌트가 남지 않는다.**

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

### 1-3. 파이프라인 실행 롤 생성 (스키마와 무관 — 언제 실행해도 안전)

**최종 전체 리뷰 A5 로 순서를 다시 짰다.** 예전 버전은 번호 절차 안에 스키마 의존 GRANT 5문장을
같이 적어 놓고, 그 바로 아래 산문으로 "사실 이 시점엔 스키마가 없어서 실패한다"고 정정하는
구조였다 — 운영자는 번호를 위에서 아래로 따라가므로 이 구조 자체가 사고 원인이었다. 이제
스키마와 **무관한 것만** 여기 둔다:

```sql
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'pipeline_executor_t<id>') THEN
    CREATE ROLE pipeline_executor_t<id> LOGIN PASSWORD '<임시_아무값>';
  END IF;
  EXECUTE format('GRANT CONNECT ON DATABASE %I TO pipeline_executor_t<id>', current_database());
END
$$;
```

### 1-4. 스키마 의존 권한 — 보통 손으로 실행할 필요가 없다

**최종 전체 리뷰 B4 반영 후: 아래 6문장(5 GRANT/ALTER + REVOKE)은 이제 `TenantSchemaProvisioner`
의 자가치유 범위 전부에 들어간다.** 1-3 으로 롤을 만들고 나서 **아무것도 더 하지 않아도**,
첫 데이터셋 생성 시점에 `ensureCurrentTenantSchema()` 가 `roleExists(tx, executorRole)` 를
확인하고 이 6문장(REVOKE 포함)을 전부 대신 걸어 준다(소스 확인). 그래서 이 단계는 **전부
생략해도 되는 선택 사항**이다 — 운영자가 즉시 걸어 두고 싶을 때만 아래를 실행한다.

> ⚠ **전부 실행하거나 전부 생략하라 — 부분 실행은 자가치유되지 않는다** (코드리뷰 지적).
> `TenantSchemaProvisioner` 의 단락 판정은 "executor 롤이 테이블·시퀀스 **기본 권한**을 둘 다
> 이미 받았는가"만 본다. 즉 아래 6문장 중 두 `ALTER DEFAULT PRIVILEGES` 까지만 실행하고 마지막
> `REVOKE` 를 빠뜨리면, 그 순간부터 프로비저너가 **영구히 단락돼** REVOKE 를 대신 걸어 주지
> 않는다. 결과: 그 테넌트의 파이프라인 실행 롤이 `public` 스키마 접근권을 계속 유지한다
> (테넌트 데이터 격리가 깨지는 것은 아니다 — V32·V111 이 세운 심층 방어선 한 겹이 빠지는
> 것이다). 판정을 "6문장 전부 걸렸는가" 로 넓히는 것은 6번 백로그에 있다.

```sql
-- 먼저 스키마 존재를 확인한다. data_t<id> 는 첫 데이터셋 생성 전까지 없다(1-7 참조) — 아래
-- GRANT 들은 스키마가 없으면 그 자리에서 실패한다.
SELECT to_regnamespace('data_t<id>');  -- NULL 이면 아직 없다 — 이 단계를 생략하고 1-5 로 넘어가라

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

**`REVOKE ALL ON SCHEMA public`(최종 전체 리뷰 B4).** 처음 버전은 이 문장이 자가치유 6문장
안에 없어서, 이 단계를 건너뛰고 자가치유에만 맡기면 V32·V111 이 일부러 세운 방어선("파이프라인
실행 롤은 `public` 메타데이터를 읽으면 안 된다")이 신규 테넌트에서만 조용히 빠지는 비대칭이
있었다 — `TenantSchemaProvisioner` 코드에 이 REVOKE 를 추가해 닫았다(코드 커밋, 이 문서 편집
아님). 그래서 지금은 이 단계를 완전히 생략해도(1-3 만 실행) 첫 데이터셋 생성 시 REVOKE 까지
자동으로 걸린다. 단 위 경고대로 **부분 실행에는 이 자가치유가 적용되지 않는다** — 자가치유의
진입 조건 자체가 기본 권한 부재이기 때문이다.

### 1-5. search_path 를 DB 한정으로 고정

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

**이전 버전은 이 사실을 실측하지 않았다고 적었는데, 최종 전체 리뷰가 test DB 에서 직접 확인해
이 문서의 "확인하지 않았다" 표시를 닫는다** — 이 실측을 다시 재현해 확인했다:

```
$ docker exec smart-fire-hub-db-test-1 psql -U app -d smartfirehub_test -c \
  "select rolname, setdatabase, setconfig from pg_db_role_setting drs
   join pg_roles r on r.oid = drs.setrole
   where r.rolname in ('pipeline_executor','pipeline_executor_t1');"
       rolname        | setdatabase |     setconfig
----------------------+-------------+--------------------
 pipeline_executor    |           0 | {search_path=data}
 pipeline_executor_t1 |       16384 | {search_path=data}
```

레거시 `pipeline_executor` = `setdatabase 0`(전 DB — V111 이 피하려던 그 상태의 실물),
`pipeline_executor_t1` = `setdatabase 16384`(= `smartfirehub_test`, DB 한정). **V111 의 주장과
이 절의 인용은 실측으로 참이다.** 운영자는 신규 테넌트 롤 생성 후 다음 쿼리로 같은 방식으로
확인할 수 있다:

```sql
select rolname, setdatabase, setconfig from pg_db_role_setting drs
  join pg_roles r on r.oid = drs.setrole
  where r.rolname = 'pipeline_executor_t<id>';
-- setdatabase 가 0 이면 IN DATABASE 를 빠뜨린 것이다 — 즉시 재실행해 바로잡을 것.
-- (setdatabase 는 pg_database.oid 값이라 DB 마다 다르다 — 0 인지만 확인하면 된다.)
```

### 1-6. 비밀번호 동기화

앱을 재기동(또는 Flyway `migrate` 를 다시 트리거)하면 `RolePasswordSyncCallback` 이 1-3 에서
임시로 넣은 비밀번호를 `TenantPipelineRole.password(<id>, secret)` 로 파생한 실제 값으로
덮어쓴다. 이 콜백은 `AFTER_MIGRATE` 이벤트에서 매 기동마다 실행되며(대기 중인 마이그레이션이
없어도), `FlywayCallbackConfig.resolveActiveTenantPipelineRolePasswords` 가 ACTIVE 테넌트를
순회해 존재하는 `pipeline_executor_t{id}` 롤만 골라 동기화 대상에 넣는다(소스 확인:
`roleExists` 로 존재 여부를 먼저 검사).

### 1-7. 물리 스키마 — 만들지 않는다

`data_t<id>` 스키마는 이 절차 어디서도 명시적으로 만들지 않는다. **첫 데이터셋 생성 시**
`DataTableService.createTable()` 의 첫 줄인 `schemaProvisioner.ensureCurrentTenantSchema()`
가 지연 생성한다(소스 확인, `DataTableService.java:55` 부근). 이 지연 생성 설계의 근거는
`TenantSchemaProvisioner` 클래스 Javadoc 에 있다: "공유 test DB 에 테넌트가 243개 쌓여 있고
테스트가 이를 정리하지 않는다. 테넌트마다 스키마를 선제 생성하면 스키마가 무한 누적된다."

### 1-8. 검증 — 첫 데이터셋 생성 후 운영자가 확인할 것 (최종 전체 리뷰 B5)

이 문서는 신규 테넌트를 만드는 **유일한 경로**인데, 예전 버전은 성공을 확인할 수단이 없었다.
첫 데이터셋을 하나 만든 뒤(스모크 테스트) 아래 세 가지를 확인한다.

**(a) `search_path` 가 DB 한정으로 들어갔는가** — 1-5 의 쿼리를 그대로 다시 실행한다.
`setdatabase=0` 이면 클러스터 전역 오염이고, 이 문서가 가장 강하게 경고하는 바로 그 실수다.

**(b) 기본 권한(`pg_default_acl`) 2항목이 실제로 걸렸는가** — 이 밴드에서 **가장 조용한
실패 지점**이었다(`GRANT ... ON ALL TABLES` 는 갓 만든 빈 스키마에서 no-op 이라, 이 항목이
없으면 신규 테넌트의 파이프라인이 **나중에 만들어질** 테이블을 영영 못 본다):

```sql
select defaclrole::regrole, defaclnamespace::regnamespace, defaclobjtype, defaclacl
  from pg_default_acl
  where defaclnamespace = 'data_t<id>'::regnamespace;
-- defaclrole 이 app_tenant 이고 defaclobjtype 이 'r'(테이블)·'S'(시퀀스) 두 행이 있어야 하며,
-- 각 defaclacl 에 pipeline_executor_t<id> 가 포함돼야 한다.
```

**(c) `app_tenant` 가 이 스키마에 `CREATE` 를 가졌는가** — 없으면 다음 데이터셋 생성이
permission denied 로 실패한다:

```sql
select has_schema_privilege('app_tenant', 'data_t<id>', 'CREATE');  -- true 여야 한다
```

### 1-9. 되돌리기 — 잘못 만든 테넌트 정리 (최종 전체 리뷰 B5)

**첫 데이터셋을 아직 만들지 않았다면**(=1-7 의 스키마가 아직 생성되지 않았다면), 아래 네
곳만 지우면 된다 — 정확히 지금까지 이 절차가 만든 것들이다.

```sql
-- 1. 이 롤에 남은 기본 권한 항목(신규 테넌트가 1-4 를 손으로 실행했다면 생길 수 있다).
--    보통은 없다 — 스키마가 없으면 1-4 의 ALTER DEFAULT PRIVILEGES 자체가 실패했을 것이다.

-- 2. search_path 설정(1-5).
alter role pipeline_executor_t<id> in database <db> reset search_path;

-- 3. 롤 자체.
drop role if exists pipeline_executor_t<id>;

-- 4. 테넌트 행(1-1)과 provision_tenant_defaults(1-2)가 만든 role/role_permission/
--    report_template 행 — tenant_id 로 스코프해서 지운다. 정확한 삭제 순서·대상 테이블은
--    이 문서 밖(#383 운영 절차)에서 다룬다 — 여기서는 "네 곳이 있다"는 목록만 남긴다.
delete from tenant where id = <id>;
```

**첫 데이터셋을 이미 만들었다면(=`data_t<id>` 스키마가 존재한다) 되돌리기가 훨씬 무겁다 —
그리고 V112 의 `dataset.table_name` 유니크 접기 때문에, 그 시점 이후로는 코드(V112) 를
되돌려도 이 특정 테넌트의 스키마는 사실상 도달 불가가 된다(최종 전체 리뷰 A2).** V112 마이그레이션
주석에 적은 "구조적으로 실패 불가" 근거는 **접는 방향에만** 성립한다 — 되돌리는 방향(전역
유니크 재생성)은 이 신규 테넌트가 기존 테넌트와 같은 `table_name` 을 한 번이라도 썼다면 그
순간부터 23505 로 실패한다. 즉 **`data_t<id>` 에 실제로 데이터가 남아 있어도, 코드를 V112
이전으로 되돌리면 그 테넌트의 데이터셋 카탈로그 행을 더 이상 정상적으로 다루지 못한다** —
이것은 "롤백하면 없던 일이 된다"는 일반적인 기대와 달리 **데이터는 남지만 코드 경로가
막힌다**는 뜻이다. `data_t<id>` 를 통째로 `DROP SCHEMA ... CASCADE` 하는 것은 코드 변경이
아니라 **데이터 삭제**이므로 이 문서가 권장할 수 있는 일반적인 "되돌리기"가 아니다 — 운영자가
그 테넌트의 데이터를 실제로 버려도 되는지 개별적으로 판단해야 한다.

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
클래스는 `TenantSchemaProvisioner` 하나뿐**이다.

**최종 전체 리뷰 B10 반영 후 — 이 사실을 강제하는 규약 가드가 생겼다.**
`SchemaOwnerDataSourceExposureGuardTest`(신규, §3-4 참조)가 `schemaOwnerDataSource` 문자열을
`src/main/java` 전체에서 스캔해 이 두 파일(`SchemaOwnerDataSourceConfig`,
`TenantSchemaProvisioner`) 밖에서 나오면 실패한다 — 변이 테스트로 확인(다른 프로덕션
클래스에 이 빈을 주입받는 필드를 임시로 추가하면 정확히 이 가드만 빨개진다). 사전 차단은
아니다(가드를 지우는 것 자체는 여전히 가능) — 사후 감지 장치다.

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
풀에서 얻은 커넥션은 RLS 뿐 아니라 어떤 GRANT/REVOKE 도 우회한다. **prod 에
`schemaOwnerDataSource` 가 없는 이유를 정정한다(최종 전체 리뷰 A3)**: DB 스키마 버전(V80)과는
무관하다 — `@Configuration` 빈은 컨텍스트 기동 시 DB 상태와 상관없이 항상 만들어진다. 없는
이유는 단순히 **이 밴드의 코드 자체가 prod 에 배포되지 않았기 때문**이다(코드가 없으니 빈
정의도 없다). 결론(오늘 prod 에 이 노출이 없다)은 맞지만 근거가 뒤바뀌어 있었다 — **배포되는
순간 이 노출이 prod 에도 그대로 생긴다**는 결론은 여전히 유효하다.

### 3-3. 축소 방안 비교

| 방안 | 내용 | 장점 | 단점 |
|---|---|---|---|
| **A. 전용 비-슈퍼유저 롤** | `CREATE ON DATABASE` 권한만 가진 새 롤(예: `schema_owner`)을 만들어 그 자격증명으로 풀을 연다 | 가장 좁은 권한 — 이 롤이 뚫려도 RLS·다른 스키마 데이터는 안전 | 새 롤 생성이 곧 새 마이그레이션(운영 DB 에도 적용해야 함) + 기존 `app` 소유 스키마와의 소유권 정리 필요(스키마 소유자를 바꿀지, `CREATE` 권한만 위임할지 설계 필요) |
| **B. `@Qualifier` 노출 축소** | 빈을 별도 패키지-private 팩토리나 `TenantSchemaProvisioner` 내부 필드로만 존재하게 감춰 다른 클래스가 주입받을 길 자체를 없앤다 | 코드 변경만으로 가능, 마이그레이션 불필요 | 스프링은 Java 접근 제한자로 빈 주입을 막지 않는다 — `@Configuration` 클래스를 어떻게 분리해도 같은 애플리케이션 컨텍스트 안에서는 여전히 빈 이름으로 주입 가능한 경우가 많다(완전한 은닉이 스프링 표준 메커니즘만으로 되는지 검증하지 않았다 — **확인하지 않았다**) |
| **C. 현상 유지 + 규약 가드** | 코드는 그대로 두고, `@Qualifier("schemaOwnerDataSource")` 를 `TenantSchemaProvisioner` 밖에서 쓰면 실패하는 테스트를 추가한다(소스 스캔 방식, `DataSchemaResolutionTest` 류와 같은 패턴) | 구현 비용이 가장 작고 기동 실패 위험이 없다 | 오용을 사후에 잡을 뿐 사전 차단은 아니다 — 가드를 우회하는 것 자체는 여전히 가능(컴파일·런타임 모두 막지 않음) |

### 3-4. 컨트롤러 룰링 — 방안 C 만 채택, 방안 A 는 이연 (최종 전체 리뷰 B10)

**최종 전체 리뷰가 이 절 "고치지 않는다"를 원장의 명시된 종료 조건("밴드 종료 전 반드시
처리")에 대한 범위 축소로 판단했다** — T6 초판의 근거(prod 미배포, 자격증명 경로 변경의
기동 실패 위험)는 타당하지만, 이 밴드가 이미 운영 중인 규약 가드 인프라(방안 C)조차
채택하지 않은 것은 근거 부족이라는 지적이었다. **컨트롤러 룰링: 방안 C(소스 스캔 규약
가드)만 채택하고 방안 A·B 는 이연한다.**

**채택한 것 — 방안 C**: `SchemaOwnerDataSourceExposureGuardTest`(신규)가
`schemaOwnerDataSource` 문자열을 `src/main/java` 전체에서 스캔해, `SchemaOwnerDataSourceConfig`
(빈 정의)와 `TenantSchemaProvisioner`(유일한 프로덕션 주입 지점) 밖에서 이 문자열이 나오면
빨개지게 했다 — `DataSchemaResolutionTest` 류와 같은 패턴이라 이 밴드에 사실상 무료였다.
변이 테스트로 확인: 다른 프로덕션 클래스(`DataTableService`)에 이 빈을 주입받는 필드를
임시로 추가하자 정확히 이 가드만 빨개졌다(`cp`+`cmp` 로 원본 복원 확인).

**이연한 것 — 방안 A(전용 비-슈퍼유저 롤)**: 자격증명 경로 변경은 새 마이그레이션 +
기동 실패 위험이 있는 별도 승인 사안이다. 근거:

1. **위험 대비 시급성이 낮다.** prod 는 아직 V80 이고 이 밴드가 배포되지 않았으므로, 오늘
   시점에 이 노출이 실제로 악용 가능한 환경은 없다 — 배포 시점에 함께 재검토하면 된다.
2. **자격증명 경로 변경은 기동 실패 위험이 크다.** 이 리포는 `spring.flyway.user`
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
4. **`app` 슈퍼유저 노출 축소(§3)** — 방안 C(소스 스캔 규약 가드)는 채택 완료
   (`SchemaOwnerDataSourceExposureGuardTest`). 방안 A(전용 비-슈퍼유저 롤)를 별도 승인 하에
   검토할 것을 권고한다 — 가드는 사후 감지일 뿐 사전 차단이 아니다.
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
8. **`TenantSchemaProvisioner` 단락 판정의 범위가 부여 블록보다 좁다(코드리뷰 지적)** —
   단락 판정은 executor 롤의 **기본 권한 완료**만 보는데, 판정이 가드하는 블록은 그 외에도
   `app_tenant` 대상 스키마 GRANT 와 `REVOKE ALL ON SCHEMA public` 을 실행한다. 그래서 두
   경로가 자가치유되지 않는다: (a) 운영자가 §1-4 를 **부분 실행**해 `ALTER DEFAULT PRIVILEGES`
   둘은 걸고 `REVOKE` 를 빠뜨린 경우, (b) 스키마가 절차 밖에서(§1-7 이 금지한 수동
   `CREATE SCHEMA`) `app_tenant` GRANT 없이 만들어진 경우. 둘 다 오늘 prod 노출 0(이 밴드는
   prod 미배포, prod 는 V80)이고 (a) 는 심층 방어 한 겹, (b) 는 금지된 절차다. 정공법은
   부여 블록을 **비용이 싼 스키마 단위 문장(매번 실행)** 과 **테이블을 순회하는 비싼 문장
   (`ON ALL TABLES`/`ON ALL SEQUENCES`, 판정으로 가드)** 으로 쪼개는 것 — 그러면 판정 범위와
   가드 대상이 일치한다. 이 밴드에서 하지 않은 이유: 최종 전체 리뷰 통과 후의 구조 변경이고,
   `ALTER DEFAULT PRIVILEGES` 를 테넌트 1 의 hot path(모든 `createTable`)로 올리는 비용을
   실측하지 않았다.
9. **"테넌트 1 = `data`" 특수 케이스가 Java·Python 두 곳에 손코딩돼 있다(simplify 패스 ALTITUDE
   축)** — `DataSchema.LEGACY_SCHEMA_BY_TENANT` 와 `app/tenant.py` 의 `_LEGACY_SCHEMA_BY_TENANT`.
   갈라지면 크로스 테넌트 스키마 오접속이고, 이를 지키는 것은 타입도 단일 진입점도 아니라
   `TenantSchemaVectorConformanceTest`(Java 가 `tenant.py` 소스를 파싱해 대조)뿐이다. **옳은
   고도**는 `tenant` 테이블에 스키마명 컬럼을 두어(신규 마이그레이션) 파생 규칙 자체를 없애는
   것 — 사실이 코드 두 벌이 아니라 데이터 한 행이 된다. 이행 비용이 작지 않다: 캐시 계층(요청당
   `DataSchema.current()` 호출이 많다), Python executor 는 별도 프로세스라 DB 조회 경로가 새로
   필요하거나 API 가 페이로드로 내려야 하는데 그건 지금 일부러 피한 "클라이언트 제공 식별자
   신뢰" 문제를 되살린다, 그리고 `DataSchemaResolutionTest` 의 개수 PIN 가드를 재설계해야 한다.
   현 상태는 "틀린 설계" 가 아니라 **이연된 올바른 설계의 임시 근사**로 읽어라. P3-c 후속 슬라이스
   후보.
10. **`ensureCurrentTenantSchema()` 배선이 관례로만 지켜진다(simplify 패스 ALTITUDE 축)** —
    프로덕션 호출부는 `DataTableService.createTable` 한 곳뿐이고, 다른 물리 테이블 생성 경로
    (`createTempTable`·`cloneTable`·staging·executor 의 swap)는 전부 "이미 존재하는 데이터셋의
    스키마 안에서만 동작한다"는 **암묵 불변식**에 기대어 안전하다(오늘 모든 경로를 추적해 확인함).
    그런데 그 불변식은 타입도 강제도 아니다 — 예컨대 스텝 SQL 이 `CREATE TABLE ... AS` 를 직접
    실행하는 기능이 생기면(`SqlScriptExecutor` 는 이미 임의 스크립트를 실행한다) 조용히 배선을
    우회하고 신규 테넌트에서 늦게 터진다. 근본 비대칭: "스키마명을 조립하는 유일한 지점" 은
    `DataSchema` 인데 "스키마가 존재하게 만드는 지점" 은 다른 클래스에 있다. 정공법(배선을
    `DataSchema` 로 옮기기)은 정적 유틸 → 인스턴스 전환이라 호출부 수십 곳을 건드리는 큰
    리팩터다. **싼 중간안**: `DataSchema.qualify`/`current` 를 호출하는 프로덕션 클래스 목록을
    핀하는 규약 가드를 하나 더 두어, 새 클래스가 등장하면 사람이 배선 여부를 검토하게 한다
    (이 밴드가 이미 쓰는 패턴이라 비용이 낮다).
11. **`schemaOwnerDataSource` 오용 방지를 자바 접근 제어로 구조화(§3 방안 A 보다 훨씬 싸다)** —
    지금은 평범한 public `@Bean DataSource` 를 `@Qualifier` 로 노출하고 소스 스캔 화이트리스트
    (`SchemaOwnerDataSourceExposureGuardTest`)로 감지한다. 대안: 설정 클래스를 `global.tenant`
    패키지로 옮기고 `DataSource` 를 감싼 **package-private** 타입을 그 패키지 안에서만 정의해
    `@Bean` 도 package-private 으로 선언한다 — 다른 패키지는 그 타입을 참조조차 못 하므로
    화이트리스트가 소스 스캔이 아니라 **컴파일러**가 된다. 완료되면 가드 테스트를 삭제할 수
    있다. 스프링이 package-private `@Bean`·타입을 문제없이 처리하는지는 확인하지 않았다.

---

## 7. 이 밴드 전체에 대한 남은 우려 (Task 6 작성자 메모)

- **prod 배포는 별도 승인 사안이다.** 이 문서의 실측은 전부 dev·test·(읽기 전용) prod 조회를
  근거로 하며, 이 밴드 자체를 prod 에 배포하는 결정은 포함하지 않는다.
- **`spring.flyway.baseline-version` 을 배포 시점에 올려야 한다는 이전 지시는 정정한다(최종
  전체 리뷰 A1).** 실측: prod DB 는 `flyway_schema_history` 가 이미 존재하고 최신 버전이
  `80` 이다. **히스토리 테이블이 있으면 `baseline-on-migrate`/`baseline-version` 은 아무
  역할도 하지 않고** V81→V112 가 순서대로 그냥 적용된다 — 즉 이 값을 올리는 것은 **이 리포의
  현재 배포 흐름에서는 아무 효과가 없다.** 이 값이 실제로 작동하는 유일한 조건은 "히스토리
  테이블이 없는, 비어 있지 않은 스키마" 인데, `.claude/docs/deploy.md` 에 그런 경로(백업
  복원·`pg_restore`·초기화 절차)가 없고 `docker-compose*.yml` 에도 `docker-entrypoint-initdb.d`
  류 시드가 없어(빈 볼륨은 빈 스키마로 시작 → `baseline-on-migrate` 자체가 발동 안 함) 그
  상태가 발생하는 경로를 찾지 못했다 — **이 리포의 과거 관례(계획서 다수가 "baseline-version
  을 최신보다 낮게 두지 말 것"이라고 적음)와 실제 상태(P3 전체가 이미 80 에 머물러 있음)가
  서로 어긋나 있다는 사실만 기록한다.** 관례를 재검토할지는 이 밴드 밖이다.
- **§1-4(구 §1-3)의 순서 재구성(최종 전체 리뷰 A5·B4)으로 이 우려는 대부분 해소됐다** —
  스키마 의존 GRANT/REVOKE 를 절차에서 선택 사항으로 뺐고, `TenantSchemaProvisioner` 의
  자가치유가 그 6문장(REVOKE 포함, B4)을 전부 대신 걸어 주므로 운영자가 순서를 틀려도 첫
  데이터셋 생성 시점에 자동으로 바로잡힌다. 다만 §1-4 를 **직접 실행하기로 택한 경우** 그
  SQL 을 실제로 순서대로 돌려 실패/성공을 확인하지는 않았다 — 그 경로는 여전히 확인하지
  않았다.
- **§3 의 방안 B(빈 노출 축소)가 스프링 표준 메커니즘만으로 실제로 가능한지 검증하지 않았다.**

---

## 6. 운영자 평면 (P7-a, 2026-08-19)

`/api/platform/**` 이 신설됐다. 테넌트 평면(`/api/v1/**`)과 **토큰·권한·쿠키가 모두 분리**되며,
평면이 어긋난 요청은 403 으로 끊긴다.

### 6-1. 최초 SUPER_ADMIN 부트스트랩 — 신규 환경에서 반드시 필요

V82 가 기존 전역 `ADMIN` 롤 보유자를 `SUPER_ADMIN` 으로 승격해 뒀다. 그래서 dev DB 는 3명이
있지만, **그런 사용자가 없던 환경은 `platform_user_role` 이 0행이고 그러면 아무도 운영자
로그인을 할 수 없다**(test DB 가 실제로 그 상태다 — 실측). 신규/빈 환경에서는 첫 운영자를
직접 만들어야 한다:

```sql
INSERT INTO platform_user_role (user_id, platform_role_id)
SELECT u.id, pr.id
FROM "user" u, platform_role pr
WHERE u.email = '<운영자 이메일>' AND pr.name = 'SUPER_ADMIN'
ON CONFLICT DO NOTHING;
```

확인:

```sql
SELECT u.username, p.code
FROM platform_user_role pur
JOIN "user" u  ON u.id = pur.user_id
JOIN platform_role_permission prp ON prp.platform_role_id = pur.platform_role_id
JOIN permission p ON p.id = prp.permission_id
ORDER BY 1, 2;
```

`SUPER_ADMIN` 은 V82 의 4건 + V113 의 2건 = **6건**을 보유한다:
`platform:member:read`, `platform:settings:read`, `platform:settings:write`,
`platform:tenant:create`, `platform:tenant:read`, `platform:tenant:suspend`.

### 6-2. 엔드포인트

| 메서드·경로 | 권한 | 비고 |
|---|---|---|
| `POST /api/platform/auth/login` | (public) | 플랫폼 롤 보유자만 성공. 실패 응답은 자격증명 오류와 구분되지 않는다(계정 열거 방지) |
| `POST /api/platform/auth/refresh` | (public) | 쿠키 `platformRefreshToken`. 테넌트 리프레시 토큰은 거부된다 |
| `POST /api/platform/auth/logout` | 인증만 | 이 사용자의 리프레시 토큰 전부 폐기 |
| `GET /api/platform/auth/me` | 인증만 | 보유 권한 포함 |
| `GET /api/platform/tenants` | `platform:tenant:read` | 멤버 수 집계 포함 |
| `GET /api/platform/tenants/{id}` | `platform:tenant:read` | 도메인 데이터는 포함하지 않는다 |
| `POST /api/platform/tenants` | `platform:tenant:create` | 행 + 초기 Owner + 기본 시드가 한 트랜잭션 |
| `POST /api/platform/tenants/{id}/suspend` | `platform:tenant:suspend` | |
| `POST /api/platform/tenants/{id}/activate` | `platform:tenant:suspend` | 같은 상태 스위치의 양방향이라 같은 권한 |
| `GET /api/platform/tenants/{id}/members` | `platform:member:read` | 표시용 `membership.role` |
| `GET /api/platform/settings` | `platform:settings:read` | 18키 전체, 비밀값 마스킹. **쓰기는 P7-b** |

### 6-3. 반드시 알아야 할 세 가지

1. **정지는 즉시 반영되지 않는다.** `tenant.status = 'SUSPENDED'` 는 `select-tenant` 와
   `refresh` 에서 재검증되므로, **이미 발급된 액세스 토큰은 만료(기본 30분)까지 유효하다.**
   즉시 차단이 필요하면 별도 조치(리프레시 토큰 폐기 + 만료 단축 또는 서버측 차단 목록)가
   필요하며 P7-a 범위 밖이다. 사고 대응 시에는 해당 사용자들의 리프레시 토큰을 폐기해
   30분 뒤 확실히 끊기게 만드는 것이 현재 할 수 있는 최선이다.
2. **테넌트 생성은 데이터 스키마(`data_t{id}`)를 만들지 않는다.** `TenantSchemaProvisioner` 는
   의도적으로 지연 생성이며(§1-7), 첫 데이터셋 생성 시점에 스키마와 권한이 함께 만들어진다.
   생성 직후 `data_t{id}` 가 없는 것은 정상이다.
3. **운영자 로그인은 감사 로그를 남기지 않는다(P7-a 의 의도된 이연).** 테넌트 로그인은
   `audit_log` 에 기록하면서 GUC 로 테넌트를 붙이지만, 운영자에게는 테넌트가 없어 NULL 테넌트
   행이 된다. `audit_log` 정책은 형태 (b)(`IS NOT DISTINCT FROM`)라 **GUC 가 빈 조회에서 NULL
   테넌트 행이 매칭된다** — 즉 운영자 감사 행을 넣으려면 그 가시성 규칙을 먼저 정해야 한다.
   그 결정 전까지 운영자 행위 추적은 애플리케이션 로그에 의존한다.
