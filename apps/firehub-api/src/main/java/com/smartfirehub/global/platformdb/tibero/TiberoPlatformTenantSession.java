package com.smartfirehub.global.platformdb.tibero;

import com.smartfirehub.global.platformdb.ConditionalOnTiberoPlatformDb;
import com.smartfirehub.global.platformdb.PlatformTenantSession;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * {@link PlatformTenantSession} 의 Tibero 구현 — 소유자 패키지 {@code FH_TENANT} 로 테넌트 컨텍스트를 심고 지운다(WD-492).
 *
 * <p>컨텍스트는 {@code CREATE CONTEXT … USING FH_TENANT} 로 그 패키지에만 묶여 있어 런타임 계정이 {@code
 * DBMS_SESSION.SET_CONTEXT} 를 직접 부르면 거부된다(실측 -17004). 그래서 앱은 패키지 루틴만 부르고 컨텍스트 이름은 모른다.
 *
 * <p><b>컨텍스트는 세션 범위다</b>(커밋·롤백 뒤에도 남는다) — 그래서 {@link PlatformTenantSession} 계약의 세션 범위 쪽 길대로 {@link
 * #clearConnection} 을 재정의한다.
 *
 * <p>{@code FH_TENANT.set_tenant} 호출은 이 파일에만 둔다(소스 검사 테스트가 고정) — 트랜잭션 매니저 밖에서 컨텍스트를 심으면 지워 줄 자리가
 * 없다.
 */
@Component
@ConditionalOnTiberoPlatformDb
public class TiberoPlatformTenantSession implements PlatformTenantSession {

  /** 테넌트 심기 — 패키지가 NULL 을 거부한다. */
  static final String SET_TENANT_CALL = "{call FH_TENANT.set_tenant(?)}";

  /** 테넌트 지우기 — TENANT_ID 와 BYPASS 를 모두 지운다. */
  static final String CLEAR_TENANT_CALL = "{call FH_TENANT.clear_tenant}";

  /**
   * 심기·지우기 호출의 쿼리 타임아웃(초). 지우기는 커넥션을 풀에 돌려주는 정리 경로에서 돈다 — 네트워크가 멈춘 세션에서 호출이 끝없이 기다리면 요청 스레드와 그 커넥션이
   * 함께 묶여 풀이 마른다. 타임아웃으로 실패하면 매니저가 그 커넥션을 버리고 넘어간다. 패키지 호출은 한 번의 왕복이라 10초면 정상 경로에는 걸리지 않는다.
   */
  static final int CALL_TIMEOUT_SECONDS = 10;

  /** 플랫폼 풀(@Primary). 트랜잭션 매니저가 같은 DataSource 로 트랜잭션을 묶는다. */
  private final DataSource dataSource;

  public TiberoPlatformTenantSession(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  @Override
  public void applyToConnection(Connection connection, long tenantId) throws SQLException {
    call(connection, SET_TENANT_CALL, tenantId);
  }

  /**
   * 이미 열린 현재 트랜잭션의 커넥션에 테넌트를 심는다(로그인처럼 테넌트가 트랜잭션보다 늦게 정해지는 경로).
   *
   * <p>이 플랫폼 풀이 현재 실제 트랜잭션에 묶여 있지 않으면 거부한다. 묶이지 않았으면 {@link DataSourceUtils} 가 트랜잭션 매니저 밖의 커넥션을
   * 내주고, 거기 심은 세션 컨텍스트는 아무도 지우지 않은 채 풀로 돌아가 다음 요청에 이전 테넌트를 물려준다. {@code
   * isActualTransactionActive()} 만 보지 않는 이유: 다른 DataSource 의 트랜잭션 안이어도 참이 되기 때문이다({@code
   * TiberoPlatformLocks} 의 같은 가드, ADR 0001 WD-493). PG 호출자 {@code CurrentTransactionTenant.apply} 는
   * 트랜잭션이 없으면 먼저 걸러 경고만 남긴다.
   *
   * @throws IllegalStateException 이 풀이 현재 트랜잭션에 묶여 있지 않을 때
   * @throws DataAccessResourceFailureException 패키지 호출이 실패했을 때
   */
  @Override
  public void applyToCurrentTransaction(long tenantId) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || !TransactionSynchronizationManager.hasResource(dataSource)) {
      throw new IllegalStateException(
          "플랫폼 DB 트랜잭션 밖에서 테넌트 컨텍스트를 심을 수 없다 — 세션 범위 값이라 지워 줄 자리가 없다(WD-492)");
    }
    Connection connection = DataSourceUtils.getConnection(dataSource);
    try {
      applyToConnection(connection, tenantId);
    } catch (SQLException e) {
      // 트랜잭션은 이미 열려 있으므로 CannotCreateTransactionException 이 아니다 — 자원(세션) 실패로 알린다.
      throw new DataAccessResourceFailureException("Tibero 테넌트 컨텍스트 설정 실패", e);
    } finally {
      DataSourceUtils.releaseConnection(connection, dataSource);
    }
  }

  /** 커넥션의 테넌트 컨텍스트(TENANT_ID·BYPASS)를 지운다. 실패하면 그대로 던져 매니저가 커넥션을 버리게 한다. */
  @Override
  public void clearConnection(Connection connection) throws SQLException {
    call(connection, CLEAR_TENANT_CALL, null);
  }

  /** 패키지 루틴 하나를 {@link #CALL_TIMEOUT_SECONDS} 제한으로 부른다. {@code tenantOrNull} 이 있으면 첫 인자로 묶는다. */
  private static void call(Connection connection, String sql, Long tenantOrNull)
      throws SQLException {
    try (CallableStatement cs = connection.prepareCall(sql)) {
      cs.setQueryTimeout(CALL_TIMEOUT_SECONDS);
      if (tenantOrNull != null) {
        cs.setLong(1, tenantOrNull);
      }
      cs.execute();
    }
  }
}
