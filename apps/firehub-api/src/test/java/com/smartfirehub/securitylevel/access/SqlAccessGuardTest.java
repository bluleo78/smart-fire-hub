package com.smartfirehub.securitylevel.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;

import com.smartfirehub.dataset.storage.DataStoreAccountRole;
import com.smartfirehub.dataset.storage.DataStoreBackend;
import com.smartfirehub.dataset.storage.DataStoreResolver;
import com.smartfirehub.global.exception.CodedApiException;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.pipeline.exception.TiberoUnqualifiedTableException;
import com.smartfirehub.pipeline.exception.UnsafeSqlException;
import com.smartfirehub.pipeline.service.validator.SqlValidator;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** 스펙 §4.1 requireSql: 참조 테이블 → 데이터셋 매핑 → 판정, fail-closed, 쓰기 하향 금지. */
class SqlAccessGuardTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder encoder;
  @Autowired private DatasetAccessGuard guard;
  @Autowired private ClearanceResolver clearanceResolver;

  /**
   * Tibero 테넌트 판정(WD-77) 테스트만 백엔드를 TIBERO 로 바꾼다. 스파이라 다른 테스트에서는 실제 해석(PG)을 그대로 쓰고, 스텁은 테스트마다 초기화된다.
   */
  @MockitoSpyBean private DataStoreResolver dataStoreResolver;

  /** PG 파이프라인 실행 검증기 빈(WD-232 — 판정 파서와 같은 규칙인지 대조). */
  @Autowired private SqlValidator pipelineValidator;

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

  /**
   * S4 — 쓰기 하향 거부는 대화형만. PIPELINE_RUN 은 허용하고 실효 등급·쓰기 대상을 돌려준다(게이트가 자동 상향, 스펙 §4.5), PIPELINE_SAVE
   * 는 VIEW 만 본다(판단 사항 4).
   */
  @Test
  void writeDowngrade_onlyInteractiveRejects() {
    Clearance c = userAt("민감");
    String downgrade = "INSERT INTO " + pub + " (a) SELECT a FROM " + high;
    var run = guard.requireSql(c, downgrade, SqlAccessMode.PIPELINE_RUN);
    assertThat(run.allowed()).isTrue();
    assertThat(run.effectiveLevel().name()).isEqualTo("민감");
    assertThat(run.writeDatasetIds()).hasSize(1);
    assertThat(guard.requireSql(c, downgrade, SqlAccessMode.PIPELINE_SAVE).allowed()).isTrue();
    assertThatThrownBy(() -> guard.requireSql(c, downgrade, SqlAccessMode.INTERACTIVE))
        .isInstanceOf(CodedApiException.class)
        .extracting(e -> ((CodedApiException) e).code())
        .isEqualTo("SQL_WRITE_DOWNGRADE");
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
   * WD-232 — PG 파이프라인 모드(PIPELINE_SAVE·PIPELINE_RUN) 판정 결과 고정. 판정 파서를 실행 검증 파서로 바꾸기 전후로 한정 이름 판정이
   * 같아야 한다(미한정 이름만 달라질 수 있다 — 아래 별도 테스트).
   */
  @Test
  void pgPipelineModes_qualifiedNameJudgement_isPinned() {
    Clearance c = userAt("민감");
    String schema = DataSchema.current();
    for (SqlAccessMode mode : List.of(SqlAccessMode.PIPELINE_SAVE, SqlAccessMode.PIPELINE_RUN)) {
      // 한정 이름 허용 + 읽기 등급 반영
      SqlAccessResult ok = guard.requireSql(c, "SELECT * FROM " + schema + "." + high, mode);
      assertThat(ok.allowed()).as(mode.name()).isTrue();
      assertThat(ok.readDatasetIds()).hasSize(1);
      // 다른 스키마 거부
      assertThat(guard.checkSql(c, "SELECT * FROM public.dataset", mode).code())
          .as(mode.name())
          .isEqualTo("DATASET_SQL_ACCESS_DENIED");
      // 숨김 테이블 거부
      assertThat(guard.checkSql(c, "SELECT * FROM " + schema + "." + hidden, mode).code())
          .as(mode.name())
          .isEqualTo("DATASET_SQL_ACCESS_DENIED");
      // 쓰기 하향은 파이프라인 모드에서 허용(게이트가 자동 상향)
      assertThat(
              guard
                  .checkSql(
                      c,
                      "INSERT INTO "
                          + schema
                          + "."
                          + pub
                          + " (a) SELECT a FROM "
                          + schema
                          + "."
                          + high,
                      mode)
                  .allowed())
          .as(mode.name())
          .isTrue();
    }
  }

  /**
   * WD-232 — PG 파이프라인 모드의 미한정 이름 판정 고정. 판정 파서를 실행 검증기(allowUnqualifiedTables=false)로 바꿔도 {@code
   * referencedTables} 는 미한정 이름을 거부하지 않고 스키마 null 로 돌려주므로 판정 결과는 그대로다(허용 데이터셋이면 허용, 숨김이면 거부). 미한정
   * 이름의 거부는 판정이 아니라 실행 검증기({@code validate})가 맡는다 — 같은 SQL 을 실행 검증기가 거부하는지를 함께 고정한다.
   */
  @Test
  void pgPipelineModes_unqualifiedName_judgementUnchanged_executionValidatorRejects() {
    Clearance c = userAt("민감");
    String visible = "SELECT * FROM " + high;
    String secret = "SELECT * FROM " + hidden;
    assertThatThrownBy(() -> pipelineValidator.validate(visible))
        .isInstanceOf(UnsafeSqlException.class);
    for (SqlAccessMode mode : List.of(SqlAccessMode.PIPELINE_SAVE, SqlAccessMode.PIPELINE_RUN)) {
      assertThat(guard.checkSql(c, visible, mode).allowed()).as(mode.name()).isTrue();
      assertThat(guard.checkSql(c, secret, mode).code())
          .as(mode.name())
          .isEqualTo("DATASET_SQL_ACCESS_DENIED");
    }
    assertThat(guard.requireSql(c, visible, SqlAccessMode.INTERACTIVE).allowed()).isTrue();
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
            "SELECT '\\' AS a FROM pub, hidden -- '",
            // 줄 주석은 \r 에서도 끝난다(PG newline [\n\r])
            "SELECT 1 AS x FROM pub --\rWHERE 1 = /* /* */ '*/ (SELECT 1 FROM hidden LIMIT 1) --'\n",
            "SELECT 1 AS x FROM pub --\r\nWHERE 1 = /* /* */ '*/ (SELECT 1 FROM hidden LIMIT 1) --'\r\n",
            "SELECT 1 AS x FROM pub --\rWHERE 1 = /* /* */ '*/ (SELECT 1 FROM hidden LIMIT 1) --'\r",
            // JSqlParser 만 아는 주석·인용 표기(//, q'[...]', $ 든 $$, 숫자 뒤 $$, 백틱)
            "SELECT 0 * 1 //* c */ 1 + (SELECT a FROM hidden LIMIT 1) AS v",
            "SELECT 1 AS x FROM pub WHERE 1 = 1 //* c */ + (SELECT 1 FROM hidden LIMIT 1)",
            "SELECT q'[ ' AS c, (SELECT a FROM hidden LIMIT 1) AS w -- ]' FROM pub",
            "SELECT $$a$ = '$$ AS c, (SELECT a FROM hidden LIMIT 1) AS d -- '",
            "SELECT 1$$ ' $$ AS c, (SELECT a FROM hidden LIMIT 1) AS d -- '",
            "SELECT `x, (SELECT a FROM hidden LIMIT 1)` FROM pub");
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

  // ---------------------------------------------------------------------------------------------
  // Tibero 테넌트(WD-77, 스펙 §5.0·§5.4) — 스키마는 GENIA_T{id}, 따옴표 없는 이름은 대문자로 접는다.
  // Tibero 테넌트의 SQL 은 모드와 무관하게 Tibero 에서 실행되므로(파이프라인 SQL 단계, 그리고 WD-76 부터 대화형·분석·지표 SQL 도
  // db-executor 경유) 모든 모드를 Tibero 규칙으로 판정한다. 대화형만의 차이(미한정 이름 거부)는 맨 아래 테스트가 고정한다.
  // ---------------------------------------------------------------------------------------------

  /** Tibero 규칙으로 판정하는 모드 — 파이프라인 SQL 단계와 대화형(WD-76) 모두. */
  private static final List<SqlAccessMode> TIBERO_RULE_MODES =
      List.of(SqlAccessMode.PIPELINE_SAVE, SqlAccessMode.PIPELINE_RUN, SqlAccessMode.INTERACTIVE);

  /** 현재(기본 테스트) 테넌트를 Tibero 저장소로 보이게 하고, 그 테넌트의 Tibero 스키마 이름을 돌려준다. */
  private String asTiberoTenant() {
    doReturn(DataStoreBackend.TIBERO).when(dataStoreResolver).currentBackend();
    return DataStoreAccountRole.OWNER.usernameFor(DEFAULT_TEST_TENANT_ID);
  }

  private boolean allowed(Clearance c, String sql, SqlAccessMode mode) {
    return guard.checkSql(c, sql, mode).allowed();
  }

  @Test
  void tibero_tenantSchemaSpellings_areJudgedAgainstDatasets() {
    String schema = asTiberoTenant();
    Clearance c = userAt("민감"); // 기밀(허용 목록 필요)은 못 본다
    List<String> spellings =
        List.of(
            schema.toLowerCase() + ".%s", // genia_t1.orders — 대문자로 접힌다
            schema + ".%S", // GENIA_T1.ORDERS
            "\"" + schema + "\".\"%S\"", // "GENIA_T1"."ORDERS" — 러너 {{#N}} 치환 꼴
            "%s"); // 미한정 — OWNER 세션의 기본 스키마로 해석된다
    for (String spelling : spellings) {
      assertThat(
              allowed(c, "SELECT * FROM " + spelling.formatted(high), SqlAccessMode.PIPELINE_RUN))
          .as(spelling)
          .isTrue();
      assertThat(
              guard
                  .checkSql(
                      c, "SELECT * FROM " + spelling.formatted(hidden), SqlAccessMode.PIPELINE_RUN)
                  .code())
          .as(spelling)
          .isEqualTo("DATASET_SQL_ACCESS_DENIED");
    }
    SqlAccessResult r =
        guard.requireSql(c, "SELECT * FROM " + schema + "." + high, SqlAccessMode.PIPELINE_RUN);
    assertThat(r.readDatasetIds()).hasSize(1);
    assertThat(r.effectiveLevel().name()).isEqualTo("민감");
  }

  @Test
  void tibero_otherSchemasAndForeignCaseSpellings_areDenied() {
    String schema = asTiberoTenant();
    Clearance c = userAt("기밀");
    List<String> denied =
        List.of(
            // PG 데이터 스키마(data/data_t{id})는 Tibero 테넌트의 스키마가 아니다
            "SELECT * FROM " + DataSchema.current() + "." + high,
            // 다른 테넌트 스키마
            "SELECT * FROM genia_t999." + high,
            // 인용 소문자 스키마는 Tibero 에서 다른 스키마다
            "SELECT * FROM \"" + schema.toLowerCase() + "\"." + high,
            // 인용 소문자 테이블은 우리 물리 테이블(대문자)일 수 없다
            "SELECT * FROM " + schema + ".\"" + high + "\"",
            // 사전 뷰는 데이터셋이 아니다
            "SELECT * FROM all_tables");
    for (String sql : denied) {
      assertThat(guard.checkSql(c, sql, SqlAccessMode.PIPELINE_RUN).code())
          .as(sql)
          .isEqualTo("DATASET_SQL_ACCESS_DENIED");
      // 대화형: 스키마 있는 거부 대상은 같은 코드로, 미한정 이름(사전 뷰)은 문법 요구(UnsafeSqlException)로 막힌다
      if (sql.equals("SELECT * FROM all_tables")) {
        assertThatThrownBy(() -> guard.checkSql(c, sql, SqlAccessMode.INTERACTIVE))
            .as("INTERACTIVE: " + sql)
            .isInstanceOf(UnsafeSqlException.class);
      } else {
        assertThat(guard.checkSql(c, sql, SqlAccessMode.INTERACTIVE).code())
            .as("INTERACTIVE: " + sql)
            .isEqualTo("DATASET_SQL_ACCESS_DENIED");
      }
    }
  }

  @Test
  void tibero_unqualifiedDual_isNotATable_butCannotBeWritten() {
    asTiberoTenant();
    Clearance c = userAt("민감");
    SqlAccessResult constant =
        guard.requireSql(c, "SELECT SYSDATE FROM DUAL", SqlAccessMode.PIPELINE_RUN);
    assertThat(constant.allowed()).isTrue();
    assertThat(constant.readDatasetIds()).isEmpty();
    assertThat(constant.effectiveLevel()).isNull();
    assertThat(
            guard
                .requireSql(c, "SELECT * FROM " + high + ", dual", SqlAccessMode.PIPELINE_RUN)
                .readDatasetIds())
        .hasSize(1);
    for (String sql :
        List.of("DELETE FROM DUAL", "INSERT INTO DUAL (dummy) SELECT 'x' FROM DUAL")) {
      assertThat(guard.checkSql(c, sql, SqlAccessMode.PIPELINE_RUN).code())
          .as(sql)
          .isEqualTo("DATASET_SQL_ACCESS_DENIED");
    }
  }

  /**
   * DUAL 건너뛰기는 <b>미한정</b> DUAL 만이다. 테넌트 스키마로 한정한 {@code genia_t1.dual} 은 시스템 DUAL 이 아니라 그 스키마의
   * 테이블이므로 다른 테이블과 같이 데이터셋 매핑으로 판정한다(매핑이 없으면 거부).
   */
  @Test
  void tibero_qualifiedDual_isJudgedLikeAnyTable() {
    String schema = asTiberoTenant();
    Clearance c = userAt("기밀");
    for (String sql :
        List.of(
            "SELECT * FROM " + schema.toLowerCase() + ".dual",
            "SELECT * FROM \"" + schema + "\".\"DUAL\"")) {
      assertThat(guard.checkSql(c, sql, SqlAccessMode.PIPELINE_RUN).code())
          .as(sql)
          .isEqualTo("DATASET_SQL_ACCESS_DENIED");
    }
  }

  /**
   * Tibero 테넌트는 PG 어휘 검사 대신 Tibero 어휘 규칙으로 본다(스펙 §5.4) — PG 검사가 막던 표기 중 Tibero 에 모호하지 않은 것은 통과한다.
   */
  @Test
  void tibero_lexicalRules_replacePgCheck() {
    asTiberoTenant();
    Clearance c = userAt("민감");
    assertThat(allowed(c, "SELECT 'C:\\' FROM " + high, SqlAccessMode.PIPELINE_RUN)).isTrue();
    assertThat(allowed(c, "/* a /* b */ SELECT 1 FROM " + high, SqlAccessMode.PIPELINE_RUN))
        .isTrue();
    // 백슬래시가 있어도 판정은 뒤의 숨김 참조를 본다(JSqlParser·Tibero 가 같게 자른다)
    assertThat(
            guard
                .checkSql(
                    c,
                    "SELECT a FROM "
                        + pub
                        + " WHERE b = 'x\\' OR EXISTS (SELECT 1 FROM "
                        + hidden
                        + ") -- '",
                    SqlAccessMode.PIPELINE_RUN)
                .code())
        .isEqualTo("DATASET_SQL_ACCESS_DENIED");
    // JSqlParser 만 주석으로 읽는 // 는 거부(실측: JSqlParser 는 뒤의 숨김 참조를 못 본다)
    String slashSlash = "SELECT 0 * 1 //* c */ 1 + (SELECT a FROM " + hidden + ") AS v FROM DUAL";
    for (SqlAccessMode mode : TIBERO_RULE_MODES) {
      assertThatThrownBy(() -> guard.checkSql(c, slashSlash, mode))
          .as(mode.name())
          .isInstanceOf(UnsafeSqlException.class);
    }
    assertThatThrownBy(
            () -> guard.checkSql(c, "SELECT 'open FROM DUAL", SqlAccessMode.PIPELINE_RUN))
        .isInstanceOf(UnsafeSqlException.class)
        .hasMessage("SQL 의 따옴표 또는 주석이 닫히지 않았습니다.");
  }

  @Test
  void tibero_pipelineSave_ignoresQuotedUppercaseStepRefPlaceholder_only() {
    String schema = asTiberoTenant();
    Clearance c = userAt("공개");
    String dummy = "SELECT * FROM \"" + schema + "\".\"STEP_REF_1\"";
    assertThat(allowed(c, dummy, SqlAccessMode.PIPELINE_SAVE)).isTrue();
    assertThat(allowed(c, dummy, SqlAccessMode.PIPELINE_RUN)).isFalse();
  }

  @Test
  void tibero_alternativeQuoting_isRejectedWithTiberoMessage() {
    asTiberoTenant();
    Clearance c = userAt("민감");
    String sql = "SELECT q'[ ' AS c, (SELECT a FROM " + hidden + ") AS w -- ]' FROM " + pub;
    for (SqlAccessMode mode : TIBERO_RULE_MODES) {
      assertThatThrownBy(() -> guard.checkSql(c, sql, mode))
          .as(mode.name())
          .isInstanceOf(UnsafeSqlException.class)
          .hasMessage(SqlValidator.TIBERO_ALTERNATIVE_QUOTING_MESSAGE);
    }
  }

  /**
   * CTE 이름 대조도 Tibero 접기를 따른다. PG 접기라면 {@code WITH "hidden" … FROM hidden} 의 뒤쪽 {@code hidden} 이
   * CTE 로 보여 판정에서 빠지지만, Tibero 는 그것을 {@code HIDDEN}(실제 테이블)으로 읽는다 — 우회가 되면 안 된다.
   */
  @Test
  void tibero_cteMatching_followsTiberoFolding() {
    asTiberoTenant();
    Clearance c = userAt("민감");
    assertThat(
            guard
                .checkSql(
                    c,
                    "WITH \"" + hidden + "\" AS (SELECT 1 a FROM DUAL) SELECT * FROM " + hidden,
                    SqlAccessMode.PIPELINE_RUN)
                .code())
        .isEqualTo("DATASET_SQL_ACCESS_DENIED");
    assertThat(
            allowed(
                c,
                "WITH "
                    + hidden
                    + " AS (SELECT 1 a FROM DUAL) SELECT * FROM "
                    + hidden.toUpperCase(),
                SqlAccessMode.PIPELINE_RUN))
        .as("같은 접기로 맞는 CTE 참조는 실제 테이블이 아니다")
        .isTrue();
  }

  /**
   * WD-76 — 대화형·분석·지표 SQL(INTERACTIVE)은 Tibero 테넌트면 db-executor 를 거쳐 Tibero(읽기 전용 계정)에서 실행된다. 그래서
   * 실행될 곳의 규칙, 즉 Tibero 어휘 규칙과 테넌트 스키마({@code GENIA_T{id}})로 판정해야 한다 — PG 규칙으로 보면 판정이 본
   * 스키마(data_t{id})와 실행되는 스키마가 갈라진다. (예전 F4 는 "아직 PG 에서 실행"을 전제로 PG 규칙을 고정했으나, 그 전제가 이 작업으로 바뀌었다.)
   *
   * <p>미한정 이름은 대화형에서는 거부한다 — 읽기 전용 계정 세션의 기본 스키마는 테넌트 스키마가 아니라서, 판정이 본 테이블(GENIA_T{id}.X)과 실행이 해석하는
   * 테이블이 다르다. 이는 등급 거부(403)가 아니라 문법 요구라 Tibero 검증기와 같은 문구의 {@link UnsafeSqlException}(400)이다 —
   * 숨김·허용 테이블에 똑같이 나오므로 존재를 드러내지 않는다. 미한정 DUAL(상수 조회)만 예외다.
   */
  @Test
  void tibero_interactiveMode_isJudgedWithTiberoRules() {
    String tiberoSchema = asTiberoTenant();
    Clearance c = userAt("민감"); // 기밀(hidden)은 못 보고 민감(high)은 본다
    String lower = tiberoSchema.toLowerCase();
    // 테넌트 스키마의 허용 데이터셋은 허용 — 따옴표 없는 소문자·대문자·인용 대문자 모두 같은 테이블이다.
    for (String sql :
        List.of(
            "SELECT * FROM " + lower + "." + high,
            "SELECT * FROM " + tiberoSchema + "." + high.toUpperCase(),
            "SELECT * FROM \"" + tiberoSchema + "\".\"" + high.toUpperCase() + "\"",
            // CTE 이름은 미한정이어도 테이블이 아니다(미한정 거부에 걸리지 않는다).
            "WITH x AS (SELECT * FROM " + lower + "." + high + ") SELECT * FROM x",
            // 미한정 DUAL 은 상수 조회라 데이터셋이 아니다.
            "SELECT 1 FROM DUAL")) {
      assertThat(allowed(c, sql, SqlAccessMode.INTERACTIVE)).as(sql).isTrue();
    }
    SqlAccessResult r =
        guard.requireSql(c, "SELECT * FROM " + lower + "." + high, SqlAccessMode.INTERACTIVE);
    assertThat(r.readDatasetIds()).hasSize(1);
    assertThat(r.effectiveLevel().name()).isEqualTo("민감");
    // Tibero 어휘 규칙이다 — PG 검사가 막던 백슬래시 문자열도 Tibero 에선 모호하지 않아 통과한다.
    assertThat(allowed(c, "SELECT 'C:\\' FROM " + lower + "." + high, SqlAccessMode.INTERACTIVE))
        .isTrue();

    // 거부: 등급 미달 데이터셋, 다른 테넌트 스키마, PG 데이터 스키마 — 모두 구분 불가 같은 코드다(존재 은닉).
    for (String sql :
        List.of(
            "SELECT * FROM " + lower + "." + hidden,
            "SELECT * FROM genia_t999." + high,
            "SELECT * FROM " + DataSchema.current() + "." + high)) {
      assertThat(guard.checkSql(c, sql, SqlAccessMode.INTERACTIVE).code())
          .as(sql)
          .isEqualTo("DATASET_SQL_ACCESS_DENIED");
    }
    // 미한정 이름은 문법 요구 — 숨김(hidden)·허용(high) 구분 없이 Tibero 검증기와 같은 400 문구(쓴 그대로의 이름).
    for (String name : List.of(high, hidden)) {
      String expected =
          "테이블 참조에 스키마가 없습니다: '"
              + name
              + "'. Tibero 저장소에서는 "
              + lower
              + "."
              + name
              + " 처럼 스키마와 함께 써 주세요.";
      for (String sql :
          List.of(
              "SELECT * FROM " + name,
              "SELECT * FROM " + lower + "." + high + " JOIN " + name + " ON 1 = 1")) {
        assertThatThrownBy(() -> guard.checkSql(c, sql, SqlAccessMode.INTERACTIVE))
            .as(sql)
            // WD-81 — 가드를 거쳐도 하위 타입이 보존돼야 지표 SQL 저장 선검사(MetricSqlAccessChecker)가 삼키지 않고 전파한다.
            .isInstanceOf(TiberoUnqualifiedTableException.class)
            .hasMessage(expected);
        // requireSql 경로(저장 선검사가 실제로 부르는 메서드)도 같은 하위 타입을 그대로 던진다.
        assertThatThrownBy(() -> guard.requireSql(c, sql, SqlAccessMode.INTERACTIVE))
            .as(sql)
            .isInstanceOf(TiberoUnqualifiedTableException.class)
            .hasMessage(expected);
      }
    }
    // 파이프라인 모드의 미한정 이름(OWNER 세션 기본 스키마로 해석)은 그대로 허용 — 대화형 전용 규칙이다.
    assertThat(allowed(c, "SELECT * FROM " + high, SqlAccessMode.PIPELINE_RUN)).isTrue();
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
