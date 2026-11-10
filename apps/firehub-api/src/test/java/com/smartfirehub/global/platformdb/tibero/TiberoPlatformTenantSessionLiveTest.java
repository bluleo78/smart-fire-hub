package com.smartfirehub.global.platformdb.tibero;

import static com.smartfirehub.support.tibero.TiberoSql.exec;
import static com.smartfirehub.support.tibero.TiberoSql.queryLong;
import static com.smartfirehub.support.tibero.TiberoSql.queryString;
import static com.smartfirehub.support.tibero.TiberoTenantRlsSchema.setTenant;
import static com.smartfirehub.support.tibero.TiberoTestEvidence.write;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.global.platformdb.PlatformTenantSession;
import com.smartfirehub.global.tenant.TenantAwareTransactionManager;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.support.tibero.TiberoLive;
import com.smartfirehub.support.tibero.TiberoTenantRlsSchema;
import com.smartfirehub.support.tibero.TiberoTestServer;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Tibero 테넌트 세션(WD-492) 실서버 시험 — {@link TenantAwareTransactionManager} + {@link
 * TiberoPlatformTenantSession} 이 운영 앱 풀처럼(런타임 계정, CURRENT_SCHEMA = 소유자) 트랜잭션 시작에 컨텍스트를 심고, 커넥션을 풀에
 * 돌려주기 전에 지우고, 지우기에 실패한 커넥션은 버리는지 본다.
 *
 * <p>왜 실서버인가: Tibero 컨텍스트는 세션 범위라 커밋·롤백 뒤에도 남는다. 「같은 물리 커넥션을 다음 트랜잭션이 받았을 때 비어 있다」는 것은 풀 크기 1 에서 세션
 * id({@code USERENV.SESSIONID} — Tibero 에는 {@code SID} 매개변수가 없다, -11006 실측)가 같음을 함께 확인해야 증명된다 — 새
 * 커넥션이라 비어 있는 것과 구별하기 위해서다.
 *
 * <p>공유 서버 세션 예산: 이 클래스는 풀 둘(크기 1·2)을 {@code @BeforeAll} 에서 열고 {@code @AfterAll} 에서 닫는다. 넣은 행은 소유자
 * 연결로 지운다.
 */
@TiberoLive
class TiberoPlatformTenantSessionLiveTest {

  /** 이 클래스가 넣는 픽스처 행의 표식 — tenant.slug, "USER".username, saved_query.name. */
  private static final String MARK = "wd492-tsess";

  private static TiberoTenantRlsSchema schema;

  /** 같은 물리 커넥션 재사용을 보는 풀(크기 1). */
  private static HikariDataSource single;

  /** REQUIRES_NEW 용 풀(크기 2 — 바깥·안쪽이 커넥션을 하나씩 쥔다). */
  private static HikariDataSource pair;

  private static long tenant2;
  private static long userId;

  @BeforeAll
  static void setUp() throws SQLException {
    schema = TiberoTenantRlsSchema.shared();
    seedRows();
    single = schema.newRuntimePool(1);
    pair = schema.newRuntimePool(2);
  }

  /** 소유자 연결(정책에 걸리지 않음)로 테넌트 2 와 saved_query 행을 테넌트 1·2 에 하나씩 넣는다. */
  private static void seedRows() throws SQLException {
    try (Connection c = schema.owner().dataSource().getConnection()) {
      exec(c, "INSERT INTO tenant (slug, name) VALUES ('" + MARK + "', '" + MARK + "')");
      tenant2 = queryLong(c, "SELECT id FROM tenant WHERE slug = '" + MARK + "'");
      exec(c, "INSERT INTO \"USER\" (username, password, name) VALUES ('" + MARK + "', 'x', 'x')");
      userId = queryLong(c, "SELECT id FROM \"USER\" WHERE username = '" + MARK + "'");
      for (long t : new long[] {1, tenant2}) {
        exec(
            c,
            "INSERT INTO saved_query (name, sql_text, created_by, tenant_id) VALUES ('"
                + MARK
                + "', 'SELECT 1', "
                + userId
                + ", "
                + t
                + ")");
      }
    }
  }

  /** 풀을 닫고(세션 반납) 넣은 행을 FK 역순으로 지운다. */
  @AfterAll
  static void tearDown() throws SQLException {
    try {
      if (single != null) single.close();
    } finally {
      if (pair != null) pair.close();
    }
    if (schema == null || userId == 0) return;
    try (Connection c = schema.owner().dataSource().getConnection()) {
      exec(c, "DELETE FROM saved_query WHERE created_by = " + userId);
      exec(c, "DELETE FROM tenant WHERE id = " + tenant2);
      exec(c, "DELETE FROM \"USER\" WHERE id = " + userId);
    }
  }

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void 테넌트_1_트랜잭션은_테넌트_1_행만_본다() {
    TransactionTemplate tx = tx(single);
    TenantContext.set(1L);

    List<Long> seen = tx.execute(s -> savedQueryTenants(single));

    assertThat(seen).containsExactly(1L);
  }

  @Test
  void 커밋_뒤_같은_커넥션의_컨텍스트가_비어_있다() {
    TransactionTemplate tx = tx(single);
    TenantContext.set(1L);
    Snapshot first = tx.execute(s -> snapshot(single));
    TenantContext.clear();

    Snapshot next = tx.execute(s -> snapshot(single));

    assertThat(first.tenant()).isEqualTo("1");
    assertThat(first.rows()).containsExactly(1L);
    assertThat(next.sid()).as("같은 물리 커넥션이어야 증명이 된다").isEqualTo(first.sid());
    assertThat(next.tenant()).isNull();
    assertThat(next.rows()).isEmpty();
    write("커밋 뒤 컨텍스트: 같은 SID " + first.sid() + ", 앞 " + first.tenant() + " → 뒤 " + next.tenant());
  }

  @Test
  void 롤백_뒤_같은_커넥션의_컨텍스트가_비어_있다() {
    TransactionTemplate tx = tx(single);
    TenantContext.set(1L);
    Snapshot first =
        tx.execute(
            s -> {
              s.setRollbackOnly();
              return snapshot(single);
            });
    TenantContext.clear();

    Snapshot next = tx.execute(s -> snapshot(single));

    assertThat(first.tenant()).isEqualTo("1");
    assertThat(next.sid()).as("같은 물리 커넥션이어야 증명이 된다").isEqualTo(first.sid());
    assertThat(next.tenant()).isNull();
    assertThat(next.rows()).isEmpty();
  }

  /**
   * 매니저 밖에서 컨텍스트가 남은 채 풀로 돌아온 커넥션(앞선 대여자의 끝 지우기·버리기가 모두 실패한 상황을 흉내 낸다)을 테넌트 없는 트랜잭션이 받아도 시작에 지워
   * 0행이다. 풀 크기 1 에서 같은 SESSIONID 를 함께 단언해 새 커넥션이라 비어 있는 헛통과를 막는다.
   */
  @Test
  void 컨텍스트가_남은_커넥션을_받아도_테넌트_없는_트랜잭션은_비어_있다() throws SQLException {
    String leftSid;
    // 매니저를 거치지 않고 풀에서 직접 빌려 심고 돌려준다 — 트랜잭션을 열기 전에 닫아야 크기 1 풀이 막히지 않는다.
    try (Connection c = single.getConnection()) {
      setTenant(c, 1L);
      leftSid = sessionId(c);
      assertThat(contextTenant(c)).as("심은 값이 남은 채 돌려준다").isEqualTo("1");
    }
    try {
      Snapshot next = tx(single).execute(s -> snapshot(single));

      assertThat(next.sid()).as("같은 물리 커넥션이어야 증명이 된다").isEqualTo(leftSid);
      assertThat(next.tenant()).isNull();
      assertThat(next.rows()).isEmpty();
      write("시작 지우기: 남은 값 1 인 SID " + leftSid + " → 테넌트 없는 트랜잭션에서 " + next.tenant());
    } finally {
      // 실패해도 공용 풀에 값을 남기지 않는다.
      try (Connection c = single.getConnection()) {
        TiberoTenantRlsSchema.clearTenant(c);
      }
    }
  }

  @Test
  void 안쪽_REQUIRES_NEW_가_끝나도_바깥_테넌트는_남는다() {
    TenantAwareTransactionManager tm = new TenantAwareTransactionManager(pair, session(pair));
    TransactionTemplate outer = new TransactionTemplate(tm);
    TransactionTemplate inner = new TransactionTemplate(tm);
    inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    TenantContext.set(1L);

    List<Snapshot> seen =
        outer.execute(
            s -> {
              Snapshot before = snapshot(pair);
              TenantContext.set(tenant2);
              Snapshot in = inner.execute(i -> snapshot(pair));
              TenantContext.set(1L);
              Snapshot after = snapshot(pair);
              return List.of(before, in, after);
            });

    Snapshot before = seen.get(0);
    Snapshot in = seen.get(1);
    Snapshot after = seen.get(2);
    assertThat(in.sid()).as("안쪽은 다른 커넥션").isNotEqualTo(before.sid());
    assertThat(in.rows()).containsExactly(tenant2);
    assertThat(after.sid()).isEqualTo(before.sid());
    assertThat(after.tenant()).isEqualTo("1");
    assertThat(after.rows()).containsExactly(1L);
  }

  @Test
  void 현재_트랜잭션에_테넌트를_심는다() {
    TiberoPlatformTenantSession session = session(single);
    TransactionTemplate tx =
        new TransactionTemplate(new TenantAwareTransactionManager(single, session));

    Snapshot planted =
        tx.execute(
            s -> {
              assertThat(savedQueryTenants(single)).as("테넌트 없이 시작").isEmpty();
              session.applyToCurrentTransaction(tenant2);
              return snapshot(single);
            });
    Snapshot next = tx.execute(s -> snapshot(single));

    assertThat(planted.rows()).containsExactly(tenant2);
    assertThat(next.sid()).isEqualTo(planted.sid());
    assertThat(next.tenant()).isNull();
    assertThat(next.rows()).isEmpty();
  }

  @Test
  void 트랜잭션_밖에서_현재_트랜잭션에_심으면_거부한다() {
    // 이 가드가 없으면 트랜잭션 밖 커넥션에 컨텍스트가 남은 채 풀로 돌아간다.
    assertThatThrownBy(() -> session(single).applyToCurrentTransaction(1L))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void 지우기에_실패한_커넥션은_다음_트랜잭션에_오지_않는다() {
    // 지우기를 위임하지 않고 실패만 한다 — 위임하면 버리기가 동작하지 않아도 컨텍스트가 비어 시험이 헛통과한다.
    TiberoPlatformTenantSession real = session(single);
    PlatformTenantSession failingClear =
        new PlatformTenantSession() {
          @Override
          public void applyToConnection(Connection connection, long tenantId) throws SQLException {
            real.applyToConnection(connection, tenantId);
          }

          @Override
          public void applyToCurrentTransaction(long tenantId) {
            real.applyToCurrentTransaction(tenantId);
          }

          @Override
          public void clearConnection(Connection connection) throws SQLException {
            throw new SQLException("시험용 지우기 실패");
          }
        };
    TransactionTemplate failing =
        new TransactionTemplate(new TenantAwareTransactionManager(single, failingClear));
    TenantContext.set(1L);
    Snapshot abandoned = failing.execute(s -> snapshot(single));
    TenantContext.clear();

    Snapshot next = tx(single).execute(s -> snapshot(single));

    assertThat(abandoned.tenant()).isEqualTo("1");
    assertThat(next.sid()).as("버려진 세션이 아니라 새 세션").isNotEqualTo(abandoned.sid());
    assertThat(next.tenant()).isNull();
    assertThat(next.rows()).isEmpty();
    assertThat(single.getHikariPoolMXBean().getTotalConnections()).isLessThanOrEqualTo(1);
    write("버린 커넥션: SID " + abandoned.sid() + " → 다음 트랜잭션 SID " + next.sid());
  }

  /** ADR 0001 「Tibero 테넌트 격리」의 설치 전 점검 쿼리가 실제로 도는지 DBA 로 확인하고 열 이름을 증거로 남긴다(접속 정보는 남기지 않는다). */
  @Test
  void 설치_전_점검_쿼리가_돈다() throws SQLException {
    try (Connection c = TiberoTestServer.openDba(TiberoTestServer.dbaFromEnv());
        Statement st = c.createStatement()) {
      List<String> columns = new ArrayList<>();
      String edition;
      try (ResultSet rs = st.executeQuery("SELECT * FROM V$LICENSE")) {
        ResultSetMetaData md = rs.getMetaData();
        for (int i = 1; i <= md.getColumnCount(); i++) columns.add(md.getColumnName(i));
      }
      assertThat(columns).contains("EDITION");
      try (ResultSet rs = st.executeQuery("SELECT EDITION FROM V$LICENSE")) {
        assertThat(rs.next()).isTrue();
        edition = rs.getString(1);
      }
      String rlsStatus;
      try (ResultSet rs =
          st.executeQuery(
              "SELECT STATUS FROM DBA_OBJECTS WHERE OWNER = 'SYS' AND OBJECT_NAME = 'DBMS_RLS'"
                  + " AND OBJECT_TYPE = 'PACKAGE'")) {
        assertThat(rs.next()).isTrue();
        rlsStatus = rs.getString(1);
      }
      assertThat(rlsStatus).isEqualTo("VALID");
      write("설치 전 점검: V$LICENSE 열 " + columns + ", EDITION=" + edition + ", DBMS_RLS " + rlsStatus);
    }
  }

  // --- 도우미 ---

  /** 한 트랜잭션 안에서 본 세션 id·컨텍스트 테넌트·보이는 표식 행의 테넌트. */
  private record Snapshot(String sid, String tenant, List<Long> rows) {}

  private static TiberoPlatformTenantSession session(DataSource ds) {
    return new TiberoPlatformTenantSession(ds);
  }

  /** 운영과 같은 조합(TenantAware 매니저 + Tibero 세션)의 트랜잭션 템플릿. */
  private static TransactionTemplate tx(HikariDataSource ds) {
    return new TransactionTemplate(new TenantAwareTransactionManager(ds, session(ds)));
  }

  /** 현재 트랜잭션 커넥션에서 세션 id·컨텍스트·보이는 행을 읽는다(JdbcTemplate 은 트랜잭션에 묶인 커넥션을 쓴다). */
  private static Snapshot snapshot(DataSource ds) {
    JdbcTemplate jdbc = new JdbcTemplate(ds);
    String sid =
        jdbc.queryForObject("SELECT SYS_CONTEXT('USERENV','SESSIONID') FROM DUAL", String.class);
    String tenant =
        jdbc.queryForObject(
            "SELECT SYS_CONTEXT('" + schema.contextName() + "','TENANT_ID') FROM DUAL",
            String.class);
    return new Snapshot(sid, tenant, savedQueryTenants(ds));
  }

  /** 커넥션의 세션 id. */
  private static String sessionId(Connection c) throws SQLException {
    return queryString(c, "SELECT SYS_CONTEXT('USERENV','SESSIONID') FROM DUAL");
  }

  /** 커넥션의 컨텍스트 테넌트(없으면 null). */
  private static String contextTenant(Connection c) throws SQLException {
    return schema.context(c, "TENANT_ID");
  }

  private static List<Long> savedQueryTenants(DataSource ds) {
    return new JdbcTemplate(ds)
        .queryForList(
            "SELECT tenant_id FROM saved_query WHERE name = '" + MARK + "' ORDER BY tenant_id",
            Long.class);
  }
}
