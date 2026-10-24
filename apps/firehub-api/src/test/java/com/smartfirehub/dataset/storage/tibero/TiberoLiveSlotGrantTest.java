package com.smartfirehub.dataset.storage.tibero;

import static com.smartfirehub.jooq.Tables.DATASET;
import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.apiconnection.service.EncryptionService;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.storage.DataStoreAccountRole;
import com.smartfirehub.dataset.storage.DataStorePasswordGenerator;
import com.smartfirehub.dataset.storage.StoredAccount;
import com.smartfirehub.dataset.storage.TenantDataStoreRepository;
import com.smartfirehub.dataset.storage.external.ExternalAccountProvisioner;
import com.smartfirehub.dataset.storage.external.ExternalStoreConnections;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.securitylevel.pythonread.TiberoPythonReadGrantSync;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.SecurityFixture;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
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
@EnabledIfEnvironmentVariable(named = "TIBERO_TEST_HOST", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TiberoLiveSlotGrantTest extends IntegrationTestBase {

  @Autowired private TiberoPythonReadGrantSync sync;
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
    TiberoTestEnvironment.assumeDriverPresent();
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
            TiberoTestEnvironment.dbaFromEnv().target().jdbcUrl(),
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
}
