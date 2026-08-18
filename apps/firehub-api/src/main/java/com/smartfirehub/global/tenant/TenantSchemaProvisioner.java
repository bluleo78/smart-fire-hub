package com.smartfirehub.global.tenant;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;

import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * 현재 테넌트의 데이터 스키마를 <b>지연 생성</b>한다 — 실제로 데이터 테이블을 만드는 시점에.
 *
 * <p>왜 테넌트 생성 시점이 아닌가: 공유 test DB 에 테넌트가 243개 쌓여 있고(2026-08-18 실측)
 * 테스트가 이를 정리하지 않는다. 테넌트마다 스키마를 선제 생성하면 스키마가 무한 누적된다.
 * 실제로 데이터 테이블을 만드는 테넌트만 스키마를 갖는 것이 안전하고 충분하다.
 *
 * <p>권한 부여는 CREATE SCHEMA 와 같은 트랜잭션에서 한다. 스키마만 있고 pipeline_executor_t{id}
 * 가 USAGE 를 못 받은 중간 상태가 남으면, 파이프라인이 "테이블이 없다"가 아니라 "권한이 없다"로
 * 터지고 원인 추적이 훨씬 어려워진다.
 */
@Service
public class TenantSchemaProvisioner {

  /** 새 스키마의 런타임 CRUD 주체. RLS 를 지는 이 롤에 스키마 USAGE·CREATE 를 준다. */
  private static final String RUNTIME_ROLE = "app_tenant";

  /**
   * 소유자 롤(app) 자격증명으로 여는 DSLContext.
   *
   * <p>⚠ 절대 스프링이 주입하는 기본 DSLContext 를 쓰지 않는다 — 그것은 런타임 DataSource
   * (app_tenant) 기반이고, CREATE SCHEMA 가 permission denied 로 터진다. 그리고 그 실패는
   * "D4 판단이 틀렸다" 처럼 읽혀서 원인 추적을 크게 헤매게 만든다. 아래처럼 명시적으로 만든다.
   */
  private final DSLContext ownerDsl;

  public TenantSchemaProvisioner(
      @Qualifier("schemaOwnerDataSource") DataSource schemaOwnerDataSource) {
    this.ownerDsl = DSL.using(schemaOwnerDataSource, SQLDialect.POSTGRES);
  }

  /**
   * 현재 테넌트의 스키마가 없으면 만들고 필요한 권한을 부여한다. 멱등 — 이미 있으면 아무것도
   * 하지 않는다.
   *
   * @throws MissingTenantScopeException 테넌트 컨텍스트가 없을 때(={@link DataSchema#current()}
   *     가 던진다)
   */
  public void ensureCurrentTenantSchema() {
    String schema = DataSchema.current(); // 컨텍스트 없으면 여기서 터진다
    long tenantId = TenantContext.require("테넌트 스키마 프로비저닝");
    String executorRole = TenantPipelineRole.roleName(tenantId);

    // 진입 시점 상태를 먼저 잡아 둔다 — catch 블록의 R11 재개정 판정(아래)이 이 값을 쓴다.
    boolean existedBefore = schemaExists(schema);

    // 단락 판정이 지키는 두 불변식(상세 이력은 progress.md 원장 R9·R12·B1):
    //
    // 1) **테넌트 1(data)에 락 폭풍을 내지 않는다.** 아래 부여 블록의 GRANT ... ON ALL TABLES 는
    //    대상 테이블마다 AccessExclusiveLock 을 잡는다. prod 의 data 에는 테이블이 86개 있으므로
    //    (실측) 단락이 없으면 createTable 마다 그 락을 다시 걸었다 풀며, 동시에 임포트가 돌면
    //    락 대기·데드락으로 번진다.
    // 2) **자가치유 경로를 닫지 않는다.** 신규 테넌트의 pipeline_executor_t{id} 생성은 운영자
    //    절차(#383)라 첫 데이터셋 생성 시점에는 없는 것이 흔하고, 그때 executor 대상 문장들은
    //    전부 건너뛴다. 그래서 "스키마가 있으면 끝" 으로 단락시키면 롤이 나중에 생겨도 다시
    //    들어오지 않아(FlywayCallbackConfig 는 비밀번호만 동기화한다) 그 테넌트의 파이프라인이
    //    에러 없이 영구히 안 돈다. "USAGE 보유" 로 판정해도 같은 함정이 남는다 — 운영자가 1차
    //    조치로 GRANT USAGE 만 손으로 주면 그 순간 영구 단락된다. 그래서 치유 완료의 정의를
    //    "테이블·시퀀스 기본 권한이 둘 다 걸려 있는가"(hasCompleteDefaultPrivileges)로 잡는다 —
    //    GRANT ... ON ALL TABLES 는 갓 만든 스키마에서 no-op 이라 기본 권한만이 진짜 완료다.
    //
    // 판정을 순수 함수로 뽑아 둔 이유: 이 조건을 살아있는 DB 로 재현하려면 테넌트 1 의 data 를
    // 건드려야 하는데 그건 테스트 규율이 금지한다(TenantSchemaProvisionerTest 클래스 Javadoc).
    // 그래서 결정표(TenantSchemaProvisionerSkipDecisionTest)로 판정만 직접 검증하고, 판정이
    // 실제로 단락을 일으키는지는 TenantSchemaProvisionerTest
    // #skipsProvisioningOnceDefaultPrivilegesAreComplete 가 신규 스키마에서 행동으로 확인한다.
    //
    // 아래 두 값을 existedBefore 로 먼저 가드하는 것은 && / || 단락 평가와 같은 목적이다 —
    // existedBefore=false 면 어차피 스키마를 새로 만들어야 하므로 카탈로그를 더 볼 이유가 없다.
    boolean roleExistsNow = existedBefore && roleExists(ownerDsl, executorRole);
    boolean hasCompletePrivilegesNow =
        existedBefore && roleExistsNow && hasCompleteDefaultPrivileges(schema, executorRole);
    if (shouldSkipProvisioning(
        new ExistedBefore(existedBefore),
        new RoleExists(roleExistsNow),
        new HasCompleteDefaultPrivileges(hasCompletePrivilegesNow))) {
      return;
    }

    try {
      ownerDsl.transaction(
          cfg -> {
            DSLContext tx = DSL.using(cfg);
            tx.execute("CREATE SCHEMA IF NOT EXISTS {0}", name(schema));
            tx.execute("GRANT USAGE ON SCHEMA {0} TO {1}", name(schema), name(RUNTIME_ROLE));
            // 런타임이 이 안에 데이터셋 테이블을 만드는 주체 — 없으면 첫 데이터셋 생성이
            // permission denied 로 터진다(V111 에는 없는 V83 계승분).
            tx.execute("GRANT CREATE ON SCHEMA {0} TO {1}", name(schema), name(RUNTIME_ROLE));

            // executorRole 이 아직 없을 수 있다 — 신규 테넌트 롤 생성은 운영자 절차(#383)이지
            // 이 프로비저너가 만드는 것이 아니다. 없다고 실패시키면, 롤이 아직 프로비저닝되지
            // 않은 테넌트 하나 때문에 데이터셋 생성 전체가 막힌다(FlywayCallbackConfig.roleExists
            // 와 같은 판단). 롤이 나중에 생기면 위 단락 조건이 다시 이 블록으로 들여보낸다.
            if (roleExists(tx, executorRole)) {
              tx.execute("GRANT USAGE ON SCHEMA {0} TO {1}", name(schema), name(executorRole));
              tx.execute(
                  "GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA {0} TO {1}",
                  name(schema), name(executorRole));
              tx.execute(
                  "GRANT USAGE ON ALL SEQUENCES IN SCHEMA {0} TO {1}",
                  name(schema), name(executorRole));
              // FOR ROLE 은 반드시 app_tenant 다(V83 이 이미 겪은 함정) — 런타임 DDL 주체가
              // app_tenant 이므로, FOR ROLE app 으로 걸면 신규 테이블이 executor 롤에게 조용히
              // 안 보인다.
              tx.execute(
                  "ALTER DEFAULT PRIVILEGES FOR ROLE {0} IN SCHEMA {1}"
                      + " GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO {2}",
                  name(RUNTIME_ROLE), name(schema), name(executorRole));
              tx.execute(
                  "ALTER DEFAULT PRIVILEGES FOR ROLE {0} IN SCHEMA {1}"
                      + " GRANT USAGE ON SEQUENCES TO {2}",
                  name(RUNTIME_ROLE), name(schema), name(executorRole));
              // V32·V111 이 세운 방어선을 신규 테넌트에도 적용한다 — 파이프라인 실행 롤은
              // public 메타데이터를 읽으면 안 된다. 멱등이다(없는 권한을 회수해도 오류가 아니다).
              //
              // ⚠ 자가치유 범위의 한계: 위 단락 판정은 executor 롤의 **기본 권한 완료 여부만**
              // 본다. 그래서 운영자가 런북 §1-4 를 부분 실행해 ALTER DEFAULT PRIVILEGES 둘까지
              // 걸고 이 REVOKE 를 빠뜨리면 그 순간부터 이 블록에 다시 들어오지 않아 REVOKE 가
              // 끝내 걸리지 않는다. 자가치유가 성립하는 것은 §1-4 를 **전부 생략**했을 때다
              // (런북 §1-4 경고, 정공법은 §6-8 백로그).
              tx.execute("REVOKE ALL ON SCHEMA public FROM {0}", name(executorRole));
            }
          });
    } catch (DataAccessException e) {
      // 경합 흡수의 범위는 "진짜 생성 경합" 하나다 — 판정 기준이 "지금 존재하는가" 가 아니라
      // "들어올 때 없었는데 지금은 있는가" 인 것이 핵심이다(상세 이력은 progress.md 원장 R11).
      //
      // existedBefore=false 이고 지금은 존재하면 다른 트랜잭션이 막 만든 것이므로
      // (CREATE SCHEMA IF NOT EXISTS 의 경쟁 조건 — 실측 SQLSTATE 23505,
      // pg_namespace_nspname_index) 성공과 같은 상태다. 그래서 삼킨다.
      //
      // existedBefore=true 면 무슨 예외든 **항상 전파한다.** 그 경로는 자가치유 경로이고
      // (진입 시점에 스키마가 이미 있다) 거기서 "지금 존재하는가" 로 판정하면 트랜잭션이 무슨
      // 이유로 실패해도(롤 동시 드롭, 데드락, statement timeout) 조건이 항상 참이라 전부
      // 삼켜져 조용히 정상 반환한다 — 권한 부여 실패가 성공으로 보고되고 다음 호출도 같은
      // 실패를 반복한다. 단락 판정이 막으려던 무성 실패를 catch 가 다시 여는 셈이다.
      // 존재 재확인을 조건식 안에서 하지 않는다(코드리뷰 지적) — 그 조회는 크기 1 인 소유자
      // 풀에서 커넥션을 새로 얻으므로, 원래 실패가 "커넥션 사망·풀 고갈·타임아웃" 이었다면
      // 이 확인도 같이 터진다. 조건식 안에서 터지면 새 예외가 e 를 대체해 버려서, 바로 위
      // R11 재개정이 드러내려는 근본 원인이 사라진다. 확인 실패는 suppressed 로 붙이고 원래
      // 예외를 그대로 던진다.
      boolean existsNow;
      try {
        existsNow = schemaExists(schema);
      } catch (RuntimeException probeFailure) {
        e.addSuppressed(probeFailure);
        throw e;
      }
      if (!shouldSwallowCreationRace(new ExistedBefore(existedBefore), new ExistsNow(existsNow))) {
        throw e;
      }
    }
  }

  /**
   * {@link #shouldSwallowCreationRace} 의 첫 번째 인자를 감싼다(라운드 3 리뷰 N7).
   *
   * <p><b>왜 {@code boolean} 두 개가 아니라 타입 두 개인가 — 다음 사람이 "쓸데없이 감쌌다"고
   * 되돌리지 말 것.</b> 인자가 둘 다 {@code boolean} 이면 호출부에서 순서를 바꿔도(예:
   * {@code shouldSwallowCreationRace(schemaExists(schema), existedBefore)}) 컴파일이 통과하고,
   * {@link TenantSchemaProvisionerSwallowDecisionTest} 의 결정표 테스트도 그대로 초록을
   * 유지한다(그 테스트는 정적 메서드를 직접 호출하므로 실제 호출부의 인자 순서 실수를 못
   * 잡는다). 그 변이의 실제 피해: 진짜 생성 경합에서 삼키지 않고 그대로 전파해, 한 테넌트가
   * 데이터셋 두 개를 동시에 만드는 흔한 경로에서 23505 가 사용자에게 그대로 노출된다. 두
   * 값을 별도 타입으로 감싸면 순서를 바꾸는 순간 컴파일 에러가 나 이 변이 자체가 성립하지
   * 않는다.
   */
  record ExistedBefore(boolean value) {}

  /** {@link #shouldSwallowCreationRace} 의 두 번째 인자를 감싼다 — 이유는 {@link ExistedBefore} 참조. */
  record ExistsNow(boolean value) {}

  /**
   * catch 블록의 판정을 순수 함수로 뽑아 둔다 — DB 없이 결정표를 직접 단위 테스트하기
   * 위해서다. {@code app} 롤이 이 저장소의 test·dev DB 에서 슈퍼유저라(실측) 권한(ACL) 기반
   * 실패를 살아있는 DB 로 재현할 방법이 사실상 없다(슈퍼유저는 GRANT 류를 항상 통과시킨다).
   *
   * @param existedBefore {@code ensureCurrentTenantSchema} 진입 시점에 스키마가 이미 있었는가
   * @param existsNow 트랜잭션 실패 직후 스키마가 존재하는가
   * @return 진짜 생성 경합(existedBefore=false 인데 지금은 존재)이면 {@code true}(삼킨다).
   *     {@code existedBefore=true}(자가치유 경로)면 결과와 무관하게 항상 {@code false}(전파한다).
   */
  static boolean shouldSwallowCreationRace(ExistedBefore existedBefore, ExistsNow existsNow) {
    return !existedBefore.value() && existsNow.value();
  }

  /** {@link #shouldSkipProvisioning} 의 두 번째 인자를 감싼다 — 이유는 {@link ExistedBefore} 참조. */
  record RoleExists(boolean value) {}

  /** {@link #shouldSkipProvisioning} 의 세 번째 인자를 감싼다 — 이유는 {@link ExistedBefore} 참조. */
  record HasCompleteDefaultPrivileges(boolean value) {}

  /**
   * {@code ensureCurrentTenantSchema} 진입부의 단락 판정. 이 판정이 지키는 두 불변식과 순수
   * 함수로 뽑은 이유는 호출부 주석에 있다.
   *
   * @param existedBefore 진입 시점에 스키마가 이미 있었는가
   * @param roleExists executor 롤이 존재하는가(스키마가 없으면 항상 {@code false} 로 들어온다
   *     — 호출부가 불필요한 카탈로그 조회를 피하려고 미리 가드한다)
   * @param hasCompleteDefaultPrivileges executor 롤이 테이블·시퀀스 기본 권한을 이미 모두
   *     받았는가(위와 같은 이유로 {@code existedBefore && roleExists} 가 아니면 항상 {@code
   *     false})
   * @return 단락(=아무 것도 하지 않고 반환)해야 하면 {@code true}
   */
  static boolean shouldSkipProvisioning(
      ExistedBefore existedBefore,
      RoleExists roleExists,
      HasCompleteDefaultPrivileges hasCompleteDefaultPrivileges) {
    return existedBefore.value()
        && (!roleExists.value() || hasCompleteDefaultPrivileges.value());
  }

  /** 소유자 커넥션으로 pg_namespace 를 조회해 스키마 존재 여부를 확인한다. */
  private boolean schemaExists(String schema) {
    return ownerDsl.fetchExists(
        ownerDsl
            .selectOne()
            .from("pg_namespace")
            .where(field("nspname", String.class).eq(schema)));
  }

  /** pg_roles 에 롤이 실제로 존재하는지 확인한다(FlywayCallbackConfig.roleExists 와 같은 판단). */
  private boolean roleExists(DSLContext dsl, String roleName) {
    return dsl.fetchExists(
        dsl.selectOne().from("pg_roles").where(field("rolname", String.class).eq(roleName)));
  }

  /**
   * executor 롤이 이 스키마에서 테이블('r')·시퀀스('S') 기본 권한을 <b>모두</b> 이미 받았는지
   * 확인한다(R12 자가치유 단락 조건, 라운드 2 리뷰로 개정 — 이전엔 {@code has_schema_privilege}
   * 로 USAGE 만 봤는데, 운영자가 GRANT USAGE 만 손으로 주면 그 순간부터 영구 단락돼
   * ALTER DEFAULT PRIVILEGES 는 끝내 안 걸리는 중간 상태에 갇혔다).
   *
   * <p><b>테스트의 {@code defaultAclExists} 와 SQL 을 공유하지 않는다.</b> 같은 함수를 프로덕션과
   * 테스트가 함께 쓰면, 그 함수 자체가 틀렸을 때 테스트가 그 결함을 잡지 못한다(라운드 2 리뷰
   * 지시) — 그래서 이 메서드는 독립적으로 같은 판정을 다시 구현한다. 두 구현이 어긋나지 않는지는
   * {@code TenantSchemaProvisionerTest.skipsProvisioningOnceDefaultPrivilegesAreComplete} 가
   * 행동으로 대조한다(코드리뷰 지적 — 그 전까지 이 메서드는 검증하는 테스트가 하나도 없어
   * {@code return false} 변이가 스위트를 통과했다).
   */
  private boolean hasCompleteDefaultPrivileges(String schema, String roleName) {
    Object result =
        ownerDsl.fetchValue(
            "select"
                + "  exists ("
                + "    select 1 from pg_default_acl da"
                + "    join pg_namespace n on n.oid = da.defaclnamespace"
                + "    join pg_roles r on r.oid = da.defaclrole"
                + "    where n.nspname = ? and r.rolname = ? and da.defaclobjtype = 'r'"
                + "      and exists ("
                + "        select 1 from unnest(da.defaclacl) as acl(item)"
                + "        where item::text like ?"
                + "      )"
                + "  )"
                + "  and exists ("
                + "    select 1 from pg_default_acl da"
                + "    join pg_namespace n on n.oid = da.defaclnamespace"
                + "    join pg_roles r on r.oid = da.defaclrole"
                + "    where n.nspname = ? and r.rolname = ? and da.defaclobjtype = 'S'"
                + "      and exists ("
                + "        select 1 from unnest(da.defaclacl) as acl(item)"
                + "        where item::text like ?"
                + "      )"
                + "  )",
            schema,
            RUNTIME_ROLE,
            roleName + "=%",
            schema,
            RUNTIME_ROLE,
            roleName + "=%");
    // Boolean.TRUE.equals 로 조용히 false 로 떨어뜨리지 않는다(코드리뷰 지적). false 는
    // "단락하지 않는다" = 6문장 재실행이므로, 테넌트 1(data, 86 테이블)에서 R9 가 막으려던
    // 락 폭풍이 그대로 돌아온다. select exists(...) 는 항상 boolean 한 행을 돌려주므로,
    // 아니라면 구조적으로 뭔가 틀린 것 — 조용히 넘기지 않고 터뜨린다.
    if (!(result instanceof Boolean complete)) {
      throw new IllegalStateException(
          "기본 권한 완료 판정 조회가 boolean 이 아닌 값을 돌려줬다: " + result);
    }
    return complete;
  }
}
