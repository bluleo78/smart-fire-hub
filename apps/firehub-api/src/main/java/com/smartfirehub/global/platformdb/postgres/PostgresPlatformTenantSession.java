package com.smartfirehub.global.platformdb.postgres;

import com.smartfirehub.global.platformdb.ConditionalOnPostgresPlatformDb;
import com.smartfirehub.global.platformdb.PlatformTenantSession;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import org.jooq.DSLContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * {@link PlatformTenantSession} 의 PostgreSQL 구현 — {@code set_config('app.tenant_id', ?, true)}.
 *
 * <p>기존 {@code TenantAwareTransactionManager.doBegin} 과 {@code CurrentTransactionTenant} 에 있던 SQL 을
 * 한 글자도 바꾸지 않고 옮겼다. 세 번째 인자 {@code true} 가 트랜잭션-로컬이다.
 *
 * <p><b>왜 {@code ObjectProvider<DSLContext>} 인가:</b> 메인 {@code DSLContext} 빈은 트랜잭션 매니저 빈을 인자로 받아
 * 만들어지고({@code SpringTransactionProvider}), 트랜잭션 매니저는 이 빈을 생성자로 받는다. {@code DSLContext} 를 바로 주입하면
 * DSL → TM → 세션 → DSL 순환이 생긴다. {@link #applyToConnection} 은 JDBC 커넥션만 쓰므로 DSL 이 필요 없고, DSL 이 필요한
 * {@link #applyToCurrentTransaction} 에서만 호출 시점에 늦게 꺼내 순환을 끊는다.
 */
@Component
@ConditionalOnPostgresPlatformDb
public class PostgresPlatformTenantSession implements PlatformTenantSession {

  /** 커넥션에 직접 심는 문장 — 트랜잭션 매니저가 쓰던 것과 같다. */
  static final String SET_TENANT_GUC_SQL = "SELECT set_config('app.tenant_id', ?, true)";

  // 두 상수의 대소문자(SELECT/select) 차이는 의도다 — 기존 SQL 텍스트를 바이트까지 보존하는 것이 목적이므로 하나로 합치지 않는다.
  /** 현재 트랜잭션에 심는 문장 — 로그인 경로가 쓰던 것과 같다(소문자 그대로 보존). */
  static final String SET_TENANT_GUC_DSL_SQL = "select set_config('app.tenant_id', ?, true)";

  /** 순환 의존을 피하기 위해 호출 시점에 꺼내는 메인 DSL. */
  private final ObjectProvider<DSLContext> dsl;

  public PostgresPlatformTenantSession(ObjectProvider<DSLContext> dsl) {
    this.dsl = dsl;
  }

  @Override
  public void applyToConnection(Connection connection, long tenantId) throws SQLException {
    try (PreparedStatement ps = connection.prepareStatement(SET_TENANT_GUC_SQL)) {
      ps.setString(1, Long.toString(tenantId));
      ps.execute();
    }
  }

  @Override
  public void applyToCurrentTransaction(long tenantId) {
    dsl.getObject().execute(SET_TENANT_GUC_DSL_SQL, String.valueOf(tenantId));
  }
}
