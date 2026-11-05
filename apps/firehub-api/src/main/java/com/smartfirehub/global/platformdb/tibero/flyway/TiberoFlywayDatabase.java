package com.smartfirehub.global.platformdb.tibero.flyway;

import org.flywaydb.core.api.configuration.Configuration;
import org.flywaydb.core.internal.jdbc.JdbcConnectionFactory;
import org.flywaydb.core.internal.jdbc.StatementInterceptor;
import org.flywaydb.database.oracle.OracleConnection;
import org.flywaydb.database.oracle.OracleDatabase;
import org.flywaydb.database.oracle.TiberoOracleBridge;

/**
 * Tibero 를 위한 Flyway 데이터베이스 구현(WD-488 PoC).
 *
 * <p>Tibero 는 Oracle 호환 문법·사전 뷰를 대부분 갖고 있어 Oracle 구현을 그대로 쓰고, Tibero 에서 실패하는 지점만 덮어쓴다.
 *
 * <ul>
 *   <li>{@code doGetCatalog}: Oracle 구현은 {@code GLOBAL_NAME} 을 조회하는데 Tibero 에는 없다. 카탈로그 이름은 이력 표시에만
 *       쓰이므로 고정값을 돌려준다.
 *   <li>{@code ensureSupported}: Oracle 버전 검사(제품 버전이 Oracle 기준)를 건너뛴다.
 *   <li>{@code doGetConnection}: 스키마 사전 조회를 Tibero 보정판({@link TiberoOracleBridge})으로 바꾼다.
 * </ul>
 */
public class TiberoFlywayDatabase extends OracleDatabase {

  /** Oracle 구현과 같은 협력 객체로 만든다. */
  public TiberoFlywayDatabase(
      Configuration configuration,
      JdbcConnectionFactory jdbcConnectionFactory,
      StatementInterceptor statementInterceptor) {
    super(configuration, jdbcConnectionFactory, statementInterceptor);
  }

  /** {@code GLOBAL_NAME} 대신 고정값 — 이력 표시에만 쓰인다. */
  @Override
  protected String doGetCatalog() {
    return "TIBERO";
  }

  /** Oracle 버전 검사를 건너뛴다. */
  @Override
  public void ensureSupported(Configuration configuration) {
    // Tibero 는 Oracle 버전 체계를 따르지 않는다 — 지원 여부는 이 플러그인의 실서버 시험(TiberoFlywayPocTest)으로 판정한다.
  }

  /** 스키마 사전 조회를 Tibero 보정판으로 바꾼 연결. */
  @Override
  protected OracleConnection doGetConnection(java.sql.Connection connection) {
    return TiberoOracleBridge.connection(this, connection);
  }
}
