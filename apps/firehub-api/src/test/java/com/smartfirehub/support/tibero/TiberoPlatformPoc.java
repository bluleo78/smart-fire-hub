package com.smartfirehub.support.tibero;

import com.smartfirehub.dataset.storage.DataStorePasswordGenerator;
import com.smartfirehub.dataset.storage.tibero.TiberoDialect;
import com.smartfirehub.dataset.storage.tibero.TiberoNames;
import com.smartfirehub.dataset.storage.tibero.TiberoTestEnvironment;
import com.smartfirehub.dataset.storage.tibero.TiberoTestEnvironment.Dba;
import com.smartfirehub.global.platformdb.mybatis.MyBatisPlatformConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 플랫폼 리포지토리 MyBatis 구현을 Tibero 실서버에서 돌리는 테스트 하네스(WD-487 PoC).
 *
 * <p>무엇을: 환경변수({@code TIBERO_TEST_*}, {@link TiberoTestEnvironment})의 DBA 로 PoC 전용 계정 {@value
 * #ACCOUNT} 를 만들고, 그 계정의 Hikari 풀(최대 2)에 {@code tibero-poc/schema.sql} 을 적용한 뒤, 앱과 같은 설정의
 * SqlSessionFactory({@link MyBatisPlatformConfig#sqlSessionFactory} — databaseId 는 "tibero" 로
 * 해소된다)와 Tibero 전용 트랜잭션 관리자를 제공한다.
 *
 * <p>JVM 당 하나({@link #shared()}): 계정·DDL·풀·팩토리 준비가 테스트 클래스마다 반복되지 않게 첫 호출에서 한 번 만들고, JVM 종료 훅이 풀을
 * 닫고 계정을 지운다. 그래서 각 테스트 클래스는 자기가 넣은 행·테이블을 스스로 지워야 한다(다음 클래스가 같은 스키마를 본다).
 *
 * <p>공유 서버 규칙: 세션 20개를 다른 팀과 나눠 쓰므로 풀은 2커넥션(동시성 시험의 최소치)·짧은 유휴 시간이고, DBA 연결은 준비·정리 때만 연다. 계정 이름은
 * 고정이고 생성 시각({@code DBA_USERS.CREATED})이 PoC 시작일 이후일 때만 지운다 — 남의 계정을 지우지 않기 위해서다. 비밀번호는 테스트 안에서
 * 무작위로 만들고 어디에도 남기지 않는다(계정 준비 오류도 원문 대신 오류 코드만 남긴다). 같은 계정 이름을 쓰므로 Tibero 테스트를 여러 JVM 에서 동시에 돌리면 안
 * 된다.
 */
public final class TiberoPlatformPoc {

  /** PoC 계정 이름(고정). */
  public static final String ACCOUNT = "WD487_POC";

  /** 이 날짜 이후에 만들어진 {@value #ACCOUNT} 만 PoC 가 만든 것으로 보고 지운다. */
  private static final LocalDate POC_START = LocalDate.of(2026, 11, 4);

  private static final String SCHEMA_RESOURCE = "tibero-poc/schema.sql";

  /** 정리 증거(남은 계정 수)를 남기는 파일 — 종료 훅 출력은 테스트별 결과 XML 에 실리지 않는다. 작업 디렉터리(apps/firehub-api) 기준. */
  private static final Path CLEANUP_EVIDENCE = Path.of("build", "tibero-poc-cleanup.txt");

  /** JVM 당 하나인 하네스. 준비에 실패하면 남기지 않아 다음 호출이 다시 시도한다. */
  private static TiberoPlatformPoc shared;

  private final Dba dba;
  private final HikariDataSource dataSource;
  private final SqlSessionTemplate sqlSessionTemplate;
  private final DataSourceTransactionManager transactionManager;
  private final TransactionTemplate transactionTemplate;
  private final JdbcTemplate jdbc;

  private TiberoPlatformPoc(Dba dba, HikariDataSource dataSource) {
    this.dba = dba;
    this.dataSource = dataSource;
    this.sqlSessionTemplate =
        new SqlSessionTemplate(MyBatisPlatformConfig.sqlSessionFactory(dataSource));
    // 팩토리와 트랜잭션 관리자가 같은 DataSource 인스턴스를 써야 SqlSessionTemplate 이 트랜잭션 커넥션에 참여한다.
    this.transactionManager = new DataSourceTransactionManager(dataSource);
    this.transactionTemplate = new TransactionTemplate(transactionManager);
    this.jdbc = new JdbcTemplate(dataSource);
  }

  /**
   * JVM 공용 하네스를 돌려준다. 첫 호출이 계정 생성 → 풀 → DDL 을 하고 종료 훅(풀 닫기 → 계정 삭제)을 건다. 드라이버 jar 가 없으면 테스트를
   * 건너뛴다(환경변수만 있고 jar 가 없을 때 훅을 초록으로 유지).
   */
  public static synchronized TiberoPlatformPoc shared() {
    TiberoTestEnvironment.assumeDriverPresent();
    if (shared == null) {
      TiberoPlatformPoc poc = start();
      Runtime.getRuntime().addShutdownHook(new Thread(poc::close, "wd487-tibero-poc-cleanup"));
      shared = poc;
    }
    return shared;
  }

  /** 계정 생성 → 풀 → DDL 순으로 준비한다. 계정을 만든 뒤 실패하면 만든 것을 되돌리고 예외를 던진다. */
  private static TiberoPlatformPoc start() {
    Dba dba = TiberoTestEnvironment.dbaFromEnv();
    String password = new DataStorePasswordGenerator().generate();
    HikariDataSource ds = null;
    try {
      try (Connection c = TiberoTestEnvironment.openDba(dba)) {
        Timestamp leftover = dropPocOwned(c);
        if (leftover != null) {
          System.out.println(
              "TIBERO_POC leftover " + ACCOUNT + " (created " + leftover + ") dropped");
        }
        createAccount(c, password, System.getenv("TIBERO_TEST_TABLESPACE"));
      }
      ds = pool(dba, password);
      TiberoPlatformPoc poc = new TiberoPlatformPoc(dba, ds);
      poc.applySchema();
      return poc;
    } catch (SQLException e) {
      // 원인 예외를 붙이지 않는다 — CREATE USER 문장(비밀번호 포함)이 드라이버 오류 메시지에 섞일 수 있다.
      throw cleanupAfterFailure(
          dba, ds, new IllegalStateException("PoC 계정 준비 실패(오류 코드 " + e.getErrorCode() + ")"));
    } catch (RuntimeException e) {
      throw cleanupAfterFailure(dba, ds, e);
    }
  }

  /**
   * 준비 실패 보상: 풀을 닫고 PoC 가 만든 계정을 지운다(CREATE USER 는 됐는데 QUOTA·GRANT·DDL 이 실패하면 계정이 남는다). 보상 실패는 원래
   * 예외에 덧붙인다.
   *
   * @return 호출자가 던질 원래 예외
   */
  private static RuntimeException cleanupAfterFailure(
      Dba dba, HikariDataSource ds, RuntimeException primary) {
    if (ds != null) ds.close();
    try (Connection c = TiberoTestEnvironment.openDba(dba)) {
      dropPocOwned(c);
    } catch (SQLException | RuntimeException suppressed) {
      primary.addSuppressed(suppressed);
    }
    return primary;
  }

  /** PoC 계정 풀의 JdbcTemplate(자동 커밋) — 픽스처용. */
  public JdbcTemplate jdbc() {
    return jdbc;
  }

  /**
   * IDENTITY 컬럼 테이블에 한 행을 넣고 생성된 id 를 돌려준다(픽스처용). Tibero 에는 RETURNING 이 없어 JDBC 생성 키(컬럼 이름 "id"
   * 지정)로 받는다 — 컬럼 이름 없이 RETURN_GENERATED_KEYS 로 받으면 ROWID 문자열이 온다(실측).
   */
  public long insertReturningId(String sql, Object... args) {
    GeneratedKeyHolder keys = new GeneratedKeyHolder();
    jdbc.update(
        con -> {
          PreparedStatement ps = con.prepareStatement(sql, new String[] {"id"});
          for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
          return ps;
        },
        keys);
    Number key = keys.getKey();
    if (key == null) throw new IllegalStateException("생성 키 없음: " + sql);
    return key.longValue();
  }

  /** PoC 계정 풀(최대 2). 원시 JDBC 탐침용 — 빌린 커넥션은 반드시 닫는다. */
  public javax.sql.DataSource dataSource() {
    return dataSource;
  }

  /** Tibero 트랜잭션 템플릿 — 여러 리포지토리 호출을 한 트랜잭션으로 묶을 때 쓴다. */
  public TransactionTemplate tx() {
    return transactionTemplate;
  }

  /** Tibero 팩토리에 묶인 매퍼(스프링 트랜잭션에 참여하는 SqlSessionTemplate 경유). */
  public <M> M mapper(Class<M> type) {
    return sqlSessionTemplate.getMapper(type);
  }

  /**
   * 구현 객체를 Tibero 트랜잭션 관리자로 감싼 인터페이스 프록시를 만든다.
   *
   * <p>앱 컨테이너에서 {@code @Transactional} 프록시가 하는 일을 그대로 흉내 낸다 — 같은 {@link
   * AnnotationTransactionAttributeSource} 가 구현 클래스의 클래스·메서드 애너테이션(REQUIRED, {@code NOT_SUPPORTED}
   * 등)을 읽으므로 전파 의미가 앱과 같다. 다른 점은 트랜잭션 관리자가 TenantAwareTransactionManager(GUC 주입)가 아니라 Tibero
   * DataSource 의 평범한 관리자라는 것뿐이다.
   */
  public <T> T transactional(Class<T> iface, T target) {
    ProxyFactory factory = new ProxyFactory();
    factory.setTarget(target);
    factory.addInterface(iface);
    factory.addAdvice(
        new TransactionInterceptor(transactionManager, new AnnotationTransactionAttributeSource()));
    return iface.cast(factory.getProxy(TiberoPlatformPoc.class.getClassLoader()));
  }

  /**
   * 종료 훅: 풀을 닫고(세션이 남으면 DROP USER 가 거절된다) 계정을 지운 뒤, 남은 계정 수(0 이어야 한다)를 표준 출력과 {@link
   * #CLEANUP_EVIDENCE} 에 남긴다.
   */
  private void close() {
    try {
      dataSource.close();
    } finally {
      try (Connection c = TiberoTestEnvironment.openDba(dba)) {
        dropPocOwned(c);
        evidence("TIBERO_POC " + ACCOUNT + " remaining count=" + countAccounts(c));
      } catch (SQLException e) {
        evidence("TIBERO_POC " + ACCOUNT + " 정리 실패(오류 코드 " + e.getErrorCode() + ")");
        throw new IllegalStateException("PoC 계정 정리 실패(오류 코드 " + e.getErrorCode() + ")", e);
      }
    }
  }

  private static void evidence(String line) {
    System.out.println(line);
    try {
      Files.createDirectories(CLEANUP_EVIDENCE.getParent());
      Files.writeString(CLEANUP_EVIDENCE, line + "\n", StandardCharsets.UTF_8);
    } catch (IOException ignored) {
      // 증거 파일은 보조 수단이다 — 표준 출력에는 이미 남겼다.
    }
  }

  // ── 계정 ──────────────────────────────────────────────

  /**
   * {@value #ACCOUNT} 가 있으면 PoC 가 만든 것(생성 시각이 PoC 시작일 이후)일 때만 지운다. 이전 계정이면 건드리지 않고 실패한다. 이전 실행의 잔재
   * 정리·준비 실패 보상·종료 정리가 모두 이 한 곳을 지난다.
   *
   * @return 지운 계정의 생성 시각, 계정이 없었으면 null
   */
  private static Timestamp dropPocOwned(Connection c) throws SQLException {
    Timestamp created;
    try (PreparedStatement ps =
        c.prepareStatement("SELECT CREATED FROM DBA_USERS WHERE USERNAME = ?")) {
      ps.setString(1, ACCOUNT);
      try (ResultSet rs = ps.executeQuery()) {
        created = rs.next() ? rs.getTimestamp(1) : null;
      }
    }
    if (created == null) return null;
    if (created.toLocalDateTime().toLocalDate().isBefore(POC_START)) {
      throw new IllegalStateException(
          ACCOUNT + " 계정이 PoC 시작일(" + POC_START + ") 이전에 만들어져 있다 — 다른 주체의 계정일 수 있어 건드리지 않는다");
    }
    try (Statement st = c.createStatement()) {
      st.execute("DROP USER " + TiberoNames.quote(ACCOUNT) + " CASCADE");
    }
    return created;
  }

  /** 계정 생성 + 최소 권한. 비밀번호가 든 문장은 오류 메시지에 SQL 이 섞일 수 있어 호출자가 오류 코드만 남긴다. */
  private static void createAccount(Connection c, String password, String tablespace)
      throws SQLException {
    String account = TiberoNames.quote(ACCOUNT);
    String ts =
        TiberoNames.quote((tablespace == null || tablespace.isBlank()) ? "USR" : tablespace);
    try (Statement st = c.createStatement()) {
      st.execute(
          "CREATE USER "
              + account
              + " IDENTIFIED BY "
              + TiberoNames.quote(password)
              + " DEFAULT TABLESPACE "
              + ts);
      st.execute("ALTER USER " + account + " QUOTA UNLIMITED ON " + ts);
      st.execute("GRANT CREATE SESSION, CREATE TABLE, CREATE SEQUENCE TO " + account);
    }
  }

  private static long countAccounts(Connection c) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement("SELECT COUNT(*) FROM DBA_USERS WHERE USERNAME = ?")) {
      ps.setString(1, ACCOUNT);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  // ── 풀·DDL ────────────────────────────────────────────

  /**
   * PoC 계정 풀 — 최대 2(공유 서버 세션 한도), 유휴 0·유휴 10초(Hikari 최소값, JVM 내내 살아 있어 빌리지 않는 세션을 빨리 돌려준다), 대기
   * 10초(고갈 시 빨리 실패). 세션 초기화·검증 문장은 운영 Tibero 풀과 같다(세션 NLS 고정).
   */
  private static HikariDataSource pool(Dba dba, String password) {
    HikariConfig cfg = new HikariConfig();
    cfg.setPoolName("wd487-tibero-poc");
    cfg.setDriverClassName(TiberoDialect.DRIVER_CLASS);
    cfg.setJdbcUrl(dba.target().jdbcUrl());
    cfg.setUsername(ACCOUNT);
    cfg.setPassword(password);
    cfg.setMaximumPoolSize(2);
    cfg.setMinimumIdle(0);
    cfg.setIdleTimeout(10_000);
    cfg.setConnectionTimeout(10_000);
    cfg.setConnectionInitSql(TiberoDialect.SESSION_INIT_SQL);
    cfg.setConnectionTestQuery(TiberoDialect.VALIDATION_QUERY);
    return new HikariDataSource(cfg);
  }

  /** schema.sql 을 ';' 로 끝나는 줄 단위로 나눠 실행한다(주석 줄은 버린다). DDL 은 Tibero 에서 자동 커밋된다. */
  private void applySchema() {
    for (String sql : schemaStatements()) {
      try {
        jdbc.execute(sql);
      } catch (RuntimeException e) {
        throw new IllegalStateException("schema.sql 문장 실패: " + sql, e);
      }
    }
  }

  private static List<String> schemaStatements() {
    String text;
    try (InputStream in =
        TiberoPlatformPoc.class.getClassLoader().getResourceAsStream(SCHEMA_RESOURCE)) {
      if (in == null) throw new IllegalStateException(SCHEMA_RESOURCE + " 없음");
      text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    List<String> out = new ArrayList<>();
    StringBuilder cur = new StringBuilder();
    for (String line : text.split("\n")) {
      String t = line.strip();
      if (t.isEmpty() || t.startsWith("--")) continue;
      cur.append(line).append('\n');
      if (t.endsWith(";")) {
        String s = cur.toString().strip();
        out.add(s.substring(0, s.length() - 1));
        cur.setLength(0);
      }
    }
    return out;
  }
}
