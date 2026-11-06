package com.smartfirehub.global.datastore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import com.smartfirehub.support.DataStorePostgresTestContainer;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.PostgresTestContainer;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 분리 모드({@code firehub.data-store.postgres.separate=true})에서 데이터 저장소 연결이 플랫폼 Spring 트랜잭션을 따라
 * 커밋·롤백되는지 검증한다(WD-494 스펙 2절).
 *
 * <p>플랫폼 DB 는 첫 컨테이너, 데이터 저장소는 두 번째 컨테이너다. 검증 조회는 각 컨테이너에 슈퍼유저로 직접 붙어서 한다 — 검증 대상 DSLContext 로 조회하면
 * 묶인 연결(미커밋 상태)을 다시 읽어 "커밋됐다"를 잘못 확인할 수 있기 때문이다.
 */
class SeparateDataStoreTransactionTest extends IntegrationTestBase {

  /** 이 테스트가 만드는 테이블 — 컨테이너를 공유하므로 매 테스트 뒤 지운다. */
  private static final List<String> TABLES =
      List.of(
          "t_commit",
          "t_rollback",
          "t_requires_new",
          "t_autocommit",
          "t_local_tx",
          "t_savepoint",
          "t_readonly",
          "t_after_commit");

  @DynamicPropertySource
  static void separateDataStoreProperties(DynamicPropertyRegistry registry) {
    DataStorePostgresTestContainer.registerSeparateDataStoreProperties(registry);
  }

  @Autowired private DataSource primaryDataSource;
  @Autowired private DSLContext primaryDsl;
  @Autowired @DataStoreDb private DataSource dataStoreDataSource;
  @Autowired @DataStoreDb private DSLContext dsl;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private Environment environment;

  @AfterEach
  void dropTables() throws SQLException {
    try (Connection c = DataStorePostgresTestContainer.openSuperuserConnection();
        Statement st = c.createStatement()) {
      for (String t : TABLES) {
        st.execute("DROP TABLE IF EXISTS data." + t);
      }
    }
  }

  @Test
  void 플랫폼_트랜잭션이_커밋되면_데이터저장소도_커밋되고_플랫폼_DB에는_쓰이지_않는다() throws SQLException {
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            s -> {
              dsl.execute("CREATE TABLE data.t_commit(id int)");
              dsl.execute("INSERT INTO data.t_commit VALUES (1)");
            });

    assertThat(countRowsInDataStore("data.t_commit")).isEqualTo(1);
    assertThat(tableExistsInPlatform("data.t_commit")).isFalse();
  }

  @Test
  void 플랫폼_트랜잭션이_롤백되면_데이터저장소_DDL도_롤백된다() throws SQLException {
    assertThatThrownBy(
            () ->
                new TransactionTemplate(transactionManager)
                    .executeWithoutResult(
                        s -> {
                          dsl.execute("CREATE TABLE data.t_rollback(id int)");
                          throw new IllegalStateException("의도한 실패");
                        }))
        .isInstanceOf(IllegalStateException.class);

    assertThat(tableExistsInDataStore("data.t_rollback")).isFalse();
  }

  @Test
  void REQUIRES_NEW_안쪽_트랜잭션은_자기_연결을_써서_바깥_롤백과_무관하게_남는다() throws SQLException {
    createTableAutoCommit("data.t_requires_new");
    TransactionTemplate outer = new TransactionTemplate(transactionManager);
    TransactionTemplate inner = new TransactionTemplate(transactionManager);
    inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

    List<Integer> pids = new ArrayList<>();

    assertThatThrownBy(
            () ->
                outer.executeWithoutResult(
                    s -> {
                      dsl.execute("INSERT INTO data.t_requires_new VALUES (1)");
                      pids.add(backendPid());
                      inner.executeWithoutResult(
                          s2 -> {
                            dsl.execute("INSERT INTO data.t_requires_new VALUES (2)");
                            pids.add(backendPid());
                          });
                      // 재개 후 바깥 연결이 다시 묶였는지 — 같은 백엔드여야 한다(트랜잭션당 연결 하나: SET LOCAL 등이 기댄다).
                      pids.add(backendPid());
                      dsl.execute("INSERT INTO data.t_requires_new VALUES (3)");
                      throw new IllegalStateException("바깥 롤백");
                    }))
        .isInstanceOf(IllegalStateException.class);

    assertThat(idsInDataStore("data.t_requires_new")).containsExactly(2);
    assertThat(pids.get(1)).as("안쪽은 바깥과 다른 연결").isNotEqualTo(pids.get(0));
    assertThat(pids.get(2)).as("재개 후 바깥 연결이 다시 묶인다").isEqualTo(pids.get(0));
    assertThat(activeConnections()).isZero();
  }

  @Test
  void 트랜잭션_밖에서는_문장마다_자동_커밋된다() throws SQLException {
    dsl.execute("CREATE TABLE data.t_autocommit(id int)");
    dsl.execute("INSERT INTO data.t_autocommit VALUES (1)");

    assertThat(countRowsInDataStore("data.t_autocommit")).isEqualTo(1);
    assertThat(activeConnections()).isZero();
  }

  @Test
  void 트랜잭션_밖_dsl_transaction은_로컬_트랜잭션으로_롤백되고_연결을_반납한다() throws SQLException {
    createTableAutoCommit("data.t_local_tx");

    assertThatThrownBy(
            () ->
                dsl.transaction(
                    c -> {
                      DSL.using(c).execute("INSERT INTO data.t_local_tx VALUES (1)");
                      throw new IllegalStateException("로컬 롤백");
                    }))
        .isInstanceOf(RuntimeException.class);

    assertThat(countRowsInDataStore("data.t_local_tx")).isZero();
    assertThat(activeConnections()).isZero();
  }

  @Test
  void Spring_트랜잭션_안의_dsl_transaction은_SAVEPOINT라_그_안의_쓰기만_사라진다() throws SQLException {
    createTableAutoCommit("data.t_savepoint");

    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            s -> {
              dsl.execute("INSERT INTO data.t_savepoint VALUES (1)");
              try {
                dsl.transaction(
                    c -> {
                      DSL.using(c).execute("INSERT INTO data.t_savepoint VALUES (2)");
                      throw new IllegalStateException("안쪽 롤백");
                    });
              } catch (RuntimeException expected) {
                // 안쪽 SAVEPOINT 만 롤백된다 — 바깥 트랜잭션은 계속한다.
              }
              dsl.execute("INSERT INTO data.t_savepoint VALUES (3)");
            });

    assertThat(idsInDataStore("data.t_savepoint")).containsExactly(1, 3);
    assertThat(activeConnections()).isZero();
  }

  @Test
  void 읽기_전용_트랜잭션도_연결_하나를_묶는다() {
    TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
    readOnly.setReadOnly(true);

    // pg_backend_pid 만 비교하면 풀이 같은 물리 연결을 다시 내줘서 묶지 않아도 같을 수 있다. transaction_timestamp() 는 한
    // 트랜잭션 안에서만 같고 자동 커밋 문장마다 달라지므로 "같은 트랜잭션"을 가른다. 사이에 대여 중 연결 수도 본다.
    List<Object> observed =
        readOnly.execute(
            s -> {
              Object first = transactionTimestamp();
              int activeBetween = activeConnections();
              Object second = transactionTimestamp();
              return List.of(first, activeBetween, second);
            });

    assertThat(observed.get(2)).isEqualTo(observed.get(0));
    assertThat(observed.get(1)).as("두 조회 사이에도 묶인 연결을 쥐고 있다").isEqualTo(1);
    assertThat(activeConnections()).isZero();
  }

  /**
   * 최종 리뷰 지적 1 — 읽기 전용 Spring 트랜잭션이면 묶인 데이터 저장소 연결도 읽기 전용이어야 한다. 플랫폼 연결은 Spring 이 읽기 전용으로 두는데 데이터
   * 저장소 연결만 쓰기가 되면 {@code @Transactional(readOnly = true)} 서비스가 데이터 저장소에 조용히 쓸 수 있다.
   */
  @Test
  void 읽기_전용_트랜잭션에서는_데이터저장소_쓰기가_거부된다() throws SQLException {
    createTableAutoCommit("data.t_readonly");
    TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
    readOnly.setReadOnly(true);

    assertThatThrownBy(
            () ->
                readOnly.executeWithoutResult(
                    s -> dsl.execute("INSERT INTO data.t_readonly VALUES (1)")))
        .hasRootCauseMessage("ERROR: cannot execute INSERT in a read-only transaction");

    assertThat(countRowsInDataStore("data.t_readonly")).isZero();
    assertThat(activeConnections()).isZero();
  }

  /**
   * 읽기 전용 표시는 연결을 풀에 돌려주기 전에 걷어야 한다 — 그러지 않으면 같은 물리 연결을 다음에 받은 읽기·쓰기 트랜잭션이 쓰지 못한다. Hikari 도 반납 시
   * 되돌리지만, 바인딩이 스스로 원상복구하는지 회귀로 지킨다.
   */
  @Test
  void 읽기_전용_트랜잭션_뒤의_읽기쓰기_트랜잭션은_쓸_수_있다() throws SQLException {
    createTableAutoCommit("data.t_readonly");
    TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
    readOnly.setReadOnly(true);
    readOnly.executeWithoutResult(s -> dsl.fetch("SELECT count(*) FROM data.t_readonly"));

    new TransactionTemplate(transactionManager)
        .executeWithoutResult(s -> dsl.execute("INSERT INTO data.t_readonly VALUES (1)"));

    assertThat(countRowsInDataStore("data.t_readonly")).isEqualTo(1);
    assertThat(activeConnections()).isZero();
  }

  /**
   * 최종 리뷰 지적 2 — afterCommit 콜백(AFTER_COMMIT 이벤트 리스너와 같은 단계)에서 <b>처음</b> 묶은 연결은 beforeCommit 을 받지
   * 못한다. 그 연결을 afterCompletion 이 롤백하면 쓰기가 조용히 사라지므로, 플랫폼이 커밋됐으면 커밋해야 한다.
   *
   * <p>트랜잭션 본문에서는 데이터 저장소를 건드리지 않는다 — 본문에서 먼저 묶으면 "이미 커밋된 묶음 → 자동 커밋 연결" 경로를 타서 이 결함을 재현하지 못한다.
   */
  @Test
  void afterCommit_에서_처음_쓴_데이터저장소_쓰기도_커밋된다() throws SQLException {
    createTableAutoCommit("data.t_after_commit");

    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            s ->
                TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                      @Override
                      public void afterCommit() {
                        dsl.execute("INSERT INTO data.t_after_commit VALUES (1)");
                      }
                    }));

    assertThat(countRowsInDataStore("data.t_after_commit")).isEqualTo(1);
    assertThat(activeConnections()).isZero();
  }

  /**
   * 분리 모드 키가 IntegrationTestBase 의 첫 컨테이너 등록을 이기는지 — 소유자·파이프라인 URL 도 두 번째 컨테이너여야 한다(Task 5 가 기댄다).
   */
  @Test
  void 분리_모드_속성은_소유자와_파이프라인_URL도_두번째_컨테이너로_돌린다() {
    String url = DataStorePostgresTestContainer.INSTANCE.getJdbcUrl();
    assertThat(environment.getProperty("firehub.data-store.postgres.url")).isEqualTo(url);
    assertThat(environment.getProperty("firehub.data-store.postgres.owner-url")).isEqualTo(url);
    assertThat(environment.getProperty("app.pipeline.datasource.url")).isEqualTo(url);
  }

  @Test
  void 분리_모드에서_데이터저장소_빈은_Primary_빈과_다른_인스턴스다() {
    assertNotSame(primaryDataSource, dataStoreDataSource);
    assertNotSame(primaryDsl, dsl);
  }

  // --- 검증 도우미: 검증 대상 빈을 거치지 않고 컨테이너에 직접 붙는다 ---

  private int backendPid() {
    return dsl.fetchOne("SELECT pg_backend_pid()").get(0, Integer.class);
  }

  private Object transactionTimestamp() {
    return dsl.fetchOne("SELECT transaction_timestamp()").get(0);
  }

  private int activeConnections() {
    return ((HikariDataSource) dataStoreDataSource).getHikariPoolMXBean().getActiveConnections();
  }

  private static void createTableAutoCommit(String table) throws SQLException {
    try (Connection c = DataStorePostgresTestContainer.openSuperuserConnection();
        Statement st = c.createStatement()) {
      st.execute("CREATE TABLE " + table + "(id int)");
      st.execute("GRANT ALL ON " + table + " TO app_tenant");
    }
  }

  private static int countRowsInDataStore(String table) throws SQLException {
    return idsInDataStore(table).size();
  }

  private static List<Integer> idsInDataStore(String table) throws SQLException {
    try (Connection c = DataStorePostgresTestContainer.openSuperuserConnection();
        Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("SELECT id FROM " + table + " ORDER BY id")) {
      List<Integer> ids = new ArrayList<>();
      while (rs.next()) {
        ids.add(rs.getInt(1));
      }
      return ids;
    }
  }

  private static boolean tableExistsInDataStore(String table) throws SQLException {
    try (Connection c = DataStorePostgresTestContainer.openSuperuserConnection()) {
      return regclassExists(c, table);
    }
  }

  private static boolean tableExistsInPlatform(String table) throws SQLException {
    try (Connection c =
        DriverManager.getConnection(
            PostgresTestContainer.INSTANCE.getJdbcUrl(),
            PostgresTestContainer.INSTANCE.getUsername(),
            PostgresTestContainer.INSTANCE.getPassword())) {
      return regclassExists(c, table);
    }
  }

  private static boolean regclassExists(Connection c, String table) throws SQLException {
    try (Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("SELECT to_regclass('" + table + "') IS NOT NULL")) {
      rs.next();
      return rs.getBoolean(1);
    }
  }
}
