package com.smartfirehub.global.platformdb.tibero.flyway;

import static com.smartfirehub.support.tibero.TiberoSql.exec;
import static com.smartfirehub.support.tibero.TiberoSql.longs;
import static com.smartfirehub.support.tibero.TiberoSql.queryLong;
import static com.smartfirehub.support.tibero.TiberoSql.strings;
import static com.smartfirehub.support.tibero.TiberoTestEvidence.write;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.support.tibero.TiberoLive;
import com.smartfirehub.support.tibero.TiberoTenantRlsSchema;
import com.smartfirehub.support.tibero.TiberoTenantRlsSchema.RuntimeWork;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Tibero 테넌트 간 함수(WD-492) 실서버 시험 — {@code FH_TENANT} 패키지의 테넌트 간 함수 6개가 런타임 계정 + 테넌트 1 컨텍스트에서도 다른
 * 테넌트 행까지 보고, 끝나면(정상이든 예외든) 우회 표시(BYPASS)를 지우는지 본다.
 *
 * <p>PG 의 테넌트 간 함수는 7개다. 그중 {@code tenant_default_security_level_id} 는 열 DEFAULT 라 Tibero 에서는 보안 등급
 * 트리거(Task 2, {@code TiberoTenantRlsLiveTest})가 맡는다. 그래서 이 패키지에 있는 것은 나머지 6개다.
 *
 * <p>픽스처: 소유자 연결(정책에 걸리지 않음)로 시드 테넌트 1 과 이 클래스의 테넌트 B 에 행을 넣는다. 하네스는 JVM 공용이라 넣은 행은 {@code
 * AfterAll} 에서 FK 역순으로 지운다.
 */
@TiberoLive
class TiberoTenantCrossFunctionsLiveTest {

  /** 이 클래스가 넣는 픽스처 행의 표식. TiberoTenantRlsLiveTest 의 표식(wd492-rls)과 겹치지 않게 한다. */
  private static final String MARK = "wd492-xfn";

  /**
   * Tibero JDBC 의 REF CURSOR 타입 번호(com.tmax.tibero.TbTypes.CURSOR) — 드라이버 클래스에 컴파일 의존하지 않으려고 값을 둔다.
   */
  private static final int TB_CURSOR = -17;

  /** FK 대상 없음(TiberoPlatformDbErrors.FK_MISSING_TARGET 과 같은 값). */
  private static final int FK_MISSING_TARGET = -10008;

  /** 시드(기준선) 테넌트. provision_tenant_defaults 의 복사 원본이기도 하다. */
  private static final long TENANT_1 = 1L;

  private static final String TOKEN_HASH = MARK + "-token-hash";
  private static final String WEBHOOK_ID = MARK + "-webhook-id";
  private static final String TEAM_ID = "WD492XFNTEAM";
  private static final String REVOKED_TEAM_ID = "WD492XFNREVOKED";

  private static TiberoTenantRlsSchema schema;

  /** 소유자가 만든 다른 테넌트(IDENTITY 로 받는다). */
  private static long tenantB;

  /** provision 시험용 새 테넌트(기본값이 하나도 없는 상태로 만든다). */
  private static long tenantP;

  /** created_by·user_id 로 쓰는 픽스처 사용자. tenantP 의 OWNER 멤버이기도 하다. */
  private static long userId;

  @BeforeAll
  static void setUp() throws SQLException {
    schema = TiberoTenantRlsSchema.shared();
    seedRows();
  }

  /**
   * 소유자 연결로 픽스처를 넣는다.
   *
   * <ul>
   *   <li>notification_outbox: 테넌트 1·B 에 PENDING 하나씩, 테넌트 B 에 SENT 하나(상태 거름 확인).
   *   <li>proactive_job_execution: 테넌트 1 PENDING, 테넌트 B RUNNING.
   *   <li>pipeline_trigger: 테넌트 B 의 API 트리거(켜짐)와 WEBHOOK 트리거(꺼짐 — V140 은 꺼진 트리거도 해석한다).
   *   <li>slack_workspace: 테넌트 B 의 살아 있는 워크스페이스와 해지된 워크스페이스.
   *   <li>tenantP + 그 테넌트의 OWNER 멤버십(provision 이 ADMIN 역할을 줄 대상).
   * </ul>
   */
  private static void seedRows() throws SQLException {
    try (Connection c = owner()) {
      exec(c, "INSERT INTO tenant (slug, name) VALUES ('" + MARK + "', '" + MARK + "')");
      tenantB = queryLong(c, "SELECT id FROM tenant WHERE slug = '" + MARK + "'");
      exec(c, "INSERT INTO tenant (slug, name) VALUES ('" + MARK + "-p', '" + MARK + "-p')");
      tenantP = queryLong(c, "SELECT id FROM tenant WHERE slug = '" + MARK + "-p'");
      exec(c, "INSERT INTO \"USER\" (username, password, name) VALUES ('" + MARK + "', 'x', 'x')");
      userId = queryLong(c, "SELECT id FROM \"USER\" WHERE username = '" + MARK + "'");
      exec(
          c,
          "INSERT INTO membership (user_id, tenant_id, role) VALUES ("
              + userId
              + ", "
              + tenantP
              + ", 'OWNER')");

      insertOutbox(c, TENANT_1, "PENDING", "1");
      insertOutbox(c, tenantB, "PENDING", "2");
      insertOutbox(c, tenantB, "SENT", "3");

      for (long t : new long[] {TENANT_1, tenantB}) {
        exec(
            c,
            "INSERT INTO proactive_job (user_id, name, tenant_id) VALUES ("
                + userId
                + ", '"
                + MARK
                + "', "
                + t
                + ")");
        long job =
            queryLong(
                c, "SELECT id FROM proactive_job WHERE name = '" + MARK + "' AND tenant_id = " + t);
        exec(
            c,
            "INSERT INTO proactive_job_execution (job_id, status, tenant_id) VALUES ("
                + job
                + ", '"
                + (t == TENANT_1 ? "PENDING" : "RUNNING")
                + "', "
                + t
                + ")");
      }

      exec(
          c,
          "INSERT INTO pipeline (name, created_by, tenant_id) VALUES ('"
              + MARK
              + "', "
              + userId
              + ", "
              + tenantB
              + ")");
      long pipeline = queryLong(c, "SELECT id FROM pipeline WHERE name = '" + MARK + "'");
      exec(
          c,
          "INSERT INTO pipeline_trigger (pipeline_id, trigger_type, name, is_enabled, config,"
              + " created_by, tenant_id) VALUES ("
              + pipeline
              + ", 'API', '"
              + MARK
              + "-api', 1, '{\"tokenHash\":\""
              + TOKEN_HASH
              + "\"}', "
              + userId
              + ", "
              + tenantB
              + ")");
      exec(
          c,
          "INSERT INTO pipeline_trigger (pipeline_id, trigger_type, name, is_enabled, config,"
              + " created_by, tenant_id) VALUES ("
              + pipeline
              + ", 'WEBHOOK', '"
              + MARK
              + "-hook', 0, '{\"webhookId\":\""
              + WEBHOOK_ID
              + "\"}', "
              + userId
              + ", "
              + tenantB
              + ")");

      exec(
          c,
          "INSERT INTO slack_workspace (team_id, bot_user_id, bot_token_enc, signing_secret_enc,"
              + " tenant_id) VALUES ('"
              + TEAM_ID
              + "', 'U1', 'x', 'x', "
              + tenantB
              + ")");
      exec(
          c,
          "INSERT INTO slack_workspace (team_id, bot_user_id, bot_token_enc, signing_secret_enc,"
              + " revoked_at, tenant_id) VALUES ('"
              + REVOKED_TEAM_ID
              + "', 'U2', 'x', 'x', LOCALTIMESTAMP, "
              + tenantB
              + ")");
    }
  }

  private static void insertOutbox(Connection c, long tenant, String status, String suffix)
      throws SQLException {
    exec(
        c,
        "INSERT INTO notification_outbox (idempotency_key, correlation_id, event_type,"
            + " channel_type, status, tenant_id) VALUES ('"
            + MARK
            + "-"
            + suffix
            + "', '"
            + MARK
            + "', 'TEST', 'EMAIL', '"
            + status
            + "', "
            + tenant
            + ")");
  }

  /** 이 클래스가 넣은 행(provision 이 tenantP 에 만든 기본값 포함)을 FK 역순으로 지운다. */
  @AfterAll
  static void tearDown() throws SQLException {
    if (schema == null || userId == 0) return;
    try (Connection c = owner()) {
      exec(
          c,
          "DELETE FROM slack_workspace WHERE team_id IN ('"
              + TEAM_ID
              + "', '"
              + REVOKED_TEAM_ID
              + "')");
      exec(c, "DELETE FROM pipeline_trigger WHERE created_by = " + userId);
      exec(c, "DELETE FROM pipeline WHERE created_by = " + userId);
      exec(
          c,
          "DELETE FROM proactive_job_execution WHERE job_id IN (SELECT id FROM proactive_job"
              + " WHERE user_id = "
              + userId
              + ")");
      exec(c, "DELETE FROM proactive_job WHERE user_id = " + userId);
      exec(c, "DELETE FROM notification_outbox WHERE correlation_id = '" + MARK + "'");
      for (long t : new long[] {tenantP, tenantB}) {
        exec(c, "DELETE FROM user_role WHERE tenant_id = " + t);
        exec(c, "DELETE FROM role_permission WHERE tenant_id = " + t);
        exec(c, "DELETE FROM role WHERE tenant_id = " + t);
        exec(c, "DELETE FROM report_template WHERE tenant_id = " + t);
        exec(c, "DELETE FROM dataset_category WHERE tenant_id = " + t);
        exec(c, "DELETE FROM security_level WHERE tenant_id = " + t);
        exec(c, "DELETE FROM membership WHERE tenant_id = " + t);
        exec(c, "DELETE FROM tenant WHERE id = " + t);
      }
      exec(c, "DELETE FROM \"USER\" WHERE id = " + userId);
    }
  }

  @Test
  void outbox_tenant_ids_는_두_테넌트를_돌려준다() throws SQLException {
    List<Long> ids =
        withRuntime(
            TENANT_1,
            c -> {
              List<Long> got =
                  cursorLongs(c, "{? = call FH_TENANT.outbox_tenant_ids(?)}", "PENDING");
              assertThat(bypass(c)).as("함수가 끝나면 BYPASS 는 비어 있다").isNull();
              return got;
            });
    assertThat(ids).containsExactlyInAnyOrder(TENANT_1, tenantB);
  }

  /** 쉼표 구분 상태 목록 — 목록에 든 상태만 본다(PG 의 status = ANY(p_statuses)). */
  @Test
  void outbox_tenant_ids_는_쉼표로_이은_상태_목록을_받는다() throws SQLException {
    List<Long> sent =
        withRuntime(
            TENANT_1, c -> cursorLongs(c, "{? = call FH_TENANT.outbox_tenant_ids(?)}", "SENT"));
    List<Long> both =
        withRuntime(
            TENANT_1,
            c -> cursorLongs(c, "{? = call FH_TENANT.outbox_tenant_ids(?)}", "SENT,PENDING"));
    List<Long> none =
        withRuntime(
            TENANT_1,
            c -> cursorLongs(c, "{? = call FH_TENANT.outbox_tenant_ids(?)}", "CANCELLED"));

    assertThat(sent).containsExactly(tenantB);
    assertThat(both).containsExactlyInAnyOrder(TENANT_1, tenantB);
    assertThat(none).isEmpty();
  }

  /**
   * Review Focus 3 — 커서는 BYPASS 를 켠 채 OPEN 하고 지운 뒤 돌려준다. 정책 술어가 OPEN 때가 아니라 FETCH 때 정해진다면 함수가 끝난 뒤
   * 읽는 행에서 다른 테넌트가 빠진다.
   *
   * <p>두 길로 본다. (1) JDBC: 호출 → BYPASS 가 비었음을 먼저 확인 → 그다음 커서를 읽는다. 드라이버가 실행 때 행을 미리 받아 둘 수 있어 이
   * 길만으로는 헛통과할 수 있다. (2) 서버 쪽 PL/SQL 블록: 함수가 돌려준 커서를 BYPASS 를 확인한 뒤 블록 안에서 FETCH 한다 — 드라이버 버퍼가 끼지
   * 않는다.
   */
  @Test
  void 커서를_함수가_끝난_뒤_읽어도_테넌트를_넘는다() throws SQLException {
    withRuntime(
        TENANT_1,
        c -> {
          // (1) JDBC — 실행만 하고, 읽기 전에 BYPASS 를 본다.
          Set<Long> jdbc = new TreeSet<>();
          try (CallableStatement cs = c.prepareCall("{? = call FH_TENANT.outbox_tenant_ids(?)}")) {
            cs.registerOutParameter(1, TB_CURSOR);
            cs.setString(2, "PENDING");
            cs.execute();
            assertThat(bypass(c)).as("커서를 읽기 전 BYPASS").isNull();
            try (ResultSet rs = (ResultSet) cs.getObject(1)) {
              while (rs.next()) jdbc.add(rs.getLong(1));
            }
          }

          // (2) 서버 쪽 — 블록 안에서 BYPASS 를 잡아 둔 뒤 FETCH 한다.
          String block =
              "DECLARE v_cur SYS_REFCURSOR; v_id NUMBER; v_seen VARCHAR2(4000); BEGIN"
                  + " v_cur := FH_TENANT.outbox_tenant_ids('PENDING');"
                  + " ? := SYS_CONTEXT('"
                  + schema.contextName()
                  + "', 'BYPASS');"
                  + " LOOP FETCH v_cur INTO v_id; EXIT WHEN v_cur%NOTFOUND;"
                  + " v_seen := v_seen || ',' || v_id; END LOOP; CLOSE v_cur;"
                  + " ? := v_seen; END;";
          String serverBypass;
          Set<Long> server = new TreeSet<>();
          try (CallableStatement cs = c.prepareCall(block)) {
            cs.registerOutParameter(1, Types.VARCHAR);
            cs.registerOutParameter(2, Types.VARCHAR);
            cs.execute();
            serverBypass = cs.getString(1);
            String seen = cs.getString(2);
            if (seen != null) {
              for (String s : seen.split(",")) if (!s.isEmpty()) server.add(Long.parseLong(s));
            }
          }
          write(
              "늦게 읽는 커서: JDBC "
                  + jdbc
                  + ", 서버 FETCH "
                  + server
                  + "(FETCH 전 BYPASS="
                  + serverBypass
                  + "), 테넌트 B="
                  + tenantB);

          assertThat(jdbc).containsExactlyInAnyOrder(TENANT_1, tenantB);
          assertThat(serverBypass).as("서버 FETCH 전 BYPASS").isNull();
          assertThat(server).containsExactlyInAnyOrder(TENANT_1, tenantB);
          return null;
        });
  }

  @Test
  void proactive_active_execution_tenant_ids_는_두_테넌트를_돌려준다() throws SQLException {
    List<Long> ids =
        withRuntime(
            TENANT_1,
            c ->
                cursorLongs(c, "{? = call FH_TENANT.proactive_active_execution_tenant_ids}", null));
    assertThat(ids).containsExactlyInAnyOrder(TENANT_1, tenantB);
  }

  @Test
  void 토큰_해시로_다른_테넌트의_트리거를_찾는다() throws SQLException {
    String fn = "{? = call FH_TENANT.resolve_trigger_tenant_by_token_hash(?)}";
    assertThat(resolve(fn, TOKEN_HASH)).isEqualTo(tenantB);
    assertThat(resolve(fn, MARK + "-없음")).isNull();
    // 웹훅 id 를 토큰 해시로 찾지 않는다 — 트리거 종류도 거른다.
    assertThat(resolve(fn, WEBHOOK_ID)).isNull();
  }

  /** 테넌트 B 의 웹훅 트리거는 꺼져 있다 — V140 은 꺼진 트리거도 해석한다(켜짐 판정은 호출자가 RLS 아래에서 한다). */
  @Test
  void 웹훅_id_로_다른_테넌트의_꺼진_트리거도_찾는다() throws SQLException {
    String fn = "{? = call FH_TENANT.resolve_trigger_tenant_by_webhook_id(?)}";
    assertThat(resolve(fn, WEBHOOK_ID)).isEqualTo(tenantB);
    assertThat(resolve(fn, MARK + "-없음")).isNull();
  }

  /** 해지된 워크스페이스는 테넌트조차 알려주지 않는다(V106). */
  @Test
  void 팀_id_로_다른_테넌트의_슬랙_워크스페이스를_찾는다() throws SQLException {
    String fn = "{? = call FH_TENANT.resolve_slack_workspace_tenant_by_team_id(?)}";
    assertThat(resolve(fn, TEAM_ID)).isEqualTo(tenantB);
    assertThat(resolve(fn, REVOKED_TEAM_ID)).isNull();
    assertThat(resolve(fn, "WD492XFNNONE")).isNull();
  }

  /**
   * V133 판 의미: 기본 4등급, 테넌트 1 의 시스템 역할·그 권한·공용 양식 복사, 기본 분류 3개, OWNER 멤버에게 ADMIN. ADMIN 은 최상위 등급,
   * 나머지는 기본 등급을 명시로 받는다. 두 번 불러도 늘지 않는다.
   */
  @Test
  void provision_tenant_defaults_는_새_테넌트에_기본값을_만든다() throws SQLException {
    withRuntime(TENANT_1, c -> provision(c, tenantP));
    Counts first = counts(tenantP);
    withRuntime(TENANT_1, c -> provision(c, tenantP));
    Counts second = counts(tenantP);

    try (Connection c = owner()) {
      long sourceRoles =
          queryLong(c, "SELECT COUNT(*) FROM role WHERE tenant_id = 1 AND is_system = 1");
      long sourcePerms =
          queryLong(
              c,
              "SELECT COUNT(*) FROM role_permission rp JOIN role r ON r.id = rp.role_id"
                  + " WHERE r.tenant_id = 1 AND r.is_system = 1");
      long sourceTemplates =
          queryLong(
              c, "SELECT COUNT(*) FROM report_template WHERE tenant_id = 1 AND user_id IS NULL");
      write(
          "provision_tenant_defaults: 새 테넌트 "
              + first
              + " / 원본 역할 "
              + sourceRoles
              + "·권한 "
              + sourcePerms
              + "·양식 "
              + sourceTemplates);

      assertThat(sourceRoles).as("원본 시스템 역할이 있어야 헛통과하지 않는다").isPositive();
      assertThat(sourcePerms).isPositive();
      assertThat(first.roles).isEqualTo(sourceRoles);
      assertThat(first.rolePermissions).isEqualTo(sourcePerms);
      assertThat(first.templates).isEqualTo(sourceTemplates);
      assertThat(first.levels).isEqualTo(4);
      assertThat(first.categories).isEqualTo(3);
      assertThat(
              strings(
                  c,
                  "SELECT name FROM dataset_category WHERE tenant_id = "
                      + tenantP
                      + " ORDER BY name"))
          .containsExactlyInAnyOrder("행정", "운영", "통계");

      // 자격 명시: ADMIN = 이 테넌트의 최상위(4) 등급, USER = 이 테넌트의 기본 등급.
      long top =
          queryLong(
              c, "SELECT id FROM security_level WHERE tenant_id = " + tenantP + " AND rank = 4");
      long dflt =
          queryLong(
              c,
              "SELECT id FROM security_level WHERE tenant_id = " + tenantP + " AND is_default = 1");
      assertThat(
              queryLong(
                  c,
                  "SELECT max_security_level_id FROM role WHERE tenant_id = "
                      + tenantP
                      + " AND name = 'ADMIN'"))
          .isEqualTo(top);
      assertThat(
              queryLong(
                  c,
                  "SELECT max_security_level_id FROM role WHERE tenant_id = "
                      + tenantP
                      + " AND name = 'USER'"))
          .isEqualTo(dflt);

      // OWNER 멤버는 이 테넌트의 ADMIN 역할을 받는다.
      assertThat(
              queryLong(
                  c,
                  "SELECT COUNT(*) FROM user_role ur JOIN role r ON r.id = ur.role_id"
                      + " WHERE ur.tenant_id = "
                      + tenantP
                      + " AND ur.user_id = "
                      + userId
                      + " AND r.name = 'ADMIN'"))
          .isEqualTo(1);
    }
    assertThat(second).as("두 번 불러도 늘지 않는다").isEqualTo(first);
  }

  /**
   * 함수가 끝나면 BYPASS 는 비고 TENANT_ID 는 그대로다 — bypass_off 가 컨텍스트 전체를 지우면 이어지는 조회가 "테넌트 없음(0행)" 으로 걸러져
   * 겉보기엔 맞아 보인다. 그래서 테넌트 1 행이 정확히 보이는지까지 본다.
   */
  @Test
  void 함수가_끝나면_BYPASS_는_비고_일반_조회는_다시_걸러진다() throws SQLException {
    withRuntime(
        TENANT_1,
        c -> {
          assertThat(
                  callNumber(
                      c,
                      "{? = call FH_TENANT.resolve_slack_workspace_tenant_by_team_id(?)}",
                      TEAM_ID))
              .isEqualTo(tenantB);
          cursorLongs(c, "{? = call FH_TENANT.outbox_tenant_ids(?)}", "PENDING");
          assertAfterCall(c);
          return null;
        });
  }

  /** 존재하지 않는 테넌트로 provision → 첫 INSERT 가 FK 위반 → 예외 처리기가 BYPASS 를 지우고 다시 던진다. */
  @Test
  void 함수_안에서_예외가_나도_BYPASS_는_빈다() throws SQLException {
    withRuntime(
        TENANT_1,
        c -> {
          // FK 위반 코드까지 본다 — bypass_on 앞에서 나는 실패(함수 없음 등)로는 이 시험이 통과하지 않게.
          assertThatThrownBy(() -> provision(c, -1L))
              .isInstanceOfSatisfying(
                  SQLException.class,
                  e -> {
                    write("provision 예외 경로 오류 코드 " + e.getErrorCode());
                    assertThat(e.getErrorCode()).isEqualTo(FK_MISSING_TARGET);
                  });
          assertAfterCall(c);
          return null;
        });
  }

  /**
   * BYPASS 를 켤 공개 루틴이 없다 — 패키지 명세의 공개 루틴은 정확히 set_tenant·clear_tenant + 테넌트 간 함수 6개다. 비공개
   * bypass_on/bypass_off 는 본문에만 있어 보이지 않아야 한다.
   */
  @Test
  void 런타임은_BYPASS_를_켤_공개_루틴이_없다() throws SQLException {
    List<String> names = new ArrayList<>();
    try (Connection c = owner();
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT PROCEDURE_NAME FROM ALL_PROCEDURES WHERE OWNER = ? AND OBJECT_NAME ="
                    + " 'FH_TENANT' AND PROCEDURE_NAME IS NOT NULL")) {
      ps.setString(1, schema.owner().name());
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) names.add(rs.getString(1));
      }
    }
    write("FH_TENANT 공개 루틴 " + new TreeSet<>(names));
    assertThat(names)
        .containsExactlyInAnyOrder(
            "SET_TENANT",
            "CLEAR_TENANT",
            "OUTBOX_TENANT_IDS",
            "PROACTIVE_ACTIVE_EXECUTION_TENANT_IDS",
            "RESOLVE_TRIGGER_TENANT_BY_TOKEN_HASH",
            "RESOLVE_TRIGGER_TENANT_BY_WEBHOOK_ID",
            "RESOLVE_SLACK_WORKSPACE_TENANT_BY_TEAM_ID",
            "PROVISION_TENANT_DEFAULTS");
  }

  /** 함수 호출 뒤 세션 상태: BYPASS 는 비고, TENANT_ID 는 1 그대로, 일반 조회는 테넌트 1 행만(비어 있지 않음). */
  private static void assertAfterCall(Connection c) throws SQLException {
    assertThat(bypass(c)).as("BYPASS").isNull();
    assertThat(context(c, "TENANT_ID")).as("TENANT_ID 는 남는다").isEqualTo("1");
    assertThat(
            longs(
                c,
                "SELECT tenant_id FROM notification_outbox WHERE correlation_id = '" + MARK + "'"))
        .containsExactly(TENANT_1);
  }

  /** provision 뒤 그 테넌트의 기본값 수(소유자 연결로 센다). */
  private record Counts(
      long roles, long rolePermissions, long categories, long levels, long templates) {}

  private static Counts counts(long tenant) throws SQLException {
    try (Connection c = owner()) {
      return new Counts(
          queryLong(c, "SELECT COUNT(*) FROM role WHERE tenant_id = " + tenant),
          queryLong(c, "SELECT COUNT(*) FROM role_permission WHERE tenant_id = " + tenant),
          queryLong(c, "SELECT COUNT(*) FROM dataset_category WHERE tenant_id = " + tenant),
          queryLong(c, "SELECT COUNT(*) FROM security_level WHERE tenant_id = " + tenant),
          queryLong(
              c,
              "SELECT COUNT(*) FROM report_template WHERE tenant_id = "
                  + tenant
                  + " AND user_id IS NULL"));
    }
  }

  private static Void provision(Connection c, long tenant) throws SQLException {
    try (CallableStatement cs = c.prepareCall("{call FH_TENANT.provision_tenant_defaults(?)}")) {
      cs.setLong(1, tenant);
      cs.execute();
    }
    return null;
  }

  /** 런타임 + 테넌트 1 컨텍스트에서 해석 함수를 부르고, 끝난 뒤 BYPASS 가 비었는지도 본다. */
  private static Long resolve(String call, String arg) throws SQLException {
    return withRuntime(
        TENANT_1,
        c -> {
          Long v = callNumber(c, call, arg);
          assertThat(bypass(c)).as("함수가 끝나면 BYPASS 는 비어 있다").isNull();
          return v;
        });
  }

  /** NUMBER 를 돌려주는 함수 호출(NULL 은 null). */
  private static Long callNumber(Connection c, String call, String arg) throws SQLException {
    try (CallableStatement cs = c.prepareCall(call)) {
      cs.registerOutParameter(1, Types.NUMERIC);
      cs.setString(2, arg);
      cs.execute();
      long v = cs.getLong(1);
      return cs.wasNull() ? null : v;
    }
  }

  /**
   * SYS_REFCURSOR 를 돌려주는 함수 호출 → 행 목록(중복 그대로 — DISTINCT 를 검증하려고 집합으로 모으지 않는다). arg 가 null 이면 인자 없는
   * 함수다. WD-491 MyBatis 가 열 이름으로 매핑하므로 커서 열이 tenant_id 하나인지도 본다.
   */
  private static List<Long> cursorLongs(Connection c, String call, String arg) throws SQLException {
    List<Long> values = new ArrayList<>();
    try (CallableStatement cs = c.prepareCall(call)) {
      cs.registerOutParameter(1, TB_CURSOR);
      if (arg != null) cs.setString(2, arg);
      cs.execute();
      try (ResultSet rs = (ResultSet) cs.getObject(1)) {
        assertThat(rs.getMetaData().getColumnCount()).as("커서 열 수").isEqualTo(1);
        assertThat(rs.getMetaData().getColumnLabel(1))
            .as("커서 열 이름")
            .isEqualToIgnoringCase("TENANT_ID");
        while (rs.next()) values.add(rs.getLong(1));
      }
    }
    return values;
  }

  /** 공용 하네스의 런타임 커넥션에서 테넌트를 정하고 일을 한 뒤 지운다. */
  private static <T> T withRuntime(long tenant, RuntimeWork<T> work) throws SQLException {
    return schema.withRuntime(tenant, work);
  }

  private static Connection owner() throws SQLException {
    return schema.owner().dataSource().getConnection();
  }

  private static String bypass(Connection c) throws SQLException {
    return context(c, "BYPASS");
  }

  private static String context(Connection c, String attribute) throws SQLException {
    return schema.context(c, attribute);
  }
}
