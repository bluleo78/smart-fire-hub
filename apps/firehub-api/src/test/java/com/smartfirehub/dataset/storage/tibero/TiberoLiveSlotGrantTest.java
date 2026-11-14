package com.smartfirehub.dataset.storage.tibero;

import static com.smartfirehub.jooq.Tables.DATASET;
import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.apiconnection.service.EncryptionService;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.repository.TenantDataStoreRepository;
import com.smartfirehub.dataset.storage.DataStoreAccountRole;
import com.smartfirehub.dataset.storage.DataStorePasswordGenerator;
import com.smartfirehub.dataset.storage.StoredAccount;
import com.smartfirehub.dataset.storage.external.ExternalAccountProvisioner;
import com.smartfirehub.dataset.storage.external.ExternalStoreConnections;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.securitylevel.pythonread.PythonReadSyncExecutor;
import com.smartfirehub.securitylevel.pythonread.TiberoPythonReadGrantSync;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.SecurityFixture;
import com.smartfirehub.support.TenantRlsTestSupport;
import com.smartfirehub.support.tibero.TiberoLive;
import com.smartfirehub.support.tibero.TiberoTestServer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Tibero 슬롯 계정 SELECT 권한 실서버 테스트(WD-78, 스펙 §5.1) — 실행: {@code scripts/tibero-test.sh api}.
 *
 * <p>일회용 테넌트(예약 대역 id, 계정 12개)에 등급 3개(공개 위치 1·내부 위치 2·기밀 허용 목록)를 두고 <b>진짜 동기화</b>({@link
 * TiberoPythonReadGrantSync})를 돌린 뒤, 슬롯 계정으로 <b>실제 로그인해</b> 확인한다: 슬롯 S10 은 낮은 등급 테이블을 읽고 높은(허용 목록)
 * 테이블은 못 읽는다. 그리고 스펙 §5.1 의 확인 항목 — <b>이미 열려 있는 슬롯 세션</b>(풀 재사용 연결 모델)에서 REVOKE 가 다음 문장부터 적용되는가 — 를
 * 한 JDBC 연결을 REVOKE 를 가로질러 열어 둔 채 본다(PROBE 줄로 기록). 만든 테넌트·계정만 정리한다. 접속 정보·비밀번호는 출력하지 않는다.
 */
@TiberoLive
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TiberoLiveSlotGrantTest extends IntegrationTestBase {

  @Autowired private TiberoPythonReadGrantSync sync;
  @Autowired private PythonReadSyncExecutor syncExecutor;
  @Autowired private TiberoDataTableStore tableStore;
  @Autowired private DSLContext dsl;
  @Autowired private TenantDataStoreRepository storeRepository;
  @Autowired private ExternalAccountProvisioner provisioner;
  @Autowired private ExternalStoreConnections connections;
  @Autowired private EncryptionService encryption;
  @Autowired private DataStorePasswordGenerator passwordGenerator;
  @Autowired private PasswordEncoder encoder;

  private TiberoTestEnvironment.Provisioned env;
  private final List<Long> datasetIds = new ArrayList<>();
  private SecurityFixture fx;
  private long user;

  @BeforeAll
  void prepare() {
    TiberoTestServer.assumeDriverPresent();
    env =
        TiberoTestEnvironment.provision(
            dsl,
            fixtureTransactionTemplate,
            storeRepository,
            provisioner,
            connections,
            encryption,
            passwordGenerator);
    fx = new SecurityFixture(dsl, fixtureTransactionTemplate, encoder);
    user = fx.createUser("tlive_slot");
  }

  @AfterAll
  void cleanUp() {
    if (env != null) {
      TenantContext.set(env.tenantId);
      for (long id : datasetIds) {
        TenantRlsTestSupport.runInTenantTransaction(
            fixtureTransactionTemplate,
            env.tenantId,
            () -> dsl.deleteFrom(DATASET).where(DATASET.ID.eq(id)).execute());
      }
      env.close();
      env = null;
      TenantContext.clear();
    }
    if (fx != null) {
      fx.deleteUser(user);
    }
  }

  @BeforeEach
  void useTiberoTenant() {
    TenantContext.set(env.tenantId);
  }

  private static void probe(String name, String result) {
    System.out.println("PROBE " + name + ": " + result);
  }

  private String schema() {
    return connections.schemaOf(env.tenantId);
  }

  /** 슬롯 계정 비밀번호는 저장소 행에서 복호화해 메모리로만 쓴다(출력하지 않는다). */
  private Connection openSlot(int k) throws SQLException {
    StoredAccount account =
        storeRepository.findAccounts(env.tenantId).stream()
            .filter(a -> a.role() == DataStoreAccountRole.valueOf("S" + k))
            .findFirst()
            .orElseThrow();
    Connection c =
        DriverManager.getConnection(
            TiberoTestServer.dbaFromEnv().target().jdbcUrl(),
            account.username(),
            encryption.decrypt(account.encryptedPassword()));
    c.setAutoCommit(true);
    return c;
  }

  private void createLevel(String name, int rank, boolean allowlist) {
    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        env.tenantId,
        () ->
            dsl.execute(
                "insert into security_level (tenant_id, rank, name, is_default, allowlist_required)"
                    + " values (?, ?, ?, false, ?)",
                env.tenantId,
                rank,
                name,
                allowlist));
  }

  private long levelId(String name) {
    return TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        env.tenantId,
        () ->
            dsl.fetchSingle("select id from security_level where name = ?", name)
                .get(0, Long.class));
  }

  /** 테이블을 만들고(OWNER) 행 1개를 넣고 데이터셋 행(등급 지정)을 단다. 반환: 데이터셋 id. */
  private long dataset(String table, String level) {
    tableStore.createTable(
        table, List.of(new DatasetColumnRequest("v", "v", "TEXT", null, true, false, null, false)));
    connections.withDdl(
        env.tenantId,
        c -> {
          try (Statement st = c.createStatement()) {
            st.execute(
                "INSERT INTO " + TiberoNames.table(schema(), table) + " (\"V\") VALUES ('row')");
          }
          return null;
        });
    long lid = levelId(level);
    long id =
        TenantRlsTestSupport.runInTenantTransaction(
            fixtureTransactionTemplate,
            env.tenantId,
            () ->
                dsl.insertInto(DATASET)
                    .set(DATASET.NAME, "live_" + table)
                    .set(DATASET.TABLE_NAME, table)
                    .set(DATASET.CREATED_BY, user)
                    .set(DATASET.STORAGE_TYPE, "TABLE")
                    .set(DATASET.ORIGIN_TYPE, "SOURCE")
                    .set(DATASET.SECURITY_LEVEL_ID, lid)
                    .returning(DATASET.ID)
                    .fetchSingle(DATASET.ID));
    datasetIds.add(id);
    return id;
  }

  /** SELECT COUNT(*) — 성공하면 null, 실패하면 오류 코드(SQLException.getErrorCode). */
  private String countOrError(Connection c, String table) {
    try (Statement st = c.createStatement();
        ResultSet rs =
            st.executeQuery("SELECT COUNT(*) FROM " + TiberoNames.table(schema(), table))) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getInt(1)).isEqualTo(1);
      return null;
    } catch (SQLException e) {
      return String.valueOf(e.getErrorCode());
    }
  }

  @Test
  void slotS10_readsLowLevel_notHighLevel_andRevokeHitsAlreadyOpenSession() throws Exception {
    createLevel("공개", 1, false); // 위치 1
    // 기본 등급 '내부'(rank 2) 가 위치 2 로 이미 있다.
    createLevel("기밀", 3, true); // 허용 목록 등급 — 어느 슬롯도 읽을 수 없다
    String suffix = Long.toString(System.nanoTime() % 1_000_000_000L);
    String low = "sl_low_" + suffix;
    String mid = "sl_mid_" + suffix;
    String high = "sl_high_" + suffix;
    long lowId = dataset(low, "공개");
    dataset(mid, "내부");
    dataset(high, "기밀");

    sync.syncTenant();

    try (Connection s10 = openSlot(10);
        Connection s1 = openSlot(1)) {
      assertThat(countOrError(s10, low)).as("S10 은 낮은 등급(공개)을 읽는다").isNull();
      assertThat(countOrError(s10, mid)).as("S10 은 내부도 읽는다(위치 2 ≤ 10)").isNull();
      String highError = countOrError(s10, high);
      assertThat(highError).as("S10 은 허용 목록 등급을 읽지 못한다").isNotNull();
      probe("slot-s10-high-level-select", "DENIED code=" + highError);
      assertThat(countOrError(s1, low)).as("S1 은 공개만 읽는다").isNull();
      String s1Mid = countOrError(s1, mid);
      assertThat(s1Mid).as("S1 은 내부(위치 2)를 읽지 못한다").isNotNull();
      probe("slot-s1-higher-position-select", "DENIED code=" + s1Mid);

      // 이미 열린 세션 s10 에서 한 번 더 읽어 두고(준비된 문장 포함), 같은 연결을 쥔 채 REVOKE 한다.
      try (PreparedStatement prepared =
          s10.prepareStatement("SELECT COUNT(*) FROM " + TiberoNames.table(schema(), low))) {
        try (ResultSet rs = prepared.executeQuery()) {
          assertThat(rs.next()).isTrue();
        }
        // 낮은 등급 테이블을 허용 목록 등급으로 올린다 → 동기화가 모든 슬롯의 SELECT 를 REVOKE.
        long highLevel = levelId("기밀");
        TenantRlsTestSupport.runInTenantTransaction(
            fixtureTransactionTemplate,
            env.tenantId,
            () ->
                dsl.update(DATASET)
                    .set(DATASET.SECURITY_LEVEL_ID, highLevel)
                    .where(DATASET.ID.eq(lowId))
                    .execute());
        sync.syncDataset(lowId);

        // 같은 연결(s10)에서 REVOKE 직후 다음 문장.
        String freshAfterRevoke = countOrError(s10, low);
        probe(
            "revoke-on-open-session-fresh-statement",
            freshAfterRevoke == null
                ? "STILL READABLE (REVOKE not applied)"
                : "DENIED code=" + freshAfterRevoke);
        String preparedAfterRevoke;
        try (ResultSet rs = prepared.executeQuery()) {
          preparedAfterRevoke = rs.next() ? null : "no row";
        } catch (SQLException e) {
          preparedAfterRevoke = String.valueOf(e.getErrorCode());
        }
        probe(
            "revoke-on-open-session-prepared-statement",
            preparedAfterRevoke == null
                ? "STILL READABLE (REVOKE not applied)"
                : "DENIED code=" + preparedAfterRevoke);
        assertThat(freshAfterRevoke).as("REVOKE 는 열려 있던 세션의 다음 문장부터 적용된다(스펙 §5.1)").isNotNull();
        assertThat(preparedAfterRevoke).as("이미 준비·실행한 문장도 거부된다").isNotNull();
      }

      // 되돌리면(등급을 다시 공개로) 같은 열린 세션에서 다시 읽힌다 — GRANT 도 다음 문장부터 반영.
      long lowLevel = levelId("공개");
      TenantRlsTestSupport.runInTenantTransaction(
          fixtureTransactionTemplate,
          env.tenantId,
          () ->
              dsl.update(DATASET)
                  .set(DATASET.SECURITY_LEVEL_ID, lowLevel)
                  .where(DATASET.ID.eq(lowId))
                  .execute());
      sync.syncDataset(lowId);
      String afterRegrant = countOrError(s10, low);
      probe(
          "regrant-on-open-session",
          afterRegrant == null ? "READABLE" : "DENIED code=" + afterRegrant);
      assertThat(afterRegrant).isNull();
    }
  }

  /** 사용자 DDL 처럼 OWNER 자동 커밋 단발 연결로 문장 하나를 던진다. 실패는 목록에 모은다(테스트가 끝에서 한꺼번에 단언). */
  private void ownerDdl(String sql, List<String> failures) {
    try {
      connections.withDdl(
          env.tenantId,
          c -> {
            try (Statement st = c.createStatement()) {
              // 운영의 사용자 DDL 경로(runIgnoring)와 같은 재시도 도우미를 쓴다(WD-739).
              TiberoErrors.executeDdl(st, sql);
            }
            return null;
          });
    } catch (RuntimeException e) {
      failures.add(sql + " → " + e.getMessage());
    }
  }

  /**
   * 겹침 실측(WD-629): 커밋 뒤 슬롯 GRANT 동기화는 전용 실행기에서 자동 커밋 GRANT/REVOKE 를 던지고, GRANT 가 DDL 이라 암묵 커밋으로 테이블
   * 잠금이 먼저 풀려 잠금으로는 직렬화되지 않는다. 겹친 DDL 은 기다리지 않고 -12033(NOWAIT)으로 즉시 실패하며(실측, WD-739), 사용자 DDL 쪽이
   * {@code TiberoErrors.executeDdl} 로 짧게 재시도해 흡수한다. PG 판 회귀({@code PythonReadGrantWiringTest
   * .syncTableAfterCommit_overlappingAlterAndDrop_...})와 같은 모양으로, 동기화와 같은 테이블의 ADD COLUMN·DROP
   * TABLE 을 겹쳐 사용자 DDL 이 실패하지 않는지 본다. 실패하면 재시도 흡수가 부족하다는 증거다.
   */
  @Test
  void grantOverlappingAlterAndDrop_doesNotFailUserDdl() throws Exception {
    List<String> failures = new ArrayList<>();
    String suffix = Long.toString(System.nanoTime() % 1_000_000_000L);
    // 기본 등급 '내부'(위치 2) — 슬롯 2..10 이 SELECT 를 받는다.
    String t = "ov_" + suffix;
    dataset(t, "내부");
    String physical = TiberoNames.physical(t);
    String qt = TiberoNames.table(schema(), t);
    List<String> slotAccounts = new ArrayList<>();
    for (int k = 1; k <= 10; k++) {
      slotAccounts.add(DataStoreAccountRole.valueOf("S" + k).usernameFor(env.tenantId));
    }

    // (1) ADD COLUMN — 매 회 슬롯 GRANT 를 걷어 동기화가 GRANT 하게 만들고, 동기화가 끝날 때까지 ADD COLUMN 을 연달아 던진다.
    int col = 0;
    for (int round = 0; round < 15; round++) {
      for (String account : slotAccounts) {
        // 없는 권한 회수 오류는 무시한다(준비 단계라 실패 목록에 넣지 않는다).
        ownerDdl(TiberoDdlSql.revokeSelectFromSlot(schema(), physical, account), new ArrayList<>());
      }
      TenantRlsTestSupport.runInTenantTransaction(
          fixtureTransactionTemplate, env.tenantId, () -> sync.syncTableAfterCommit(t));
      int perRound = 0;
      do {
        ownerDdl("ALTER TABLE " + qt + " ADD (\"C" + (col++) + "\" VARCHAR2(10))", failures);
      } while (!syncExecutor.awaitIdle(java.time.Duration.ZERO) && ++perRound < 20);
      assertThat(syncExecutor.awaitIdle(java.time.Duration.ofSeconds(30))).isTrue();
      try (Connection s2 = openSlot(2)) {
        String err = countOrError(s2, t);
        if (err != null) {
          failures.add("round " + round + ": 겹친 동기화 뒤 슬롯 2 가 읽지 못한다 code=" + err);
        }
      }
    }

    // (2) DROP TABLE — 데이터셋이 아닌 테이블에 슬롯 GRANT 를 걸어 두면 동기화가 REVOKE 한다. 그 REVOKE 와 직후의 DROP 이 겹친다.
    // 여기서 보는 것은 사용자 DROP 이 실패하지 않는지뿐이다. 동기화 쪽 REVOKE 가 겹친 DROP 때문에 실패하면 회수 실패로 남는 것이
    // 의도다(fail-closed — RENAME·휴지통 BIN$ 로 GRANT 가 남을 수 있어 "사라졌다"로 넘기지 않는다, WD-629). 커밋 뒤 경로는 WARN 만
    // 남긴다.
    for (int round = 0; round < 15; round++) {
      String raw = "ovd_" + suffix + "_" + round;
      String rawPhysical = TiberoNames.physical(raw);
      String qraw = TiberoNames.table(schema(), raw);
      ownerDdl("CREATE TABLE " + qraw + " (\"ID\" NUMBER)", failures);
      ownerDdl(
          TiberoDdlSql.grantSelectToSlot(schema(), rawPhysical, slotAccounts.get(0)), failures);
      TenantRlsTestSupport.runInTenantTransaction(
          fixtureTransactionTemplate, env.tenantId, () -> sync.syncTableAfterCommit(raw));
      ownerDdl("DROP TABLE " + qraw, failures);
      assertThat(syncExecutor.awaitIdle(java.time.Duration.ofSeconds(30))).isTrue();
    }

    probe("overlapping-grant-and-ddl-failures", failures.isEmpty() ? "NONE" : failures.toString());
    assertThat(failures).as("동기화와 겹친 사용자 DDL 이 실패하지 않고, GRANT 는 계산값대로 남는다").isEmpty();
  }

  /**
   * 휴지통 객체의 권한 가시성(WD-645): 휴지통이 켜져 있으면 PURGE 없이 DROP 한 테이블이 {@code BIN$...} 객체로 슬롯 GRANT 를 쥔 채
   * 남는다. {@code TiberoPythonReadGrantSync} 재판정의 fail-closed 는 {@link
   * TiberoCatalog#slotSelectGrantsAmong}({@code USER_TAB_PRIVS_MADE})가 이 BIN$ 객체의 권한을 계속 보여 준다는 데
   * 기대므로, 그것을 실서버로 확인한다. 휴지통이 꺼져 있으면(DROP 뒤 {@code USER_RECYCLEBIN} 에 원래 이름이 없으면) 확인할 대상이 없으므로
   * 건너뛴다.
   */
  @Test
  void droppedTableInRecycleBin_slotGrantStillListed() {
    List<String> failures = new ArrayList<>();
    String suffix = Long.toString(System.nanoTime() % 1_000_000_000L);
    String raw = "rb_" + suffix;
    String rawPhysical = TiberoNames.physical(raw);
    String qraw = TiberoNames.table(schema(), raw);
    String account = DataStoreAccountRole.valueOf("S1").usernameFor(env.tenantId);
    ownerDdl("CREATE TABLE " + qraw + " (\"ID\" NUMBER)", failures);
    ownerDdl(TiberoDdlSql.grantSelectToSlot(schema(), rawPhysical, account), failures);
    // 운영 삭제와 같은 문장(PURGE 없음) — 휴지통이 켜져 있으면 BIN$ 객체로 남는다.
    ownerDdl(TiberoDdlSql.dropTableOnly(schema(), raw), failures);
    assertThat(failures).as("준비 DDL").isEmpty();

    // 휴지통 사용 여부는 매개변수 이름·조회 권한이 판마다 달라 실제 결과(USER_RECYCLEBIN 에 남았는가)로 판단한다.
    String bin =
        connections.withDdl(
            env.tenantId,
            c -> {
              try (PreparedStatement ps =
                  c.prepareStatement(
                      "SELECT OBJECT_NAME FROM USER_RECYCLEBIN WHERE ORIGINAL_NAME = ?")) {
                ps.setString(1, rawPhysical);
                try (ResultSet rs = ps.executeQuery()) {
                  return rs.next() ? rs.getString(1) : null;
                }
              } catch (SQLException e) {
                probe("recyclebin-query", "FAILED code=" + e.getErrorCode());
                return null;
              }
            });
    probe("recyclebin-object", bin == null ? "NONE (recycle bin off)" : bin);
    Assumptions.assumeTrue(bin != null, "휴지통이 꺼져 있어 BIN$ 권한 가시성을 확인할 수 없다");
    try {
      Map<String, Set<String>> grants =
          connections.withDdl(
              env.tenantId, c -> TiberoCatalog.slotSelectGrantsAmong(c, List.of(account), null));
      Map<String, Set<String>> likeGrants =
          connections.withDdl(
              env.tenantId, c -> TiberoCatalog.slotSelectGrantsAmong(c, env.tenantId, null));
      probe("recyclebin-grant-visible", String.valueOf(grants.containsKey(bin)));
      assertThat(grants.get(bin)).as("정확 바인드 조회가 BIN$ 객체의 슬롯 GRANT 를 보여 준다").contains(account);
      assertThat(likeGrants.get(bin)).as("LIKE 조회도 BIN$ 객체의 슬롯 GRANT 를 보여 준다").contains(account);
    } finally {
      // 휴지통에서 지운다(정리 단계라 실패는 무시한다).
      ownerDdl("PURGE TABLE " + TiberoNames.quote(bin), new ArrayList<>());
    }
  }

  /**
   * IN 목록 나누기 실측(WD-686): {@link TiberoCatalog#IN_LIST_CHUNK}(1000) 를 넘는 이름 목록으로 테이블·슬롯 권한을 조회해도
   * Tibero 가 IN 목록 제한 오류 없이 답하고, 첫 조각과 둘째 조각에 놓인 실제 테이블을 모두 찾는지 본다. 가짜 JDBC 테스트는 문장 모양만 보므로, 실서버에서
   * 나눈 조각이 실제로 받아들여지는지(바인드 수·문법)를 여기서 확인한다.
   *
   * <p>객체 1001개를 만들면 느리고 공유 서버를 어지럽히므로 실제 테이블은 두 개만 만들고 나머지는 없는 이름으로 채운다 — 나누기는 이름 수로 일어나므로 경계를 넘는
   * 것은 같다.
   */
  @Test
  void inListOverChunkBoundary_findsTablesAndGrantsInBothChunks() {
    List<String> failures = new ArrayList<>();
    String suffix = Long.toString(System.nanoTime() % 1_000_000_000L);
    String first = TiberoNames.physical("ch_a_" + suffix);
    String second = TiberoNames.physical("ch_b_" + suffix);
    String account = DataStoreAccountRole.valueOf("S1").usernameFor(env.tenantId);
    for (String t : List.of(first, second)) {
      ownerDdl("CREATE TABLE " + TiberoNames.qualified(schema(), t) + " (\"ID\" NUMBER)", failures);
      ownerDdl(TiberoDdlSql.grantSelectToSlot(schema(), t, account), failures);
    }
    assertThat(failures).as("준비 DDL").isEmpty();
    try {
      // 첫 이름은 첫 조각, IN_LIST_CHUNK 번째(0부터) 이름은 둘째 조각 — 경계를 넘는 수(IN_LIST_CHUNK + 5).
      List<String> names = new ArrayList<>();
      names.add(first);
      for (int i = 1; i < TiberoCatalog.IN_LIST_CHUNK; i++) {
        names.add("NO_SUCH_" + suffix + "_" + i);
      }
      names.add(second);
      for (int i = 0; i < 4; i++) {
        names.add("NO_SUCH_TAIL_" + suffix + "_" + i);
      }
      assertThat(names).hasSize(TiberoCatalog.IN_LIST_CHUNK + 5);

      // 세 카탈로그 조회는 연결 하나에서 한 번에 한다 — 테이블·정확 바인드 권한·LIKE 권한.
      record Lookups(
          Set<String> tables, Map<String, Set<String>> exact, Map<String, Set<String>> like) {}
      Lookups found =
          connections.withDdl(
              env.tenantId,
              c ->
                  new Lookups(
                      TiberoCatalog.ownTablesAmong(c, names),
                      TiberoCatalog.slotSelectGrantsAmong(c, List.of(account), names),
                      TiberoCatalog.slotSelectGrantsAmong(c, env.tenantId, names)));
      probe("in-list-chunk-tables", String.valueOf(found.tables()));
      probe("in-list-chunk-grants", String.valueOf(found.exact().keySet()));

      assertThat(found.tables()).as("두 조각의 테이블을 모두 찾는다").containsExactlyInAnyOrder(first, second);
      assertThat(found.exact()).as("정확 바인드 권한 조회").containsOnlyKeys(first, second);
      assertThat(found.exact().get(second)).contains(account);
      assertThat(found.like()).as("LIKE 권한 조회").containsKeys(first, second);
    } finally {
      for (String t : List.of(first, second)) {
        ownerDdl("DROP TABLE " + TiberoNames.qualified(schema(), t) + " PURGE", new ArrayList<>());
      }
    }
  }

  /**
   * 휴지통(BIN$) 객체 회수 실측(WD-686): PURGE 없이 DROP 해 슬롯 GRANT 를 쥔 채 남은 BIN$ 객체를 테넌트 전체 동기화가 만나면, 실제로 회수해
   * 권한 목록에서 사라지거나, 회수하지 못하면 회수 실패({@code revokeFailed})로 남아야 한다(fail-closed, WD-645·WD-660). 어느
   * 쪽인지는 Tibero 판·설정에 달려 있어 PROBE 줄로 남긴다. 휴지통이 꺼져 있으면 건너뛴다.
   */
  @Test
  void recycleBinObjectGrant_isRevokedOrReportedAsRevokeFailure() {
    List<String> failures = new ArrayList<>();
    String suffix = Long.toString(System.nanoTime() % 1_000_000_000L);
    String raw = "rbs_" + suffix;
    String rawPhysical = TiberoNames.physical(raw);
    String account = DataStoreAccountRole.valueOf("S1").usernameFor(env.tenantId);
    ownerDdl("CREATE TABLE " + TiberoNames.table(schema(), raw) + " (\"ID\" NUMBER)", failures);
    ownerDdl(TiberoDdlSql.grantSelectToSlot(schema(), rawPhysical, account), failures);
    ownerDdl(TiberoDdlSql.dropTableOnly(schema(), raw), failures);
    assertThat(failures).as("준비 DDL").isEmpty();

    String bin =
        connections.withDdl(
            env.tenantId,
            c -> {
              try (PreparedStatement ps =
                  c.prepareStatement(
                      "SELECT OBJECT_NAME FROM USER_RECYCLEBIN WHERE ORIGINAL_NAME = ?")) {
                ps.setString(1, rawPhysical);
                try (ResultSet rs = ps.executeQuery()) {
                  return rs.next() ? rs.getString(1) : null;
                }
              } catch (SQLException e) {
                probe("recyclebin-sync-query", "FAILED code=" + e.getErrorCode());
                return null;
              }
            });
    probe("recyclebin-sync-object", bin == null ? "NONE (recycle bin off)" : bin);
    Assumptions.assumeTrue(bin != null, "휴지통이 꺼져 있어 BIN$ 회수를 확인할 수 없다");
    try {
      var result = sync.syncTenant();
      Map<String, Set<String>> after =
          connections.withDdl(
              env.tenantId, c -> TiberoCatalog.slotSelectGrantsAmong(c, List.of(account), null));
      boolean stillGranted = after.getOrDefault(bin, Set.of()).contains(account);
      boolean reported = result.revokeFailed().getOrDefault(bin, Set.of()).contains(account);
      probe(
          "recyclebin-sync-revoke",
          stillGranted ? (reported ? "NOT REVOKED, REPORTED" : "NOT REVOKED, SILENT") : "REVOKED");
      assertThat(!stillGranted || reported)
          .as("BIN$ 권한은 회수되거나, 남으면 회수 실패로 보고된다(fail-closed)")
          .isTrue();
    } finally {
      ownerDdl("PURGE TABLE " + TiberoNames.quote(bin), new ArrayList<>());
    }
  }
}
