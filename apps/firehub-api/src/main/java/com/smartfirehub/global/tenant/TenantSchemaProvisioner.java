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

    // 단락 조건(R12) — 단순 schemaExists 가 아니다.
    //
    // 신규 테넌트가 첫 데이터셋을 만드는 시점에는 pipeline_executor_t{id} 가 아직 없다(롤 생성은
    // 운영자 절차, #383). 그러면 아래 executor 대상 grant 6문장은 전부 건너뛰고 스키마만 만들어
    // 진다. 여기서 단순히 "스키마가 있으면 끝"으로 단락시키면, 운영자가 나중에 롤을 만들어도
    // 이 메서드가 그 스키마에 다시 들어오지 않아 — 자가치유 경로가 없다(FlywayCallbackConfig 는
    // 비밀번호만 동기화하고 권한은 건드리지 않는다) — 그 테넌트의 파이프라인이 에러 없이 영구히
    // 안 돌게 된다.
    //
    // 그래서 "스키마가 있다" 만으로는 부족하고, "롤이 아직 없거나(당장 해 줄 게 없다) 롤이
    // 이미 이 스키마에 USAGE 를 갖고 있다(이미 grant 됐다)"를 추가로 확인한다. 두 조건 모두
    // 거짓인 경우 — 즉 롤은 생겼는데 아직 이 스키마에 권한이 없는 경우 — 에만 grant 블록을
    // 다시 실행해 자가치유한다. 카탈로그 조회가 하나(has_schema_privilege) 늘 뿐이고, 정상
    // 상태(권한이 이미 걸린 상태)에서는 여전히 grant 를 재실행하지 않으므로 R9 의 의도(락을
    // 잡는 ALTER DEFAULT PRIVILEGES 를 매 호출마다 돌리지 않는다)는 그대로 유지된다.
    if (schemaExists(schema)
        && (!roleExists(ownerDsl, executorRole) || hasSchemaUsage(executorRole, schema))) {
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
            }
          });
    } catch (DataAccessException e) {
      // 경합 흡수(R11, 2026-08-18 실측으로 개정) — SQLSTATE 목록이 아니라 "지금 스키마가 실제로
      // 존재하는가"로 판정한다.
      //
      // CREATE SCHEMA IF NOT EXISTS 는 PostgreSQL 에서 경합에 안전하지 않다(CREATE TABLE IF NOT
      // EXISTS 와 같은 창). 한 테넌트에서 데이터셋 두 개를 동시에 만들면 둘 다 위 존재 확인을
      // 통과하고 하나가 진다. 애초 구현은 이 경합이 42P06(duplicate_schema)을 낸다고 가정하고
      // 그 코드만 삼켰는데, 두 세션 동시 실행으로 직접 재현한 결과 실제로 던져지는 것은
      // **23505**(unique_violation, pg_namespace_nspname_index)였다 — 42P06 은 이 경로(IF NOT
      // EXISTS + 선행 존재 확인)에서 도달 불가능한 죽은 분기였다. SQLSTATE 를 하나 더 목록에
      // 추가하는 대신 판정 방식 자체를 바꾼다: 예외가 나도 스키마가 실제로 존재하면 그건 이미
      // 다른 트랜잭션이 만든 것이므로 성공과 같은 상태다. PG 버전이 SQLSTATE 를 바꿔도 이 판정은
      // 깨지지 않는다.
      if (!schemaExists(schema)) {
        throw e;
      }
    }
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

  /** executor 롤이 이 스키마에 이미 USAGE 를 갖고 있는지 확인한다(R12 자가치유 단락 조건). */
  private boolean hasSchemaUsage(String roleName, String schema) {
    Object result =
        ownerDsl.fetchValue("select has_schema_privilege(?, ?, 'USAGE')", roleName, schema);
    return Boolean.TRUE.equals(result);
  }
}
