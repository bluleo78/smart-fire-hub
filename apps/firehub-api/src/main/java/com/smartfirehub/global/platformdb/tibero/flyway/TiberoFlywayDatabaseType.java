package com.smartfirehub.global.platformdb.tibero.flyway;

import java.sql.Connection;
import org.flywaydb.core.api.configuration.Configuration;
import org.flywaydb.core.internal.database.base.Database;
import org.flywaydb.core.internal.jdbc.JdbcConnectionFactory;
import org.flywaydb.core.internal.jdbc.StatementInterceptor;
import org.flywaydb.database.oracle.OracleDatabaseType;

/**
 * Flyway 가 Tibero 연결을 알아보게 하는 데이터베이스 유형 플러그인(WD-488 PoC).
 *
 * <p>Flyway 10 은 Tibero 를 지원하지 않아 그대로 쓰면 {@code Unsupported Database: Tibero} 로 거부한다. Oracle 유형을
 * 상속해 파서·스크립트 실행기(PL/SQL 블록의 {@code /} 구분 등)는 Oracle 것을 쓰고, 제품 판별·드라이버·데이터베이스 구현만 Tibero 로 바꾼다.
 *
 * <p>등록: {@code META-INF/services/org.flywaydb.core.extensibility.Plugin} — Flyway 가 ServiceLoader
 * 로 읽는다. 제품명이 Tibero 일 때만 맞으므로 PostgreSQL 연결에는 영향이 없다.
 */
public class TiberoFlywayDatabaseType extends OracleDatabaseType {

  /** Tibero JDBC 드라이버 클래스. */
  static final String DRIVER_CLASS = "com.tmax.tibero.jdbc.TbDriver";

  /** Flyway 로그·info 에 보이는 데이터베이스 이름. */
  @Override
  public String getName() {
    return "Tibero";
  }

  /** Tibero thin URL 만 맡는다 — Oracle({@code jdbc:oracle:})·PostgreSQL URL 은 원래 플러그인이 맡는다. */
  @Override
  public boolean handlesJDBCUrl(String url) {
    return url.startsWith("jdbc:tibero:");
  }

  /** URL 로 DataSource 를 만들 때 쓸 드라이버. Oracle 유형의 기본값(oracle.jdbc)을 Tibero 드라이버로 바꾼다. */
  @Override
  public String getDriverClass(String url, ClassLoader classLoader) {
    return DRIVER_CLASS;
  }

  /**
   * 연결의 제품명으로 판별한다. Tibero 드라이버의 {@code DatabaseMetaData.getDatabaseProductName()} 은 "Tibero"(뒤에
   * 버전이 붙을 수 있다)라 접두사로 본다 — Flyway 는 DataSource 로 받은 연결도 이 값으로 유형을 고르므로 URL 판별만으로는 부족하다.
   */
  @Override
  public boolean handlesDatabaseProductNameAndVersion(
      String databaseProductName, String databaseProductVersion, Connection connection) {
    return databaseProductName.startsWith("Tibero");
  }

  /** Tibero 보정판 데이터베이스 구현을 만든다. */
  @Override
  public Database createDatabase(
      Configuration configuration,
      JdbcConnectionFactory jdbcConnectionFactory,
      StatementInterceptor statementInterceptor) {
    return new TiberoFlywayDatabase(configuration, jdbcConnectionFactory, statementInterceptor);
  }
}
