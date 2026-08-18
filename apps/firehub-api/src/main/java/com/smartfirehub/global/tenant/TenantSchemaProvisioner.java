package com.smartfirehub.global.tenant;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;

import java.sql.SQLException;
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

  /** CREATE SCHEMA IF NOT EXISTS 가 경합으로 던지는 duplicate_schema SQLSTATE. */
  private static final String SQLSTATE_DUPLICATE_SCHEMA = "42P06";

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

    // 존재 확인 후 즉시 반환 — 이것이 최적화가 아니라 정확성이다. 아래 GRANT·ALTER DEFAULT
    // PRIVILEGES 는 CREATE 와 달리 "이미 있으면 no-op" 이 아니라 매번 실제로 실행되고 락을 잡는다.
    // 단락 없이 두면 모든 createTable 호출마다(테스트 스위트 전체에서 수천 번, prod 에서는 86개
    // 테이블이 든 data 스키마를 상대로) 반복 실행된다. 스키마가 이미 있으면 권한은 생성 시점에
    // 이미 걸렸으므로 건너뛰는 것이 맞다.
    if (schemaExists(schema)) {
      return;
    }

    String executorRole = TenantPipelineRole.roleName(tenantId);
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
            // 와 같은 판단).
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
      // 42P06 = duplicate_schema. CREATE SCHEMA IF NOT EXISTS 는 PostgreSQL 에서 경합에 안전하지
      // 않다(CREATE TABLE IF NOT EXISTS 와 같은 창). 한 테넌트에서 데이터셋 두 개를 동시에 만들면
      // 둘 다 존재 확인을 통과하고 하나가 42P06 으로 진다. 그건 성공과 같은 상태이므로 삼킨다.
      if (!SQLSTATE_DUPLICATE_SCHEMA.equals(sqlStateOf(e))) {
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

  /** 예외 체인에서 SQLException 을 찾아 SQLSTATE 를 돌려준다. jOOQ/Spring 이 여러 겹으로 감싼다. */
  private static String sqlStateOf(Throwable t) {
    for (Throwable cur = t; cur != null; cur = cur.getCause()) {
      if (cur instanceof SQLException sql) {
        return sql.getSQLState();
      }
      if (cur.getCause() == cur) {
        break;
      }
    }
    return null;
  }
}
