package com.smartfirehub.pipeline.service.validator;

import com.smartfirehub.pipeline.exception.UnsafeSqlException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.AnalyticExpression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.AllTableColumns;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.statement.update.Update;
import org.springframework.stereotype.Component;

/**
 * 사용자 작성 SQL 이 도는 여러 호출 문맥(파이프라인 SQL 스텝, 데이터셋 애드혹 쿼리, 애널리틱스)이 공유하는 안전 정책 검증기.
 *
 * <p>허용 규칙:
 *
 * <ul>
 *   <li>정확히 1개의 SQL 스테이트먼트 (trailing 세미콜론 외 추가 금지)
 *   <li>최상위 형태가 SELECT / INSERT / UPDATE / DELETE 중 하나
 *   <li>참조하는 모든 테이블의 스키마가 {@link #allowedSchema}(문맥마다 다를 수 있음). 미한정(스키마 없음) 참조 허용 여부는 {@link
 *       #allowUnqualifiedTables} 참조
 *   <li>위험 함수({@code pg_read_file}, {@code lo_import}, {@code dblink_connect} 등) 호출 금지
 * </ul>
 *
 * <p>이중 방어 — DB 역할({@code pipeline_executor} 등)이 시스템 함수/스키마를 차단하지만, 애플리케이션 레이어에서 조기 차단하여 명확한 에러를
 * 제공한다. (#136, #385)
 *
 * <p><b>순회 방식 — 절을 열거하지 않고 AST 객체 그래프 전체를 리플렉션으로 훑는다(#385 재재리뷰 C1).</b> 예전에는
 * {@code TablesNamesFinder}(절 단위 visitor)를 세 벌 상속해 테이블/함수/INTO 를 각각 검사했는데, 이 방식은
 * {@code ORDER BY}, {@code GROUP BY}, {@code IS [NOT] NULL} 피연산자, {@code FILTER (WHERE ...)},
 * {@code OVER (...)}, {@code RETURNING}, UPDATE/DELETE 의 {@code WHERE} 등 이미 다루는 절 밖의 함수·테이블
 * 참조를 통째로 못 봤다(실측: {@code ORDER BY pg_sleep(5)}, {@code RETURNING pg_sleep(5)},
 * {@code WHERE pg_sleep(5) IS NULL}, {@code ORDER BY (SELECT count(*) FROM public.usr)} 전부 통과 —
 * 마지막 것은 스키마 화이트리스트까지 뚫었다). 절을 하나씩 추가하는 방식은 다음 절에서 또 뚫린다(이게 세 번째
 * 재발이었다) — 그래서 {@link AstNodeCollector}가 절을 전혀 모른 채 파싱된 객체 그래프를 리플렉션 게터로
 * 전수 방문해 모든 {@link Function}/{@link Table}/{@link PlainSelect} 노드를 모은다. 새 절이 생겨도(JSqlParser
 * 버전업 등) 그 절의 필드도 게터를 통해 자동으로 방문되므로 이 사각이 구조적으로 재발하지 않는다.
 *
 * <p><b>원리적 한계 — "AST 가 다 막아 준다"고 오해하지 말 것(#385 최종 리뷰 m5).</b> {@link #requireDataSchemaOnly}의
 * 스키마 화이트리스트는 SQL 문법상 <i>테이블 참조 노드</i>로 나타나는 이름만 볼 수 있다. 인자가 **문자열 리터럴**인 함수 —
 * {@code query_to_xml('SELECT ... FROM public.t', ...)}, {@code nextval('public.some_seq')},
 * {@code 'public.v'::regclass} 등 — 는 그 문자열 안의 테이블 이름을 AST 파서가 볼 수 없다(문자열 리터럴 노드일 뿐 테이블
 * 참조가 아니다). 이런 함수는 **deny-list({@link #BLOCKED_FUNCTIONS})가 유일한 방어**다 — 스키마 화이트리스트로
 * 막을 수 있다고 가정하고 함수를 빠뜨리면 그 함수가 곧 우회 경로가 된다. 새 함수를 검토할 때 "인자가 문자열이고 그 문자열이 SQL
 * 조각으로 재해석되는가"를 반드시 확인하라.
 *
 * <p><b>또 다른 심층 방어 손실 — 한정된 컬럼 참조({@code SELECT public.usr.email FROM data.t})는 이 검증기를
 * 통과한다(#385 재재재리뷰 minor 2, 코드 변경 없이 기록만).</b> {@code public.usr.email} 은 {@link
 * net.sf.jsqlparser.schema.Column}(테이블 한정자 "public.usr" + 컬럼 "email")로 파싱되고, {@code
 * Column.getTable()} 은 위에서 이미 설명한 이유로(진짜 FROM 소스가 아니라 컬럼 한정자) {@link AstNodeCollector}가
 * 의도적으로 walk 하지 않는다. 실측상 우회는 아니다 — {@code data.t} 에 실제로 존재하지 않는 테이블을 컬럼
 * 한정자로 대면 PostgreSQL 이 "missing FROM-clause entry for table" 로 자연스럽게 거부한다(스스로 방어됨).
 * 다만 이 검증기 레벨에서는 걸러지지 않으므로, DB 실행 전에 명확한 에러 메시지를 주는 조기 차단 계층 하나가
 * 빠진 상태다 — 트레이드오프다: 컬럼 한정자를 일반 테이블 참조처럼 취급하면 정상 쿼리(별칭 있는 JOIN 등,
 * {@code allows_cte_referencing_only_data_schema} 류)가 깨진다는 것을 이미 실측했으므로(Column/
 * AllTableColumns 제외 로직 참고) 이 손실을 받아들인다.
 *
 * <p><b>이월 — {@code WITH c AS MATERIALIZED (...)}를 JSqlParser 가 못 파싱한다(#385 코드리뷰 후속,
 * 고치지 않음).</b> PostgreSQL 12+ 의 합법 구문({@code MATERIALIZED}/{@code NOT MATERIALIZED} 힌트)인데
 * 실측(JSqlParser 5.0): {@code ParseException: Encountered unexpected token: "MATERIALIZED"}.
 * 이 검증기를 지나는 사용자가 이 구문을 쓰면 파싱 실패로 400 이 되어(R8, 파싱 실패 폴백 없음) 합법
 * PG 문이 거부된다 — 밴드 범위 밖이라 지금은 고치지 않는다. 밴드 종료 시 별도 이슈로 올린다.
 */
@Slf4j
@Component
public class SqlValidator {

  /** 허용 스키마. 호출 문맥마다 다를 수 있어 인스턴스 필드로 둔다(스레드 안전 — 생성 후 불변). */
  private final String allowedSchema;

  /**
   * 스키마 없는(미한정) 테이블 참조를 허용할지 여부.
   *
   * <p>⚠ 미한정 이름은 호출부가 {@code SET LOCAL search_path} 를 **단일 스키마**로 고정했을 때만 안전하다. 두 스키마(예:
   * {@code 'data', 'public'})를 세우는 호출부는 이 플래그를 켜면 안 된다 — 미한정 이름이 어느 스키마로 해석될지 애플리케이션 레이어에서 알 수 없기
   * 때문이다.
   *
   * <p>⚠⚠ 그 전제에도 예외가 하나 있다: {@code pg_catalog} 는 {@code search_path} 설정과 무관하게 **항상 암묵적으로
   * 가장 먼저 검색된다**(PostgreSQL 고정 동작). 즉 {@code search_path = 'data'} 로만 좁혀도 미한정 {@code
   * pg_tables}, {@code pg_roles} 같은 카탈로그 뷰는 여전히 해석된다 — 다른 스키마의 테이블 이름, 롤 목록 등이 새는 경로다
   * (#385 Task 3 실측: {@code SELECT * FROM pg_tables} 가 172행을 반환, 그중 104건이 {@code data}/{@code
   * pg_catalog}/{@code information_schema} 밖 스키마). {@link #requireDataSchemaOnly}가 미한정 이름의 {@code
   * pg_} 접두어를 별도로 거부하는 이유가 이것이다 — 지우면 이 구멍이 다시 열린다.
   */
  private final boolean allowUnqualifiedTables;

  /** 파이프라인 SQL 스텝 등 기존 호출부를 위한 기본 생성자. 기존 정책({@code allowedSchema="data"}, 미한정 거부)을 그대로 유지한다. */
  public SqlValidator() {
    this("data", false);
  }

  /**
   * 허용 스키마와 미한정 테이블 허용 여부를 호출 문맥에서 주입받는 생성자.
   *
   * @param allowedSchema 참조를 허용할 유일한 스키마명
   * @param allowUnqualifiedTables 스키마 없는 테이블 참조 허용 여부 — 안전 전제는 {@link #allowUnqualifiedTables} 참조
   */
  public SqlValidator(String allowedSchema, boolean allowUnqualifiedTables) {
    this.allowedSchema = allowedSchema;
    this.allowUnqualifiedTables = allowUnqualifiedTables;
  }

  /**
   * 데이터셋 애드혹 쿼리·애널리틱스 직접 실행 경로 전용 인스턴스를 만든다. (#385 코드리뷰 R1)
   *
   * <p>두 호출부({@code DataTableQueryService}, {@code AnalyticsQueryExecutionService})가 각자
   * {@code new SqlValidator("data", true)}를 필드로 만들면서 "스프링 빈은 파이프라인 정책(무인자 =
   * {@code allowedSchema="data"}, 미한정 거부)이라 재사용할 수 없다"는 거의 같은 근거 주석을 따로 적어
   * 뒀다(이 저장소의 복붙 재발 패턴) — 근거를 이 팩터리 한 곳에만 남기고 호출부는 이 메서드만 부르게
   * 한다. {@code data} 스키마만 허용하고 미한정(스키마 없음) 테이블·시퀀스 참조도 허용하는 정책이다.
   * 검증기는 불변(생성자만 정책을 갖고 이후 상태가 없음)이라 각 호출부가 필드로 캐시해 재사용해도
   * 스레드 안전하다.
   *
   * <p>파이프라인 SQL 스텝은 이 팩터리를 쓰지 않는다 — 스프링이 관리하는 무인자 빈({@code
   * allowedSchema="data"}, {@code allowUnqualifiedTables=false})이 그 경로의 정책이고, 애드혹/애널리틱스와
   * 정책이 다르므로(미한정 허용 여부) 공유 빈을 쓰면 안 된다.
   */
  public static SqlValidator forAdhocDataSchemaQueries() {
    return new SqlValidator("data", true);
  }

  /**
   * SELECT 본문 등에서 호출 가능한 위험 함수 deny-list.
   *
   * <p>AST 통과(SELECT 형태)이지만 실제로는 파일/네트워크/DB 카탈로그를 노출하는 함수들. DB 역할이 EXECUTE 권한을 갖지 않더라도 애플리케이션 레이어에서
   * 조기 차단하여 명확한 에러를 제공한다.
   *
   * <p>{@code public} 스키마의 {@code SECURITY DEFINER} 함수 5개(RLS 를 의도적으로 우회하도록 설계된 함수 —
   * {@code TenantSchemaConformanceTest.KNOWN_SECURITY_DEFINER_FUNCTIONS} 참고)도 여기 포함한다. 이 함수들은
   * {@code PUBLIC EXECUTE} 권한을 갖고 있어(정상적인 permitAll 호출부를 위한 것) 미한정 호출이 실제로 실행된다(#385 Task 4
   * 리뷰어 실측). {@code provision_tenant_defaults} 는 변경 함수라 위험이 특히 크다. deny-list 는 정확하고 값싸지만
   * "새 definer 함수가 추가돼도 아무것도 빨개지지 않는" 사각을 못 막으므로, {@code
   * SqlValidatorSecurityDefinerConformanceTest}가 {@code pg_proc}에서 이 5개를 전수 발견해 이 목록(또는 문서화된
   * 허용목록)에 있는지 구조적으로 검사한다 — 이 목록을 손으로만 믿지 마라.
   *
   * <p>PostGIS 함수(예: {@code ST_AsGeoJSON})는 {@code SECURITY DEFINER}가 아니고 미한정 호출이 정상 사용례이므로
   * 여기 포함하지 않는다 — {@code public} 함수 전면 차단은 하지 않는다.
   *
   * <p>{@code pg_sleep} 도 차단한다(#385 Task 2 실측: dev {@code saved_query} 에 {@code SELECT
   * pg_sleep(5)}가 저장돼 있었다 — 펜테스트 흔적으로 보인다). 데이터셋 애드혹/애널리틱스 두 경로 모두 {@code SET LOCAL
   * statement_timeout='30s'}가 걸려 있어 쿼리 **지속 시간**은 묶이지만, {@code pg_sleep} 은 그 시간 동안 **커넥션을
   * 점유**한다 — 커넥션 풀 고갈(DoS)로 이어질 수 있고 쿼리 UI 에 정당한 사용례가 없다. deny-list 는 파이프라인/애드혹/애널리틱스가
   * 공유하므로 파이프라인 SQL 스텝에도 함께 적용되는 것을 알고 받아들인 판정이다(dev 이력에 파이프라인에서 의도적 지연을 쓰는 사용례는
   * 없었다).
   *
   * <p>{@code query_to_xml} 계열(및 형제 {@code table_to_xml}/{@code database_to_xml}, 각각의
   * {@code _and_xmlschema}/{@code _xmlschema} 변형, {@code pg_get_viewdef})도 최종 리뷰에서 추가했다 —
   * **가장 심각한 우회였다**: 이 함수들은 인자로 **SQL 문자열 리터럴을 받아 그 안에서 별도 쿼리를 실행**한다. 그 문자열
   * 안의 테이블 참조는 {@link AstNodeCollector}가 아무리 절을 전수 방문해도 절대 못 본다 — 순회 사각이 아니라
   * AST 상 문자열 리터럴일 뿐 테이블 참조 노드가 아니기 때문이다(원리적 한계, 클래스 상단 문서 참고). 실측: {@code
   * search_path='data'} 로 좁힌 애드혹
   * 경로에서도 {@code SELECT query_to_xml('SELECT count(*) c FROM public."user"', true, false, '')} 가
   * 성공했다(`<c>7777</c>` 반환) — 스키마 화이트리스트를 완전히 무력화한다. DML 은 "non-volatile function"
   * 오류로 자체 차단되지만 **임의 SELECT 전면 열람**은 열려 있었다. {@code pg_get_viewdef}(뷰 정의 노출)도 같은 성격 —
   * 인자가 이름/oid 든 문자열이든 그 안에서 별도 조회를 도는 함수 계열은 원리적으로 AST 화이트리스트를 우회하므로 여기서만 막을 수
   * 있다({@link #unqualifiedTableNames} 근처 클래스 상단 주석의 "원리적 한계" 참고). 이 함수들은 파이프라인 SQL
   * 스텝에도 정당한 용도가 없다(테이블 값을 조회하는 것이 목적이면 일반 SELECT 로 충분) — 공유 deny-list 를 그대로 적용한다.
   *
   * <p>{@code resolve_trigger_tenant_by_token_hash} 등 정의자 함수 5개와 {@code query_to_xml} 계열은
   * 파이프라인 SQL 스텝 경로(무인자 {@code SqlValidator}, strict 모드)에도 함께 걸린다 — deny-list 를 공유하기
   * 때문이다. 파이프라인 replay 코퍼스({@code StoredUserSqlReplayTest})는 애드혹/애널리틱스 저장 쿼리만 덮고 파이프라인
   * SQL 스텝 이력은 덮지 않으므로, 이 함수들이 기존 파이프라인 스텝에서 쓰인 적이 있는지는 이 초록 스위트로 보장되지 않는다.
   * dev 파이프라인 SQL 스텝에 이 함수들을 호출하는 정당한 용도가 있다는 근거는 없었고(카탈로그 열람·정의자 함수 호출은 ETL
   * 변환 로직에 필요할 이유가 없다), 있다면 차단이 옳은 방향이라 판단했다 — 근거가 이 판단을 뒤집으면 파이프라인 전용
   * 예외를 검증기에 추가해야 한다(현재는 그런 신호가 없다).
   */
  static final Set<String> BLOCKED_FUNCTIONS =
      Set.of(
          "pg_read_file",
          "pg_read_binary_file",
          "pg_ls_dir",
          "pg_stat_file",
          "pg_sleep",
          "lo_import",
          "lo_export",
          "dblink",
          "dblink_connect",
          "dblink_connect_u",
          "dblink_exec",
          "current_setting",
          "set_config",
          "resolve_trigger_tenant_by_token_hash",
          "resolve_trigger_tenant_by_webhook_id",
          "provision_tenant_defaults",
          "resolve_slack_workspace_tenant_by_team_id",
          "outbox_tenant_ids",
          "query_to_xml",
          "query_to_xmlschema",
          "query_to_xml_and_xmlschema",
          "table_to_xml",
          "table_to_xmlschema",
          "table_to_xml_and_xmlschema",
          "database_to_xml",
          "database_to_xmlschema",
          "database_to_xml_and_xmlschema",
          "schema_to_xml",
          "schema_to_xmlschema",
          "schema_to_xml_and_xmlschema",
          "pg_get_viewdef");

  /**
   * 표준 SQL 내장 함수(집계/윈도/수학/문자열/날짜·시간/JSON/배열) — 손으로 큐레이션(아래 {@link
   * #ALLOWED_FUNCTIONS} 주석 참고). 파일/네트워크/GUC/세션/역할/카탈로그 정보를 노출하는 함수는 의도적으로
   * 제외했다({@code current_setting}, {@code version}, {@code has_table_privilege}, {@code
   * pg_typeof}, {@code obj_description} 등 — 이 중 어느 것도 애널리틱스/애드혹 쿼리에 정당한 필요가 없다).
   * {@code current_user}/{@code session_user}/{@code current_catalog}/{@code current_schema}는
   * 여기서 "제외"한 게 아니라 애초에 함수가 아니라서 이 목록과 무관하다 — {@link
   * #requireNoReservedPseudoColumns}가 별도로 막는다(#385 코드리뷰 m4, 예전엔 이 문서가 그 넷도
   * "허용목록에서 제외한 함수"라고 잘못 적어 뒀었다).
   *
   * <p><b>필드 선언 순서 주의</b> — 이 필드와 {@link #POSTGIS_SAFE_FUNCTIONS}는 {@link #ALLOWED_FUNCTIONS}
   * 보다 <b>먼저</b> 선언돼야 한다. Java 는 static 필드를 선언 순서대로 초기화하므로, 반대 순서였을 때
   * {@code buildAllowedFunctions()}가 아직 초기화 안 된 {@code null} 을 읽어 {@code
   * NullPointerException}으로 전체 Spring 컨텍스트 기동이 깨졌다(실측 — 최초 구현에서 실제로 발생, 전체
   * 스위트가 컨텍스트 로드 실패로 대량 적색화됨).
   */
  private static final Set<String> SQL_STANDARD_FUNCTIONS =
      Set.of(
          // 집계
          "count", "sum", "avg", "min", "max", "array_agg", "string_agg", "json_agg", "jsonb_agg",
          "json_object_agg", "jsonb_object_agg", "bool_and", "bool_or", "every", "bit_and",
          "bit_or", "variance", "var_pop", "var_samp", "stddev", "stddev_pop", "stddev_samp",
          "mode", "percentile_cont", "percentile_disc", "corr", "covar_pop", "covar_samp",
          // 윈도
          "row_number", "rank", "dense_rank", "percent_rank", "cume_dist", "ntile", "lag", "lead",
          "first_value", "last_value", "nth_value",
          // 수학
          "abs", "ceil", "ceiling", "floor", "round", "trunc", "sign", "power", "sqrt", "cbrt",
          "exp", "ln", "log", "log10", "mod", "pi", "radians", "degrees", "sin", "cos", "tan",
          "asin", "acos", "atan", "atan2", "sinh", "cosh", "tanh", "asinh", "acosh", "atanh",
          "gcd", "lcm", "factorial", "div", "width_bucket", "random", "greatest", "least",
          // NULL/조건
          "coalesce", "nullif",
          // 문자열
          "upper", "lower", "initcap", "length", "char_length", "character_length", "bit_length",
          "octet_length", "trim", "ltrim", "rtrim", "btrim", "substring", "substr", "replace",
          "translate", "concat", "concat_ws", "position", "strpos", "left", "right", "lpad",
          "rpad", "split_part", "regexp_replace", "regexp_match", "regexp_matches",
          "regexp_split_to_array", "regexp_split_to_table", "regexp_count", "regexp_instr",
          "regexp_like", "regexp_substr", "format", "repeat", "reverse", "quote_ident",
          "quote_literal", "quote_nullable", "ascii", "chr", "md5", "starts_with", "unaccent",
          "overlay",
          // 날짜/시간
          "now", "current_date", "current_time", "current_timestamp", "localtime",
          "localtimestamp", "date_trunc", "date_part", "to_char", "to_date", "to_timestamp",
          "age", "make_date", "make_time", "make_timestamp", "make_timestamptz", "make_interval",
          "justify_days", "justify_hours", "justify_interval", "timezone",
          // 형변환
          "to_number",
          // JSON/XML
          "json_build_object", "jsonb_build_object", "json_build_array", "jsonb_build_array",
          "json_extract_path", "jsonb_extract_path", "json_extract_path_text",
          "jsonb_extract_path_text", "json_array_elements", "jsonb_array_elements",
          "json_array_elements_text", "jsonb_array_elements_text", "json_each", "jsonb_each",
          "json_each_text", "jsonb_each_text", "json_object_keys", "jsonb_object_keys",
          "json_typeof", "jsonb_typeof", "row_to_json", "to_json", "to_jsonb",
          "json_strip_nulls", "jsonb_strip_nulls", "jsonb_set", "jsonb_path_exists",
          "jsonb_path_query", "jsonb_path_query_array", "jsonb_path_query_first",
          // xmlagg 는 집계 함수라 카탈로그 열람 함수(query_to_xml 등, BLOCKED_FUNCTIONS)와 다르다 — 인자로
          // 받은 XML 값을 그대로 연결할 뿐 SQL 을 재해석하지 않는다.
          "xmlagg",
          // 배열 — generate_series 도 여기: 정수/날짜 범위를 만들 뿐 카탈로그·시스템 접근이 없다(애널리틱스
          // 상용 함수, 재재리뷰 M2 실측으로 오탐 확인 후 추가).
          "array_length", "array_upper", "array_lower", "unnest", "array_to_string",
          "string_to_array", "array_append", "array_prepend", "array_cat", "array_remove",
          "array_replace", "array_position", "array_positions", "array_dims", "array_ndims",
          "cardinality", "num_nonnulls", "num_nulls", "generate_series");

  /**
   * {@code postgis}/{@code postgis_topology} 확장 소유 함수 중 부작용 없는(provolatile ≠ volatile) 함수.
   * {@code src/main/resources/sql-validator/postgis-safe-functions.txt}에서 로드 — 그 파일의 생성 쿼리와
   * 이름 단위(오버로드 아님) 필터링 근거는 파일 헤더 주석 참고. 클래스로더 리소스를 못 찾으면(패키징 오류 등)
   * 조용히 빈 집합으로 fail-open 되는 대신 예외를 던져 애플리케이션 기동 시점에 드러나게 한다.
   *
   * <p><b>낡음 감지(#385 재재리뷰 M3)</b> — 이 리소스 파일은 특정 시점의 카탈로그 스냅샷이다. PostGIS 가
   * 업그레이드되면 함수가 추가/제거될 수 있는데, 이 필드 자체는 그 변화를 감지하지 못한다(방향은 fail-closed —
   * 새로 생긴 정상 함수가 조용히 거부되는 유지보수 갭이지, 위험 함수가 새어 들어오는 보안 구멍은 아니다).
   * {@code PostgisSafeFunctionsConformanceTest}가 이 파일과 라이브 카탈로그를 양방향 대조해 드리프트를
   * 구조적으로 잡는다(`TenantSchemaConformanceTest` 선례). package-private 인 이유는 그 테스트가 같은
   * 패키지에서 직접 참조하기 위해서다.
   */
  static final Set<String> POSTGIS_SAFE_FUNCTIONS = loadPostgisSafeFunctions();

  /**
   * 함수 허용목록의 정본 — 그 이하 함수는 전부 거부한다(fail-closed). (#385 재재리뷰 — deny-list 전면 폐기가 아니라 정본을
   * 허용목록으로 바꾼다.)
   *
   * <p><b>왜 deny-list 를 정본으로 못 쓰는가.</b> 재재리뷰에서 이름 대조 자체를 무너뜨리는 우회 2건이 나왔다:
   *
   * <ol>
   *   <li>{@code schema_to_xml('public', true, false, '')} — {@code public} 스키마 전체를 XML 로 덤프한다
   *       (실측: 13,929,550자, {@code public."user"} 의 email·password 포함). 테이블 참조가 0개라 {@link
   *       #requireDataSchemaOnly}가 아예 관여하지 않는다 — strict 모드에서도 통과했다.
   *   <li>{@code U&"pg_sl\0065ep"(5)}(유니코드 이스케이프 식별자) — psql 실측으로 {@code
   *       U&"current_sett\0069ng"(...)}, {@code U&"query_to_x\006Dl"(...)} 이 실제로 실행됐다. 이건 이름이
   *       빠졌다는 문제가 아니라 <b>이름 대조라는 방식 자체가 무너지는 것</b>이다 — {@code \+XXXXXX}, {@code
   *       UESCAPE} 절 등 표기 변형이 원리적으로 무한하다.
   * </ol>
   *
   * <p><b>단계 0 실측 — JSqlParser 는 {@code U&} 이스케이프를 디코딩하지 않는다.</b> {@code Function#getName()}
   * 이 {@code "pg_sl\0065ep"} 를 <b>원문 그대로</b>(디코딩 없이) 돌려준다(스크래치 프로브 실측 후 삭제). 즉 이
   * 이름은 이미 어떤 deny-list 항목과도 매칭되지 않고, 어떤 허용목록 항목과도 매칭되지 않는다 — <b>허용목록은
   * "모르는 이름 = 거부"이므로 이 우회에 원리적으로 면역이다</b>(디코딩까지 갔다면 이 필드에 별도 이스케이프 거부
   * 로직이 필요했겠지만 실측상 불필요 — 다만 {@link #SIMPLE_IDENTIFIER} 검사가 방어 심층으로 비표준 식별자
   * 표기 자체를 별도로 거부한다).
   *
   * <p><b>구성 — 두 출처의 합집합, 둘 다 손으로 열거하지 않는다(원칙적으로).</b>
   *
   * <ul>
   *   <li>{@link #POSTGIS_SAFE_FUNCTIONS} — {@code postgis}/{@code postgis_topology} 확장 소유 함수 중
   *       {@code provolatile <> 'v'}(부작용 없음)만, 카탈로그 쿼리 결과를 리소스 파일로 그대로 옮긴 것(생성
   *       쿼리는 그 파일 헤더에 있다). {@code addgeometrycolumn}/{@code droptopology} 등 스키마를 바꾸는
   *       위험 함수는 전부 volatile 이라 이 필터로 자동 제외됐다(실측).
   *   <li>{@link #SQL_STANDARD_FUNCTIONS} — 표준 SQL 집계/수학/문자열/날짜/JSON 내장 함수. <b>이건 카탈로그
   *       유도가 아니라 손으로 큐레이션했다</b> — {@code pg_catalog} 는 PostGIS 와 달리 provolatile 로 안전을
   *       가를 수 없다({@code current_setting}/{@code pg_get_viewdef}/{@code table_to_xml}/{@code
   *       database_to_xml} 이 전부 {@code provolatile='s'}(stable)로 표시돼 있어 그 필터로는 안 걸린다 —
   *       psql 실측). 표준 함수 표면이 PostGIS 보다 훨씬 작고 안정적이라 손 큐레이션이 실용적이다.
   * </ul>
   *
   * <p>새 함수를 추가하려면 "파일/네트워크/GUC/카탈로그 접근이 없는가", "문자열 인자를 SQL 로 재해석하지
   * 않는가"(위 원리적 한계 참고)를 반드시 확인하라.
   *
   * <p><b>이 정책은 파이프라인 SQL 스텝 경로에도 적용된다(#385 재재리뷰 M1).</b> 무인자 {@link #SqlValidator()}
   * 생성자도 이 static 필드를 공유하므로, 허용목록 밖의 함수는 파이프라인 SQL 스텝에서도 전부 거부된다 —
   * "알려진 함수만 허용"에서 "알려진 함수만 허용, 기본값은 실패"로 뒤집는 것이므로 명시적 근거가 필요하다.
   * dev 실측: {@code pipeline_step} 12건 중 SQL 타입 5건 전부 함수 호출을 쓰지 않아 오늘 시점 깨지는 스텝은
   * 0건이다. 확장이 필요하면(파이프라인 전용 함수가 생기면) 이 목록에 이름과 사유를 추가하는 것으로 한다 —
   * 파이프라인 전용 별도 허용목록을 만들지 않는다(정책이 두 곳으로 갈라지면 한쪽만 갱신되는 사고가 난다).
   */
  static final Set<String> ALLOWED_FUNCTIONS = buildAllowedFunctions();

  private static Set<String> buildAllowedFunctions() {
    Set<String> combined = new HashSet<>(SQL_STANDARD_FUNCTIONS);
    combined.addAll(POSTGIS_SAFE_FUNCTIONS);
    return Set.copyOf(combined);
  }

  private static Set<String> loadPostgisSafeFunctions() {
    String resourcePath = "/sql-validator/postgis-safe-functions.txt";
    try (InputStream in = SqlValidator.class.getResourceAsStream(resourcePath)) {
      if (in == null) {
        throw new IllegalStateException("리소스를 찾을 수 없습니다: " + resourcePath);
      }
      Set<String> names = new HashSet<>();
      try (BufferedReader reader =
          new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          String trimmed = line.strip();
          if (trimmed.isEmpty() || trimmed.startsWith("#")) {
            continue;
          }
          names.add(trimmed.toLowerCase());
        }
      }
      if (names.isEmpty()) {
        throw new IllegalStateException("PostGIS 안전 함수 목록이 비어 있습니다: " + resourcePath);
      }
      return Set.copyOf(names);
    } catch (IOException e) {
      throw new IllegalStateException("PostGIS 안전 함수 목록 로드 실패: " + resourcePath, e);
    }
  }

  /**
   * 함수 이름이 순수 식별자 형태({@code [A-Za-z_][A-Za-z0-9_]*})인지 검사한다. {@code U&"..."} 유니코드
   * 이스케이프, 백슬래시 등은 이 패턴에 안 걸려 별도의 명확한 에러로 거부된다 — {@link #ALLOWED_FUNCTIONS}
   * 대조만으로도 결과적으로 막히지만("모르는 이름"이 되므로), 재발 방지 차원에서 이 우회 형태 자체를 이름 대며
   * 거부하는 편이 다음 사람이 원인을 바로 알 수 있다.
   */
  private static final Pattern SIMPLE_IDENTIFIER = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

  /**
   * {@code nextval}/{@code currval} — 시퀀스 값을 문자열 리터럴 이름으로 조작하는 함수. (#385 재재리뷰 M2)
   *
   * <p>일반 허용목록 대조로는 못 막는다 — 인자가 시퀀스 이름을 담은 **문자열 리터럴**이라 그 문자열 안의 스키마는
   * {@link AstNodeCollector}가 아무리 전수 방문해도 테이블 참조 노드가 아니라서 안 보인다(클래스 상단 "원리적
   * 한계" 참고). 그렇다고 이름 자체를 deny 하면 파이프라인 SQL 스텝의 정상 INSERT(자동 증가 컬럼 시퀀스 사용)가
   * 깨진다(재재리뷰 실측: dev pipeline_step 에 {@code nextval} 사용례 존재). 그래서 이 두 함수만 허용하되
   * {@link #requireSafeSequenceArgument}가 리터럴 인자 자체를 파싱해 스키마를 검사한다 — 리터럴이 아니면(계산된
   * 표현식이면) 정적으로 검증할 수 없으므로 무조건 거부한다. 재재리뷰어가 {@code nextval('public.
   * slack_workspace_id_seq')}로 남의 시퀀스를 754→755 로 실제 진행시켰다 — 그냥 허용목록에 이름만 올리면
   * 안 되는 이유다.
   *
   * <p><b>미한정 시퀀스는 항상 거부한다(#385 코드리뷰 C2, 테이블과 다른 정책).</b> 처음엔 테이블과 같은 정책
   * (permissive 모드에서 미한정 허용)을 그대로 가져다 썼는데, 애널리틱스는 {@code ("data", true)} +
   * {@code search_path='data','public'}이라 {@code SELECT nextval('slack_workspace_id_seq')}가
   * public 시퀀스를 실제로 증가시켰다(코드리뷰 실측). 테이블의 "public 그림자" 백스톱
   * ({@code rejectUnqualifiedNamesShadowedByPublic})은 {@code Table} 노드만 보므로 시퀀스에는 적용되지
   * 않고, executor 경로는 카탈로그 검사 자체가 없다 — 백스톱이 전혀 없다는 뜻이다. 시퀀스는 테이블과 달리
   * dev 이력에 정당한 미한정 사용례가 없었다(코드리뷰 실측) — 그래서 "미한정도 카탈로그로 확인" 대신 더 단순한
   * "항상 스키마 한정을 요구"로 간다. 이 정책 차이(테이블은 미한정 허용, 시퀀스는 미한정 거부) 자체가
   * 옳다 — 시퀀스는 인자가 문자열이라 AST 로 이름 해석을 도울 방법이 원천적으로 없다.
   */
  private static final Set<String> SEQUENCE_FUNCTIONS = Set.of("nextval", "currval");

  /** 검증 실패 시 {@link UnsafeSqlException}을 던진다. */
  public void validate(String scriptContent) {
    if (scriptContent == null || scriptContent.isBlank()) {
      throw new UnsafeSqlException("SQL 스크립트가 비어 있습니다.");
    }

    Statement statement = parseSingleStatement(scriptContent);
    requireDmlOrSelect(statement);

    AstNodeCollector collected = AstNodeCollector.collect(statement);
    requireDataSchemaOnly(collected.tableFqns());
    requireNoBlockedFunctions(collected.functions(), collected.analyticFunctionNames());
    requireOnlyKnownFunctions(collected.functions(), collected.analyticFunctionNames());
    requireNoReservedPseudoColumns(collected.columns());
    requireNoSelectInto(collected.plainSelects());
  }

  /** JSqlParser로 파싱하고 단일 스테이트먼트인지 확인한다. */
  private Statement parseSingleStatement(String sql) {
    Statements parsed;
    try {
      parsed = CCJSqlParserUtil.parseStatements(sql);
    } catch (JSQLParserException e) {
      throw new UnsafeSqlException("SQL 파싱 실패: " + e.getMessage(), e);
    }
    if (parsed == null) {
      // 선행 결함(#385 재재재리뷰 minor 1) — 특정 입력(깊은 우편향 표현식 등)에서 parseStatements 가
      // 예외 대신 null 을 돌려줄 수 있다. 이 null 검사가 없으면 바로 아래 parsed.getStatements() 에서
      // NPE 가 새어 나가 컨트롤러 레벨에서 400 대신 500 이 된다 — UnsafeSqlException 으로 명시 변환한다.
      throw new UnsafeSqlException("SQL 파싱 실패: 파서가 결과를 반환하지 않았습니다.");
    }

    List<Statement> statements = parsed.getStatements();
    if (statements == null || statements.isEmpty()) {
      throw new UnsafeSqlException("실행 가능한 SQL 문장이 없습니다.");
    }
    if (statements.size() > 1) {
      throw new UnsafeSqlException(
          "멀티 스테이트먼트는 금지됩니다. 1개의 SQL만 작성하세요. (감지된 문장 수: " + statements.size() + ")");
    }
    return statements.get(0);
  }

  /** 최상위 statement가 SELECT / INSERT / UPDATE / DELETE 중 하나인지 검사한다. */
  private void requireDmlOrSelect(Statement statement) {
    if (statement instanceof Select
        || statement instanceof Insert
        || statement instanceof Update
        || statement instanceof Delete) {
      return;
    }
    throw new UnsafeSqlException(
        "허용되지 않는 SQL 형태입니다. SELECT / INSERT / UPDATE / DELETE만 허용됩니다. (감지: "
            + statement.getClass().getSimpleName()
            + ")");
  }

  /**
   * 모든 실제 테이블 참조가 {@code data} 스키마인지 검사한다.
   *
   * <p>{@link AstNodeCollector#tableFqns()}가 CTE 별칭을 제외한 실제 테이블 FQN 문자열만 돌려주므로 그 목록만
   * 검사하면 된다. 결과 형식 예: {@code "스키마.t"}, {@code "스키마.\"My Table\""}, {@code "public.\"user\""},
   * {@code "t"}(스키마 없음).
   *
   * <p>미한정 이름이 {@code pg_} 로 시작하면 {@link #allowUnqualifiedTables} 값과 무관하게 항상 거부한다(아래
   * 미한정 분기보다 먼저 검사) — {@code pg_catalog} 는 {@code search_path} 설정을 타지 않고 항상 암묵 검색되므로({@link
   * #allowUnqualifiedTables} 필드 문서 참고), 이 규칙이 없으면 미한정 허용 자체가 카탈로그 열람 경로가 된다. PostgreSQL 이
   * {@code pg_} 접두어를 시스템 카탈로그 전용으로 예약하고 있어 정당한 사용자 테이블과 충돌할 일이 거의 없다.
   *
   * <p>strict 모드({@code allowUnqualifiedTables=false})에서는 이 검사를 지워도 **최종 결과(거부)는 바뀌지 않는다**
   * — 바로 아래 미한정 분기가 어차피 모든 미한정 이름을 거부하기 때문이다(단, 에러 메시지는 이 pg_ 전용 메시지 대신 일반
   * "스키마가 없습니다" 메시지로 바뀐다). 이 검사가 통과/거부 결과 자체를 바꾸는 것은 permissive 모드(Task 3/4 배선)뿐이다
   * — 그래서 이 검사가 필요한 문맥은 permissive 모드지만, 검사 자체는 두 모드 모두에서 실행된다.
   */
  private void requireDataSchemaOnly(List<String> tableFqns) {
    for (String fqn : tableFqns) {
      // m5(#385 코드리뷰) — 점이 2개 이상인 FQN(예: data.public.role, catalog.schema.table 형태)은
      // 거부한다. indexOf('.')로 앞부분만 잘라 스키마로 검사하면 "data.public.role"이 스키마
      // "data"(허용) + 이름 "public.role"(검사 안 됨)로 쪼개져 통과해버린다 — 오늘은 PostgreSQL 이
      // "cross-database references are not implemented"로 막아 주지만, 이 클래스의 전제는 "AST
      // 화이트리스트가 정본"이지 "DB 가 대신 막아 준다"가 아니다. 따옴표 안의 점(예: 스키마."my.table")은
      // 세지 않는다.
      if (countUnquotedDots(fqn) > 1) {
        throw new UnsafeSqlException(
            "허용되지 않는 테이블 참조 표기(점이 2개 이상): '" + fqn + "'. 스키마.테이블 형식만 허용됩니다.");
      }
      // 스키마/테이블 이름의 양쪽 따옴표만 제거 (식별자 인용 보정)
      // indexOfUnquotedDot 사용 — 순수 indexOf('.')는 "my.table"처럼 이름 자체에 점이 있는 미한정
      // 테이블(따옴표 안의 점)을 "스키마.이름"으로 잘못 쪼개 엉뚱한 메시지를 냈다(위 m5 후속 정정).
      int dot = indexOfUnquotedDot(fqn);
      if (dot < 0) {
        String name = stripQuotes(fqn);
        if (name.toLowerCase().startsWith("pg_")) {
          throw new UnsafeSqlException(
              "테이블 참조에 스키마가 없습니다: '"
                  + name
                  + "'. pg_ 로 시작하는 이름은 search_path 설정과 무관하게 pg_catalog 로 해석될 수 있어 미한정 허용 여부와"
                  + " 관계없이 거부됩니다.");
        }
        if (allowUnqualifiedTables) {
          continue;
        }
        throw new UnsafeSqlException(
            "테이블 참조에 스키마가 없습니다: '" + name + "'. " + allowedSchema + "." + name + " 형식으로 명시하세요.");
      }
      String schema = stripQuotes(fqn.substring(0, dot));
      String name = stripQuotes(fqn.substring(dot + 1));
      if (!allowedSchema.equalsIgnoreCase(schema)) {
        throw new UnsafeSqlException(
            "허용되지 않는 스키마 참조: '" + schema + "." + name + "'. " + allowedSchema + " 스키마만 사용할 수 있습니다.");
      }
    }
  }

  private static String stripQuotes(String s) {
    if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
      return s.substring(1, s.length() - 1);
    }
    return s;
  }

  /** 따옴표 밖(top-level)의 {@code .} 개수만 센다 — {@code 스키마."my.table"}의 따옴표 안 점은 세지 않는다. */
  private static int countUnquotedDots(String s) {
    int count = 0;
    boolean inQuotes = false;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '"') {
        inQuotes = !inQuotes;
      } else if (c == '.' && !inQuotes) {
        count++;
      }
    }
    return count;
  }

  /**
   * 따옴표 밖(top-level)의 첫 {@code .} 위치를 찾는다. 없으면 -1.
   *
   * <p>m5 메시지 정정(#385 코드리뷰 후속) — {@link #requireDataSchemaOnly}가 이 헬퍼 대신 순수
   * {@code String.indexOf('.')}을 썼을 때, {@code "my.table"}처럼 이름 자체에 점이 포함된 <b>미한정</b>
   * 테이블(따옴표 안의 점)을 "스키마.이름 표기"로 잘못 쪼개 엉뚱한 "허용되지 않는 스키마 참조" 메시지를
   * 냈다(거부/허용 여부 자체는 정책대로였지만 진단 메시지가 틀렸다). {@link #countUnquotedDots}로 이미
   * 2개 이상인 표기는 먼저 걸러지므로, 여기서는 남은 유일한 점(있다면)의 위치만 따옴표를 무시하고 찾는다.
   */
  private static int indexOfUnquotedDot(String s) {
    boolean inQuotes = false;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '"') {
        inQuotes = !inQuotes;
      } else if (c == '.' && !inQuotes) {
        return i;
      }
    }
    return -1;
  }

  /**
   * PostgreSQL 식별자 폴딩 규칙 — 인용된 식별자는 원문 대소문자를 보존하고, 인용되지 않은 식별자만
   * 소문자화한다(PG 파서가 인용 없는 식별자를 항상 소문자로 접기 때문). CTE 별칭 스코프 대조와 미한정
   * 테이블 이름 정규화가 이 규칙을 공유한다(#385 코드리뷰 Medium — 전에는 CTE 별칭 등록만 무조건
   * 소문자화해서 {@code WITH role AS (...) SELECT * FROM "ROLE"}처럼 인용된 혼합 대소문자 이름이
   * 실제로는 CTE 참조가 아닌데도 별칭과 잘못 매칭되거나, 반대로 진짜 별칭 매칭이 빠지는 fail-open 여지가
   * 있었다).
   */
  private static String foldIdentifier(String raw) {
    boolean quoted = raw.length() >= 2 && raw.startsWith("\"") && raw.endsWith("\"");
    return quoted ? stripQuotes(raw) : raw.toLowerCase();
  }

  /** 함수 이름(schema.fn 표기 가능)에서 실제 함수명만 뽑아 정규화한다(마지막 dot 뒤 토큰, 따옴표 제거). */
  private static String simpleFunctionName(String rawName) {
    int dot = rawName.lastIndexOf('.');
    String simple = dot >= 0 ? rawName.substring(dot + 1) : rawName;
    return stripQuotes(simple);
  }

  /**
   * {@code validate(sql)} 를 통과시킬 SQL 에서 미한정(스키마 없는) 테이블 이름만 추출한다.
   *
   * <p>애널리틱스 경로처럼 {@code search_path} 가 복수 스키마({@code 'data', 'public'})인 호출부는 AST 만으로는 미한정 이름이
   * 실제로 어느 스키마로 해석될지 알 수 없다(이름 해석은 DB 카탈로그의 몫). 이 메서드는 그 판단에 필요한 "미한정 이름 목록"만
   * 돌려준다 — 카탈로그 대조는 호출부(DB 접근 가능한 서비스 레이어)의 책임이다(#385 Task 4, R1 옵션 2).
   *
   * <p><b>대소문자 규칙은 PostgreSQL 식별자 폴딩을 따른다</b> — 따옴표로 감싼 식별자는 원문 대소문자를 그대로 보존하고(PG 도 그렇게
   * 저장한다), 따옴표 없는 식별자만 소문자화한다(PG 파서가 따옴표 없는 식별자를 항상 소문자로 접기 때문). 무조건 소문자화하면 {@code
   * "MyTable"}처럼 인용된 혼합 대소문자 테이블을 호출부가 {@code pg_class.relname}과 대조할 때 놓친다(리뷰 지적) — 반환값이
   * {@code pg_class.relname}과 바이트 단위로 일치해야 카탈로그 대조가 정확하다.
   *
   * <p>{@code validate(sql)} 와 별개로 다시 파싱한다(추가 파싱 비용 발생) — 두 메서드가 같은 SQL 을 각자 파싱하는 것은 이 검증기를
   * DB 접근 없는 순수 AST 컴포넌트로 유지하기 위한 트레이드오프다. 짧은 사용자 SQL 문 하나를 다시 파싱하는 비용은 이어지는 DB 카탈로그
   * 조회·쿼리 실행 비용에 비해 무시할 만하다.
   */
  public Set<String> unqualifiedTableNames(String sql) {
    Statement statement = parseSingleStatement(sql);
    AstNodeCollector collected = AstNodeCollector.collect(statement);
    Set<String> result = new LinkedHashSet<>();
    for (String fqn : collected.tableFqns()) {
      // indexOfUnquotedDot 사용 — 순수 indexOf('.')는 "my.table"(따옴표 안에 점이 있는 미한정 테이블)을
      // "점이 있으니 한정됐다"고 오판해 이 결과 집합에서 빠뜨렸다(m5 후속 정정과 같은 근본 원인).
      if (indexOfUnquotedDot(fqn) < 0) {
        result.add(foldIdentifier(fqn));
      }
    }
    return result;
  }

  /**
   * AST 내 모든 함수 호출이 deny-list({@link #BLOCKED_FUNCTIONS})에 포함되지 않는지 검사한다 — 알려진 위험
   * 함수에 더 구체적인 메시지를 주는 심층 방어. 정본은 {@link #requireOnlyKnownFunctions}(허용목록)다.
   *
   * <p>{@code analyticFunctionNames}(#385 코드리뷰 M3) — {@code SELECT count(*) OVER ()} 같은 윈도 호출은
   * JSqlParser 5.0 에서 {@link Function} 이 아니라 {@code AnalyticExpression} 을 만든다. 이 함수 목록에도
   * 같은 이름 정규화·대조를 적용하지 않으면 윈도 형태로 감싸는 것만으로 deny-list/허용목록 양쪽을 우회한다
   * (PG 가 {@code OVER} 뒤에 집계/윈도 함수를 요구해 지금 당장 시연 가능한 익스플로잇은 아니지만, fail-closed
   * 허용목록의 불변식 — "이름이 있으면 반드시 검사한다" — 이 깨진 상태였다).
   */
  private void requireNoBlockedFunctions(List<Function> functions, List<String> analyticFunctionNames) {
    for (Function function : functions) {
      String fnName = function.getName();
      if (fnName == null) {
        continue;
      }
      requireNotBlocked(fnName);
    }
    for (String fnName : analyticFunctionNames) {
      if (fnName == null) {
        continue;
      }
      requireNotBlocked(fnName);
    }
  }

  private void requireNotBlocked(String fnName) {
    // 따옴표 제거(식별자 인용 보정) — 최종 리뷰 지적(C2): 테이블 경로(stripQuotes 적용됨)와 달리
    // 처음엔 이 보정이 없어 SELECT "pg_sleep"(5), "current_setting"(...),
    // "resolve_trigger_tenant_by_token_hash"(...) 처럼 함수 이름을 따옴표로 감싸기만 해도
    // deny-list 전체가 무력화됐다(psql 실측: 인용 형태가 그대로 실행됨). PostgreSQL 은 함수 호출에서도
    // 따옴표 유무를 구분하지 않고 같은 함수로 해석하므로, 검증기도 똑같이 취급해야 한다.
    String unquoted = simpleFunctionName(fnName);
    if (BLOCKED_FUNCTIONS.contains(unquoted.toLowerCase())) {
      throw new UnsafeSqlException("허용되지 않는 함수 호출: '" + fnName + "'. 시스템/네트워크 접근 함수는 차단됩니다.");
    }
  }

  /**
   * AST 내 모든 함수 호출이 {@link #ALLOWED_FUNCTIONS} 허용목록에 있는지 검사한다 — <b>이 검증의 정본</b>(#385
   * 재재리뷰). {@link #requireNoBlockedFunctions}(deny-list)는 이보다 먼저 실행돼 알려진 위험 함수에 더
   * 구체적인 에러 메시지를 주는 심층 방어일 뿐이고, 최종 판단은 이 메서드가 한다 — 목록에 없으면 무조건 거부하므로
   * 새 위험 함수가 추가돼도(또는 이름이 이스케이프로 위장돼도) 안전한 쪽으로 실패한다.
   *
   * <p>{@link #SIMPLE_IDENTIFIER} 검사와 이 허용목록 대조는 서로 다른 것을 본다 — 헷갈리지 말 것. 허용목록 대조만
   * 있었다면 {@code U&"..."} 이스케이프도 "모르는 이름"으로 결국 거부되긴 한다(결과적으로 안전). 그런데도
   * {@link #SIMPLE_IDENTIFIER} 검사를 별도로 앞에 두는 이유는 순전히 <b>진단성</b>이다 — 허용목록 대조만 실패하면
   * 에러 메시지가 "알려진 안전 함수 목록에 없습니다"가 되어 다음 사람이 "왜?"를 알기 어렵다. 이스케이프 표기 자체를
   * 먼저 잡으면 "유니코드 이스케이프는 거부됩니다"라는 원인이 바로 보인다 — 독립된 보안 경계가 두 겹이라는 뜻이
   * 아니다.
   *
   * <p>{@code analyticFunctionNames} — {@link #requireNoBlockedFunctions} 문서 참고(M3, {@code
   * AnalyticExpression}은 {@link Function}이 아니라서 별도 목록으로 들어온다). 윈도 호출은 인자로 시퀀스
   * 이름을 받을 문법이 없으므로 {@link #SEQUENCE_FUNCTIONS} 특례는 적용하지 않는다 — 이름 검사만 한다.
   */
  private void requireOnlyKnownFunctions(List<Function> functions, List<String> analyticFunctionNames) {
    for (Function function : functions) {
      String fnName = function.getName();
      if (fnName == null) {
        continue;
      }
      String unquoted = simpleFunctionName(fnName);
      requireSimpleIdentifierForm(unquoted, fnName);
      String lower = unquoted.toLowerCase();
      if (SEQUENCE_FUNCTIONS.contains(lower)) {
        requireSafeSequenceArgument(function, fnName);
        continue;
      }
      requireKnownFunctionName(lower, fnName);
    }
    for (String fnName : analyticFunctionNames) {
      if (fnName == null) {
        continue;
      }
      String unquoted = simpleFunctionName(fnName);
      requireSimpleIdentifierForm(unquoted, fnName);
      requireKnownFunctionName(unquoted.toLowerCase(), fnName);
    }
  }

  private void requireSimpleIdentifierForm(String unquoted, String rawName) {
    if (!SIMPLE_IDENTIFIER.matcher(unquoted).matches()) {
      throw new UnsafeSqlException(
          "허용되지 않는 함수 이름 표기: '" + rawName + "'. 유니코드 이스케이프 등 비표준 식별자 표기는 거부됩니다.");
    }
  }

  private void requireKnownFunctionName(String lower, String rawName) {
    if (!ALLOWED_FUNCTIONS.contains(lower)) {
      throw new UnsafeSqlException("허용되지 않는 함수 호출: '" + rawName + "'. 알려진 안전 함수 목록에 없습니다.");
    }
  }

  /**
   * {@code current_user}/{@code session_user}/{@code current_catalog}/{@code current_schema}/
   * {@code current_role}/{@code user} — PostgreSQL 예약 의사 상수(pseudo-constant). (#385 코드리뷰 m4,
   * `current_role`/`user` 는 코드리뷰 후속 지적으로 추가)
   *
   * <p>이 이름들은 함수 호출이 아니라 {@link net.sf.jsqlparser.schema.Column}(한정자 없는 컬럼 참조)으로
   * 파싱돼 {@link #requireOnlyKnownFunctions}의 함수 허용목록을 아예 지나가지 않는다 — 클래스 문서에 이
   * 이름들이 "허용목록에서 의도적으로 제외했다"고 적어 뒀지만, 함수가 아니므로 애초에 허용목록 대조 대상이
   * 아니었다(문서와 실제가 어긋남, 실측: 두 경로 모두 통과해 DB 롤 이름을 반환). 세션 정보 노출이 데이터
   * 유출 자체는 아니지만 이 검증기의 "알려진 안전한 것만 통과"라는 불변식에 어긋나므로 명시적으로 막는다.
   *
   * <p><b>인용된 형태는 의사 상수가 아니다(실측) — {@link #requireNoReservedPseudoColumns}가 인용 여부로
   * 분기한다.</b> psql 실측: {@code SELECT "user"}, {@code SELECT "current_user"}는 둘 다 {@code column
   * "user"/"current_user" does not exist}로 실패한다 — PG 는 <b>인용 없는 키워드 형태만</b> 의사 상수로
   * 해석한다. 인용된 {@code "user"}는 진짜(존재한다면) 컬럼 참조이므로 막으면 안 된다 — 이 시스템은 실제로
   * {@code user} 테이블이 있어 그 컬럼을 인용해 다루는 정상 쿼리를 깨뜨릴 수 있다. {@code user}를 이
   * 목록에 추가하면서 생기는 유일한 트레이드오프는 "미한정 컬럼 이름이 우연히 {@code user}인 실제 컬럼을
   * 인용 없이 참조하는" 드문 정상 쿼리가 거부되는 것인데, 세션 정보 노출 차단이 이 비용보다 낫다고 판단했다
   * (dev 이력에 그런 사용례는 없었다).
   */
  private static final Set<String> RESERVED_PSEUDO_CONSTANTS =
      Set.of(
          "current_user", "session_user", "current_catalog", "current_schema", "current_role",
          "user");

  private void requireNoReservedPseudoColumns(List<Column> columns) {
    for (Column column : columns) {
      if (column.getTable() != null) {
        continue; // 한정된 컬럼 참조(t.current_user 등)는 실제 컬럼명일 뿐 의사 상수가 아니다.
      }
      String raw = column.getColumnName();
      boolean quoted = raw.length() >= 2 && raw.startsWith("\"") && raw.endsWith("\"");
      if (quoted) {
        continue; // 인용된 형태는 PG 가 의사 상수로 해석하지 않는다(위 문서의 실측 참고) — 진짜 컬럼일 수 있다.
      }
      if (RESERVED_PSEUDO_CONSTANTS.contains(raw.toLowerCase())) {
        throw new UnsafeSqlException(
            "허용되지 않는 참조: '" + raw + "'. 세션/역할 정보를 노출하는 의사 상수는 차단됩니다.");
      }
    }
  }

  /**
   * {@code nextval}/{@code currval} 의 인자가 안전한지 검사한다 — {@link #SEQUENCE_FUNCTIONS} 참고. 인자가
   * 정확히 하나의 문자열 리터럴이어야 하고(계산된 표현식이면 정적 검증이 불가능하므로 거부), 그 리터럴을 스키마
   * 한정 이름으로 파싱해 {@link #allowedSchema}인지 확인한다. 미한정 시퀀스는 {@link
   * #allowUnqualifiedTables} 값과 무관하게 항상 거부한다(테이블과 다른 정책 — 위 {@link
   * #SEQUENCE_FUNCTIONS} 문서의 "미한정 시퀀스는 항상 거부한다" 근거 참고).
   */
  private void requireSafeSequenceArgument(Function function, String fnName) {
    var params = function.getParameters();
    List<?> exprs = params == null ? List.of() : params.getExpressions();
    if (exprs.size() != 1 || !(exprs.get(0) instanceof StringValue literal)) {
      throw new UnsafeSqlException(
          "허용되지 않는 함수 호출: '"
              + fnName
              + "'. 시퀀스 이름은 문자열 리터럴 하나여야 합니다(계산된 표현식은 정적으로 검증할 수 없어 거부됩니다).");
    }
    String seqRef = literal.getValue();
    int dot = seqRef.lastIndexOf('.');
    if (dot < 0) {
      String name = stripQuotes(seqRef);
      throw new UnsafeSqlException(
          "허용되지 않는 함수 호출: '"
              + fnName
              + "'. 시퀀스 참조에 스키마를 명시하세요(예: "
              + allowedSchema
              + "."
              + name
              + ") — 미한정 시퀀스는 허용하지 않습니다.");
    }
    String schema = stripQuotes(seqRef.substring(0, dot));
    if (!allowedSchema.equalsIgnoreCase(schema)) {
      throw new UnsafeSqlException(
          "허용되지 않는 함수 호출: '"
              + fnName
              + "'. 시퀀스가 "
              + allowedSchema
              + " 스키마 밖입니다: '"
              + seqRef
              + "'.");
    }
  }

  /**
   * 최상위 {@code SELECT ... INTO <table>} / {@code SELECT ... INTO TEMP <table>} 형태를 거부한다.
   * (#385 최종 리뷰 지적 M4)
   *
   * <p>JSqlParser 는 이 형태를 {@link PlainSelect}로 그대로 모델링해 {@link #requireDmlOrSelect}를
   * 통과시키고, INTO 대상은 별개의 접근자({@code getIntoTables()}/{@code getIntoTempTable()})에만 담겨
   * 일반 테이블 참조 목록에는 나타나지 않는다 — 즉 {@link #requireDataSchemaOnly}의 스키마 화이트리스트가
   * INTO 대상에는 아예 적용되지 않는다. 실측: {@code SELECT * INTO public.pwned FROM data.t}가 검증기를
   * 통과했고, 실제 DB 에서 {@code SELECT 1 AS x INTO data.zz_probe}가 테이블을 생성했다. {@code public}
   * 스키마로의 INTO 는 GRANT 로만 막히는데, 그건 "AST 스키마 화이트리스트가 정본"이라는 이 클래스의 전제와
   * 어긋난다. 더 나쁜 점 — {@code SqlValidationUtils.detectQueryType}은 이 문장을 {@code SELECT}로 분류해
   * 애널리틱스의 {@code readOnly=true}(MCP 도구) 게이트까지 통과시킨다. 사용자 SQL 경로(파이프라인/애드혹/
   * 애널리틱스) 어디에도 INTO 로 새 테이블을 만드는 정당한 용도가 없으므로 전면 거부한다.
   */
  private void requireNoSelectInto(List<PlainSelect> plainSelects) {
    for (PlainSelect plainSelect : plainSelects) {
      if ((plainSelect.getIntoTables() != null && !plainSelect.getIntoTables().isEmpty())
          || plainSelect.getIntoTempTable() != null) {
        throw new UnsafeSqlException("SELECT ... INTO 는 허용되지 않습니다. 조회 결과로 새 테이블을 만들 수 없습니다.");
      }
    }
  }

  /**
   * 파싱된 AST 객체 그래프를 리플렉션으로 전수 순회해 모든 {@link Function}/{@link Table}/{@link PlainSelect}
   * 노드를 수집한다. (#385 재재리뷰 C1 — 절 단위 visitor 폐기, 클래스 상단 "순회 방식" 문서 참고)
   *
   * <p>사이클 방지: 방문한 객체를 {@link IdentityHashMap} 기반 집합에 기록한다(부모 포인터
   * {@code ASTNodeAccessImpl.getParent()} 등으로 인한 순환을 막는다 — equals/hashCode 오버라이드에 기대지
   * 않도록 항등성 비교를 쓴다). 순회 대상은 {@code net.sf.jsqlparser} 패키지 소속 객체로 한정하고, 그중에서도
   * {@code net.sf.jsqlparser.parser} 패키지(JJTree 파서 내부 CST — {@code getASTNode()}가 돌려주는 {@code
   * SimpleNode} 등)는 명시적으로 제외한다 — AST 와 별개의 파서 내부 구조라 우리 관심사가 아니고, 자칫 파서
   * 내부 대형 객체를 끌고 들어올 위험이 있다.
   *
   * <p><b>깊이 상한 도달 = 거부(fail-closed), 조용한 통과가 아니다(#385 재재재리뷰 C).</b> 최초 구현은 상한
   * 도달 시 그냥 {@code return} 해서 그 아래 서브트리 전체가 미검사 통과였다 — 재리뷰어 실측: {@code WHERE a
   * IN (SELECT ...)} 를 169단 중첩하고 최내부에 {@code FROM public.usr} 를 심으면 검증기를 통과했고, DB 도
   * 같은 형태 200단을 실제로 실행했다({@code public."user"} 7853행 반환). 같은 뿌리로 스키마 화이트리스트·
   * INTO 차단·{@link #unqualifiedTableNames}(카탈로그 대조용 미한정 이름 목록)가 전부 조용히 무너졌다 —
   * {@code unqualifiedTableNames}가 빈 집합을 반환하는 쪽이 특히 나빴다(호출부는 "미한정 참조 없음"으로
   * 잘못 읽는다). 정상 SQL 은 이 상한 근처에 가지 않는다(재리뷰 실측: UNION×500 11ms, 1000컬럼 82ms, 40단
   * CTE 3ms) — 그래서 상한을 올리는 것은 처방이 아니라 같은 버그의 숫자만 바꾸는 것이다. 상한 도달은 이제
   * {@link UnsafeSqlException}을 던진다.
   *
   * <p>부수 효과 — 절 열거 방식({@code TablesNamesFinder} 상속)을 버리면서 그 유틸리티의 알려진 버그(윈도
   * 프레임 {@code ROWS BETWEEN ... PRECEDING}에서 {@code WindowOffset.getExpression()}이 null 일 때
   * 발생하는 NPE)도 함께 사라졌다 — 정상 윈도 SQL 이 "SQL 테이블 분석 실패"로 오탐 거부되던 선행 결함이었다
   * (재재리뷰어 실측, 이 클래스가 처음 만들어졌을 때부터 있던 결함). 리플렉션 순회는 null 값을 만나면 그냥
   * 멈추므로 이 오탐이 재현되지 않는다({@code SqlValidatorTest}에 회귀 테스트 고정).
   */
  private static final class AstNodeCollector {
    private static final String PARSER_INTERNAL_PACKAGE = "net.sf.jsqlparser.parser";
    private static final int MAX_DEPTH = 500;
    private static final Map<Class<?>, List<Method>> GETTER_CACHE = new ConcurrentHashMap<>();

    private final Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
    private final List<Function> functions = new ArrayList<>();
    private final List<String> analyticFunctionNames = new ArrayList<>();
    private final List<Column> columns = new ArrayList<>();
    private final List<PlainSelect> plainSelects = new ArrayList<>();

    /** 현재 방문 지점에서 유효한 CTE 별칭 스코프 스택 — 스코프 단위 처리 근거는 {@link #tableFqns} 문서 참고. */
    private final Deque<Set<String>> cteScopeStack = new ArrayDeque<>();

    /** {@code Table} 노드와 그 노드를 만난 시점에 유효했던 CTE 별칭 집합을 함께 보관한다. */
    private record TableRef(Table table, Set<String> activeCteAliases) {}

    private final List<TableRef> tables = new ArrayList<>();

    static AstNodeCollector collect(Statement statement) {
      AstNodeCollector collector = new AstNodeCollector();
      collector.walk(statement, 0);
      return collector;
    }

    List<Function> functions() {
      return functions;
    }

    /** {@code AnalyticExpression}(윈도 호출)의 함수 이름 목록 — M3 문서는 {@link #requireNoBlockedFunctions} 참고. */
    List<String> analyticFunctionNames() {
      return analyticFunctionNames;
    }

    List<Column> columns() {
      return columns;
    }

    List<PlainSelect> plainSelects() {
      return plainSelects;
    }

    /**
     * CTE 별칭을 <b>스코프 단위로</b> 제외한 실제 테이블 FQN 문자열 목록. (#385 코드리뷰 C1 — 전역 제외에서
     * 스코프 인식으로 전환)
     *
     * <p><b>왜 전역 제외가 틀렸는가.</b> 최초 구현은 트리 전체에서 발견한 모든 CTE 별칭을 하나의 집합으로
     * 모아 이름만 같으면 무조건 제외했다. 파생 테이블 안의 {@code WITH} 는 그 서브쿼리에만 스코프되는데,
     * 코드리뷰 실측(PG, {@code search_path='data','public'}): {@code SELECT count(*) FROM (WITH role AS
     * (SELECT 1 AS x) SELECT x FROM role) s, role} 가 {@code public.role} 3행을 반환했다 — 두 번째
     * {@code role}(스코프 밖의 진짜 테이블)까지 "CTE 별칭과 이름이 같다"는 이유로 검사 목록에서 사라졌다.
     * 스키마 화이트리스트뿐 아니라 {@code pg_} 접두어 가드도 같은 방식으로 뚫린다(CTE 이름을 {@code
     * pg_roles} 로 지으면 스코프 밖의 진짜 {@code pg_roles} 참조까지 가드에서 빠진다).
     *
     * <p><b>수정 — CTE 별칭이 실제로 유효한 스코프에서 발견된 {@code Table}만 제외한다.</b> {@link
     * #walk}가 {@code Select}(또는 그 하위 타입) 노드에 진입할 때 그 노드 자신의 {@code
     * getWithItemsList()}로 별칭을 스코프 스택에 push 하고, 그 노드의 서브트리를 다 훑은 뒤 pop 한다. 각
     * {@code Table}을 기록할 때 <b>그 시점에 유효했던 스코프 스냅샷</b>을 함께 저장해 두므로, 스코프 밖에서
     * 만난 동명의 {@code Table}은 그 스냅샷에 별칭이 없어 제외되지 않는다. 정확한 스코프 추적이라 "이름이
     * 같으면 fail-closed로 남긴다" 같은 근사치가 필요 없다.
     */
    List<String> tableFqns() {
      List<String> result = new ArrayList<>();
      for (TableRef ref : tables) {
        String fqn = ref.table().getFullyQualifiedName();
        if (indexOfUnquotedDot(fqn) < 0 && ref.activeCteAliases().contains(foldIdentifier(fqn))) {
          continue; // 그 스코프에서 유효한 CTE 참조 — 실제 테이블이 아니다.
        }
        result.add(fqn);
      }
      return result;
    }

    private void walk(Object node, int depth) {
      if (node == null) {
        return;
      }
      if (depth > MAX_DEPTH) {
        // fail-open 금지(#385 재재재리뷰 C) — 여기서 조용히 return 하면 그 아래 서브트리 전체가
        // 미검사 통과였다(실측: 169단 중첩 서브쿼리 최내부의 public.usr 참조가 검증기를 통과, DB 도
        // 200단을 실제 실행). 정상 SQL 은 이 상한 근처에 가지 않으므로(위 클래스 상단 문서의 벤치마크
        // 참고) 거부가 곧 fail-closed 다.
        throw new UnsafeSqlException("SQL 구조가 너무 깊게 중첩되어 있습니다(허용 깊이 " + MAX_DEPTH + " 초과).");
      }
      if (node instanceof Iterable<?> iterable) {
        for (Object element : iterable) {
          walk(element, depth + 1);
        }
        return;
      }
      if (node instanceof Map<?, ?> map) {
        for (Object value : map.values()) {
          walk(value, depth + 1);
        }
        return;
      }
      if (node.getClass().isArray()) {
        int length = Array.getLength(node);
        for (int i = 0; i < length; i++) {
          walk(Array.get(node, i), depth + 1);
        }
        return;
      }

      String packageName = node.getClass().getPackageName();
      boolean isAstNode =
          packageName.startsWith("net.sf.jsqlparser") && !packageName.equals(PARSER_INTERNAL_PACKAGE);
      if (!isAstNode) {
        return; // 문자열/enum/boxed 타입 등 리프 값이거나 파서 내부 CST — 더 내려갈 것이 없다.
      }
      if (!visited.add(node)) {
        return; // 이미 방문(사이클 방지)
      }

      // 이 노드(Select/PlainSelect/WithItem/ParenthesedSelect 또는 Update/Delete/Insert)가 자기 소유의
      // WITH 절을 가지면 그 별칭을 스코프 스택에 push 한다. (#385 코드리뷰 B1/B2)
      //
      // B1(자기그림자, self-shadowing) — 비재귀 CTE 의 본문은 PostgreSQL 이 "그 CTE 자신의 스코프 밖"에서
      // 해석한다. {@code WITH "user" AS (SELECT * FROM "user") SELECT * FROM "user"} 의 안쪽 "user" 는
      // CTE 자신이 아니라 바깥의 진짜 {@code public."user"} 를 가리킨다(psql 실측: 7967행 반환,
      // {@code WITH pg_roles AS (SELECT * FROM pg_roles) ...} 는 17행 — pg_ 가드도 같은 경로로 뚫렸다).
      // 최초 구현은 노드 진입 즉시 전체 별칭을 push 해 자기 본문 안에서도 자기 자신을 참조하는 것처럼
      // 오분류했다({@code unqualifiedTableNames()}가 빈 집합을 반환해 카탈로그 백스톱까지 무력화됨). 수정:
      // 비재귀 항목은 **그 항목의 본문을 walk 한 뒤에** 자기 별칭을 push 한다 — 항목 N 은 1..N-1 만 보고
      // 자기 자신은 못 본다(PG 의 순차적 가시성과 일치). {@code RECURSIVE} 로 표시된 항목이 하나라도 있으면
      // 그 WITH 절 전체를 재귀로 취급해(PG 의미상 WITH RECURSIVE 는 목록 전체가 상호 참조 가능) 본문을
      // walk 하기 전에 전체 별칭을 미리 push 한다(자기/상호 참조가 합법이므로).
      //
      // B2(DML-WITH 회귀, 이번 스코프 전환이 새로 낸 결함) — Update/Delete/Insert 는 Select 가 아니라서
      // {@code node instanceof Select} 만으로는 그들이 소유한 {@code getWithItemsList()}가 push 되지
      // 않았다 — {@code WITH role AS (...) UPDATE data.t ...} 에서 CTE 별칭 "role"이 진짜 미한정 테이블로
      // 오독돼 정당한 쿼리가 거부됐다(예전 전역 수집 방식은 이 형태를 처리하고 있었다 — 회귀였다).
      // {@link #ownWithItemsOf}로 네 타입을 함께 처리한다.
      boolean pushedCteScope = false;
      List<WithItem> ownWithItems = ownWithItemsOf(node);
      if (ownWithItems != null && !ownWithItems.isEmpty()) {
        Set<String> inherited = cteScopeStack.isEmpty() ? Set.of() : cteScopeStack.peek();
        boolean recursive = ownWithItems.stream().anyMatch(WithItem::isRecursive);
        if (recursive) {
          Set<String> combined = new HashSet<>(inherited);
          for (WithItem withItem : ownWithItems) {
            addAlias(combined, withItem);
          }
          cteScopeStack.push(Set.copyOf(combined));
        } else {
          Set<String> running = new HashSet<>(inherited);
          for (WithItem withItem : ownWithItems) {
            // 이 항목은 이전 항목까지만 본다 — 자기 자신은 아직 running 에 없다(B1 의 핵심).
            cteScopeStack.push(Set.copyOf(running));
            walk(withItem, depth + 1);
            cteScopeStack.pop();
            addAlias(running, withItem);
          }
          cteScopeStack.push(Set.copyOf(running)); // 본문(메인 쿼리)은 전체 CTE 를 본다
        }
        pushedCteScope = true;
      }

      if (node instanceof Function function) {
        functions.add(function);
      } else if (node instanceof AnalyticExpression analytic) {
        // M3(#385 코드리뷰) — SELECT count(*) OVER () 는 Function 이 아니라 AnalyticExpression 을 만든다
        // (JSqlParser 5.0). 이름만 별도로 수집해 같은 정규화·대조 경로를 태운다(클래스 상단 관련 메서드
        // 문서 참고).
        if (analytic.getName() != null) {
          analyticFunctionNames.add(analytic.getName());
        }
      } else if (node instanceof Table table) {
        Set<String> activeAliases = cteScopeStack.isEmpty() ? Set.of() : cteScopeStack.peek();
        tables.add(new TableRef(table, activeAliases));
      } else if (node instanceof PlainSelect plainSelect) {
        plainSelects.add(plainSelect);
      } else if (node instanceof Column column) {
        columns.add(column);
      }

      // Column("t.id")/AllTableColumns("t.*") 의 getTable() 은 FROM/JOIN 소스가 아니라 이미 FROM/JOIN 이
      // 도입한 별칭(또는 실제 테이블 이름)을 다시 가리키는 "한정자" 참조일 뿐이다 — 반면 Delete/Update/
      // Insert.getTable() 은 진짜 DML 대상 테이블이라 반드시 walk 해야 한다. 두 역할이 같은 메서드 이름
      // (getTable)을 쓰지만 의미가 다르므로, 메서드 이름이 아니라 "한정자 보유 타입인가"로 구분한다.
      // 이 필드까지 일반 Table 노드처럼 수집하면 별칭이 스키마 없는 "테이블 참조"로 오분류돼 정상 쿼리
      // (`FROM data.t ... SELECT t.id`)가 거부된다(회귀 실측: `allows_cte_referencing_only_data_schema`).
      // 절 단위가 아니라 타입 단위 규칙이라 C1 이 겨냥한 "절을 몰라서 뚫리는" 사각과는 다른 종류다 —
      // Column/AllTableColumns 는 어느 절에 있든 이 필드의 의미가 같다.
      boolean isQualifierHolder = node instanceof Column || node instanceof AllTableColumns;
      try {
        for (Method getter : gettersOf(node.getClass())) {
          if (isQualifierHolder && getter.getName().equals("getTable")) {
            continue;
          }
          try {
            walk(getter.invoke(node), depth + 1);
          } catch (UnsafeSqlException e) {
            // 재재재리뷰 실측 버그: 이 catch 가 없으면 깊이 상한 초과로 던진 UnsafeSqlException 이 바로
            // 아래 넓은 catch(RuntimeException)에 "게터 실패"로 오인돼 삼켜져 fail-closed 처방이 무력화됐다
            // (재현: 169단 중첩에서도 계속 PASS). 검증 실패는 반드시 호출자까지 전파해야 한다.
            throw e;
          } catch (ReflectiveOperationException | RuntimeException ignored) {
            // 특정 상태에서만 값을 갖는 게터가 실패해도(또는 접근 불가여도) 순회는 계속한다.
          }
        }
      } finally {
        if (pushedCteScope) {
          cteScopeStack.pop();
        }
      }
    }

    /**
     * 노드가 소유한 {@code WITH} 절 목록을 돌려준다(없으면 {@code null}). {@code Select}(및 하위 타입)와
     * {@code Update}/{@code Delete}/{@code Insert}가 각자 독립적으로 {@code getWithItemsList()}를 선언하고
     * 있어(공통 상위 타입이 없다) 타입별로 나눠 확인한다 — B2(#385 코드리뷰)의 원인이 정확히 이 목록에서
     * DML 세 타입이 빠졌던 것이었다.
     */
    private static List<WithItem> ownWithItemsOf(Object node) {
      if (node instanceof Select select) {
        return select.getWithItemsList();
      }
      if (node instanceof Update update) {
        return update.getWithItemsList();
      }
      if (node instanceof Delete delete) {
        return delete.getWithItemsList();
      }
      if (node instanceof Insert insert) {
        return insert.getWithItemsList();
      }
      return null;
    }

    /** {@code WithItem}의 별칭을 PG 식별자 폴딩 규칙({@link SqlValidator#foldIdentifier})으로 집합에 더한다. */
    private static void addAlias(Set<String> aliases, WithItem withItem) {
      if (withItem.getAlias() != null && withItem.getAlias().getName() != null) {
        aliases.add(foldIdentifier(withItem.getAlias().getName()));
      }
    }

    private static List<Method> gettersOf(Class<?> clazz) {
      return GETTER_CACHE.computeIfAbsent(
          clazz,
          c -> {
            List<Method> getters = new ArrayList<>();
            for (Method m : c.getMethods()) {
              if (m.getParameterCount() != 0 || m.getReturnType() == void.class) {
                continue;
              }
              String name = m.getName();
              if (name.equals("getClass") || !(name.startsWith("get") || name.startsWith("is"))) {
                continue;
              }
              m.setAccessible(true);
              getters.add(m);
            }
            return List.copyOf(getters);
          });
    }
  }
}
