package com.smartfirehub.securitylevel.pythonread;

import static com.smartfirehub.jooq.Tables.DATASET;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.dataset.repository.TenantDataStoreRepository;
import com.smartfirehub.dataset.storage.DataStoreAccountRole;
import com.smartfirehub.dataset.storage.DataStoreBackend;
import com.smartfirehub.dataset.storage.DataStoreProvisionMode;
import com.smartfirehub.dataset.storage.StoredAccount;
import com.smartfirehub.dataset.storage.TenantDataStoreRow;
import com.smartfirehub.dataset.storage.TenantDataStoreStatus;
import com.smartfirehub.dataset.storage.external.ExternalStoreConnections;
import com.smartfirehub.dataset.storage.external.ExternalStoreConnections.SqlWork;
import com.smartfirehub.global.platformdb.postgres.PostgresPlatformLocks;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.LevelPolicy;
import com.smartfirehub.securitylevel.pythonread.PythonReadGrantBackend.NarrowResult;
import com.smartfirehub.securitylevel.repository.SecurityLevelRepository;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.SecurityFixture;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
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

  /** 카탈로그 IN 목록 한 조각 크기({@code TiberoCatalog.IN_LIST_CHUNK}, 패키지 전용) — Oracle 계열 IN 1000개 제한. */
  private static final int IN_LIST_CHUNK = 1000;

  @Autowired private DSLContext dsl;
  @Autowired private DatasetRepository datasetRepository;
  @Autowired private PasswordEncoder encoder;
  @Autowired private SecurityLevelRepository levelRepository;
  @Autowired private PlatformTransactionManager txManager;
  @Autowired private PythonReadSyncExecutor syncExecutor;

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

  /** 실패시키는 문장을 만났을 때 예외 직전에 돌릴 동작(겹친 RENAME·DROP 흉내, WD-629). */
  private Runnable onFailingStatement = () -> {};

  private int connectionsOpened;

  /** USER_TABLES 조회 수 — 재판정(WD-645)이 돌았는지, 실패 없을 때 추가 조회가 없는지 센다. */
  private int tableQueries;

  /** 실패시킨 문장(순서대로) — 재판정이 한 번만 도는지 센다(WD-645). */
  private final List<String> failedStatements = new ArrayList<>();

  /** 계정을 정확히 바인드한 슬롯 GRANT 조회 수(실행 준비 경로, WD-78). */
  private int exactGrantQueries;

  /** 슬롯 GRANT 조회마다 묶은 테이블 이름(순서대로, 빈 목록 = 테넌트 전체) — 재확인 범위를 고정한다(WD-679). */
  private final List<List<String>> grantQueryTables = new ArrayList<>();

  /** REVOKE 가 "성공" 해도 사라지지 않는 "TABLE|GRANTEE" 권한 — 재확인이 남은 권한을 잡는지 본다(WD-679). */
  private final Set<String> stickyGrants = new TreeSet<>();

  /** 이 물리 테이블의 슬롯 GRANT 조회(LIKE)를 실패시킨다(WD-644 리뷰 — 묶음 안 테이블 하나의 읽기 실패 흉내). null 이면 실패 없음. */
  private String failGrantLookupFor;

  private TenantDataStoreRow storeRow;
  private List<StoredAccount> accounts;
  private TiberoPythonReadGrantSync sync;
  private TenantDataStoreRepository repository;
  private ExternalStoreConnections connections;

  /**
   * 직결 접속에 걸리는 시간(ms, WD-664 6) — 느린 Tibero 접속 흉내. 상한 없는 접속(withDdl 2인자)은 이만큼 기다린 뒤 접속에 성공하고, 상한 있는
   * 접속(3인자)은 상한을 넘기면 상한만큼만 기다린 뒤 접속 시간 초과로 실패한다(운영 접속기와 같은 규칙).
   */
  private volatile long connectDelayMs;

  /** 상한 있는 직결 접속에 넘어온 접속 상한(순서대로, WD-664 6). */
  private final List<Duration> connectTimeouts = new ArrayList<>();

  /** 문장(GRANT/REVOKE)·카탈로그 질의에 걸린 queryTimeout 초(순서대로, WD-664 6). */
  private final List<Integer> queryTimeouts = new ArrayList<>();

  /**
   * 이 조각이 든 GRANT/REVOKE 문장은 {@link #slowStatementMs} 만큼 걸린다(queryTimeout 을 무시하는 드라이버 흉내, WD-664
   * 6).
   */
  private volatile String slowStatementFragment;

  private volatile long slowStatementMs;

  /** 테넌트 전체 슬롯 GRANT 조회(재판정 판)가 걸리는 시간(ms, WD-664 6). 0 이면 즉시. */
  private volatile long tenantWideGrantQueryDelayMs;

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
              sleepQuietly(connectDelayMs); // 상한 없는 접속 — 느려도 끝까지 기다린다
              connectionsOpened++;
              return inv.<SqlWork<Object>>getArgument(1).apply(conn);
            });
    // 상한 있는 직결 접속(WD-664 6) — 상한을 넘기면 상한만큼만 기다린 뒤 접속 시간 초과(운영 접속기 → withDdl 과 같은 예외).
    when(connections.withDdl(
            anyLong(),
            org.mockito.ArgumentMatchers.any(Duration.class),
            org.mockito.ArgumentMatchers.<SqlWork<Object>>any()))
        .thenAnswer(
            inv -> {
              Duration limit = inv.getArgument(1);
              connectTimeouts.add(limit);
              if (connectDelayMs > limit.toMillis()) {
                sleepQuietly(limit.toMillis());
                throw new org.springframework.dao.DataAccessResourceFailureException(
                    "연결 시간이 초과되었습니다");
              }
              sleepQuietly(connectDelayMs);
              connectionsOpened++;
              return inv.<SqlWork<Object>>getArgument(2).apply(conn);
            });
    sync = newSync(datasetRepository);
  }

  /** 가짜 Tibero 연결·PG 저장소로 동기화를 만든다 — 계산값 저장소만 바꿔 끼울 수 있게 한 곳에 둔다(WD-660). */
  private TiberoPythonReadGrantSync newSync(DatasetRepository datasetRepo) {
    return new TiberoPythonReadGrantSync(
        datasetRepo,
        connections,
        repository,
        levelRepository,
        new PostgresPlatformLocks(dsl),
        txManager,
        syncExecutor);
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
              recordQueryTimeout(ps);
              when(ps.executeQuery()).thenAnswer(x -> resultSet(rowsFor(sql, binds)));
              return ps;
            });
    when(conn.createStatement())
        .thenAnswer(
            inv -> {
              Statement st = mock(Statement.class);
              recordQueryTimeout(st);
              when(st.execute(anyString()))
                  .thenAnswer(
                      x -> {
                        String sql = x.getArgument(0);
                        String slow = slowStatementFragment;
                        if (slow != null && sql.contains(slow)) {
                          sleepQuietly(slowStatementMs); // queryTimeout 을 무시하고 오래 걸린다
                        }
                        apply(sql);
                        return false;
                      });
              return st;
            });
    return conn;
  }

  /** 문장·질의 객체에 걸리는 queryTimeout 을 기록한다(WD-664 6 — 커밋 직전 좁히기의 문장 상한). */
  private void recordQueryTimeout(Statement st) throws SQLException {
    org.mockito.Mockito.doAnswer(
            a -> {
              synchronized (queryTimeouts) {
                queryTimeouts.add(a.getArgument(0));
              }
              return null;
            })
        .when(st)
        .setQueryTimeout(org.mockito.ArgumentMatchers.anyInt());
  }

  /** 느린 외부 DB 흉내용 잠자기 — 인터럽트되면 상태만 되살린다. */
  private static void sleepQuietly(long ms) {
    if (ms <= 0) {
      return;
    }
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** 카탈로그 질의별 가짜 결과 행. */
  private List<String[]> rowsFor(String sql, List<String> binds) {
    List<String[]> rows = new ArrayList<>();
    if (sql.contains("FROM USER_TABLES")) {
      tableQueries++;
      // 바인드 없음 = 전부, 하나 = 그 테이블, 여럿 = IN 목록(재맞춤의 이름 한정 조회, WD-660).
      for (String t : tables) {
        if (binds.isEmpty() || binds.contains(t)) {
          rows.add(new String[] {t});
        }
      }
    } else if (sql.contains("FROM USER_TAB_PRIVS_MADE") && sql.contains("GRANTEE IN (")) {
      // 실행 준비: 바인드 1..n = 정확한 계정 이름, 그 뒤(선택) = 테이블 IN 목록. LIKE 를 쓰지 않는다.
      exactGrantQueries++;
      String in = sql.substring(sql.indexOf("GRANTEE IN (") + 12, sql.indexOf(')'));
      int n = in.split(",").length;
      Set<String> grantees = Set.copyOf(binds.subList(0, n));
      slotGrantRows(binds, n, grantees::contains, rows);
    } else if (sql.contains("FROM USER_TAB_PRIVS_MADE")) {
      // 바인드 1: GENIA\_T1\_S% (ESCAPE '\' 로 해석하는 진짜 LIKE), 그 뒤(선택) = 테이블 IN 목록(WD-679).
      assertThat(sql).as("LIKE 는 ESCAPE '\\' 와 함께여야 한다").contains("ESCAPE '\\'");
      if (failGrantLookupFor != null
          && binds.size() >= 2
          && failGrantLookupFor.equals(binds.get(1))) {
        // withDdl 이 SQLException 을 감싸 내는 것과 같은 Spring 일시 오류(연결 끊김 흉내).
        throw new org.springframework.dao.TransientDataAccessResourceException("슬롯 GRANT 조회 실패");
      }
      Pattern like = likeToRegex(binds.get(0));
      slotGrantRows(binds, 1, g -> like.matcher(g).matches(), rows);
    } else {
      throw new AssertionError("예상하지 못한 카탈로그 질의: " + sql);
    }
    return rows;
  }

  /**
   * 슬롯 GRANT 조회 공통부(WD-679): 앞쪽 바인드 {@code leading} 개(계정 패턴·계정 이름) 뒤의 바인드는 테이블 IN 목록(없으면 전부)이다. 묶은
   * 테이블은 순서대로 {@link #grantQueryTables} 에 남기고, 계정 조건({@code grantee})과 테이블 조건을 모두 맞는 권한 행을 돌려준다.
   */
  private void slotGrantRows(
      List<String> binds,
      int leading,
      java.util.function.Predicate<String> grantee,
      List<String[]> rows) {
    List<String> boundTables = List.copyOf(binds.subList(leading, binds.size()));
    if (boundTables.isEmpty()) {
      sleepQuietly(tenantWideGrantQueryDelayMs); // 재판정의 테넌트 전체 조회가 느린 Tibero 흉내(WD-664 6)
    }
    grantQueryTables.add(boundTables); // 순서 검증용 목록
    Set<String> onlyTables = Set.copyOf(boundTables); // 소속 판정용 집합
    for (String g : grants) {
      String[] parts = g.split("\\|");
      if (grantee.test(parts[1]) && (onlyTables.isEmpty() || onlyTables.contains(parts[0]))) {
        rows.add(new String[] {parts[0], parts[1]});
      }
    }
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
        failedStatements.add(sql);
        onFailingStatement.run();
        throw new SQLException("거부", "42000", -1);
      }
    }
    executed.add(sql);
    String key = mt.group(3) + "|" + mt.group(4);
    if (mt.group(1).equals("GRANT")) {
      grants.add(key);
    } else if (!stickyGrants.contains(key)) {
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

  /** 커밋 뒤 실행기의 재시도형 시도로 테이블 묶음을 한 번 돌린다(묶인 대기 5초 — 앞선 동기화와 잠금이 겹쳐도 BUSY 가 되지 않게). */
  private PythonReadSyncExecutor.AttemptResult runBatch(
      TiberoPythonReadGrantSync backend, List<String> tables) {
    return PythonReadGrantBackend.retryableAttempt(
            DEFAULT_TEST_TENANT_ID,
            org.slf4j.LoggerFactory.getLogger(TiberoPythonReadGrantSyncTest.class),
            "Tibero ",
            backend::syncTablesWith)
        .run(tables, Duration.ofSeconds(5));
  }

  /**
   * WD-644 리뷰: 묶음 안 테이블 b 의 슬롯 GRANT 조회가 실패해도 a·c 의 회수는 그대로 적용되고, 결과에는 b 만 일시 실패로 실린다(실행기가 b 만 다시
   * 한다). 테이블별 격리를 지우면 b 의 예외가 묶음 밖으로 새어 b 뒤의 c 는 맞추지 못하고 묶음 전체가 실패한다(변이).
   */
  @Test
  void syncTablesWith_grantLookupFailureOnOneTable_keepsOthers_andReportsOnlyThatTable() {
    String a = table("a", "민감");
    String b = table("b", "민감");
    String c = table("c", "민감");
    sync.syncTenant();
    grants.add(a.toUpperCase() + "|" + slot(1));
    grants.add(b.toUpperCase() + "|" + slot(1));
    grants.add(c.toUpperCase() + "|" + slot(1));
    executed.clear();
    failGrantLookupFor = b.toUpperCase();

    PythonReadSyncExecutor.AttemptResult result = runBatch(sync, List.of(c, b, a));

    assertThat(result)
        .as("b 만 실패(일시) — a·c 는 끝났다")
        .isEqualTo(new PythonReadSyncExecutor.PartialFailure(List.of(b), List.of()));
    assertThat(slotsOf(a)).isEqualTo(range(3, 10));
    assertThat(slotsOf(c)).as("b 뒤의 c 도 맞췄다").isEqualTo(range(3, 10));
    assertThat(slotsOf(b)).as("b 는 이번에 건드리지 못했다").contains(1);
    assertThat(executed).containsExactlyInAnyOrder(revokeSql(a, 1), revokeSql(c, 1));
  }

  /**
   * WD-644 리뷰: 테이블 b 의 PG 메타 읽기(데이터셋 행)가 문장 오류로 실패해 메타 트랜잭션이 aborted 가 돼도, savepoint 가 b 만 되돌려 뒤따르는
   * c 의 메타 읽기·회수가 이어지고 묶음은 커밋된다. 결과에는 b 만 영구 실패로 실린다. 테이블별 savepoint 를 지우면 c 의 읽기가 「current
   * transaction is aborted」 로 실패하고 커밋도 실패해 묶음 전체가 실패한다(변이).
   */
  @Test
  void syncTablesWith_metaReadFailureOnOneTable_doesNotAbortTheBatch() {
    String a = table("a", "민감");
    String b = table("b", "민감");
    String c = table("c", "민감");
    sync.syncTenant();
    grants.add(a.toUpperCase() + "|" + slot(1));
    grants.add(c.toUpperCase() + "|" + slot(1));
    executed.clear();
    com.smartfirehub.dataset.repository.DatasetRepository failingForB =
        mock(com.smartfirehub.dataset.repository.DatasetRepository.class);
    when(failingForB.findTableLevels(org.mockito.ArgumentMatchers.any()))
        .thenAnswer(
            inv -> {
              String only = inv.getArgument(0);
              if (b.equals(only)) {
                dsl.execute("select 1/0"); // PG 문장 오류 — 메타 트랜잭션을 aborted 로 만든다
              }
              return datasetRepository.findTableLevels(only);
            });
    TiberoPythonReadGrantSync isolated =
        new TiberoPythonReadGrantSync(
            failingForB,
            connections,
            repository,
            levelRepository,
            new PostgresPlatformLocks(dsl),
            txManager,
            syncExecutor);

    PythonReadSyncExecutor.AttemptResult result = runBatch(isolated, List.of(a, b, c));

    assertThat(result)
        .as("b 만 실패(영구) — a·c 는 끝났다")
        .isEqualTo(new PythonReadSyncExecutor.PartialFailure(List.of(), List.of(b)));
    assertThat(slotsOf(a)).isEqualTo(range(3, 10));
    assertThat(slotsOf(c)).as("b 뒤의 c 도 맞췄다").isEqualTo(range(3, 10));
    assertThat(executed).containsExactlyInAnyOrder(revokeSql(a, 1), revokeSql(c, 1));
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

  /**
   * fail-closed(WD-629): REVOKE 가 실패하는 순간 테이블 이름이 USER_TABLES 에서 사라져도(재생성 RENAME·휴지통 BIN$ 로 GRANT 가
   * 이름이 바뀐 객체를 따라갔을 수 있다) 여전히 회수 실패로 센다. "지워졌으니 권한도 사라졌다"고 보고 넘기면 과권한이 남은 슬롯으로 실행을 허용하게 된다.
   */
  @Test
  void revokeFailureOnTableMissingFromUserTables_isStillRevokeFailure() {
    String upper = table("sen", "민감").toUpperCase();
    String upper2 = table("sen2", "민감").toUpperCase();
    grants.add(upper + "|" + slot(1));
    failingFragments.add("REVOKE");
    // REVOKE 가 실패하는 순간 이름이 USER_TABLES 에서 사라진다 — RENAME 처럼 GRANT 는 다른 이름의 객체에 남아 있을 수 있다.
    onFailingStatement = () -> tables.remove(upper);

    // 실행 준비(JIT) — 회수 실패로 세어 슬롯 1 실행을 거부한다.
    assertThatThrownBy(() -> sync.prepareForRun(clearanceAt("공개"), levelRank("공개")))
        .isInstanceOf(PythonReadAccessException.class)
        .hasMessageContaining("회수하지 못한");

    // 전체 동기화도 같은 규칙 — 회수 실패 목록에 넣는다.
    grants.add(upper2 + "|" + slot(1));
    onFailingStatement = () -> tables.remove(upper2);
    PythonReadGrantSync.SyncResult r = sync.syncTenant();
    assertThat(r.revokeFailedTables()).as("사라진 듯한 테이블의 REVOKE 실패도 회수 실패다").contains(upper2);
    assertThat(r.revokeFailed().get(upper2)).containsExactly(slot(1));
  }

  /** 대조(WD-629): REVOKE 가 실패했는데 테이블이 남아 있으면 여전히 회수 실패(fail-closed)다. */
  @Test
  void revokeFailureOnExistingTable_isStillRevokeFailure() {
    String sens = table("sen", "민감");
    String upper = sens.toUpperCase();
    grants.add(upper + "|" + slot(1));
    failingFragments.add("REVOKE");

    PythonReadGrantSync.SyncResult r = sync.syncTenant();

    assertThat(r.revokeFailedTables()).contains(upper);
    assertThat(r.revokeFailed().get(upper)).containsExactly(slot(1));
  }

  /**
   * 가짜 카탈로그에서 테이블 from 을 to 로 RENAME 한다 — GRANT 는 이름이 바뀐 객체를 따라간다(재생성 보관본·휴지통 BIN$ 흉내). from 이 이미
   * 없으면 아무것도 하지 않는다(실패 훅이 여러 번 불려도 한 번만 옮긴다).
   */
  private void renameObject(String from, String to) {
    if (!tables.remove(from)) {
      return;
    }
    tables.add(to);
    List<String> moved = new ArrayList<>();
    grants.removeIf(
        g -> {
          if (g.startsWith(from + "|")) {
            moved.add(to + g.substring(from.length()));
            return true;
          }
          return false;
        });
    grants.addAll(moved);
  }

  /**
   * 재판정 TC 공통 준비(WD-645): 민감 등급 테이블에 슬롯 1 과권한을 걸고 그 REVOKE 가 실패하게 한다. 반환: 물리(대문자) 이름.
   *
   * @param suffix 테이블 이름 접미사
   */
  private String sensitiveWithFailingRevoke(String suffix) {
    String upper = table(suffix, "민감").toUpperCase();
    grants.add(upper + "|" + slot(1));
    failingFragments.add(revokeSql(upper, 1));
    return upper;
  }

  /** 가짜 휴지통 객체 이름({@code BIN$...}). */
  private String binName() {
    return "BIN$" + m.toUpperCase() + "==$0";
  }

  /**
   * 재판정(WD-645): 첫 REVOKE 순간 겹친 DROP(PURGE)로 테이블과 그 권한이 함께 사라지면, 같은 트랜잭션 안의 재판정이 남은 권한이 없음을 다시 읽어
   * 확인하므로 회수 실패가 아니다 — 실행 준비가 공연히 거부되지 않는다.
   */
  @Test
  void rejudge_tableDroppedDuringRevoke_noRemainingGrant_runIsAllowed() {
    String upper = sensitiveWithFailingRevoke("sen");
    // REVOKE 가 실패하는 순간 겹친 DROP 이 끝났다 — 이름도 권한도 사라진다.
    onFailingStatement =
        () -> {
          tables.remove(upper);
          grants.removeIf(g -> g.startsWith(upper + "|"));
        };

    assertThat(sync.prepareForRun(clearanceAt("공개"), levelRank("공개"))).isEqualTo(1);
    assertThat(failedStatements).as("재판정에서는 남은 권한이 없어 REVOKE 를 다시 보내지 않는다").hasSize(1);
    assertThat(tableQueries).as("재판정이 한 번 돌았다(테이블 목록을 다시 읽음)").isEqualTo(2);
  }

  /**
   * 재판정(WD-645): 겹친 재생성이 테이블을 보관본 이름으로 RENAME 하면 새 이름의 권한을 회수하고, 성공하면 회수 실패가 아니다. 테이블 하나 동기화도
   * 재맞춤에서는 대상 슬롯 계정 전부의 권한을 테넌트 전체에서 다시 읽어(WD-660) 보관본까지 본다 — 전체·테이블 동기화 두 입구를 모두 본다.
   */
  @Test
  void rejudge_tableRenamedDuringRevoke_revokesUnderNewName_noFailure() {
    for (String entry : List.of("tenant", "table")) {
      String upper = sensitiveWithFailingRevoke("rn_" + entry);
      String backup = upper + "_BAK";
      onFailingStatement = () -> renameObject(upper, backup);
      int failedBefore = failedStatements.size();

      if (entry.equals("tenant")) {
        assertThat(sync.syncTenant().revokeFailed()).as("재판정 결과가 첫 판의 실패 목록을 바꾼다").isEmpty();
      } else {
        // syncTable 은 결과를 돌려주지 않는다 — 아래 "보관본에 남은 권한 없음·추가 실패 없음" 이 revokeFailed 비어 있음과 같은 판정이다.
        sync.syncTable(upper.toLowerCase());
      }

      assertThat(executed).as(entry).contains(revokeSql(backup, 1));
      assertThat(slotsOf(backup)).as(entry).isEmpty();
      assertThat(slotsOf(upper)).as(entry).isEmpty();
      assertThat(failedStatements.subList(failedBefore, failedStatements.size()))
          .as(entry + ": 실패는 첫 판의 원래 이름 REVOKE 하나뿐")
          .containsExactly(revokeSql(upper, 1));
    }
  }

  /**
   * 재판정(WD-645)도 fail-closed: 휴지통 BIN$ 객체처럼 회수가 늘 실패하는 객체에 권한이 남아 있으면 지금 이름으로 회수 실패에 남고 실행 준비는
   * 거부된다.
   */
  @Test
  void rejudge_recycleBinObjectStillGranted_staysRevokeFailure_andRunRejected() {
    String upper = sensitiveWithFailingRevoke("sen");
    String bin = binName();
    failingFragments.add("BIN$"); // 휴지통 객체 회수는 늘 실패한다
    onFailingStatement = () -> renameObject(upper, bin);

    PythonReadGrantSync.SyncResult r = sync.syncTenant();

    assertThat(r.revokeFailed()).containsOnlyKeys(bin);
    assertThat(r.revokeFailed().get(bin)).containsExactly(slot(1));
    assertThat(tableQueries).as("첫 실패는 BIN$ 이 아니었으므로 재판정이 돌았다").isEqualTo(2);
    tableQueries = 0;
    assertThatThrownBy(() -> sync.prepareForRun(clearanceAt("공개"), levelRank("공개")))
        .isInstanceOf(PythonReadAccessException.class)
        .hasMessageContaining("회수하지 못한");
    assertThat(tableQueries).as("이제 실패는 BIN$ 뿐이라 재판정하지 않는다").isEqualTo(1);
  }

  /**
   * 휴지통(BIN$) 객체의 회수 실패뿐이면 재판정하지 않는다(WD-645) — OWNER 는 회수할 수 없어 다시 해도 같다. 그래도 회수 실패로 남아 실행은
   * 거부된다(fail-closed).
   */
  @Test
  void recycleBinOnlyFailure_noRejudge_andRunRejected() {
    table("pub", "공개");
    String bin = binName();
    grants.add(bin + "|" + slot(1));
    failingFragments.add("BIN$");

    assertThatThrownBy(() -> sync.prepareForRun(clearanceAt("공개"), levelRank("공개")))
        .isInstanceOf(PythonReadAccessException.class)
        .hasMessageContaining("회수하지 못한");
    assertThat(tableQueries).as("재판정 없음 — 테이블 목록은 한 번만 읽었다").isEqualTo(1);
    assertThat(failedStatements).containsExactly(revokeSql(bin, 1));

    tableQueries = 0;
    failedStatements.clear();
    PythonReadGrantSync.SyncResult r = sync.syncTenant();
    assertThat(r.revokeFailed()).containsOnlyKeys(bin);
    assertThat(tableQueries).isEqualTo(1);
    assertThat(failedStatements).hasSize(1);
  }

  /**
   * 변경 테이블 수는 두 판의 합집합으로 센다(WD-645). 전체 동기화의 재맞춤은 원래 범위(테넌트 전체) 안이므로 첫 판에서 실패한 GRANT 를 다시
   * 시도한다(WD-660) — 여기서는 또 실패해 과소권한(안전)으로 남고, pub 은 두 판 모두에서 바뀐 것으로 한 번만 센다.
   */
  @Test
  void rejudge_changedTables_countsUnionOfBothPasses() {
    String pub = table("pub", "공개");
    String upper = sensitiveWithFailingRevoke("sen");
    failingFragments.add(grantSql(pub, 5)); // 두 판 모두 GRANT 실패 → 과소권한
    onFailingStatement = () -> renameObject(upper, upper + "_BAK");

    PythonReadGrantSync.SyncResult r = sync.syncTenant();

    assertThat(r.revokeFailed()).isEmpty();
    assertThat(r.changedTables()).as("첫 판 {pub} ∪ 재맞춤 {pub, BAK} = 2").isEqualTo(2);
    assertThat(failedStatements)
        .as("원래 범위 안이라 재맞춤이 GRANT 를 한 번 더 시도한다")
        .filteredOn(s -> s.equals(grantSql(pub, 5)))
        .hasSize(2);
    assertThat(slotsOf(pub)).doesNotContain(5);
  }

  /** 재판정(WD-645)은 한 번만 — 두 번째도 실패하면 세 번째 시도 없이 회수 실패로 남는다(대기도 없다). */
  @Test
  void rejudge_runsOnlyOnce_whenSecondPassAlsoFails() {
    String upper = sensitiveWithFailingRevoke("sen");

    PythonReadGrantSync.SyncResult r = sync.syncTenant();

    assertThat(failedStatements).containsExactly(revokeSql(upper, 1), revokeSql(upper, 1));
    assertThat(tableQueries).isEqualTo(2);
    assertThat(connectionsOpened).as("같은 Tibero 연결(withDdl 한 번) 안에서").isEqualTo(1);
    assertThat(r.revokeFailed().get(upper)).containsExactly(slot(1));
  }

  /** 회수 실패가 없으면 재판정하지 않는다 — PYTHON 실행마다 도는 경로에 추가 조회·문장이 없다(WD-645). */
  @Test
  void noRevokeFailure_noRejudge_noExtraQueries() {
    String pub = table("pub", "공개");
    String sens = table("sen", "민감");
    grants.add(sens.toUpperCase() + "|" + slot(1)); // 회수는 성공한다

    PythonReadGrantSync.SyncResult r = sync.syncTenant();

    assertThat(r.revokeFailed()).isEmpty();
    assertThat(tableQueries).isEqualTo(1);
    assertThat(connectionsOpened).isEqualTo(1);
    assertThat(slotsOf(pub)).isEqualTo(range(1, 10));

    tableQueries = 0;
    grants.add(sens.toUpperCase() + "|" + slot(1));
    assertThat(sync.prepareForRun(clearanceAt("공개"), levelRank("공개"))).isEqualTo(1);
    assertThat(tableQueries).isEqualTo(1);
    assertThat(exactGrantQueries).as("적용 전·재확인 두 번뿐").isEqualTo(2);
  }

  /** 재판정(WD-645)은 실행 준비의 대상 계정을 넓히지 않는다 — 실행 슬롯 하나만 맞추고 다른 슬롯의 드리프트는 그대로 둔다. */
  @Test
  void rejudge_prepareForRun_keepsOnlyRunSlot() {
    String upper = sensitiveWithFailingRevoke("sen");
    String pub = table("pub", "공개");
    grants.add(upper + "|" + slot(2)); // 다른 슬롯의 과권한 — 이 실행(슬롯 1)은 건드리지 않는다
    grants.add(pub.toUpperCase() + "|" + slot(9));
    onFailingStatement = () -> renameObject(upper, upper + "_BAK");

    assertThat(sync.prepareForRun(clearanceAt("공개"), levelRank("공개"))).isEqualTo(1);
    assertThat(executed).allMatch(s -> s.contains("\"" + slot(1) + "\""));
    assertThat(failedStatements).allMatch(s -> s.contains("\"" + slot(1) + "\""));
    assertThat(slotsOf(upper + "_BAK")).as("슬롯 2 권한은 그대로").containsExactly(2);
    assertThat(exactGrantQueries).as("재판정도 실행 슬롯을 정확히 바인드해 읽는다").isEqualTo(4);
  }

  /**
   * 재맞춤의 넓히기는 첫 맞춤의 원래 범위 밖으로 나가지 않는다(WD-660): 테이블 하나 동기화에서 REVOKE 가 실패해도 재맞춤이 그 테이블 밖에 GRANT 를 내지
   * 않는다(단일 테이블 동기화가 테넌트 전체 GRANT 를 넓히면 등급 변경의 세대 번호 올림 없이 권한이 넓어진다).
   */
  @Test
  void repass_singleTableSync_neverGrantsOutsideItsTable() {
    String pub = table("pub", "공개"); // 아직 GRANT 가 없다 — 전체 동기화였다면 넓혔을 테이블
    String upper = sensitiveWithFailingRevoke("sen");

    sync.syncTable(upper.toLowerCase());

    assertThat(slotsOf(pub)).as("범위 밖 테이블은 넓히지 않는다").isEmpty();
    assertThat(executed).noneMatch(s -> s.startsWith("GRANT") && !s.contains("\"" + upper + "\""));
    assertThat(failedStatements)
        .as("재맞춤은 한 번 — 같은 REVOKE 를 다시 시도하고 끝")
        .containsExactly(revokeSql(upper, 1), revokeSql(upper, 1));
  }

  /**
   * 재맞춤은 첫 맞춤의 원래 범위 안에서는 GRANT 도 다시 시도한다(WD-660): 첫 맞춤은 한 테이블의 REVOKE 가 실패하면 그 테이블의 GRANT 를 건너뛰므로,
   * REVOKE 가 일시적으로 실패한 테이블이 재맞춤에서 회수에 성공하면 빠진 GRANT 까지 채운다. 테이블 하나 동기화면 그 테이블만 — 범위 밖 테이블은 넓히지 않는다.
   * 전체·테이블 동기화 두 입구를 모두 본다.
   */
  @Test
  void repass_retriesMissingGrantsInsideOriginalScope_only() {
    for (String entry : List.of("tenant", "table")) {
      String upper = sensitiveWithFailingRevoke("g_" + entry); // 슬롯 1 과권한, 슬롯 3..10 GRANT 없음
      String revoke = revokeSql(upper, 1);
      onFailingStatement = () -> failingFragments.remove(revoke); // 첫 판에서 한 번만 실패한다
      String other = table("o_" + entry, "공개");

      if (entry.equals("tenant")) {
        assertThat(sync.syncTenant().revokeFailed()).as(entry).isEmpty();
        assertThat(slotsOf(other)).as(entry + ": 원래 범위(전체) 안").isEqualTo(range(1, 10));
      } else {
        sync.syncTable(upper.toLowerCase());
        assertThat(slotsOf(other)).as(entry + ": 범위 밖 테이블은 넓히지 않는다").isEmpty();
      }

      assertThat(slotsOf(upper)).as(entry + ": 재맞춤이 빠진 GRANT 를 채운다").isEqualTo(range(3, 10));
      assertThat(failedStatements).as(entry).containsOnlyOnce(revoke);
    }
  }

  /**
   * 재맞춤의 회수 범위는 대상 슬롯 계정 전부의 권한이다(WD-660): 겹친 RENAME 은 모든 계정의 권한을 보관본으로 옮기므로, 첫 맞춤에서 실패하지 않은 계정 (원래
   * 테이블에서 정당하게 받은 슬롯 3)의 보관본 권한도 회수한다 — 실패 계정만 보면 데이터셋이 아닌 보관본에 SELECT 가 남는다.
   */
  @Test
  void repass_revokesOtherAccountsGrantCarriedToRenamedBackup() {
    String upper = sensitiveWithFailingRevoke("sen");
    grants.add(upper + "|" + slot(3)); // 민감 등급이라 슬롯 3 은 정당한 권한 — 첫 맞춤에서 실패하지 않는다
    String backup = upper + "_BAK";
    onFailingStatement = () -> renameObject(upper, backup);

    sync.syncTable(upper.toLowerCase());

    assertThat(executed).contains(revokeSql(backup, 1), revokeSql(backup, 3));
    assertThat(slotsOf(backup)).as("보관본에 남은 권한 없음").isEmpty();
  }

  /**
   * 테이블 하나 동기화의 재맞춤은 테넌트 전체에서 계산값에 없는 권한을 회수할 수 있지만 GRANT 는 하지 않는다(WD-660) — 다른 테이블의 어긋난 권한·뷰 권한도
   * 회수되고(fail-closed 방향), GRANT 가 필요한 범위 밖 테이블은 그대로 둔다. 회수한 객체는 변경 테이블로 센다.
   */
  @Test
  void repass_singleTableSync_revokesExcessTenantWide_neverGrants() {
    String need = table("need", "공개"); // GRANT 가 필요하지만 범위 밖이라 넓히지 않는다
    String stray = table("stray", "민감").toUpperCase();
    grants.add(stray + "|" + slot(2)); // 실패하지 않은 계정의 과권한 — 재맞춤이 회수한다
    String view = "A_VIEW_" + m.toUpperCase();
    grants.add(view + "|" + slot(3)); // 테이블이 아닌 객체 — 계산값 "없음"
    String upper = sensitiveWithFailingRevoke("sen");
    String backup = upper + "_BAK";
    onFailingStatement = () -> renameObject(upper, backup);

    sync.syncTable(upper.toLowerCase());

    assertThat(executed)
        .as("재맞춤 문장은 계산값에 없는 권한의 REVOKE 뿐")
        .containsExactlyInAnyOrder(revokeSql(backup, 1), revokeSql(stray, 2), revokeSql(view, 3));
    assertThat(slotsOf(backup)).isEmpty();
    assertThat(slotsOf(stray)).isEmpty();
    assertThat(slotsOf(view)).isEmpty();
    assertThat(slotsOf(need)).isEmpty();
  }

  /**
   * 휴지통(BIN$) 실패와 일반 실패가 섞이면(WD-660) 재맞춤은 BIN$ REVOKE 를 다시 보내지 않고 첫 맞춤의 BIN$ 실패를 그대로 옮긴다 — 일반 실패는
   * 보관본에서 회수돼 사라진다.
   */
  @Test
  void repass_mixedRecycleBinAndNormalFailure_keepsBinFailure_withoutResendingBinRevoke() {
    String bin = binName();
    grants.add(bin + "|" + slot(1));
    failingFragments.add("BIN$");
    String upper = sensitiveWithFailingRevoke("sen");
    String backup = upper + "_BAK";
    onFailingStatement = () -> renameObject(upper, backup);

    PythonReadGrantSync.SyncResult r = sync.syncTenant();

    assertThat(r.revokeFailed()).containsOnlyKeys(bin);
    assertThat(r.revokeFailed().get(bin)).containsExactly(slot(1));
    assertThat(failedStatements)
        .as("BIN$ REVOKE 는 첫 맞춤에서 한 번만")
        .containsExactlyInAnyOrder(revokeSql(bin, 1), revokeSql(upper, 1));
    assertThat(executed).contains(revokeSql(backup, 1));
    assertThat(slotsOf(backup)).isEmpty();
  }

  /**
   * 첫 맞춤의 BIN$ 실패는 재맞춤에서 다시 읽은 권한에 그 (객체, 계정) 쌍이 아직 있을 때만 옮긴다(WD-660) — 그 사이 휴지통이 비워져(PURGE) 권한까지
   * 사라졌으면 남은 과권한이 없으므로 실패가 아니다.
   */
  @Test
  void repass_dropsCarriedBinFailure_whenGrantNoLongerListed() {
    String bin = binName();
    grants.add(bin + "|" + slot(1));
    failingFragments.add("BIN$");
    String upper = sensitiveWithFailingRevoke("sen");
    String backup = upper + "_BAK";
    onFailingStatement =
        () -> {
          renameObject(upper, backup);
          grants.removeIf(g -> g.startsWith(bin + "|")); // 휴지통 비우기 — BIN$ 권한도 사라진다
        };

    PythonReadGrantSync.SyncResult r = sync.syncTenant();

    assertThat(failedStatements).as("첫 맞춤은 두 REVOKE 모두 실패").hasSize(2);
    assertThat(r.revokeFailed()).as("다시 읽은 권한에 없으면 BIN$ 실패를 옮기지 않는다").isEmpty();
    assertThat(slotsOf(backup)).isEmpty();
  }

  /** fail-closed(WD-660): 재맞춤에서 보관본의 남은 권한 회수도 실패하면 회수 실패로 남아 실행 준비가 거부된다. */
  @Test
  void repass_backupRevokeAlsoFails_staysRevokeFailure_andRunRejected() {
    String upper = sensitiveWithFailingRevoke("sen");
    String backup = upper + "_BAK";
    failingFragments.add(revokeSql(backup, 1));
    onFailingStatement = () -> renameObject(upper, backup);

    assertThatThrownBy(() -> sync.prepareForRun(clearanceAt("공개"), levelRank("공개")))
        .isInstanceOf(PythonReadAccessException.class)
        .hasMessageContaining("회수하지 못한");
    assertThat(failedStatements).containsExactly(revokeSql(upper, 1), revokeSql(backup, 1));
    assertThat(slotsOf(backup)).containsExactly(1);
  }

  /**
   * carriedBin 객체의 다른 계정 회수 성공도 변경이다(WD-679): 첫 맞춤의 BIN$ 실패(슬롯 1)를 옮긴 객체에 재맞춤에서 새로 보인 슬롯 2 권한의
   * REVOKE 가 오류 없이 끝나면 그 객체를 변경 테이블로 세고 재확인한다 — REVOKE 가 "성공" 했는데도 권한이 남으면(sticky) 재확인이 잡아 회수 실패에
   * 더한다.
   */
  @Test
  void repass_carriedBinOtherAccountRevoked_countsChanged_andRechecksRemainingGrant() {
    String bin = binName();
    grants.add(bin + "|" + slot(1));
    failingFragments.add(revokeSql(bin, 1)); // 휴지통 객체 슬롯 1 회수는 늘 실패한다
    String upper = sensitiveWithFailingRevoke("sen");
    String backup = upper + "_BAK";
    stickyGrants.add(bin + "|" + slot(2)); // 슬롯 2 REVOKE 는 오류 없이 끝나지만 권한이 남는다
    onFailingStatement =
        () -> {
          renameObject(upper, backup);
          grants.add(bin + "|" + slot(2)); // 첫 맞춤 뒤에 새로 보이는 휴지통 권한
        };

    PythonReadGrantSync.SyncResult r = sync.syncTenant();

    assertThat(executed).as("재맞춤이 새로 보인 슬롯 2 를 한 번 회수한다").contains(revokeSql(bin, 2));
    assertThat(failedStatements)
        .as("옮긴 슬롯 1 REVOKE 는 다시 보내지 않는다")
        .containsOnlyOnce(revokeSql(bin, 1));
    assertThat(r.revokeFailed().get(bin))
        .as("재확인이 \"성공\" 뒤에도 남은 슬롯 2 까지 회수 실패로 센다")
        .containsExactly(slot(1), slot(2));
    assertThat(r.changedTables()).as("보관본 + 일부 계정을 회수한 BIN$").isEqualTo(2);
  }

  /** 한 테이블에서 일부 계정 회수만 실패해도 다른 계정을 회수했으면 변경 테이블로 센다(WD-679). GRANT 는 여전히 건너뛴다(fail-closed). */
  @Test
  void partialRevokeFailure_otherAccountRevoked_countsChanged() {
    String upper = sensitiveWithFailingRevoke("sen"); // 슬롯 1 회수는 늘 실패
    grants.add(upper + "|" + slot(2)); // 슬롯 2 과권한 — 회수는 성공한다

    PythonReadGrantSync.SyncResult r = sync.syncTenant();

    assertThat(executed).contains(revokeSql(upper, 2));
    assertThat(slotsOf(upper)).as("실패한 테이블에는 GRANT 를 걸지 않는다").containsExactly(1);
    assertThat(r.revokeFailed().get(upper)).containsExactly(slot(1));
    assertThat(r.changedTables()).isEqualTo(1);
  }

  /**
   * 재확인은 바꾼 테이블이 여럿이어도 그 목록(IN)만 다시 읽는다(WD-679) — 전에는 하나일 때만 좁히고 둘 이상이면 테넌트 전체를 다시 읽었다. 전체
   * 동기화(LIKE)와 실행 준비(계정 정확히 바인드) 두 경로를 모두 본다.
   */
  @Test
  void recheck_multipleChangedTables_readsOnlyThoseTables() {
    String a = table("a", "민감").toUpperCase();
    String b = table("b", "민감").toUpperCase();
    table("c", "공개"); // 바뀌지 않는 테이블 — 재확인 목록에 들지 않는다
    sync.syncTenant(); // 먼저 계산값으로 맞춰 둔다
    grants.add(a + "|" + slot(1));
    grants.add(b + "|" + slot(2));
    grantQueryTables.clear();

    PythonReadGrantSync.SyncResult r = sync.syncTenant();

    assertThat(r.changedTables()).isEqualTo(2);
    assertThat(grantQueryTables)
        .as("적용 전 읽기는 테넌트 전체, 재확인은 바꾼 두 테이블만")
        .containsExactly(List.of(), List.of(a, b));

    grants.add(a + "|" + slot(1));
    grants.add(b + "|" + slot(1));
    grantQueryTables.clear();
    assertThat(sync.prepareForRun(clearanceAt("공개"), levelRank("공개"))).isEqualTo(1);
    assertThat(grantQueryTables).containsExactly(List.of(), List.of(a, b));
  }

  /**
   * 바꾼 객체가 IN 한 조각({@code TiberoCatalog.IN_LIST_CHUNK})을 넘어도 재확인은 바꾼 목록만 읽는다(WD-679) — 카탈로그가 1000개씩
   * 나눠 질의하므로 IN 목록 1000개 제한을 넘는 질의를 만들지 않는다.
   */
  @Test
  void recheck_moreChangedThanInChunk_readsChangedListInChunks() {
    List<String> objects = new ArrayList<>();
    for (int i = 0; i <= IN_LIST_CHUNK; i++) {
      String object = "V_" + m.toUpperCase() + "_" + i;
      objects.add(object);
      grants.add(object + "|" + slot(1)); // 테이블이 아닌 객체 — 전부 회수 대상
    }
    grantQueryTables.clear();

    PythonReadGrantSync.SyncResult r = sync.syncTenant();

    assertThat(r.revokeFailed()).isEmpty();
    assertThat(r.changedTables()).isEqualTo(IN_LIST_CHUNK + 1);
    assertThat(grantQueryTables).as("적용 전 전체 한 번 + 재확인 두 조각").hasSize(3);
    assertThat(grantQueryTables.get(0)).isEmpty();
    assertThat(grantQueryTables.get(1)).hasSize(IN_LIST_CHUNK);
    assertThat(grantQueryTables.get(2)).hasSize(1);
    List<String> rechecked = new ArrayList<>(grantQueryTables.get(1));
    rechecked.addAll(grantQueryTables.get(2));
    assertThat(rechecked).as("재확인은 바꾼 객체 전부, 그것만").containsExactlyInAnyOrderElementsOf(objects);
    assertThat(grants).noneMatch(g -> g.startsWith("V_"));
  }

  /**
   * 테이블 하나 동기화의 변경 수는 그 테이블만 센다(WD-679) — 재맞춤이 범위 밖(다른 테이블·뷰)에서 회수한 객체는 회수는 하되 변경 수에 넣지 않는다(로그에 따로
   * 싣는다). 전에는 "변경 테이블 3개" 로 과다 집계됐다.
   */
  @Test
  void singleTableSync_outOfScopeRevokes_notCountedAsChanged() {
    String stray = table("stray", "민감").toUpperCase();
    grants.add(stray + "|" + slot(2));
    String view = "A_VIEW_" + m.toUpperCase();
    grants.add(view + "|" + slot(3));
    String upper = sensitiveWithFailingRevoke("sen");
    String revoke = revokeSql(upper, 1);
    onFailingStatement = () -> failingFragments.remove(revoke); // 첫 맞춤에서 한 번만 실패한다

    PythonReadGrantSync.SyncResult r = sync.syncTable(upper.toLowerCase());

    assertThat(executed).contains(revoke, revokeSql(stray, 2), revokeSql(view, 3));
    assertThat(slotsOf(upper)).isEqualTo(range(3, 10));
    assertThat(r.revokeFailed()).isEmpty();
    assertThat(r.changedTables()).as("범위 밖 회수(stray·view)는 세지 않는다").isEqualTo(1);
  }

  /**
   * 계산값 조회 수를 세는 위임 mock 저장소를 만들고 {@link #sync} 를 그것으로 바꾼다 — 테이블 하나 동기화의 메타 조회 범위를 고정한다(WD-660).
   */
  private DatasetRepository countDesiredReads() {
    DatasetRepository counting = mock(DatasetRepository.class, delegatesTo(datasetRepository));
    sync = newSync(counting);
    return counting;
  }

  /** 실패가 없는 테이블 하나 동기화는 그 테이블의 등급 행만 읽는다(WD-660) — 테넌트 전체 메타를 읽지 않는다. */
  @Test
  void desired_singleTableSync_noFailure_readsOnlyThatTable() {
    DatasetRepository counting = countDesiredReads();
    String pub = table("pub", "공개");

    sync.syncTable(pub);

    assertThat(slotsOf(pub)).isEqualTo(range(1, 10));
    verify(counting, times(1)).findTableLevels(pub);
    verify(counting, never()).findTableLevels(isNull());
  }

  /**
   * 테이블 하나 동기화에서 재맞춤이 실제로 돌 때만 테넌트 전체 계산값을 늦게(한 번) 읽는다(WD-660) — 첫 맞춤은 그 테이블의 행만 읽는다. 재맞춤은 자기 스냅숏을
   * 쓰지만 다시 읽은 실제 권한으로 판정하므로 fail-open 이 되지 않는다.
   */
  @Test
  void desired_singleTableSync_repass_readsTenantWideLazilyOnce() {
    DatasetRepository counting = countDesiredReads();
    String upper = sensitiveWithFailingRevoke("sen");
    onFailingStatement = () -> renameObject(upper, upper + "_BAK");

    sync.syncTable(upper.toLowerCase());

    assertThat(executed).contains(revokeSql(upper + "_BAK", 1));
    verify(counting, times(1)).findTableLevels(upper.toLowerCase());
    verify(counting, times(1)).findTableLevels(isNull());
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
    awaitPythonReadGrantSync(); // 커밋 뒤 동기화는 전용 실행기에서 돈다(WD-594)
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
    awaitPythonReadGrantSync(); // 넘겼다면 끝난 뒤에 본다 — 공허한 통과 방지
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

  /**
   * 커밋 직전 좁히기(WD-624) — 등급 변경 트랜잭션 안에서(아직 커밋 전인 새 등급을 읽고) 그 테이블의 남는 슬롯 권한만 회수하고 부족한 GRANT 는 걸지 않는다.
   * 다른 테이블은 건드리지 않는다.
   */
  @Test
  void narrowDatasetInCurrentTx_revokesExcessOnly_seeingUncommittedLevel() {
    String t = table("nar", "공개");
    String other = table("noth", "민감");
    sync.syncTenant();
    grants.removeIf(g -> g.startsWith(other.toUpperCase() + "|")); // 다른 테이블은 부족한 상태
    executed.clear();
    long id = datasets.get(0);

    long from = fx.levelId("공개");
    long to = fx.levelId("민감");
    NarrowResult narrowed =
        TenantRlsTestSupport.runInTenantTransaction(
            fixtureTransactionTemplate,
            DEFAULT_TEST_TENANT_ID,
            () -> {
              dsl.update(DATASET)
                  .set(DATASET.SECURITY_LEVEL_ID, to)
                  .where(DATASET.ID.eq(id))
                  .execute();
              return sync.narrowDatasetInCurrentTx(id, from, to);
            });

    assertThat(narrowed.confirmed()).isTrue();
    assertThat(narrowed.exposedSlots()).isEqualTo(Set.of(1, 2));
    assertThat(executed).containsExactlyInAnyOrder(revokeSql(t, 1), revokeSql(t, 2));
    assertThat(slotsOf(other)).as("다른 테이블·부족한 GRANT 는 그대로(넓히기는 실행기 몫)").isEmpty();
  }

  /** 넓어지는 변경이면 회수할 것이 없어 아무 문장도 보내지 않는다(방향을 따지지 않아도 저절로 그렇게 된다). */
  @Test
  void narrowDatasetInCurrentTx_wideningChange_sendsNothing() {
    String t = table("wid", "민감");
    sync.syncTenant();
    executed.clear();
    long id = datasets.get(0);

    long from = fx.levelId("민감");
    long to = fx.levelId("공개");
    NarrowResult narrowed =
        TenantRlsTestSupport.runInTenantTransaction(
            fixtureTransactionTemplate,
            DEFAULT_TEST_TENANT_ID,
            () -> {
              dsl.update(DATASET)
                  .set(DATASET.SECURITY_LEVEL_ID, to)
                  .where(DATASET.ID.eq(id))
                  .execute();
              return sync.narrowDatasetInCurrentTx(id, from, to);
            });

    assertThat(narrowed.confirmed()).isTrue();
    assertThat(narrowed.needsFallback()).as("넓어지는 변경 — 막을 슬롯 없음").isFalse();
    assertThat(executed).isEmpty();
    assertThat(slotsOf(t)).isEqualTo(range(3, 10));
  }

  // ------------------------------------------------ WD-664 6: 커밋 직전 좁히기의 시간 상한

  /** 테스트용 짧은 상한 — 전체 1초·접속 0.6초·문장 0.5초(초 단위 queryTimeout 은 1초로 올림). */
  private static final TiberoPythonReadGrantSync.PreCommitBounds SHORT_BOUNDS =
      new TiberoPythonReadGrantSync.PreCommitBounds(
          Duration.ofSeconds(1), Duration.ofMillis(600), Duration.ofMillis(500));

  /** 등급 변경 트랜잭션 안에서 공개 → 민감 좁히기를 부르고, 결과와 걸린 시간(ms)을 돌려준다. */
  private NarrowResult narrowPublicToSensitive(long id, long[] elapsedMs) {
    long from = fx.levelId("공개");
    long to = fx.levelId("민감");
    long start = System.nanoTime();
    NarrowResult r =
        TenantRlsTestSupport.runInTenantTransaction(
            fixtureTransactionTemplate,
            DEFAULT_TEST_TENANT_ID,
            () -> {
              dsl.update(DATASET)
                  .set(DATASET.SECURITY_LEVEL_ID, to)
                  .where(DATASET.ID.eq(id))
                  .execute();
              return sync.narrowDatasetInCurrentTx(id, from, to);
            });
    elapsedMs[0] = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    return r;
  }

  /**
   * WD-664 6 — 커밋 직전 좁히기의 Tibero 직결 접속이 느리면(예전 상한 10초) 접속 상한(전체 예산 안)에서 포기하고 "확인 못 함" 으로 돌아온다 —
   * 리스너가 대체 경로(토큰 무효화 + 커밋 뒤 비동기 좁히기)로 막는다. 커밋은 상한 안에 진행된다. 고치기 전에는 상한 없는 접속을 끝까지 기다려(여기서는 3초) 시간
   * 단언이 깨지고, 늦게라도 접속돼 "확인됨" 이 됐다.
   */
  @Test
  void narrowBeforeCommit_slowConnect_givesUpWithinBudget_andFallsBack() {
    table("bcn", "공개");
    sync.syncTenant();
    long id = datasets.get(0);
    sync.preCommitBoundsForTest(SHORT_BOUNDS);
    connectDelayMs = 3_000;
    executed.clear();
    long[] elapsed = {0};

    NarrowResult narrowed = narrowPublicToSensitive(id, elapsed);

    assertThat(elapsed[0]).as("접속 상한(0.6초) 안에서 포기 — 커밋을 붙잡지 않는다").isLessThan(2_000L);
    assertThat(narrowed.confirmed()).as("대체 경로로 간다").isFalse();
    assertThat(narrowed.exposedSlots()).as("새 등급에서 못 읽는 슬롯을 막는다").containsAll(Set.of(1, 2));
    assertThat(connectTimeouts).as("접속 상한을 넘겼다").hasSize(1);
    assertThat(connectTimeouts.get(0)).isLessThanOrEqualTo(SHORT_BOUNDS.connect());
    assertThat(executed).as("접속을 못 했으니 문장도 없다").isEmpty();
  }

  /**
   * WD-664 6 — REVOKE 문장 하나가 느리면(드라이버가 queryTimeout 을 지키지 않는 최악의 경우까지) 전체 예산을 넘긴 뒤 나머지 문장은 보내지 않고
   * "확인 못 함" 으로 돌아온다. 문장마다 queryTimeout(남은 예산, 초 단위 올림)을 건다. 고치기 전에는 REVOKE 두 개를 모두 끝까지 기다려(2 ×
   * 1.5초) "확인됨" 이었다.
   */
  @Test
  void narrowBeforeCommit_slowRevoke_stopsAtBudget_andFallsBack() {
    String t = table("brv", "공개");
    sync.syncTenant();
    long id = datasets.get(0);
    sync.preCommitBoundsForTest(SHORT_BOUNDS);
    slowStatementFragment = "REVOKE";
    slowStatementMs = 1_500;
    executed.clear();
    queryTimeouts.clear();
    long[] elapsed = {0};

    NarrowResult narrowed = narrowPublicToSensitive(id, elapsed);

    assertThat(narrowed.confirmed()).as("예산을 넘겨 대체 경로로 간다").isFalse();
    assertThat(narrowed.exposedSlots()).containsAll(Set.of(1, 2));
    assertThat(executed).as("예산을 넘긴 뒤의 REVOKE 는 보내지 않는다").containsExactly(revokeSql(t, 1));
    assertThat(elapsed[0]).as("느린 문장 하나 + 여유 — 문장마다 끝까지 기다리지 않는다").isLessThan(2_800L);
    assertThat(queryTimeouts)
        .as("모든 문장·질의에 queryTimeout 을 건다")
        .isNotEmpty()
        .allMatch(sec -> sec == 1);
  }

  /**
   * WD-664 6 — 첫 판 REVOKE 가 실패해 재판정(WD-645, 테넌트 전체 조회)이 돌 때 그 조회가 느리면 예산을 넘긴 뒤 더 진행하지 않고 "확인 못 함"
   * 으로 돌아온다. 고치기 전에는 느린 재판정을 끝까지 기다려 다시 회수하고 "확인됨" 이 됐다(커밋을 그만큼 붙잡았다).
   */
  @Test
  void narrowBeforeCommit_slowRejudge_stopsAtBudget_andFallsBack() {
    String t = table("brj", "공개");
    sync.syncTenant();
    long id = datasets.get(0);
    sync.preCommitBoundsForTest(SHORT_BOUNDS);
    // 첫 판의 슬롯 1 REVOKE 만 실패(겹친 DDL 흉내) → 재판정. 재판정까지 가면 성공하므로, 예산이 없으면 "확인됨" 이 된다.
    String transientFail = revokeSql(t, 1);
    failingFragments.add(transientFail);
    onFailingStatement = () -> failingFragments.remove(transientFail);
    tenantWideGrantQueryDelayMs = 2_500;
    executed.clear();
    long[] elapsed = {0};

    NarrowResult narrowed = narrowPublicToSensitive(id, elapsed);

    assertThat(narrowed.confirmed()).isFalse();
    assertThat(narrowed.exposedSlots()).containsAll(Set.of(1, 2));
    assertThat(elapsed[0]).as("재판정 조회 하나 + 여유 — 그 뒤로 더 진행하지 않는다").isLessThan(3_800L);
    assertThat(executed)
        .as("재판정 조회 뒤 예산을 넘겨 재판정의 REVOKE 는 보내지 않는다")
        .containsExactly(revokeSql(t, 2));
  }

  /** WD-664 6 — 예산 안에 끝나면 상한이 결과를 바꾸지 않는다(확인됨). 접속·문장에 상한 값이 실린다. */
  @Test
  void narrowBeforeCommit_fastTibero_confirmedWithBoundsApplied() {
    String t = table("bok", "공개");
    sync.syncTenant();
    long id = datasets.get(0);
    executed.clear();
    queryTimeouts.clear();
    long[] elapsed = {0};

    NarrowResult narrowed = narrowPublicToSensitive(id, elapsed);

    assertThat(narrowed.confirmed()).isTrue();
    assertThat(executed).containsExactlyInAnyOrder(revokeSql(t, 1), revokeSql(t, 2));
    assertThat(connectTimeouts)
        .as("운영 상한으로 접속")
        .containsExactly(TiberoPythonReadGrantSync.PRE_COMMIT_BOUNDS.connect());
    assertThat(queryTimeouts)
        .isNotEmpty()
        .allMatch(
            sec ->
                sec >= 1
                    && sec <= TiberoPythonReadGrantSync.PRE_COMMIT_BOUNDS.statement().toSeconds());
  }

  /** 회수 문장이 실패하면 "확인 못 함" + 막을 슬롯 {1,2} — 리스너가 대체 경로로 막는다. */
  @Test
  void narrowDatasetInCurrentTx_revokeFailure_returnsUnconfirmedWithExposedSlots() {
    table("rf", "공개");
    sync.syncTenant();
    long id = datasets.get(0);
    failingFragments.add("REVOKE");
    long from = fx.levelId("공개");
    long to = fx.levelId("민감");

    NarrowResult narrowed =
        TenantRlsTestSupport.runInTenantTransaction(
            fixtureTransactionTemplate,
            DEFAULT_TEST_TENANT_ID,
            () -> {
              dsl.update(DATASET)
                  .set(DATASET.SECURITY_LEVEL_ID, to)
                  .where(DATASET.ID.eq(id))
                  .execute();
              return sync.narrowDatasetInCurrentTx(id, from, to);
            });

    assertThat(narrowed.confirmed()).isFalse();
    assertThat(narrowed.exposedSlots()).isEqualTo(Set.of(1, 2));
  }

  /**
   * 커밋 직전 좁히기(WD-624)가 휴지통(BIN$) 아닌 REVOKE 실패를 만나면 재판정(WD-645)이 같은 연결 안에서 한 번, 테넌트 전체 범위로 돈다. 재판정도
   * 회수 전용이라 부족한 GRANT 는 걸지 않고 REVOKE 만 보낸다. 재판정 뒤에도 회수 실패가 남은 객체의 슬롯은 {@code narrowResultOf} 가 「확인
   * 못 함」 결과의 막을 슬롯에 더한다(변경 후 받으면 안 되는 슬롯 {1,2} ∪ 남은 실패 슬롯 {5}).
   */
  @Test
  void
      narrowDatasetInCurrentTx_revokeFailure_rejudgesTenantWide_revokeOnly_leftoverSlotsUnconfirmed() {
    String t = table("nrj", "공개");
    String other = table("nrjo", "민감");
    sync.syncTenant();
    long id = datasets.get(0);
    String upper = t.toUpperCase();
    // 다른 테이블은 부족한 상태 — 재판정이 테넌트 전체를 보더라도 GRANT 로 채우면 안 된다(넓히기는 실행기 몫).
    grants.removeIf(g -> g.startsWith(other.toUpperCase() + "|"));
    // 테이블이 아닌 객체(뷰 흉내)에 슬롯 5 과권한 — 회수가 늘 실패한다. 첫 판(그 테이블만)에는 안 보이고 재판정(전체)에서만 보인다.
    String stuck = m.toUpperCase() + "_VW";
    grants.add(stuck + "|" + slot(5));
    failingFragments.add(revokeSql(stuck, 5));
    // 좁히는 테이블의 슬롯 1 REVOKE 는 첫 번째만 실패한다(겹친 DDL 흉내) — 재판정에서는 성공한다.
    String transientFail = revokeSql(upper, 1);
    failingFragments.add(transientFail);
    onFailingStatement = () -> failingFragments.remove(transientFail);
    executed.clear();
    tableQueries = 0;
    long from = fx.levelId("공개");
    long to = fx.levelId("민감");

    NarrowResult narrowed =
        TenantRlsTestSupport.runInTenantTransaction(
            fixtureTransactionTemplate,
            DEFAULT_TEST_TENANT_ID,
            () -> {
              dsl.update(DATASET)
                  .set(DATASET.SECURITY_LEVEL_ID, to)
                  .where(DATASET.ID.eq(id))
                  .execute();
              return sync.narrowDatasetInCurrentTx(id, from, to);
            });

    assertThat(tableQueries).as("재판정이 한 번 돌았다(테이블 목록을 두 번 읽음)").isEqualTo(2);
    assertThat(failedStatements)
        .as("첫 판은 좁히는 테이블만, 재판정은 전체 범위라 뷰의 실패가 드러난다")
        .containsExactly(transientFail, revokeSql(stuck, 5));
    assertThat(executed)
        .as("재판정도 회수 전용 — GRANT 없음")
        .isNotEmpty()
        .allMatch(s -> s.startsWith("REVOKE "));
    assertThat(executed).contains(revokeSql(t, 1), revokeSql(t, 2));
    assertThat(slotsOf(t)).isEqualTo(range(3, 10));
    assertThat(slotsOf(other)).as("부족한 GRANT 는 그대로").isEmpty();
    assertThat(narrowed.confirmed()).isFalse();
    assertThat(narrowed.exposedSlots()).isEqualTo(Set.of(1, 2, 5));
  }

  /**
   * 대체 경로 뒷정리(리뷰 1) — 커밋 직전 번호 올리기가 실패했으면({@code tokensInvalidated=false}) 번호를 올리고, 잠금 안에서 그 테이블을
   * 회수한다. 커밋 직전에 이미 올렸고 회수도 성공하면 번호를 다시 올리지 않는다(토큰이 테넌트 단위라 불필요한 무효화는 모든 실행을 끊는다).
   */
  @Test
  void syncDatasetAfterUnconfirmedNarrowing_revokes_andBumpsEpochOnlyWhenNeeded() {
    String t = table("cln", "공개");
    sync.syncTenant();
    long id = datasets.get(0);
    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () ->
            dsl.update(DATASET)
                .set(DATASET.SECURITY_LEVEL_ID, fx.levelId("민감"))
                .where(DATASET.ID.eq(id))
                .execute());

    sync.syncDatasetAfterUnconfirmedNarrowing(id, Set.of(1, 2), true);
    assertThat(slotsOf(t)).isEqualTo(range(3, 10));
    verify(repository, never()).incrementScriptTokenEpoch(DEFAULT_TEST_TENANT_ID);

    sync.syncDatasetAfterUnconfirmedNarrowing(id, Set.of(1, 2), false);
    verify(repository, times(1)).incrementScriptTokenEpoch(DEFAULT_TEST_TENANT_ID);
  }

  /** 뒷정리에서도 회수가 실패해 슬롯에 권한이 남으면 번호를 올려 실행 중 스크립트를 막는다. */
  @Test
  void syncDatasetAfterUnconfirmedNarrowing_revokeFailure_bumpsEpoch() {
    table("clf", "공개");
    sync.syncTenant();
    long id = datasets.get(0);
    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () ->
            dsl.update(DATASET)
                .set(DATASET.SECURITY_LEVEL_ID, fx.levelId("민감"))
                .where(DATASET.ID.eq(id))
                .execute());
    failingFragments.add("REVOKE");

    sync.syncDatasetAfterUnconfirmedNarrowing(id, Set.of(1, 2), true);
    verify(repository, times(1)).incrementScriptTokenEpoch(DEFAULT_TEST_TENANT_ID);
  }

  /** 트랜잭션 밖 호출은 잠금·회수가 아무것도 지키지 못하므로 거부한다. */
  @Test
  void narrowAndBump_outsideTransaction_throw() {
    assertThatThrownBy(() -> sync.narrowDatasetInCurrentTx(1L, null, 1L))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> sync.bumpScriptTokenEpochInCurrentTx(DEFAULT_TEST_TENANT_ID))
        .isInstanceOf(IllegalStateException.class);
  }

  /**
   * 커밋 직전 토큰 번호 올리기는 <b>부른 쪽 트랜잭션</b>에서 한다(새 트랜잭션이 아니다 — 등급 변경과 함께 커밋·롤백되고 커넥션을 더 쓰지 않는다). 올림 시점의
   * 트랜잭션 동기화에 등록한 콜백이 바깥 트랜잭션의 커밋에서 불리는 것으로 "같은 트랜잭션" 을 고정한다.
   */
  @Test
  void bumpScriptTokenEpochInCurrentTx_runsInCallersTransaction() {
    boolean[] committedWithOuter = {false};
    // 커밋 직전 경로는 행 잠금 대기 상한이 있는 판을 부른다(WD-664 4b).
    when(repository.incrementScriptTokenEpoch(
            DEFAULT_TEST_TENANT_ID, TiberoPythonReadGrantSync.PRE_COMMIT_EPOCH_LOCK_WAIT))
        .thenAnswer(
            inv -> {
              org.springframework.transaction.support.TransactionSynchronizationManager
                  .registerSynchronization(
                      new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                          committedWithOuter[0] = true;
                        }
                      });
              return java.util.Optional.of(3L);
            });
    boolean[] committedBeforeOuterEnds = {true};
    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () -> {
          assertThat(sync.bumpScriptTokenEpochInCurrentTx(DEFAULT_TEST_TENANT_ID)).contains(3L);
          committedBeforeOuterEnds[0] = committedWithOuter[0];
        });
    assertThat(committedBeforeOuterEnds[0]).as("새 트랜잭션이면 반환 전에 이미 커밋됐다").isFalse();
    assertThat(committedWithOuter[0]).as("바깥 트랜잭션 커밋 때 함께 커밋").isTrue();
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
