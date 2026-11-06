package com.smartfirehub.global.platformdb.tibero;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.global.platformdb.DbErrorDetail;
import com.smartfirehub.global.platformdb.PlatformDbErrors.IntegrityViolation;
import com.smartfirehub.support.tibero.TiberoLive;
import com.smartfirehub.support.tibero.TiberoTestAccount;
import com.smartfirehub.support.tibero.TiberoTestAccount.Privileges;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.jobrunr.spring.autoconfigure.JobRunrAutoConfiguration;
import org.jobrunr.spring.autoconfigure.storage.JobRunrSqlStorageAutoConfiguration;
import org.jobrunr.storage.StorageProvider;
import org.jobrunr.storage.sql.oracle.OracleStorageProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Tibero 플랫폼 DB 부트스트랩 실서버 확인(WD-496).
 *
 * <ul>
 *   <li>실제 Tibero 오류가 스프링 번역({@code sql-error-codes.xml})을 거쳐 PG 와 같은 예외 타입이 되고, {@link
 *       TiberoPlatformDbErrors} 가 같은 판정을 하는지.
 *   <li>{@link TiberoPlatformDbConfig} 의 JobRunr 저장소가 자동 설정을 대신해 Oracle 구현으로 뜨는지.
 * </ul>
 *
 * <p>공유 서버 규칙: 테스트 계정({@code FHT_PLATDB_*})을 이번 실행만 만들고 끝나면 지운다.
 */
@TiberoLive
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TiberoPlatformDbLiveTest {

  private TiberoTestAccount account;
  private JdbcTemplate jdbc;
  private final TiberoPlatformDbErrors errors = new TiberoPlatformDbErrors();

  @BeforeAll
  void setUp() throws SQLException {
    account = TiberoTestAccount.create("PLATDB", Privileges.MIGRATION_OWNER, 2);
    // 플랫폼 MyBatis 세션 템플릿과 같은 Tibero 번역기를 쓴다(MyBatisPlatformConfig.sqlSessionTemplate).
    jdbc = new JdbcTemplate(account.dataSource());
    jdbc.setExceptionTranslator(TiberoSqlExceptionTranslator.create());
    account.execute(
        "CREATE TABLE parent (id NUMBER CONSTRAINT pk_parent PRIMARY KEY,"
            + " name VARCHAR2(10) CONSTRAINT uq_parent_name UNIQUE,"
            + " qty NUMBER CONSTRAINT ck_parent_qty CHECK (qty >= 0),"
            + " security_level_id NUMBER NOT NULL)");
    account.execute(
        "CREATE TABLE child (id NUMBER PRIMARY KEY, parent_id NUMBER CONSTRAINT fk_child_parent"
            + " REFERENCES parent(id))");
    account.execute("INSERT INTO parent VALUES (1, 'a', 1, 1)");
    account.execute("INSERT INTO child VALUES (1, 1)");
  }

  @AfterAll
  void tearDown() {
    if (account != null) account.close();
  }

  /** 문장을 실행해 스프링이 번역한 예외를 돌려준다. */
  private DataAccessException fail(String sql) {
    try {
      jdbc.update(sql);
    } catch (DataAccessException e) {
      return e;
    }
    throw new AssertionError("오류가 나야 한다: " + sql);
  }

  @Test
  void 유일_위반은_DuplicateKeyException_이고_제약_이름이_나온다() {
    DataAccessException e = fail("INSERT INTO parent VALUES (2, 'a', 1, 1)");

    assertThat(e).isInstanceOf(DuplicateKeyException.class);
    assertThat(errors.classifyIntegrityViolation(e)).isEqualTo(IntegrityViolation.DUPLICATE_KEY);
    assertThat(errors.violatedConstraint(e)).contains("uq_parent_name");
  }

  @Test
  void 외래_키_두_방향과_체크_위반을_가른다() {
    DataAccessException missing = fail("INSERT INTO child VALUES (2, 99)");
    DataAccessException inUse = fail("DELETE FROM parent WHERE id = 1");
    DataAccessException check = fail("INSERT INTO parent VALUES (3, 'c', -1, 1)");

    assertThat(missing).isInstanceOf(DataIntegrityViolationException.class);
    assertThat(errors.classifyIntegrityViolation(missing))
        .isEqualTo(IntegrityViolation.FOREIGN_KEY_MISSING_TARGET);
    assertThat(errors.classifyIntegrityViolation(inUse))
        .isEqualTo(IntegrityViolation.FOREIGN_KEY_IN_USE);
    assertThat(errors.classifyIntegrityViolation(check))
        .isEqualTo(IntegrityViolation.CHECK_CONSTRAINT);
  }

  /**
   * 빈 문자열('')도 NULL 로 저장돼 NOT NULL 위반이 된다 — PG 와 다른 점이라 플랫폼 코드가 빈 문자열을 쓰는 곳은 WD-490·491 에서 점검한다.
   */
  @Test
  void 빈_문자열은_NOT_NULL_위반이고_23502_와_열_이름이_나온다() {
    DataAccessException e = fail("INSERT INTO parent VALUES (6, 'e', 1, '')");

    assertThat(e).isInstanceOf(DataIntegrityViolationException.class);
    DbErrorDetail detail = errors.findError(e).orElseThrow();
    assertThat(detail.sqlState()).isEqualTo("23502");
    assertThat(detail.column()).isEqualTo("security_level_id");
  }

  @Test
  void NOWAIT_잠금_실패는_CannotAcquireLockException() throws SQLException {
    try (Connection holder = account.dataSource().getConnection()) {
      holder.setAutoCommit(false);
      try (Statement st = holder.createStatement()) {
        st.executeQuery("SELECT id FROM parent WHERE id = 1 FOR UPDATE").close();
      }
      DataAccessException e;
      try {
        jdbc.queryForList("SELECT id FROM parent WHERE id = 1 FOR UPDATE NOWAIT");
        throw new AssertionError("잠금 충돌이 나야 한다");
      } catch (DataAccessException ex) {
        e = ex;
      } finally {
        holder.rollback();
      }
      assertThat(e).isInstanceOf(CannotAcquireLockException.class);
      assertThat(errors.findError(e)).map(DbErrorDetail::sqlState).contains("55P03");
    }
  }

  /**
   * JobRunr SQL 자동 설정은 제품명 "Tibero" 를 모른다 — 우리 빈이 있으면 물러나고 Oracle 구현이 쓰인다. 테이블은 운영에선 Flyway 가
   * 만들지만(skip-create) 여기서는 빈 테스트 계정이라 JobRunr 가 만들게 한다.
   */
  @Test
  void JobRunr_저장소는_Oracle_구현이다() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                JobRunrAutoConfiguration.class, JobRunrSqlStorageAutoConfiguration.class))
        .withUserConfiguration(TiberoPlatformDbConfig.class)
        .withBean(DataSource.class, () -> new DelegatingDataSource(account.dataSource()))
        .withPropertyValues(
            "firehub.platform-db.type=tibero",
            "org.jobrunr.database.skip-create=false",
            "org.jobrunr.background-job-server.enabled=false",
            "org.jobrunr.dashboard.enabled=false")
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              StorageProvider storage = ctx.getBean(StorageProvider.class);
              assertThat(storage).isInstanceOf(OracleStorageProvider.class);
              assertThat(storage.getJobStats()).isNotNull();
            });
  }
}
