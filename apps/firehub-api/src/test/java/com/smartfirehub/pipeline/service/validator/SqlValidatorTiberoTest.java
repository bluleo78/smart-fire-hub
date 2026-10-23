package com.smartfirehub.pipeline.service.validator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.pipeline.exception.UnsafeSqlException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tibero 테넌트용 검증기({@link SqlValidator#forTiberoSchema})의 스키마 판정·가드 규칙을 고정한다(WD-77, 스펙 §5.0·§5.4).
 *
 * <p>허용 스키마는 테넌트의 실제 스키마 {@code GENIA_T5} 하나다. 따옴표 없는 식별자는 Tibero 처럼 대문자로 접어 비교하고, 미한정 참조는 거부하되 시스템
 * 의사 테이블 {@code DUAL} 하나만 허용한다. 문자열 안의 SQL 을 실행하는 패키지·함수({@code DBMS_} 등)는 PG 검증 이전에 Tibero 가드가
 * 거부한다.
 */
class SqlValidatorTiberoTest {

  private static final String SCHEMA = "GENIA_T5";

  private final SqlValidator validator = SqlValidator.forTiberoSchema(SCHEMA);

  // ---------------------------------------------------------------------------------------------
  // Step 1 — JSqlParser 실측: Tibero(Oracle) 문법이 이 검증기를 통과하는지 실제로 돌려 본 결과를 그대로 고정한다.
  // 통과하지 못하는 문법은 거부(fail-closed)와 한국어 안내를 단언한다.
  // ---------------------------------------------------------------------------------------------

  @Test
  void probe_rownum_passes() {
    assertThat(validator.validate("SELECT ROWNUM rn, a FROM genia_t5.orders WHERE ROWNUM <= 10"))
        .isEqualTo(SqlValidator.StatementKind.QUERY);
  }

  @Test
  void probe_nvl_passesWithTiberoFunctionAllowlist() {
    // 처음 실측에서는 공용(PG) 허용목록 밖이라 거부됐다. Tibero 모드는 공용 목록 ∪ Oracle/Tibero 고정 목록을 쓴다(WD-77 결정).
    assertThat(validator.validate("SELECT NVL(a,0) FROM genia_t5.orders"))
        .isEqualTo(SqlValidator.StatementKind.QUERY);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "SELECT NVL2(a, 1, 0), TO_NUMBER(b), TO_TIMESTAMP(c), SUBSTR(d, 1, 2), INSTR(d, 'x') FROM genia_t5.t",
        "SELECT LPAD(a, 5, '0'), RPAD(a, 5), INITCAP(a), CONCAT(a, b), TRUNC(n), MOD(n, 2) FROM genia_t5.t",
        "SELECT ADD_MONTHS(d, 1), MONTHS_BETWEEN(d, e), LAST_DAY(d), NEXT_DAY(d, 'MONDAY') FROM genia_t5.t",
        "SELECT REGEXP_SUBSTR(a, 'x'), REGEXP_REPLACE(a, 'x', 'y'), REGEXP_INSTR(a, 'x') FROM genia_t5.t",
        "SELECT a FROM genia_t5.t WHERE REGEXP_LIKE(a, '^x')",
        "SELECT GREATEST(a, b), LEAST(a, b), NULLIF(a, b), SIGN(n), POWER(n, 2), SQRT(n) FROM genia_t5.t",
        "SELECT ROW_NUMBER() OVER (ORDER BY a), RANK() OVER (ORDER BY a), NTILE(4) OVER (ORDER BY a) FROM genia_t5.t",
        "SELECT LAG(a) OVER (ORDER BY a), LEAD(a) OVER (ORDER BY a), FIRST_VALUE(a) OVER (ORDER BY a) FROM genia_t5.t"
      })
  void tiberoFunctionAllowlist_acceptsListedFunctions(String sql) {
    validator.validate(sql);
  }

  @Test
  void tiberoFunctionAllowlist_stillRejectsUnknownFunctions() {
    assertThatThrownBy(() -> validator.validate("SELECT SOME_UNKNOWN_FN(1) FROM DUAL"))
        .isInstanceOf(UnsafeSqlException.class)
        .hasMessage("허용되지 않는 함수 호출: 'SOME_UNKNOWN_FN'. 알려진 안전 함수 목록에 없습니다.");
  }

  @Test
  void pgValidator_stillRejectsNvl() {
    // PG 허용목록은 바뀌지 않는다.
    assertThatThrownBy(
            () -> new SqlValidator("data", false).validate("SELECT NVL(a,0) FROM data.t"))
        .isInstanceOf(UnsafeSqlException.class)
        .hasMessage("허용되지 않는 함수 호출: 'NVL'. 알려진 안전 함수 목록에 없습니다.");
  }

  // ---------------------------------------------------------------------------------------------
  // Tibero 어휘 검사(스펙 §4.3·§5.4) — PG 어휘 검사 대신 Tibero 규칙으로 본다.
  // ---------------------------------------------------------------------------------------------

  @ParameterizedTest
  @ValueSource(
      strings = {
        // Tibero 는 백슬래시를 특수 문자로 보지 않는다(JSqlParser 도 같게 자른다 — 실측)
        "SELECT 'C:\\' FROM DUAL",
        // Tibero 블록 주석은 중첩되지 않는다(JSqlParser 도 같다)
        "/* a /* b */ SELECT 1 FROM DUAL"
      })
  void tiberoLexicalRules_acceptWhatPgCheckWouldReject(String sql) {
    validator.validate(sql);
    assertThatThrownBy(() -> new SqlValidator("data", true).validate(sql))
        .as("PG 검증기는 그대로 거부한다")
        .isInstanceOf(UnsafeSqlException.class);
  }

  @Test
  void tiberoLexicalRules_rejectUnterminatedLiteralOrComment() {
    for (String sql : new String[] {"SELECT 'abc FROM DUAL", "SELECT 1 FROM DUAL /* open"}) {
      assertThatThrownBy(() -> validator.validate(sql))
          .as(sql)
          .isInstanceOf(UnsafeSqlException.class)
          .hasMessage("SQL 의 따옴표 또는 주석이 닫히지 않았습니다.");
    }
  }

  /**
   * JSqlParser 만 특별하게 읽는 표기는 Tibero 규칙에서도 거부한다. {@code //} 는 JSqlParser 에게 줄 주석이지만 Tibero 에게는 나눗셈 +
   * 블록 주석이라 뒤의 테이블 참조를 판정이 못 본다(실측: JSqlParser 결과 테이블 0개). 백틱·{@code E'..'}·{@code $$} 도 JSqlParser
   * 만 인용으로 읽는다.
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "SELECT 0 * 1 //* c */ 1 + (SELECT a FROM genia_t5.hidden) AS v FROM DUAL",
        "SELECT `x` FROM DUAL",
        "SELECT E'\\' /*' AS a FROM DUAL, genia_t5.hidden -- */ AS a FROM DUAL",
        "SELECT $$ x $$ FROM DUAL"
      })
  void tiberoLexicalRules_rejectJsqlParserOnlyNotations(String sql) {
    assertThatThrownBy(() -> validator.validate(sql))
        .as(sql)
        .isInstanceOf(UnsafeSqlException.class)
        .hasMessageContaining("Tibero");
  }

  @Test
  void probe_decode_passes() {
    // PG 에도 decode(text, format) 가 있어 허용목록에 이름이 있다 — Oracle DECODE 도 이름 대조로 통과한다.
    assertThat(validator.validate("SELECT DECODE(a,1,'y','n') FROM genia_t5.orders"))
        .isEqualTo(SqlValidator.StatementKind.QUERY);
  }

  @Test
  void probe_oracleOuterJoinMarker_passes() {
    assertThat(validator.validate("SELECT * FROM genia_t5.a, genia_t5.b WHERE a.id = b.id(+)"))
        .isEqualTo(SqlValidator.StatementKind.QUERY);
  }

  @Test
  void probe_connectBy_passes() {
    assertThat(
            validator.validate(
                "SELECT id FROM genia_t5.t START WITH pid IS NULL CONNECT BY PRIOR id = pid"))
        .isEqualTo(SqlValidator.StatementKind.QUERY);
  }

  @Test
  void probe_sysdateFromDual_passes() {
    assertThat(validator.validate("SELECT SYSDATE FROM DUAL"))
        .isEqualTo(SqlValidator.StatementKind.QUERY);
  }

  @Test
  void probe_alternativeQuoting_isRejectedWithTiberoMessage() {
    // q'[..]' 는 JSqlParser·PG 검사와 경계가 갈리는 표기라 Tibero 전용 안내로 먼저 거부한다.
    assertThatThrownBy(() -> validator.validate("SELECT q'[it's]' FROM DUAL"))
        .isInstanceOf(UnsafeSqlException.class)
        .hasMessage(SqlValidator.TIBERO_ALTERNATIVE_QUOTING_MESSAGE);
  }

  @Test
  void probe_userMerge_isRejected() {
    // Tibero 가드는 MERGE 로 시작하는 문장을 허용하지만, 검증기는 SELECT/INSERT/UPDATE/DELETE 만 받는다(fail-closed).
    assertThatThrownBy(
            () ->
                validator.validate(
                    "MERGE INTO genia_t5.a t USING genia_t5.b s ON (t.id = s.id)"
                        + " WHEN MATCHED THEN UPDATE SET t.v = s.v"))
        .isInstanceOf(UnsafeSqlException.class)
        .hasMessageContaining("허용되지 않는 SQL 형태입니다");
  }

  // ---------------------------------------------------------------------------------------------
  // Step 2 — 스키마 판정
  // ---------------------------------------------------------------------------------------------

  @ParameterizedTest
  @ValueSource(
      strings = {
        "SELECT * FROM genia_t5.orders",
        "SELECT * FROM GENIA_T5.ORDERS",
        "SELECT * FROM \"GENIA_T5\".\"ORDERS\"",
        "SELECT 1 FROM DUAL",
        "SELECT 1 FROM dual",
        "INSERT INTO genia_t5.orders (a) SELECT a FROM genia_t5.src",
        "UPDATE genia_t5.orders SET a = 1",
        "DELETE FROM genia_t5.orders WHERE a = 1"
      })
  void tenantSchemaAndDual_areAllowed(String sql) {
    validator.validate(sql);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "SELECT * FROM data_t5.orders",
        "SELECT * FROM genia_t6.orders",
        "SELECT * FROM orders",
        "SELECT * FROM all_tables",
        "SELECT * FROM sys.dual",
        "SELECT dbms_xmlgen.getxml('select 1 from dual') FROM DUAL"
      })
  void foreignSchemaUnqualifiedAndDangerous_areRejected(String sql) {
    assertThatThrownBy(() -> validator.validate(sql))
        .as(sql)
        .isInstanceOf(UnsafeSqlException.class)
        .hasMessageMatching(".*[가-힣].*");
  }

  /**
   * 스키마 비교는 Tibero 식별자 규칙 그대로다(F6) — 인용 소문자 {@code "genia_t5"} 는 Tibero 에서 {@code GENIA_T5} 와 다른
   * 스키마라 읽기·쓰기·함수 호출 어디서든 거부한다. 대소문자 무관 비교로 통과시키면 판정한 스키마와 실행되는 스키마가 갈린다.
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "SELECT * FROM \"genia_t5\".orders",
        "SELECT * FROM \"Genia_T5\".\"ORDERS\"",
        "DELETE FROM \"genia_t5\".orders WHERE a = 1",
        "SELECT \"genia_t5\".f(1) FROM DUAL",
        "SELECT \"genia_t5\" . f(1) FROM DUAL"
      })
  void quotedNonUppercaseTenantSchema_isAnotherSchema(String sql) {
    assertThatThrownBy(() -> validator.validate(sql))
        .as(sql)
        .isInstanceOf(UnsafeSqlException.class)
        .hasMessageMatching(".*[가-힣].*");
  }

  /** 다른 스키마 함수 호출은 점 앞뒤 공백·주석으로 감춰도 거부한다(F5). */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "SELECT other .fn(a) FROM genia_t5.orders",
        "SELECT other. fn(a) FROM genia_t5.orders",
        "SELECT other /* c */ . fn(a) FROM genia_t5.orders"
      })
  void foreignSchemaFunctionCall_withSpacedDot_isRejected(String sql) {
    assertThatThrownBy(() -> validator.validate(sql))
        .as(sql)
        .isInstanceOf(UnsafeSqlException.class)
        .hasMessage("테넌트 스키마가 아닌 스키마(OTHER)의 함수 호출은 허용되지 않습니다.");
  }

  /** {@code HTP}·{@code HTF} 는 정확히 그 이름만 막는다(F8) — 이름이 그렇게 시작하는 평범한 컬럼은 통과한다. */
  @Test
  void htpHtfPrefixedColumns_pass_butExactPackageNamesAreRejected() {
    validator.validate("SELECT htf_rate, HTPRICE FROM genia_t5.orders");
    assertThatThrownBy(() -> validator.validate("SELECT htf.escape_sc('x') FROM DUAL"))
        .isInstanceOf(UnsafeSqlException.class)
        .hasMessage("Tibero 에서 허용하지 않는 함수·패키지(HTF)가 포함되어 있습니다.");
  }

  @Test
  void unqualifiedTable_messageSuggestsTenantSchema() {
    assertThatThrownBy(() -> validator.validate("SELECT * FROM orders"))
        .isInstanceOf(UnsafeSqlException.class)
        .hasMessageContaining("테이블 참조에 스키마가 없습니다")
        .hasMessageContaining(SCHEMA.toLowerCase());
  }

  @Test
  void foreignSchema_messageNamesTenantSchema() {
    assertThatThrownBy(() -> validator.validate("SELECT * FROM genia_t6.orders"))
        .isInstanceOf(UnsafeSqlException.class)
        .hasMessage("허용되지 않는 스키마 참조: 'genia_t6.orders'. GENIA_T5 스키마만 사용할 수 있습니다.");
  }

  @Test
  void dangerousPackage_isRejectedByTiberoGuardBeforeParsing() {
    assertThatThrownBy(
            () -> validator.validate("SELECT dbms_xmlgen.getxml('select 1 from dual') FROM DUAL"))
        .isInstanceOf(UnsafeSqlException.class)
        .hasMessage("Tibero 에서 허용하지 않는 함수·패키지(DBMS_XMLGEN)가 포함되어 있습니다.");
  }

  @Test
  void sysQualifiedReference_isRejectedByTiberoGuard() {
    assertThatThrownBy(() -> validator.validate("SELECT * FROM sys.dual"))
        .isInstanceOf(UnsafeSqlException.class)
        .hasMessage("Tibero 에서 허용하지 않는 함수·패키지(SYS.)가 포함되어 있습니다.");
  }

  @Test
  void foreignSchemaFunctionCall_isRejectedByTiberoGuard() {
    assertThatThrownBy(() -> validator.validate("SELECT other.fn(a) FROM genia_t5.orders"))
        .isInstanceOf(UnsafeSqlException.class)
        .hasMessage("테넌트 스키마가 아닌 스키마(OTHER)의 함수 호출은 허용되지 않습니다.");
  }

  @Test
  void dualAsWriteTarget_isNotATenantTable_butValidatorLeavesItToGate() {
    // 검증기는 미한정 DUAL 을 테이블 판정에서 빼기만 한다 — 쓰기 대상 DUAL 은 보안 게이트가 거부한다(SqlAccessGuardTest).
    validator.validate("SELECT 1 FROM DUAL WHERE 1 = 1");
  }

  @Test
  void quotedLowercaseDual_isNotTheSystemDual() {
    // Tibero 에서 "dual"(인용 소문자)은 시스템 DUAL 이 아니라 다른 이름이다 — 미한정 참조로 거부한다.
    assertThatThrownBy(() -> validator.validate("SELECT 1 FROM \"dual\""))
        .isInstanceOf(UnsafeSqlException.class)
        .hasMessageContaining("테이블 참조에 스키마가 없습니다");
  }

  /**
   * CTE 이름 대조도 Tibero 접기를 따라야 한다. PG 접기라면 {@code orders} 가 {@code "orders"} CTE 로 보여 판정에서 빠지지만,
   * Tibero 는 {@code orders} 를 {@code ORDERS} 로 접어 CTE 가 아니라 실제 테이블(OWNER 세션의 {@code
   * GENIA_T5.ORDERS})로 해석한다 — 빠뜨리면 스키마 판정·보안 게이트를 우회한다.
   */
  @Test
  void cteNameMatching_followsTiberoFolding() {
    assertThatThrownBy(
            () ->
                validator.validate(
                    "WITH \"orders\" AS (SELECT 1 a FROM DUAL) SELECT * FROM orders"))
        .isInstanceOf(UnsafeSqlException.class)
        .hasMessageContaining("테이블 참조에 스키마가 없습니다");
    // 같은 접기로 맞는 CTE 참조는 실제 테이블이 아니다.
    validator.validate("WITH orders AS (SELECT 1 a FROM DUAL) SELECT * FROM ORDERS");
    validator.validate("WITH \"ORDERS\" AS (SELECT 1 a FROM DUAL) SELECT * FROM orders");
  }

  @Test
  void referencedTables_foldsUnquotedToUppercase() {
    SqlValidator.ReferencedTables refs =
        validator.referencedTables(
            "INSERT INTO \"GENIA_T5\".\"OUT\" (a) SELECT a FROM genia_t5.orders, DUAL");
    assertThat(refs.reads())
        .containsExactlyInAnyOrder(
            new SqlValidator.TableName("GENIA_T5", "ORDERS"),
            new SqlValidator.TableName(null, "DUAL"));
    assertThat(refs.writes()).containsExactly(new SqlValidator.TableName("GENIA_T5", "OUT"));
  }

  @Test
  void pgValidator_keepsPgFolding() {
    // 기존 PG 검증기의 접기 규칙은 그대로다(따옴표 없는 이름은 소문자).
    SqlValidator pg = new SqlValidator("data", false);
    assertThat(pg.referencedTables("SELECT * FROM DATA.ORDERS").reads())
        .containsExactly(new SqlValidator.TableName("data", "orders"));
  }

  /**
   * Tibero 함수 허용목록 = 공용 허용목록 ∪ Oracle/Tibero 전용 이름. 전용 목록에서 공용과 겹치는 이름을 걷어 낸 뒤(델타만 유지)에도 Tibero 모드가
   * 받는 함수 집합이 처음 목록(겹침 포함 58개)으로 만든 것과 정확히 같아야 한다.
   */
  @Test
  void tiberoFunctionAllowlist_isSharedListPlusOracleDeltaOnly() {
    java.util.Set<String> original =
        java.util.Set.of(
            "nvl",
            "nvl2",
            "decode",
            "to_char",
            "to_date",
            "to_number",
            "to_timestamp",
            "substr",
            "instr",
            "length",
            "lpad",
            "rpad",
            "trim",
            "ltrim",
            "rtrim",
            "replace",
            "upper",
            "lower",
            "initcap",
            "concat",
            "trunc",
            "round",
            "mod",
            "abs",
            "ceil",
            "floor",
            "power",
            "sqrt",
            "sign",
            "greatest",
            "least",
            "coalesce",
            "nullif",
            "add_months",
            "months_between",
            "last_day",
            "next_day",
            "extract",
            "listagg",
            "regexp_like",
            "regexp_substr",
            "regexp_replace",
            "regexp_instr",
            "sysdate",
            "systimestamp",
            "count",
            "sum",
            "avg",
            "min",
            "max",
            "row_number",
            "rank",
            "dense_rank",
            "lag",
            "lead",
            "first_value",
            "last_value",
            "ntile");
    java.util.Set<String> expected = new java.util.HashSet<>(SqlValidator.ALLOWED_FUNCTIONS);
    expected.addAll(original);

    assertThat(SqlValidator.TIBERO_ALLOWED_FUNCTIONS).isEqualTo(expected);
    // 전용 목록은 공용 목록에 없는 이름만 둔다 — 겹치면 한쪽만 고쳐도 다른 쪽이 계속 허용해 정책이 갈라진다.
    assertThat(SqlValidator.TIBERO_EXTRA_FUNCTIONS)
        .doesNotContainAnyElementsOf(SqlValidator.ALLOWED_FUNCTIONS);
  }

  /** 스키마 없는 테이블 거부 문구는 Tibero 에서 통하는 소문자 한정 형식을 권한다(따옴표 소문자는 다른 테이블). */
  @Test
  void unqualifiedTable_message_suggestsFoldedSchemaForm() {
    assertThatThrownBy(() -> validator.validate("SELECT * FROM orders"))
        .isInstanceOf(UnsafeSqlException.class)
        .hasMessage("테이블 참조에 스키마가 없습니다: 'orders'. Tibero 저장소에서는 genia_t5.orders 처럼 스키마와 함께 써 주세요.")
        .satisfies(e -> assertThat(e.getMessage()).doesNotContain("\"orders\""));
  }

  /**
   * WD-76 — 대화형 판정(DatasetAccessGuard)이 쓰는 미한정 거부. 대화형 SQL 은 읽기 전용 계정 세션에서 실행돼 미한정 이름이 테넌트 스키마로
   * 해석되지 않는다. 문구는 validate 와 같은 빌더로 만든다(쓴 그대로의 이름). CTE 이름·미한정 DUAL·한정 이름은 통과한다.
   */
  @Test
  void requireQualifiedTables_rejectsUnqualified_withValidateMessage() {
    String ordersMessage =
        "테이블 참조에 스키마가 없습니다: 'orders'. Tibero 저장소에서는 genia_t5.orders 처럼 스키마와 함께 써 주세요.";
    assertThat(SqlValidator.tiberoUnqualifiedTableMessage(SCHEMA, "orders"))
        .isEqualTo(ordersMessage);
    for (String sql :
        java.util.List.of(
            "SELECT * FROM orders",
            "SELECT * FROM genia_t5.a JOIN orders ON 1 = 1",
            "DELETE FROM orders",
            "WITH x AS (SELECT * FROM orders) SELECT * FROM x")) {
      assertThatThrownBy(() -> validator.requireQualifiedTables(sql))
          .as(sql)
          .isInstanceOf(UnsafeSqlException.class)
          .hasMessage(ordersMessage);
    }
    // 인용 이름은 따옴표만 떼고 쓴 그대로 보여 준다(validate 와 같다).
    assertThatThrownBy(() -> validator.requireQualifiedTables("SELECT * FROM \"ORDERS\""))
        .hasMessage(SqlValidator.tiberoUnqualifiedTableMessage(SCHEMA, "ORDERS"));
    for (String sql :
        java.util.List.of(
            "SELECT * FROM genia_t5.orders",
            "SELECT * FROM \"GENIA_T5\".\"ORDERS\"",
            "WITH x AS (SELECT * FROM genia_t5.orders) SELECT * FROM x",
            "SELECT SYSDATE FROM DUAL")) {
      validator.requireQualifiedTables(sql);
    }
  }

  /** 미한정 거부 문구 규칙은 Tibero 검증기 전용이다 — PG 검증기에서 부르면 계약 위반으로 막는다. */
  @Test
  void requireQualifiedTables_isTiberoOnly() {
    assertThatThrownBy(
            () -> SqlValidator.forAdhocDataSchemaQueries().requireQualifiedTables("SELECT 1"))
        .isInstanceOf(IllegalStateException.class);
  }
}
