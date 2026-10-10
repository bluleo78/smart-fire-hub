package com.smartfirehub.pipeline.service;

import static com.smartfirehub.jooq.Tables.DATASET;
import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.service.DatasetService;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.securitylevel.access.ClearanceResolver;
import com.smartfirehub.securitylevel.pythonread.PythonReadGrantSync;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.SecurityFixture;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 로컬 PYTHON 경로의 슬롯 밖 읽기 거부(R5) — 자식 프로세스가 받는 <b>바로 그 환경값</b>({@link
 * PythonScriptExecutor#buildEnvironment} 의 DB_URL — libpq URI 안의 롤·비밀번호·호스트를 그대로 풀어서)으로 JDBC 실접속해
 * 확인한다. URI 안 자격증명으로 실제 로그인이 되는지도 이로써 검증된다(자식에겐 개별 DB_USER·DB_PASSWORD 키가 없다, CR8).
 *
 * <p>왜 자식 프로세스가 아니라 JDBC 인가: 호스트 python3(환경을 비운 자식은 HOME=/tmp 라 사용자 site-packages 를 못 본다)에 psycopg2
 * 가 없어, 스크립트에서 DB 를 읽으면 ImportError 로 실패한다 — 그 실패를 "권한 거부"로 세면 공허하다. 그래서 같은 자격증명으로 Java 에서 접속하고, 슬롯
 * 안 테이블 읽기 <b>성공</b>을 대조군으로 함께 단언한다(로그인 실패 28P01 로 통과하는 공허함 방지). 원장 Ruling 참조.
 */
class PythonScriptExecutorSlotAccessTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder encoder;
  @Autowired private DatasetService datasetService;
  @Autowired private PythonReadGrantSync pythonReadGrantSync;
  @Autowired private ClearanceResolver clearanceResolver;
  @Autowired private PythonScriptExecutor pythonScriptExecutor;

  private SecurityFixture fx;
  private final List<Long> datasets = new ArrayList<>();
  private final List<Long> users = new ArrayList<>();
  private final List<Long> roles = new ArrayList<>();
  private long owner;
  private String m;

  @BeforeEach
  void setUp() {
    fx = new SecurityFixture(dsl, fixtureTransactionTemplate, encoder);
    owner = fx.createUser("pse_owner");
    users.add(owner);
    m = "pse" + System.nanoTime();
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
  }

  /** 슬롯 2(내부) 실행 주체의 자식 환경값: 공개 테이블은 읽고, 민감 테이블은 42501, 쓰기는 42501. */
  @Test
  void slotRoleEnvironment_readsInSlotTable_butGets42501OutsideSlotAndOnWrite() throws Exception {
    String pub = table("pub", "공개");
    String sens = table("sen", "민감");
    long internal = userAt("내부");
    int slot = pythonReadGrantSync.prepareForRun(clearanceResolver.resolve(internal));
    assertThat(slot).isEqualTo(2);

    Map<String, String> env = pythonScriptExecutor.buildEnvironment(DEFAULT_TEST_TENANT_ID, slot);
    assertThat(env).doesNotContainKeys("DB_USER", "DB_PASSWORD");
    // postgresql://role:pw@host:port/db 를 풀어 JDBC 로 — psycopg2.connect(DB_URL) 이 쓰는 것과 같은 자격증명
    URI uri = URI.create(env.get("DB_URL"));
    assertThat(uri.getScheme()).isEqualTo("postgresql");
    String[] userInfo = uri.getUserInfo().split(":", 2);
    String jdbcUrl = "jdbc:postgresql://" + uri.getHost() + ":" + uri.getPort() + uri.getPath();

    try (Connection c = DriverManager.getConnection(jdbcUrl, userInfo[0], userInfo[1]);
        Statement s = c.createStatement()) {
      // 대조군: 슬롯 안 테이블은 읽힌다 — 이것이 없으면 로그인 실패도 "거부"로 통과한다.
      try (var rs = s.executeQuery("SELECT v FROM " + env.get("DB_SCHEMA") + ".\"" + pub + "\"")) {
        assertThat(rs.next()).isTrue();
      }
      SQLException readDenied =
          sqlError(s, "SELECT v FROM " + env.get("DB_SCHEMA") + ".\"" + sens + "\"");
      assertThat((Throwable) readDenied).as("민감 테이블 읽기는 거부돼야 한다").isNotNull();
      assertThat(readDenied.getSQLState()).isEqualTo("42501");
      assertThat(readDenied.getMessage()).contains("permission denied");
      SQLException writeDenied =
          sqlError(s, "INSERT INTO " + env.get("DB_SCHEMA") + ".\"" + pub + "\" (v) VALUES ('w')");
      assertThat((Throwable) writeDenied).as("슬롯 롤은 쓰기 권한이 없어야 한다").isNotNull();
      assertThat(writeDenied.getSQLState()).isEqualTo("42501");
    }
  }

  /** 문장을 실행해 SQLException 을 돌려준다(성공이면 null). */
  private static SQLException sqlError(Statement s, String sql) {
    try {
      s.execute(sql);
      return null;
    } catch (SQLException e) {
      return e;
    }
  }

  /** 물리 테이블 + 행 1개 + 등급 직접 지정(PythonReadGrantSyncTest.table 과 같은 방식). */
  private String table(String suffix, String level) {
    String t = m + "_" + suffix;
    long id =
        datasetService
            .createDataset(
                new CreateDatasetRequest(
                    t,
                    t,
                    null,
                    null,
                    "TABLE",
                    "SOURCE",
                    List.of(
                        new DatasetColumnRequest("v", "v", "TEXT", null, true, false, null, false)),
                    null),
                owner)
            .id();
    datasets.add(id);
    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () -> {
          dsl.update(DATASET)
              .set(DATASET.SECURITY_LEVEL_ID, fx.levelId(level))
              .where(DATASET.ID.eq(id))
              .execute();
          dsl.execute("INSERT INTO " + DataSchema.qualify(t) + " (v) VALUES ('row')");
        });
    return t;
  }

  /** 기본 USER 역할을 떼고 지정 등급 자격의 역할 하나만 가진 사용자. */
  private long userAt(String level) {
    long uid = fx.createUser("pse_u");
    users.add(uid);
    fx.removeUserRole(uid);
    long rid = fx.createRole("pse_r_" + System.nanoTime(), fx.levelId(level), "pipeline:read");
    roles.add(rid);
    fx.assignRole(uid, rid);
    return uid;
  }

  @AfterEach
  void tearDown() {
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    for (Long id : datasets) {
      try {
        datasetService.deleteDataset(id);
      } catch (Exception ignored) {
        // 정리 실패는 다음 정리에 맡긴다(테스트 판정과 무관).
      }
    }
    users.forEach(fx::deleteUser);
    roles.forEach(fx::deleteRole);
  }
}
