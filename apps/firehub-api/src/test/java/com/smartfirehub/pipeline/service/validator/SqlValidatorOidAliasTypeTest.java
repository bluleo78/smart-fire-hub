package com.smartfirehub.pipeline.service.validator;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.pipeline.exception.UnsafeSqlException;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * #755 — 카탈로그 OID → 이름 변환 경로로 다른 테넌트의 스키마·테이블·롤 이름을 열거하는 우회를 막는지 검증한다.
 *
 * <p><b>왜 필요한가.</b> {@code reg*} 계열 타입({@code regclass}/{@code regnamespace}/{@code regrole} …)의 출력
 * 함수는 OID 를 이름으로 바꿀 때 <b>권한 검사를 하지 않는다</b>. 그래서 {@code SELECT g::regclass::text FROM
 * generate_series(…) g} 한 줄로 USAGE 권한이 없는 다른 테넌트 데이터 스키마의 테이블 이름까지 추측 없이 열거됐다 (#755 재현: dev API
 * 데이터셋·애널리틱스 두 경로 모두). 스키마 화이트리스트는 테이블 참조 노드만 보므로 캐스트의 대상 타입은 아무도 검사하지 않았다.
 *
 * <p><b>검사 축(변이 테스트로 각 축의 비공허성을 따로 증명했다 — 커밋 메시지 참고).</b>
 *
 * <ol>
 *   <li>캐스트·배열 캐스트의 대상 타입({@code ColDataType}) — {@code ::}, {@code CAST(… AS …)}, 스키마 한정, 인용 식별자,
 *       배열 타입 이름({@code _regclass}), {@code []} 접미.
 *   <li>{@code U&"…"} 유니코드 이스케이프 타입 이름 — JSqlParser 는 이를 타입 {@code U} 와 비트 AND 로 잘못 읽는다.
 *   <li>타입 리터럴({@code regclass '…'}) — JSqlParser 는 이를 "컬럼 {@code regclass} + 문자열 별칭"으로 읽는다.
 *   <li>암묵 캐스트 경로 — 허용목록 함수 중 {@code regclass} 인자를 받아 데이터 유래 값을 돌려주는 {@code
 *       _postgis_index_extent}(정수 OID 를 넘기면 PG 가 int→regclass 암묵 캐스트로 받아들인다).
 * </ol>
 *
 * <p>모든 거부 단언은 {@code hasMessageContaining}으로 <b>어느 규칙이 막았는지</b>까지 고정한다 — 파싱 실패 등 다른 이유로 거부돼도 초록이
 * 되는 공허한 테스트를 막기 위해서다(#387 깊이 테스트 전례).
 */
class SqlValidatorOidAliasTypeTest {

  /** 애드혹(데이터셋 SQL 탭·애널리틱스) 정책과 파이프라인 SQL 스텝 정책 둘 다에 같은 규칙이 걸리는지 본다. */
  static Stream<SqlValidator> validators() {
    return Stream.of(SqlValidator.forAdhocDataSchemaQueries(), new SqlValidator());
  }

  @BeforeEach
  void setTenantContext() {
    TenantContext.set(1L);
  }

  @AfterEach
  void clearTenantContext() {
    TenantContext.clear();
  }

  private static final String OID_ALIAS_MSG = "카탈로그 OID 별칭 타입";
  private static final String UNICODE_TYPE_MSG = "유니코드 이스케이프";
  private static final String TYPED_LITERAL_MSG = "문자열 리터럴 별칭";
  private static final String BLOCKED_FN_MSG = "시스템/네트워크 접근 함수는 차단됩니다";
  private static final String NOT_ALLOWED_FN_MSG = "알려진 안전 함수 목록에 없습니다";

  private static Stream<Arguments> cross(List<String> sqls, String expectedMessage) {
    return validators()
        .flatMap(v -> sqls.stream().map(sql -> Arguments.of(v, sql, expectedMessage)));
  }

  // --- 축 1: 캐스트 대상 타입 ---

  static Stream<Arguments> oidAliasCasts() {
    return cross(
        List.of(
            // #755 본문의 열거 쿼리 4종(행 데이터 없이 이름만 노출)
            "SELECT g::regclass::text AS rel FROM generate_series(1, 10) g WHERE g::regclass::text LIKE 'zz%'",
            "SELECT CAST(g AS regclass)::text FROM generate_series(1, 10) g",
            "SELECT g::regnamespace::text FROM generate_series(16384, 400000) g WHERE g::regnamespace::text LIKE 'zz%'",
            "SELECT g::regrole::text FROM generate_series(16384, 400000) g WHERE g::regrole::text LIKE 'zz%'",
            // 표기 변형 — 대소문자·인용·스키마 한정
            "SELECT g::RegClass FROM generate_series(1, 2) g",
            "SELECT g::\"regclass\" FROM generate_series(1, 2) g",
            "SELECT g::pg_catalog.regclass FROM generate_series(1, 2) g",
            "SELECT g::\"pg_catalog\".\"regclass\" FROM generate_series(1, 2) g",
            "SELECT CAST(g AS pg_catalog.regclass) FROM generate_series(1, 2) g",
            // 문자열 리터럴 캐스트(권한 검사는 받지만 같은 규칙으로 함께 막는다)
            "SELECT 'zz_t'::regclass",
            // 배열 경유 — regclass[] 와 내부 배열 타입 이름 _regclass 둘 다 출력이 이름이다
            "SELECT '{1}'::regclass[]",
            "SELECT '{1}'::_regclass",
            "SELECT ARRAY[g]::regrole[] FROM generate_series(1, 2) g",
            // 나머지 OID 별칭 타입 계열
            "SELECT 1::regtype",
            "SELECT 1::regproc",
            "SELECT 1::regprocedure",
            "SELECT 1::regoper",
            "SELECT 1::regoperator",
            "SELECT 1::regconfig",
            "SELECT 1::regdictionary",
            "SELECT 1::regcollation",
            // 절 위치 — 순회 사각이 없는지(#385 C1 전례)
            "SELECT 1 FROM data.t ORDER BY g::regnamespace",
            "SELECT * FROM data.t WHERE id IN (SELECT g::regclass::text FROM generate_series(1, 2) g)",
            "WITH c AS (SELECT g::regclass AS r FROM generate_series(1, 2) g) SELECT * FROM c"),
        OID_ALIAS_MSG);
  }

  @ParameterizedTest
  @MethodSource("oidAliasCasts")
  void rejects_cast_to_oid_alias_type(SqlValidator validator, String sql, String expected) {
    assertThatThrownBy(() -> validator.validate(sql))
        .isInstanceOf(UnsafeSqlException.class)
        .hasMessageContaining(expected);
  }

  // --- 축 2: U& 유니코드 이스케이프 타입 이름 ---

  static Stream<Arguments> unicodeEscapedTypeNames() {
    return cross(
        List.of(
            // PG 는 둘 다 regclass 로 해석한다(psql 실측) — JSqlParser 는 타입 "U" & 컬럼 으로 읽는다.
            "SELECT g::U&\"reg\\0063lass\" FROM generate_series(1, 2) g",
            "SELECT g::pg_catalog.U&\"reg\\0063lass\" FROM generate_series(1, 2) g"),
        UNICODE_TYPE_MSG);
  }

  @ParameterizedTest
  @MethodSource("unicodeEscapedTypeNames")
  void rejects_unicode_escaped_type_name(SqlValidator validator, String sql, String expected) {
    assertThatThrownBy(() -> validator.validate(sql))
        .isInstanceOf(UnsafeSqlException.class)
        .hasMessageContaining(expected);
  }

  // --- 축 3: 타입 리터럴(typename 'literal') ---

  static Stream<Arguments> oidAliasTypedLiterals() {
    return cross(
        List.of(
            // 캐스트 토큰이 전혀 없는 형태 — dev API 에서 이름이 그대로 반환됐다(#755 재현)
            "SELECT regclass '16384'",
            "SELECT regrole '10' FROM data.t",
            "SELECT regclass $$16384$$ FROM data.t",
            "SELECT regclass E'16384' FROM data.t",
            "SELECT pg_catalog.regclass '16384' FROM data.t",
            "SELECT \"regclass\" '16384' FROM data.t",
            // 위치 변형 — 서브쿼리·CTE·INSERT … SELECT·RETURNING
            "SELECT * FROM (SELECT regclass '16384') s",
            "WITH c AS (SELECT regclass '16384') SELECT * FROM c",
            "INSERT INTO data.t (a) SELECT regclass '16384'",
            "UPDATE data.t SET a = 1 RETURNING regclass '16384'"),
        OID_ALIAS_MSG);
  }

  @ParameterizedTest
  @MethodSource("oidAliasTypedLiterals")
  void rejects_oid_alias_typed_literal(SqlValidator validator, String sql, String expected) {
    assertThatThrownBy(() -> validator.validate(sql))
        .isInstanceOf(UnsafeSqlException.class)
        .hasMessageContaining(expected);
  }

  static Stream<Arguments> nonColumnTypedLiterals() {
    return cross(
        // 타입 이름 자리에 컬럼이 아닌 식이 온 형태 — PG 는 U&"…" 를 타입 이름으로 읽어 regclass 리터럴이 된다
        // (psql 실측). 타입 이름을 정적으로 확정할 수 없으므로 문자열 리터럴 별칭 자체를 거부한다.
        List.of("SELECT U&\"reg\\0063lass\" '16384' FROM data.t"), TYPED_LITERAL_MSG);
  }

  @ParameterizedTest
  @MethodSource("nonColumnTypedLiterals")
  void rejects_typed_literal_with_non_column_type(
      SqlValidator validator, String sql, String expected) {
    assertThatThrownBy(() -> validator.validate(sql))
        .isInstanceOf(UnsafeSqlException.class)
        .hasMessageContaining(expected);
  }

  // --- 축 4: 암묵 int→regclass 캐스트를 받는 허용목록 함수 ---

  static Stream<Arguments> implicitRegclassFunctions() {
    return cross(
        List.of(
            // 정수 OID 만으로 남의 테이블 공간 인덱스 범위(데이터 유래 값)를 돌려줬다(#755 조사 중 실측)
            "SELECT _postgis_index_extent(16384, 'geom')",
            "SELECT \"_postgis_index_extent\"(16384, 'geom')"),
        BLOCKED_FN_MSG);
  }

  @ParameterizedTest
  @MethodSource("implicitRegclassFunctions")
  void rejects_function_taking_regclass_implicitly(
      SqlValidator validator, String sql, String expected) {
    assertThatThrownBy(() -> validator.validate(sql))
        .isInstanceOf(UnsafeSqlException.class)
        .hasMessageContaining(expected);
  }

  // --- 이미 막혀 있던 카탈로그 정보 경로 — 회귀 고정(허용목록에 실수로 추가되는 것을 잡는다) ---

  static Stream<Arguments> catalogInfoFunctions() {
    return cross(
        List.of(
            "SELECT to_regclass('x')",
            "SELECT to_regnamespace('x')",
            "SELECT to_regrole('x')",
            "SELECT to_regtype('x')",
            "SELECT regclass(g) FROM generate_series(1, 2) g",
            "SELECT \"regclass\"(g) FROM generate_series(1, 2) g",
            "SELECT pg_get_userbyid(10)",
            "SELECT pg_get_functiondef(1)",
            "SELECT format_type(23, NULL)",
            "SELECT obj_description(1)",
            "SELECT has_table_privilege('x', 'select')",
            "SELECT has_schema_privilege('x', 'usage')",
            "SELECT pg_typeof(1)"),
        NOT_ALLOWED_FN_MSG);
  }

  @ParameterizedTest
  @MethodSource("catalogInfoFunctions")
  void catalog_info_functions_stay_rejected(SqlValidator validator, String sql, String expected) {
    assertThatThrownBy(() -> validator.validate(sql))
        .isInstanceOf(UnsafeSqlException.class)
        .hasMessageContaining(expected);
  }

  static Stream<Arguments> catalogInfoBlocked() {
    // current_setting 은 GUC(테넌트·search_path)를 노출 — 허용목록보다 앞서 deny-list 가 막는다.
    return cross(List.of("SELECT current_setting('search_path')"), BLOCKED_FN_MSG);
  }

  @ParameterizedTest
  @MethodSource("catalogInfoBlocked")
  void current_setting_stays_rejected(SqlValidator validator, String sql, String expected) {
    assertThatThrownBy(() -> validator.validate(sql))
        .isInstanceOf(UnsafeSqlException.class)
        .hasMessageContaining(expected);
  }

  static Stream<Arguments> catalogTables() {
    return cross(
        List.of(
            "SELECT * FROM information_schema.tables",
            "SELECT * FROM pg_catalog.pg_namespace",
            "SELECT * FROM pg_catalog.pg_roles"),
        "허용되지 않는 스키마");
  }

  @ParameterizedTest
  @MethodSource("catalogTables")
  void catalog_tables_stay_rejected(SqlValidator validator, String sql, String expected) {
    assertThatThrownBy(() -> validator.validate(sql))
        .isInstanceOf(UnsafeSqlException.class)
        .hasMessageContaining(expected);
  }

  // --- 양성 대조 — 일반 캐스트·타입 리터럴·별칭은 계속 통과해야 한다 ---

  static Stream<Arguments> legitimateSql() {
    return validators()
        .flatMap(
            v ->
                Stream.of(
                        "SELECT a::int, a::bigint, a::text, a::jsonb FROM data.t",
                        "SELECT a::numeric(10, 2), a::varchar(5) FROM data.t",
                        "SELECT a::timestamp with time zone, a::double precision FROM data.t",
                        "SELECT a::text[], CAST(a AS integer) FROM data.t",
                        "SELECT geom::geometry(Point, 4326) FROM data.t",
                        "SELECT a::oid FROM data.t",
                        // 이름에 reg 가 들어가도 OID 별칭 타입이 아니면 통과(접두 일치만 거부)
                        "SELECT region::text, regexp_replace(a, 'x', 'y') FROM data.t",
                        // 정상 타입 리터럴 — JSqlParser 가 "컬럼+문자열 별칭"으로 읽는 형태도 통과해야 한다
                        "SELECT jsonb '{}', geometry 'POINT(0 0)', date '2020-01-01' FROM data.t",
                        "SELECT to_tsvector('english', a) FROM data.t",
                        // 일반 별칭(미인용·인용·한글)
                        "SELECT a AS b, a c, a AS \"D\", a AS 이름 FROM data.t")
                    .map(sql -> Arguments.of(v, sql)));
  }

  @ParameterizedTest
  @MethodSource("legitimateSql")
  void allows_ordinary_casts_and_aliases(SqlValidator validator, String sql) {
    assertThatCode(() -> validator.validate(sql)).doesNotThrowAnyException();
  }
}
