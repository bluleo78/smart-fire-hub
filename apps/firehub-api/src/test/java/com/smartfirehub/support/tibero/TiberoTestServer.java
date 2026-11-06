package com.smartfirehub.support.tibero;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.smartfirehub.dataset.storage.DataStoreBackend;
import com.smartfirehub.dataset.storage.external.ExternalDbTarget;
import com.smartfirehub.dataset.storage.tibero.TiberoDialect;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * Tibero 실서버 테스트의 접속 기반(WD-497) — 데이터 저장소(WD-74)·플랫폼 DB(WD-487·488) 하네스가 함께 쓴다.
 *
 * <p>무엇을: 환경변수({@code TIBERO_TEST_HOST/PORT/SID/DBA_USER/DBA_PASSWORD}, 선택 {@code
 * TIBERO_TEST_TABLESPACE})에서 접속 정보를 읽고, 드라이버 유무를 판정하고, DBA 연결과 테스트 계정 풀을 연다. 값은 어디에도 출력하지 않는다.
 *
 * <p>라이선스 확인: 검증 서버는 데모 라이선스를 매달 갱신해 쓴다(ADR 0001 결정 5). 만료된 서버에 붙으면 모든 테스트가 접속 오류로 실패해 결함처럼 보이므로,
 * DBA 연결을 처음 열 때 {@code V$LICENSE.EXPIRE_DATE}(yyyyMMdd) 를 한 번 읽어 만료면 건너뛰고(assumption), 7일 안이면 경고를
 * 남긴다.
 */
public final class TiberoTestServer {

  /** 드라이버가 없을 때 건너뛰는 사유. */
  private static final String NO_DRIVER_MESSAGE =
      "Tibero JDBC 드라이버 없음 — apps/firehub-api/libs/ 에 jar 를 두세요";

  /** 만료 경고를 시작하는 남은 일수. */
  private static final int LICENSE_WARN_DAYS = 7;

  /** JVM 당 한 번만 읽은 라이선스 만료일(읽기 전이면 null). */
  private static LocalDate licenseExpiry;

  private TiberoTestServer() {}

  /** Tibero JDBC 드라이버가 클래스패스에 있으면 true. */
  private static boolean driverPresent() {
    try {
      Class.forName(TiberoDialect.DRIVER_CLASS);
      return true;
    } catch (ClassNotFoundException | LinkageError e) {
      return false;
    }
  }

  /** 드라이버가 없으면 테스트를 건너뛴다(환경변수만 설정되고 jar 가 없을 때 훅이 초록을 유지하도록). */
  public static void assumeDriverPresent() {
    assumeTrue(driverPresent(), NO_DRIVER_MESSAGE);
  }

  /** 환경변수 값, 비어 있으면 기본값. */
  static String env(String name, String fallback) {
    String v = System.getenv(name);
    return (v == null || v.isBlank()) ? fallback : v;
  }

  /** 계정 기본 테이블스페이스 — {@code TIBERO_TEST_TABLESPACE}, 없거나 비면 USR. */
  public static String tablespace() {
    return env("TIBERO_TEST_TABLESPACE", "USR");
  }

  /** 운영자가 준 테이블스페이스, 없거나 비면 null(호출자의 기본값을 쓰게) — 빈 값 판정을 하네스끼리 같게 한다. */
  public static String configuredTablespace() {
    return env("TIBERO_TEST_TABLESPACE", null);
  }

  /** 환경변수에서 읽은 접속 정보. 비밀번호가 있어 toString 은 가린다. */
  public record Dba(ExternalDbTarget target, String user, String password) {
    @Override
    public String toString() {
      return "Dba[" + target.host() + ":" + target.port() + "/" + target.database() + "]";
    }
  }

  /** 환경변수에서 DBA 접속 정보를 만든다. 필수 값이 비어 있으면 IllegalStateException. */
  public static Dba dbaFromEnv() {
    String host = env("TIBERO_TEST_HOST", null);
    String user = env("TIBERO_TEST_DBA_USER", null);
    String password = env("TIBERO_TEST_DBA_PASSWORD", null);
    if (host == null || user == null || password == null) {
      throw new IllegalStateException(
          "TIBERO_TEST_HOST / TIBERO_TEST_DBA_USER / TIBERO_TEST_DBA_PASSWORD 가 필요합니다");
    }
    int port = Integer.parseInt(env("TIBERO_TEST_PORT", "8629"));
    String sid = env("TIBERO_TEST_SID", "tibero");
    return new Dba(new ExternalDbTarget(DataStoreBackend.TIBERO, host, port, sid), user, password);
  }

  /**
   * 정리 전용 DBA 연결 — 라이선스 확인을 건너뛴다. 실행 중에 만료일이 지나도(자정·종료 훅) 확인이 테스트 중단 예외를 던져 만든 계정을 남기지 않게 한다. 호출자가
   * 닫는다.
   */
  static Connection openDbaForCleanup(Dba dba) throws SQLException {
    return DriverManager.getConnection(dba.target().jdbcUrl(), dba.user(), dba.password());
  }

  /** DBA 로 직접 JDBC 연결을 연다. 첫 연결에서 라이선스 만료를 확인한다. 호출자가 닫는다. */
  public static Connection openDba(Dba dba) throws SQLException {
    Connection c = openDbaForCleanup(dba);
    try {
      checkLicenseOnce(c);
    } catch (RuntimeException | SQLException e) {
      c.close();
      throw e;
    }
    return c;
  }

  /**
   * 테스트 계정 풀 — 세션 초기화·검증 문장은 운영 Tibero 풀과 같다(세션 NLS 고정). 공유 서버 세션 한도 때문에 크기는 호출자가 작게 주고, 유휴 0·유휴
   * 10초(Hikari 최소값)로 쓰지 않는 세션을 빨리 돌려준다. 대기는 15초(고갈 시 빨리 실패).
   */
  public static HikariDataSource pool(
      String poolName, Dba dba, String user, String password, int size) {
    HikariConfig cfg = new HikariConfig();
    cfg.setPoolName(poolName);
    cfg.setDriverClassName(TiberoDialect.DRIVER_CLASS);
    cfg.setJdbcUrl(dba.target().jdbcUrl());
    cfg.setUsername(user);
    cfg.setPassword(password);
    cfg.setMaximumPoolSize(size);
    cfg.setMinimumIdle(0);
    cfg.setIdleTimeout(10_000);
    cfg.setConnectionTimeout(15_000);
    cfg.setConnectionInitSql(TiberoDialect.SESSION_INIT_SQL);
    cfg.setConnectionTestQuery(TiberoDialect.VALIDATION_QUERY);
    return new HikariDataSource(cfg);
  }

  /** V$LICENSE 만료일을 JVM 당 한 번 읽어 판정한다. 뷰를 못 읽으면(권한 등) 판정 없이 지나간다 — 확인은 보조 수단이다. */
  private static synchronized void checkLicenseOnce(Connection c) throws SQLException {
    if (licenseExpiry == null) {
      try (Statement st = c.createStatement();
          ResultSet rs = st.executeQuery("SELECT EXPIRE_DATE FROM V$LICENSE")) {
        String raw = rs.next() ? rs.getString(1) : null;
        licenseExpiry =
            raw == null
                ? LocalDate.MAX
                : LocalDate.parse(raw.strip(), DateTimeFormatter.BASIC_ISO_DATE);
      } catch (SQLException | RuntimeException e) {
        TiberoTestEvidence.write("라이선스 만료일을 읽지 못함 — 확인 생략");
        licenseExpiry = LocalDate.MAX;
      }
      long left = ChronoUnit.DAYS.between(LocalDate.now(), licenseExpiry);
      if (licenseExpiry != LocalDate.MAX && left <= LICENSE_WARN_DAYS) {
        TiberoTestEvidence.write(
            "라이선스 만료 임박: " + licenseExpiry + " (" + left + "일 남음) — 데모 라이선스 갱신 필요");
      }
    }
    assumeTrue(
        !licenseExpiry.isBefore(LocalDate.now()),
        "Tibero 라이선스 만료(" + licenseExpiry + ") — 데모 라이선스를 갱신하세요. 결함이 아니라 환경 문제로 건너뜀");
  }
}
