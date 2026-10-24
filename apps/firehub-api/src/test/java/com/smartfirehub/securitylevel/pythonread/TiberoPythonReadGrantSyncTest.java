package com.smartfirehub.securitylevel.pythonread;

import static com.smartfirehub.jooq.Tables.DATASET;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smartfirehub.dataset.storage.DataStoreAccountRole;
import com.smartfirehub.dataset.storage.DataStoreBackend;
import com.smartfirehub.dataset.storage.DataStoreProvisionMode;
import com.smartfirehub.dataset.storage.StoredAccount;
import com.smartfirehub.dataset.storage.TenantDataStoreRepository;
import com.smartfirehub.dataset.storage.TenantDataStoreRow;
import com.smartfirehub.dataset.storage.TenantDataStoreStatus;
import com.smartfirehub.dataset.storage.external.ExternalStoreConnections;
import com.smartfirehub.dataset.storage.external.ExternalStoreConnections.SqlWork;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.LevelPolicy;
import com.smartfirehub.securitylevel.repository.SecurityLevelRepository;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.SecurityFixture;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Tibero 슬롯 GRANT 동기화(WD-78) 단위 검증 — PG 는 진짜(테넌트 1 V133 시드 등급·데이터셋 행, advisory 잠금)이고 Tibero 는 메모리
 * 가짜 연결이다. 가짜는 {@code USER_TABLES}·{@code USER_TAB_PRIVS_MADE} 질의에 답하고 GRANT/REVOKE 문장을 그대로
 * 기록·반영하므로, 실행된 문장 집합 자체를 고정할 수 있다. 실서버 동작은 {@code TiberoLiveSlotGrantTest} 가 본다.
 *
 * <p>테넌트 1 등급: 공개(위치 1)·내부(2)·민감(3)·기밀(4, 허용 목록 필요).
 */
class TiberoPythonReadGrantSyncTest extends IntegrationTestBase {

  private static final String SCHEMA = "GENIA_T1";

  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder encoder;
  @Autowired private SecurityLevelRepository levelRepository;
  @Autowired private PlatformTransactionManager txManager;

  private SecurityFixture fx;
  private final List<Long> datasets = new ArrayList<>();
  private long owner;
  private String m;

  /** 가짜 Tibero 의 상태. */
  private final Set<String> tables = new TreeSet<>();

  /** "TABLE|GRANTEE" 형태의 SELECT 권한. */
  private final Set<String> grants = new TreeSet<>();

  /** 실행된 GRANT/REVOKE 문장(순서대로). */
  private final List<String> executed = new ArrayList<>();

  /** 문장에 이 조각이 들어 있으면 SQLException 을 던진다(회수 실패 흉내). 적용은 되지 않는다. */
  private final List<String> failingFragments = new ArrayList<>();

  private int connectionsOpened;

  /** 계정을 정확히 바인드한 슬롯 GRANT 조회 수(실행 준비 경로, WD-78). */
  private int exactGrantQueries;

  private TenantDataStoreRow storeRow;
  private List<StoredAccount> accounts;
  private TiberoPythonReadGrantSync sync;
  private TenantDataStoreRepository repository;
  private ExternalStoreConnections connections;

  /** 세대 번호 올림 트랜잭션이 끝난(커밋된) 뒤인가 — 첫 Tibero 접속 시점에 확인한다. */
  private boolean epochCommitted;

  private static final Pattern STATEMENT =
      Pattern.compile(
          "(GRANT|REVOKE) SELECT ON \"([^\"]+)\"\\.\"([^\"]+)\" (?:TO|FROM) \"([^\"]+)\"");

  @BeforeEach
  void setUp() throws Exception {
    fx = new SecurityFixture(dsl, fixtureTransactionTemplate, encoder);
    owner = fx.createUser("tprs_owner");
    m = "tprs" + (System.nanoTime() % 1_000_000_000L);
    TenantContext.set(DEFAULT_TEST_TENANT_ID);

    storeRow =
        new TenantDataStoreRow(
            DEFAULT_TEST_TENANT_ID,
            DataStoreBackend.TIBERO,
            "h",
            8629,
            "tibero",
            SCHEMA,
            "USR",
            DataStoreProvisionMode.DBA_AUTO,
            TenantDataStoreStatus.READY);
    accounts = new ArrayList<>();
    for (DataStoreAccountRole role : DataStoreAccountRole.all()) {
      accounts.add(new StoredAccount(role, role.usernameFor(DEFAULT_TEST_TENANT_ID), "x"));
    }
    repository = mock(TenantDataStoreRepository.class);
    when(repository.find(DEFAULT_TEST_TENANT_ID))
        .thenAnswer(inv -> java.util.Optional.of(storeRow));
    when(repository.findAccounts(DEFAULT_TEST_TENANT_ID)).thenAnswer(inv -> accounts);

    connections = mock(ExternalStoreConnections.class);
    when(connections.schemaOf(DEFAULT_TEST_TENANT_ID)).thenReturn(SCHEMA);
    Connection conn = fakeConnection();
    when(connections.withDdl(anyLong(), org.mockito.ArgumentMatchers.<SqlWork<Object>>any()))
        .thenAnswer(
            inv -> {
              connectionsOpened++;
              return inv.<SqlWork<Object>>getArgument(1).apply(conn);
            });
    sync = new TiberoPythonReadGrantSync(dsl, connections, repository, levelRepository, txManager);
  }

  @AfterEach
  void tearDown() {
    for (long id : datasets) {
      fx.deleteDatasetRow(id);
    }
    fx.deleteUser(owner);
  }

  /** Tibero 카탈로그 질의에 답하고 GRANT/REVOKE 를 반영하는 가짜 연결. */
  private Connection fakeConnection() throws SQLException {
    Connection conn = mock(Connection.class);
    when(conn.prepareStatement(anyString()))
        .thenAnswer(
            inv -> {
              String sql = inv.getArgument(0);
              List<String> binds = new ArrayList<>();
              PreparedStatement ps = mock(PreparedStatement.class);
              org.mockito.Mockito.doAnswer(
                      a -> {
                        int idx = a.getArgument(0);
                        while (binds.size() < idx) {
                          binds.add(null);
                        }
                        binds.set(idx - 1, a.getArgument(1));
                        return null;
                      })
                  .when(ps)
                  .setString(org.mockito.ArgumentMatchers.anyInt(), anyString());
              when(ps.executeQuery()).thenAnswer(x -> resultSet(rowsFor(sql, binds)));
              return ps;
            });
    when(conn.createStatement())
        .thenAnswer(
            inv -> {
              Statement st = mock(Statement.class);
              when(st.execute(anyString()))
                  .thenAnswer(
                      x -> {
                        apply(x.getArgument(0));
                        return false;
                      });
              return st;
            });
    return conn;
  }

  /** 카탈로그 질의별 가짜 결과 행. */
  private List<String[]> rowsFor(String sql, List<String> binds) {
    List<String[]> rows = new ArrayList<>();
    if (sql.contains("FROM USER_TABLES")) {
      for (String t : tables) {
        if (binds.isEmpty() || t.equals(binds.get(0))) {
          rows.add(new String[] {t});
        }
      }
    } else if (sql.contains("FROM USER_TAB_PRIVS_MADE") && sql.contains("GRANTEE IN (")) {
      // 실행 준비: 바인드 1..n = 정확한 계정 이름, 다음(선택) = 테이블. LIKE 를 쓰지 않는다.
      exactGrantQueries++;
      String in = sql.substring(sql.indexOf("GRANTEE IN (") + 12, sql.indexOf(')'));
      int n = in.split(",").length;
      List<String> grantees = binds.subList(0, n);
      for (String g : grants) {
        String[] parts = g.split("\\|");
        if (grantees.contains(parts[1]) && (binds.size() <= n || parts[0].equals(binds.get(n)))) {
          rows.add(new String[] {parts[0], parts[1]});
        }
      }
    } else if (sql.contains("FROM USER_TAB_PRIVS_MADE")) {
      // 바인드 1: GENIA\_T1\_S% (ESCAPE '\' 로 해석하는 진짜 LIKE), 바인드 2(선택): 테이블.
      assertThat(sql).as("LIKE 는 ESCAPE '\\' 와 함께여야 한다").contains("ESCAPE '\\'");
      Pattern like = likeToRegex(binds.get(0));
      for (String g : grants) {
        String[] parts = g.split("\\|");
        if (like.matcher(parts[1]).matches()
            && (binds.size() < 2 || parts[0].equals(binds.get(1)))) {
          rows.add(new String[] {parts[0], parts[1]});
        }
      }
    } else {
      throw new AssertionError("예상하지 못한 카탈로그 질의: " + sql);
    }
    return rows;
  }

  /** SQL LIKE(ESCAPE '\\')를 정규식으로: {@code \x} 는 문자 x 그대로, {@code %} 는 임의 길이, {@code _} 는 한 글자. */
  private static Pattern likeToRegex(String like) {
    StringBuilder re = new StringBuilder();
    for (int i = 0; i < like.length(); i++) {
      char c = like.charAt(i);
      if (c == '\\' && i + 1 < like.length()) {
        re.append(Pattern.quote(String.valueOf(like.charAt(++i))));
      } else if (c == '%') {
        re.append(".*");
      } else if (c == '_') {
        re.append('.');
      } else {
        re.append(Pattern.quote(String.valueOf(c)));
      }
    }
    return Pattern.compile(re.toString());
  }

  private static ResultSet resultSet(List<String[]> rows) throws SQLException {
    ResultSet rs = mock(ResultSet.class);
    int[] pos = {-1};
    when(rs.next()).thenAnswer(x -> ++pos[0] < rows.size());
    when(rs.getString(org.mockito.ArgumentMatchers.anyInt()))
        .thenAnswer(x -> rows.get(pos[0])[(int) x.getArgument(0) - 1]);
    return rs;
  }

  /** 문장을 기록하고 가짜 권한 상태에 반영한다. */
  private void apply(String sql) throws SQLException {
    Matcher mt = STATEMENT.matcher(sql);
    assertThat(mt.matches()).as("GRANT/REVOKE 문장 형태: " + sql).isTrue();
    assertThat(mt.group(2)).isEqualTo(SCHEMA);
    for (String f : failingFragments) {
      if (sql.contains(f)) {
        throw new SQLException("거부", "42000", -1);
      }
    }
    executed.add(sql);
    String key = mt.group(3) + "|" + mt.group(4);
    if (mt.group(1).equals("GRANT")) {
      grants.add(key);
    } else {
      grants.remove(key);
    }
  }

  /** 데이터셋 행(등급 지정)과 가짜 Tibero 테이블을 함께 만든다. 물리 이름은 대문자. */
  private String table(String suffix, String level) {
    String t = m + "_" + suffix;
    datasets.add(fx.createDatasetRow(t, fx.levelId(level), owner));
    tables.add(t.toUpperCase());
    return t;
  }

  private static String slot(int k) {
    return "GENIA_T" + DEFAULT_TEST_TENANT_ID + "_S" + k;
  }

  /** 테이블의 슬롯 GRANT 번호 집합. */
  private Set<Integer> slotsOf(String table) {
    Set<Integer> out = new TreeSet<>();
    for (String g : grants) {
      String[] p = g.split("\\|");
      if (p[0].equals(table.toUpperCase())) {
        out.add(Integer.parseInt(p[1].substring(p[1].lastIndexOf('S') + 1)));
      }
    }
    return out;
  }

  private static Set<Integer> range(int from, int to) {
    Set<Integer> s = new TreeSet<>();
    for (int k = from; k <= to; k++) {
      s.add(k);
    }
    return s;
  }

  private String grantSql(String table, int k) {
    return "GRANT SELECT ON \""
        + SCHEMA
        + "\".\""
        + table.toUpperCase()
        + "\" TO \""
        + slot(k)
        + "\"";
  }

  private String revokeSql(String table, int k) {
    return "REVOKE SELECT ON \""
        + SCHEMA
        + "\".\""
        + table.toUpperCase()
        + "\" FROM \""
        + slot(k)
        + "\"";
  }

  /** 위치 p 등급이면 슬롯 p..10 이 SELECT 를 받고, 허용 목록 등급은 아무도 못 받는다. 정확한 문장 집합을 고정한다. */
  @Test
  void syncTenant_grantsPositionToTenSlots_andNoneForAllowlistLevel() {
    String pub = table("pub", "공개");
    String internal = table("int", "내부");
    String sens = table("sen", "민감");
    String top = table("top", "기밀");

    PythonReadGrantSync.SyncResult r = sync.syncTenant();

    assertThat(slotsOf(pub)).isEqualTo(range(1, 10));
    assertThat(slotsOf(internal)).isEqualTo(range(2, 10));
    assertThat(slotsOf(sens)).isEqualTo(range(3, 10));
    assertThat(slotsOf(top)).as("허용 목록 등급은 무권한").isEmpty();
    assertThat(executed).contains(grantSql(pub, 1), grantSql(internal, 2), grantSql(sens, 3));
    assertThat(executed).noneMatch(s -> s.startsWith("REVOKE"));
    assertThat(r.availableSlots()).isEqualTo(range(1, 10));
    assertThat(r.revokeFailed()).isEmpty();
  }

  /** 테넌트 10·100 슬롯 계정의 GRANT 는 테넌트 1 동기화가 건드리지 않는다(LIKE 이스케이프가 접두사 충돌을 막는다). */
  @Test
  void syncTenant_leavesOtherTenantsSlotGrantsUntouched() {
    String pub = table("pub", "공개");
    grants.add(pub.toUpperCase() + "|GENIA_T10_S3");
    grants.add(pub.toUpperCase() + "|GENIA_T100_S3");
    grants.add("OTHER_TABLE|GENIA_T10_S1");

    sync.syncTenant();

    assertThat(grants)
        .contains(
            pub.toUpperCase() + "|GENIA_T10_S3",
            pub.toUpperCase() + "|GENIA_T100_S3",
            "OTHER_TABLE|GENIA_T10_S1");
    assertThat(executed).noneMatch(s -> s.contains("GENIA_T10_") || s.contains("GENIA_T100_"));
    assertThat(slotsOf(pub)).as("자기 슬롯만 계산값").contains(1, 10);
  }

  /**
   * 등급 정의 변경 동기화는 GRANT(= Tibero 접속) 전에 세대 번호를 올리고, 그 트랜잭션은 GRANT 시작 전에 이미 커밋돼 있다(Ruling 15).
   * inOrder 로 순서를, 트랜잭션 완료 콜백으로 "먼저 커밋"을 고정한다.
   */
  @Test
  void syncTenantForLevelsChange_bumpsEpochAndCommitsBeforeAnyGrant() {
    table("pub", "공개");
    when(repository.incrementScriptTokenEpoch(DEFAULT_TEST_TENANT_ID))
        .thenAnswer(
            inv -> {
              assertThat(
                      org.springframework.transaction.support.TransactionSynchronizationManager
                          .isActualTransactionActive())
                  .as("올림은 자기 트랜잭션 안에서")
                  .isTrue();
              org.springframework.transaction.support.TransactionSynchronizationManager
                  .registerSynchronization(
                      new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                          epochCommitted = true;
                        }
                      });
              return java.util.Optional.of(7L);
            });
    boolean[] committedAtFirstConnect = {false};
    org.mockito.Mockito.doAnswer(
            inv -> {
              if (connectionsOpened++ == 0) {
                committedAtFirstConnect[0] = epochCommitted;
              }
              return inv.<SqlWork<Object>>getArgument(1).apply(fakeConnection());
            })
        .when(connections)
        .withDdl(anyLong(), org.mockito.ArgumentMatchers.<SqlWork<Object>>any());

    sync.syncTenantForLevelsChange();

    org.mockito.InOrder order = org.mockito.Mockito.inOrder(repository, connections);
    order.verify(repository).incrementScriptTokenEpoch(DEFAULT_TEST_TENANT_ID);
    order
        .verify(connections, org.mockito.Mockito.atLeastOnce())
        .withDdl(anyLong(), org.mockito.ArgumentMatchers.<SqlWork<Object>>any());
    assertThat(committedAtFirstConnect[0]).as("번호 올림은 첫 GRANT 접속 전에 커밋").isTrue();
    assertThat(executed).as("GRANT 는 실제로 걸렸다").isNotEmpty();
  }

  /** 번호를 올리지 못하면 넓히는 GRANT 를 걸지 않는다(fail-closed). */
  @Test
  void syncTenantForLevelsChange_epochFailure_appliesNoGrant() {
    table("pub", "공개");
    when(repository.incrementScriptTokenEpoch(DEFAULT_TEST_TENANT_ID))
        .thenThrow(new IllegalStateException("db down"));

    assertThatThrownBy(() -> sync.syncTenantForLevelsChange())
        .isInstanceOf(IllegalStateException.class);

    assertThat(connectionsOpened).isZero();
    assertThat(executed).isEmpty();
  }

  /** 일반 동기화(생성·야간·데이터셋 등급 변경)는 세대 번호를 올리지 않는다. */
  @Test
  void otherSyncs_doNotBumpEpoch() {
    String t = table("pub", "공개");
    sync.syncTenant();
    sync.syncTable(t);
    sync.syncDataset(datasets.get(0));
    sync.prepareForRun(clearanceAt("공개"), levelRank("공개"));

    org.mockito.Mockito.verify(repository, org.mockito.Mockito.never())
        .incrementScriptTokenEpoch(anyLong());
  }

  /** 이미 맞으면 문장을 하나도 보내지 않는다(차이만 적용). */
  @Test
  void syncTenant_secondRun_sendsNothing() {
    table("pub", "공개");
    table("sen", "민감");
    sync.syncTenant();
    executed.clear();

    PythonReadGrantSync.SyncResult r = sync.syncTenant();

    assertThat(executed).isEmpty();
    assertThat(r.changedTables()).isZero();
  }

  /**
   * 데이터셋이 아닌 테이블(스테이징 STG_*)·뷰 등 테이블이 아닌 객체에 걸린 슬롯 GRANT 는 계산값이 "없음"이라 회수한다. 스테이징은 어떤 경로로도 GRANT 를
   * 받지 않는다.
   */
  @Test
  void syncTenant_revokesStrayGrantsOnStagingAndNonTables_andNeverGrantsStaging() {
    String staging = "STG_0123456789ABCDEF01234567";
    tables.add(staging);
    grants.add(staging + "|" + slot(3));
    grants.add("A_VIEW_" + m.toUpperCase() + "|" + slot(1));
    table("pub", "공개");

    sync.syncTenant();

    assertThat(slotsOf(staging)).isEmpty();
    assertThat(slotsOf("A_VIEW_" + m)).as("뷰 우회 GRANT 도 회수").isEmpty();
    assertThat(executed).noneMatch(s -> s.startsWith("GRANT") && s.contains("STG_"));
  }

  /** 등급을 올린 한 데이터셋만 다시 맞춘다 — 낮은 슬롯만 회수하고 다른 테이블·다른 GRANT 는 건드리지 않는다. */
  @Test
  void syncDataset_afterRaisingLevel_revokesOnlyLowerSlots_ofThatTable() {
    String t = table("mv", "공개");
    String other = table("oth", "공개");
    sync.syncTenant();
    executed.clear();
    long id = datasets.get(datasets.size() - 2);
    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () ->
            dsl.update(DATASET)
                .set(DATASET.SECURITY_LEVEL_ID, fx.levelId("민감"))
                .where(DATASET.ID.eq(id))
                .execute());

    sync.syncDataset(id);

    assertThat(slotsOf(t)).isEqualTo(range(3, 10));
    assertThat(executed).containsExactlyInAnyOrder(revokeSql(t, 1), revokeSql(t, 2));
  }

  /** 테이블 단위 동기화는 그 테이블만 본다 — 다른 테이블의 어긋난 GRANT 는 그대로 둔다. */
  @Test
  void syncTable_touchesOnlyThatTable() {
    String a = table("a", "공개");
    String b = table("b", "민감");
    grants.add(b.toUpperCase() + "|" + slot(1)); // b 에 걸린 과권한(전체 동기화였다면 회수됐을 것)

    sync.syncTable(a);

    assertThat(slotsOf(a)).isEqualTo(range(1, 10));
    assertThat(slotsOf(b)).as("b 는 건드리지 않았다").containsExactly(1);
    assertThat(executed).allMatch(s -> s.contains("\"" + a.toUpperCase() + "\""));
  }

  /** prepareForRun: 실행 슬롯 계정 하나만 맞추고 그 번호를 돌려준다(다른 슬롯은 건드리지 않는다). */
  @Test
  void prepareForRun_syncsOnlyTheRunSlot_andReturnsIt() {
    String pub = table("pub", "공개");
    String sens = table("sen", "민감");
    grants.add(pub.toUpperCase() + "|" + slot(9)); // 다른 슬롯의 드리프트 — 이 실행은 slot 3 만 본다
    grants.add(sens.toUpperCase() + "|" + slot(2)); // 실행 슬롯 3 과 무관한 과권한

    int slot = sync.prepareForRun(clearanceAt("민감"), levelRank("민감"));

    assertThat(slot).isEqualTo(3);
    assertThat(slotsOf(pub)).contains(3).contains(9);
    assertThat(executed).allMatch(s -> s.contains("\"" + slot(3) + "\""));
    assertThat(slotsOf(sens)).contains(3).contains(2);
    // 실행 슬롯 계정을 정확히 바인드해 읽는다(LIKE 로 테넌트 슬롯 전부를 읽지 않는다) — 적용 전·재확인 두 번.
    assertThat(exactGrantQueries).isEqualTo(2);
  }

  /** 전체 동기화는 계정 정확 바인드 질의를 쓰지 않는다(LIKE + 거르기). */
  @Test
  void syncTenant_usesLikeQuery_notExactGranteeQuery() {
    table("pub", "공개");

    sync.syncTenant();

    assertThat(exactGrantQueries).isZero();
  }

  /** 실행 슬롯 계정에 남은 과권한을 회수하지 못하면 실행을 거부한다(fail-closed). */
  @Test
  void prepareForRun_revokeFailureOnRunSlot_isRejected() {
    String sens = table("sen", "민감");
    grants.add(sens.toUpperCase() + "|" + slot(1));
    // 슬롯 1 로 실행(공개 자격)하되 민감 테이블에 슬롯 1 GRANT 가 남아 있고 회수가 실패한다.
    failingFragments.add("REVOKE");

    assertThatThrownBy(() -> sync.prepareForRun(clearanceAt("공개"), levelRank("공개")))
        .isInstanceOf(PythonReadAccessException.class)
        .hasMessageContaining("회수하지 못한");
  }

  /** 다른 슬롯의 회수 실패는 이 실행을 막지 않는다. */
  @Test
  void prepareForRun_revokeFailureOnOtherSlot_doesNotBlock() {
    String sens = table("sen", "민감");
    grants.add(sens.toUpperCase() + "|" + slot(3));
    failingFragments.add("REVOKE");

    // 슬롯 3 은 민감을 읽어야 하므로 회수 대상이 아니다 — 회수 실패가 있어도 막히지 않아야 한다.
    assertThat(sync.prepareForRun(clearanceAt("민감"), levelRank("민감"))).isEqualTo(3);
  }

  /** 계정 행이 없는 슬롯은 PG 의 "없는 슬롯 롤" 과 같다: 실행 거부, GRANT 도 하지 않는다. */
  @Test
  void missingSlotAccountRow_isUnavailable_andNeverGranted() {
    table("pub", "공개");
    accounts.removeIf(a -> a.role() == DataStoreAccountRole.S3);

    PythonReadGrantSync.SyncResult r = sync.syncTenant();

    assertThat(r.availableSlots()).doesNotContain(3).contains(2, 4);
    assertThat(grants).noneMatch(g -> g.endsWith("|" + slot(3)));
    assertThatThrownBy(() -> sync.prepareForRun(clearanceAt("민감"), levelRank("민감")))
        .isInstanceOf(PythonReadAccessException.class)
        .hasMessageContaining("준비되지 않았습니다");
  }

  /** 저장소가 아직 PENDING 이면 Tibero 에 접속하지 않고 슬롯도 준비되지 않은 것으로 본다. */
  @Test
  void pendingStore_doesNotConnect() {
    table("pub", "공개");
    storeRow =
        new TenantDataStoreRow(
            storeRow.tenantId(),
            storeRow.backend(),
            storeRow.host(),
            storeRow.port(),
            storeRow.database(),
            storeRow.schemaName(),
            storeRow.tablespace(),
            storeRow.mode(),
            TenantDataStoreStatus.PENDING);

    PythonReadGrantSync.SyncResult r = sync.syncTenant();

    assertThat(connectionsOpened).isZero();
    assertThat(r.availableSlots()).isEmpty();
    assertThatThrownBy(() -> sync.prepareForRun(clearanceAt("공개"), levelRank("공개")))
        .isInstanceOf(PythonReadAccessException.class)
        .hasMessageContaining("준비되지 않았습니다");
  }

  /** 슬롯 읽기 범위가 자격 rank 를 넘으면(PG 와 같은 사전 검사) Tibero 에 접속하기 전에 거부한다. */
  @Test
  void prepareForRun_readRankAboveClearance_isRejectedBeforeAnySql() {
    assertThatThrownBy(() -> sync.prepareForRun(clearanceAt("공개"), levelRank("민감")))
        .isInstanceOf(PythonReadAccessException.class);
    assertThat(connectionsOpened).isZero();
  }

  /** 커밋 후 훅: 트랜잭션 안에서는 문장이 없고, 커밋되면 그 테이블만 맞춘다. */
  @Test
  void syncTableAfterCommit_runsOnlyAfterCommit() {
    String t = table("hook", "공개");
    TransactionTemplate tx = new TransactionTemplate(txManager);
    tx.executeWithoutResult(
        status -> {
          sync.syncTableAfterCommit(t);
          assertThat(executed).as("커밋 전에는 아무것도 하지 않는다").isEmpty();
        });
    assertThat(slotsOf(t)).isEqualTo(range(1, 10));
  }

  /** 롤백되면 훅은 돌지 않는다. */
  @Test
  void syncTableAfterCommit_notRunOnRollback() {
    String t = table("rb", "공개");
    TransactionTemplate tx = new TransactionTemplate(txManager);
    tx.executeWithoutResult(
        status -> {
          sync.syncTableAfterCommit(t);
          status.setRollbackOnly();
        });
    assertThat(executed).isEmpty();
  }

  /** 슬롯 이름 형식이 아닌 대상은 SQL 빌더가 거부한다(식별자 주입 방지). */
  @Test
  void sqlBuilder_rejectsNonSlotGrantee() {
    assertThatThrownBy(
            () ->
                com.smartfirehub.dataset.storage.tibero.TiberoDdlSql.grantSelectToSlot(
                    SCHEMA, "T", "GENIA_T1\" ; DROP USER X"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                com.smartfirehub.dataset.storage.tibero.TiberoDdlSql.revokeSelectFromSlot(
                    SCHEMA, "T", "GENIA_T1_S11"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private int levelRank(String name) {
    long id = fx.levelId(name);
    return levelRepository.findAll().stream()
        .filter(l -> l.id() == id)
        .map(LevelPolicy::rank)
        .findFirst()
        .orElseThrow();
  }

  private Clearance clearanceAt(String level) {
    return new Clearance(
        owner, DEFAULT_TEST_TENANT_ID, levelRank(level), Set.of(), false, Set.of());
  }
}
