package com.smartfirehub.support.tibero;

import com.smartfirehub.global.platformdb.mybatis.MyBatisPlatformConfig;
import com.smartfirehub.support.tibero.TiberoTestAccount.Privileges;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 플랫폼 리포지토리 MyBatis 구현을 Tibero 실서버에서 돌리는 스키마 하네스(WD-487 PoC → WD-497 통합).
 *
 * <p>무엇을: {@link TiberoTestAccount} 로 이번 JVM 만의 계정({@code FHT_PLATFORM_*}, 풀 최대 2)을 만들고 {@code
 * tibero-poc/schema.sql} 을 적용한 뒤, 앱과 같은 설정의 SqlSessionFactory({@link
 * MyBatisPlatformConfig#sqlSessionFactory} — databaseId 는 "tibero" 로 해소된다)와 Tibero 전용 트랜잭션 관리자를
 * 제공한다.
 *
 * <p>JVM 당 하나({@link #shared()}): 계정·DDL·풀·팩토리 준비가 테스트 클래스마다 반복되지 않게 첫 호출에서 한 번 만들고, JVM 종료 훅이 풀을
 * 닫고 계정을 지운다. 그래서 각 테스트 클래스는 자기가 넣은 행·테이블을 스스로 지워야 한다(다음 클래스가 같은 스키마를 본다). 계정 이름이 실행마다 달라 여러 JVM 이
 * 동시에 돌아도 서로 부딪치지 않는다.
 */
public final class TiberoPlatformSchema {

  private static final String SCHEMA_RESOURCE = "tibero-poc/schema.sql";

  /** JVM 당 하나인 하네스. 준비에 실패하면 남기지 않아 다음 호출이 다시 시도한다. */
  private static TiberoPlatformSchema shared;

  private final TiberoTestAccount account;
  private final SqlSessionTemplate sqlSessionTemplate;
  private final DataSourceTransactionManager transactionManager;
  private final TransactionTemplate transactionTemplate;
  private final JdbcTemplate jdbc;

  private TiberoPlatformSchema(TiberoTestAccount account) {
    this.account = account;
    DataSource dataSource = account.dataSource();
    this.sqlSessionTemplate =
        new SqlSessionTemplate(MyBatisPlatformConfig.sqlSessionFactory(dataSource));
    // 팩토리와 트랜잭션 관리자가 같은 DataSource 인스턴스를 써야 SqlSessionTemplate 이 트랜잭션 커넥션에 참여한다.
    this.transactionManager = new DataSourceTransactionManager(dataSource);
    this.transactionTemplate = new TransactionTemplate(transactionManager);
    this.jdbc = new JdbcTemplate(dataSource);
  }

  /** JVM 공용 하네스를 돌려준다. 첫 호출이 계정 생성 → DDL 을 하고 종료 훅(풀 닫기 → 계정 삭제)을 건다. 드라이버 jar 가 없으면 테스트를 건너뛴다. */
  public static synchronized TiberoPlatformSchema shared() {
    TiberoTestServer.assumeDriverPresent();
    if (shared == null) {
      TiberoTestAccount account = TiberoTestAccount.create("PLATFORM", Privileges.TABLES, 2);
      TiberoPlatformSchema schema;
      try {
        schema = new TiberoPlatformSchema(account);
        schema.applySchema();
      } catch (RuntimeException e) {
        // DDL 이 실패해도 방금 만든 계정은 남기지 않는다.
        try {
          account.close();
        } catch (RuntimeException suppressed) {
          e.addSuppressed(suppressed);
        }
        throw e;
      }
      Runtime.getRuntime()
          .addShutdownHook(new Thread(account::close, "tibero-platform-schema-cleanup"));
      shared = schema;
    }
    return shared;
  }

  /** 계정(= 스키마) 이름 — 실행마다 다르다. */
  public String accountName() {
    return account.name();
  }

  /** 계정 풀의 JdbcTemplate(자동 커밋) — 픽스처용. */
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

  /** 계정 풀(최대 2). 원시 JDBC 탐침용 — 빌린 커넥션은 반드시 닫는다. */
  public DataSource dataSource() {
    return account.dataSource();
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
    return iface.cast(factory.getProxy(TiberoPlatformSchema.class.getClassLoader()));
  }

  // ── DDL ───────────────────────────────────────────────

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
        TiberoPlatformSchema.class.getClassLoader().getResourceAsStream(SCHEMA_RESOURCE)) {
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
