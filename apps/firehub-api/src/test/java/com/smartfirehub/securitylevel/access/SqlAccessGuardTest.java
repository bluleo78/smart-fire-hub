package com.smartfirehub.securitylevel.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.global.exception.CodedApiException;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.pipeline.exception.UnsafeSqlException;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.SecurityFixture;
import java.util.ArrayList;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/** 스펙 §4.1 requireSql: 참조 테이블 → 데이터셋 매핑 → 판정, fail-closed, 쓰기 하향 금지. */
class SqlAccessGuardTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder encoder;
  @Autowired private DatasetAccessGuard guard;
  @Autowired private ClearanceResolver clearanceResolver;

  private SecurityFixture fx;
  private final List<Long> datasets = new ArrayList<>();
  private final List<Long> users = new ArrayList<>();
  private final List<Long> roles = new ArrayList<>();
  private long creator;
  private String pub;
  private String hidden;
  private String high;

  @BeforeEach
  void setUp() {
    fx = new SecurityFixture(dsl, fixtureTransactionTemplate, encoder);
    creator = fx.createUser("sag_c");
    users.add(creator);
    String m = "sag" + System.nanoTime();
    pub = m + "_pub";
    hidden = m + "_hidden";
    high = m + "_high";
    // 판정만 검사한다(실행 없음) — 데이터셋 메타 행만 있으면 되고 실제 data 테이블은 필요 없다.
    datasets.add(fx.createDatasetRow(pub, fx.levelId("공개"), creator));
    datasets.add(fx.createDatasetRow(hidden, fx.levelId("기밀"), creator));
    datasets.add(fx.createDatasetRow(high, fx.levelId("민감"), creator));
  }

  @AfterEach
  void tearDown() {
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    datasets.forEach(fx::deleteDatasetRow);
    users.forEach(fx::deleteUser);
    roles.forEach(fx::deleteRole);
  }

  /** 주어진 등급 자격의 역할 하나만 가진 사용자. */
  private Clearance userAt(String level) {
    long uid = fx.createUser("sag_u");
    users.add(uid);
    fx.removeUserRole(uid);
    long rid = fx.createRole("sag_r_" + System.nanoTime(), fx.levelId(level), "dataset:read");
    roles.add(rid);
    fx.assignRole(uid, rid);
    return clearanceResolver.resolve(uid);
  }

  private void assertDenied(Clearance c, String sql, String code) {
    assertThatThrownBy(() -> guard.requireSql(c, sql, SqlAccessMode.INTERACTIVE))
        .as(sql)
        .isInstanceOf(CodedApiException.class)
        .extracting(e -> ((CodedApiException) e).code())
        .isEqualTo(code);
  }

  /**
   * Review Focus 3 — 숨김 테이블이 어느 위치(읽기·쓰기 대상·CTE 그림자 DML 대상 포함)에 있든 같은 코드로 거부, 같은 형태의 볼 수 있는 테이블은
   * 허용(양성 대조). {@code %1$s} 가 숨김/허용 테이블 자리다.
   */
  @Test
  void requireSql_deniesHiddenTableInEveryPosition() {
    Clearance c = userAt("민감"); // 기밀(허용 목록 필요)은 못 본다
    String schema = DataSchema.current();
    List<String> templates =
        List.of(
            // 읽기 위치
            "SELECT * FROM %1$s",
            "SELECT * FROM " + schema + ".%1$s",
            "SELECT * FROM " + pub + " JOIN %1$s ON true",
            "SELECT * FROM " + pub + " WHERE EXISTS (SELECT 1 FROM %1$s)",
            "SELECT * FROM " + pub + " WHERE id IN (SELECT id FROM %1$s)",
            "SELECT (SELECT max(a) FROM %1$s) FROM " + pub,
            "SELECT 1 FROM " + pub + " UNION ALL SELECT 1 FROM %1$s",
            "WITH x AS (SELECT * FROM %1$s) SELECT * FROM x",
            "SELECT * FROM (SELECT * FROM %1$s) q",
            "SELECT * FROM " + pub + ", LATERAL (SELECT * FROM %1$s) l",
            // DML 안의 읽기 위치(쓰기 대상은 같은 등급인 high — 하향이 아니므로 양성 대조가 성립)
            "INSERT INTO " + high + " (a) SELECT a FROM %1$s",
            "UPDATE " + high + " SET a = 1 FROM %1$s x",
            "DELETE FROM " + high + " USING %1$s x",
            // 쓰기 대상 위치 — UPDATE/DELETE 대상은 WHERE·RETURNING 으로 읽히므로 VIEW 를 요구한다
            "INSERT INTO %1$s (a) SELECT 1",
            "UPDATE %1$s SET a = 1",
            "UPDATE %1$s SET a = 1 RETURNING *",
            "DELETE FROM %1$s",
            "DELETE FROM " + schema + ".%1$s WHERE a = 1",
            // CTE 와 이름이 같은 DML 대상 — PG 는 DML 대상을 CTE 로 해석하지 않는다
            "WITH %1$s AS (SELECT 1 AS a) DELETE FROM %1$s",
            "WITH %1$s AS (SELECT 1 AS a) UPDATE %1$s SET a = 1",
            "WITH %1$s AS (SELECT 1 AS a) INSERT INTO %1$s (a) SELECT a FROM %1$s");
    for (String tpl : templates) {
      assertDenied(c, tpl.formatted(hidden), "DATASET_SQL_ACCESS_DENIED");
      assertDenied(c, tpl.formatted(hidden.toUpperCase()), "DATASET_SQL_ACCESS_DENIED");
      assertThat(guard.requireSql(c, tpl.formatted(high), SqlAccessMode.INTERACTIVE).allowed())
          .as(tpl)
          .isTrue();
    }
  }

  /** 존재 은닉(스펙 §2.5) — 볼 수 없는 데이터셋·데이터셋이 아닌 data 테이블·없는 테이블은 읽기든 쓰기든 상태·코드·메시지·부가 정보가 모두 같다. */
  @Test
  void unmappedAndMissingTables_areIndistinguishableFromHidden() {
    Clearance c = userAt("민감");
    String missing = "no_such_table_" + System.nanoTime();
    List<String> forms = List.of("SELECT * FROM %s", "DELETE FROM %s", "UPDATE %s SET a = 1");
    for (String form : forms) {
      String hiddenSig = denialSignature(c, form.formatted(hidden));
      assertThat(denialSignature(c, form.formatted("stg_import_does_not_matter")))
          .as(form)
          .isEqualTo(hiddenSig);
      assertThat(denialSignature(c, form.formatted(missing))).as(form).isEqualTo(hiddenSig);
    }
    assertThat(denialSignature(c, "SELECT * FROM " + hidden))
        .isEqualTo(
            "403 FORBIDDEN|DATASET_SQL_ACCESS_DENIED|"
                + DatasetAccessGuard.SQL_ACCESS_DENIED_MESSAGE
                + "|null");
  }

  @Test
  void foreignSchema_isDenied() {
    Clearance c = userAt("기밀");
    assertDenied(c, "SELECT * FROM public.dataset", "DATASET_SQL_ACCESS_DENIED");
    assertDenied(c, "DELETE FROM public.dataset", "DATASET_SQL_ACCESS_DENIED");
  }

  @Test
  void cteShadowingHiddenName_isAllowed() {
    Clearance c = userAt("공개");
    assertThat(
            guard
                .requireSql(
                    c,
                    "WITH " + hidden + " AS (SELECT 1 AS a) SELECT * FROM " + hidden,
                    SqlAccessMode.INTERACTIVE)
                .allowed())
        .isTrue();
  }

  @Test
  void writeDowngrade_isRejectedForInteractive() {
    Clearance c = userAt("민감");
    assertDenied(c, "INSERT INTO " + pub + " (a) SELECT a FROM " + high, "SQL_WRITE_DOWNGRADE");
    assertDenied(c, "UPDATE " + pub + " SET a = h.a FROM " + high + " h", "SQL_WRITE_DOWNGRADE");
    assertThat(
            guard
                .requireSql(
                    c,
                    "INSERT INTO " + high + " (a) SELECT a FROM " + pub,
                    SqlAccessMode.INTERACTIVE)
                .allowed())
        .as("상향 쓰기는 허용")
        .isTrue();
    assertThatThrownBy(
            () ->
                guard.requireSql(
                    c,
                    "INSERT INTO " + pub + " (a) SELECT a FROM " + high,
                    SqlAccessMode.INTERACTIVE))
        .hasMessage("'민감' 데이터를 더 낮은 등급 데이터셋에 쓸 수 없습니다");
  }

  /** PIPELINE_RUN 도 쓰기 하향을 거부하고, PIPELINE_SAVE 는 VIEW 만 본다(판단 사항 4·5). */
  @Test
  void writeDowngrade_pipelineRunRejects_pipelineSaveChecksViewOnly() {
    Clearance c = userAt("민감");
    String downgrade = "INSERT INTO " + pub + " (a) SELECT a FROM " + high;
    assertThatThrownBy(() -> guard.requireSql(c, downgrade, SqlAccessMode.PIPELINE_RUN))
        .isInstanceOf(CodedApiException.class)
        .extracting(e -> ((CodedApiException) e).code())
        .isEqualTo("SQL_WRITE_DOWNGRADE");
    assertThat(guard.requireSql(c, downgrade, SqlAccessMode.PIPELINE_SAVE).allowed()).isTrue();
    assertThat(
            guard
                .checkSql(c, "INSERT INTO " + hidden + " (a) SELECT 1", SqlAccessMode.PIPELINE_SAVE)
                .code())
        .as("PIPELINE_SAVE 도 쓰기 대상 VIEW 는 본다")
        .isEqualTo("DATASET_SQL_ACCESS_DENIED");
  }

  @Test
  void effectiveLevel_isMaxReadRank_andExportFlagReflectsPolicy() {
    Clearance c = userAt("민감");
    SqlAccessResult r =
        guard.requireSql(
            c, "SELECT * FROM " + pub + " JOIN " + high + " ON true", SqlAccessMode.INTERACTIVE);
    assertThat(r.effectiveLevel().name()).isEqualTo("민감");
    assertThat(r.readDatasetIds()).hasSize(2);
    assertThat(r.exportAllowed()).as("민감=PERMISSION, data:export_restricted 없음").isFalse();
    assertThat(guard.requireSql(c, "SELECT 1", SqlAccessMode.INTERACTIVE).effectiveLevel())
        .isNull();
  }

  @Test
  void pipelineSave_ignoresStepRefPlaceholders_only() {
    Clearance c = userAt("공개");
    String schema = DataSchema.current();
    assertThat(
            guard
                .requireSql(
                    c, "SELECT * FROM " + schema + ".step_ref_1", SqlAccessMode.PIPELINE_SAVE)
                .allowed())
        .isTrue();
    assertThatThrownBy(
            () ->
                guard.requireSql(
                    c, "SELECT * FROM " + schema + ".step_ref_1", SqlAccessMode.PIPELINE_RUN))
        .isInstanceOf(CodedApiException.class);
  }

  /**
   * 판정은 받은 문자열 그대로를 파싱한다(주석 정규화 없음). 정규식 주석 제거는 문자열 리터럴 안의 {@code /*}·{@code *}{@code /} 를 주석으로 오인해
   * 숨김 테이블 참조를 지워 버렸다 — 원문을 실행하는 호출자(파이프라인)에서 PG 는 hidden 을 읽는다(리뷰 지적 우회).
   */
  @Test
  void commentLikeLiterals_doNotHideReferences() {
    Clearance c = userAt("민감");
    assertDenied(
        c,
        "SELECT a FROM "
            + pub
            + " WHERE b = '/*' OR EXISTS (SELECT 1 FROM "
            + hidden
            + ") OR b = '*/'",
        "DATASET_SQL_ACCESS_DENIED");
    assertDenied(
        c,
        "SELECT a FROM " + pub + " WHERE b = '--' OR EXISTS (SELECT 1 FROM " + hidden + ")",
        "DATASET_SQL_ACCESS_DENIED");
  }

  /**
   * PG·JSqlParser 어휘가 갈리는 표기(중첩 블록 주석·E 문자열 백슬래시·태그 달러 인용)는 판정 전에 400 으로 거부된다 — 허용으로 새지 않는다. 우회 문자열
   * 목록은 {@code PgLexicalAmbiguityCheckTest.BYPASSES} 와 같다(PG 실측으로 hidden 을 스캔).
   */
  @Test
  void lexicallyAmbiguousSql_isRejectedBeforeJudgement() {
    Clearance c = userAt("민감");
    List<String> bypasses =
        List.of(
            "SELECT 1 AS x FROM pub WHERE 1 = /* /* */ '*/ (SELECT 1 FROM hidden LIMIT 1) --'",
            "SELECT 1 AS x FROM pub WHERE 1 = /* /* */ $$*/ (SELECT 1 FROM hidden LIMIT 1) --$$",
            "SELECT 1 AS x FROM pub WHERE 1 = /* /* */ \"*/ (SELECT 1 FROM hidden LIMIT 1) --\"",
            "SELECT E'\\' /*' AS a FROM pub, hidden -- */ AS a FROM pub",
            "SELECT $a$ ' $a$ AS x FROM pub, hidden --'",
            "SELECT $a$ /* $a$ AS x FROM pub, hidden -- */",
            "SELECT '\\' AS a FROM pub, hidden -- '");
    for (String tpl : bypasses) {
      String sql = tpl.replace("hidden", hidden).replace("pub", pub);
      for (SqlAccessMode mode : SqlAccessMode.values()) {
        assertThatThrownBy(() -> guard.checkSql(c, sql, mode))
            .as(mode + " " + sql)
            .isInstanceOf(UnsafeSqlException.class);
      }
    }
    // 대조군: 태그 없는 $$·'' 이중 따옴표·단일 수준 주석은 그대로 판정된다
    assertThat(
            guard
                .requireSql(
                    c,
                    "SELECT $$it's$$, 'it''s' FROM " + pub + " /* " + hidden + " */ -- x",
                    SqlAccessMode.INTERACTIVE)
                .allowed())
        .isTrue();
  }

  /** 진짜 주석 안의 테이블 이름은 참조가 아니다(양성 대조) — 끝 세미콜론도 허용. */
  @Test
  void realComments_areNotReferences() {
    Clearance c = userAt("민감");
    assertThat(
            guard
                .requireSql(
                    c,
                    "SELECT 1 FROM " + pub + " /* " + hidden + " */ -- " + hidden + "\n;",
                    SqlAccessMode.INTERACTIVE)
                .readDatasetIds())
        .hasSize(1);
  }

  /** 내보내기 허용 양성 대조 — 내보내기 ALLOW 등급(공개)만 읽으면 true. */
  @Test
  void exportAllowed_isTrue_whenOnlyExportAllowLevelsAreRead() {
    Clearance c = userAt("민감");
    assertThat(
            guard.requireSql(c, "SELECT * FROM " + pub, SqlAccessMode.INTERACTIVE).exportAllowed())
        .isTrue();
  }

  @Test
  void checkSql_returnsDenialAsValue() {
    Clearance c = userAt("공개");
    SqlAccessResult r = guard.checkSql(c, "SELECT * FROM " + high, SqlAccessMode.INTERACTIVE);
    assertThat(r.allowed()).isFalse();
    assertThat(r.code()).isEqualTo("DATASET_SQL_ACCESS_DENIED");
  }

  /** 거부 응답에서 클라이언트가 볼 수 있는 모든 것(상태·코드·메시지·부가 정보)을 한 문자열로. 허용되면 "ALLOWED". */
  private String denialSignature(Clearance c, String sql) {
    try {
      guard.requireSql(c, sql, SqlAccessMode.INTERACTIVE);
      return "ALLOWED";
    } catch (CodedApiException e) {
      return e.status() + "|" + e.code() + "|" + e.getMessage() + "|" + e.details();
    }
  }
}
