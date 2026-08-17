package com.smartfirehub.pipeline.service.validator;

import com.smartfirehub.pipeline.exception.UnsafeSqlException;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.util.TablesNamesFinder;
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
 * <p><b>원리적 한계 — "AST 가 다 막아 준다"고 오해하지 말 것(#385 최종 리뷰 m5).</b> {@link #requireDataSchemaOnly}의
 * 스키마 화이트리스트는 SQL 문법상 <i>테이블 참조 노드</i>로 나타나는 이름만 볼 수 있다. 인자가 **문자열 리터럴**인 함수 —
 * {@code query_to_xml('SELECT ... FROM public.t', ...)}, {@code nextval('public.some_seq')},
 * {@code 'public.v'::regclass} 등 — 는 그 문자열 안의 테이블 이름을 AST 파서가 볼 수 없다(문자열 리터럴 노드일 뿐 테이블
 * 참조가 아니다). 이런 함수는 **deny-list({@link #BLOCKED_FUNCTIONS})가 유일한 방어**다 — 스키마 화이트리스트로
 * 막을 수 있다고 가정하고 함수를 빠뜨리면 그 함수가 곧 우회 경로가 된다. 새 함수를 검토할 때 "인자가 문자열이고 그 문자열이 SQL
 * 조각으로 재해석되는가"를 반드시 확인하라.
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
   * **가장 심각한 우회였다**: 이 함수들은 인자로 **SQL 문자열 리터럴을 받아 그 안에서 별도 쿼리를 실행**한다. 그 문자열은
   * {@link #requireDataSchemaOnly}가 순회하는 {@code TablesNamesFinder}의 테이블 목록에 절대 안 잡힌다 —
   * AST 상 문자열 리터럴일 뿐 테이블 참조 노드가 아니기 때문이다. 실측: {@code search_path='data'} 로 좁힌 애드혹
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
          "pg_get_viewdef");

  /** 검증 실패 시 {@link UnsafeSqlException}을 던진다. */
  public void validate(String scriptContent) {
    if (scriptContent == null || scriptContent.isBlank()) {
      throw new UnsafeSqlException("SQL 스크립트가 비어 있습니다.");
    }

    Statement statement = parseSingleStatement(scriptContent);
    requireDmlOrSelect(statement);
    requireDataSchemaOnly(statement);
    requireNoBlockedFunctions(statement);
    requireNoSelectInto(statement);
  }

  /** JSqlParser로 파싱하고 단일 스테이트먼트인지 확인한다. */
  private Statement parseSingleStatement(String sql) {
    Statements parsed;
    try {
      parsed = CCJSqlParserUtil.parseStatements(sql);
    } catch (JSQLParserException e) {
      throw new UnsafeSqlException("SQL 파싱 실패: " + e.getMessage(), e);
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
   * <p>{@link TablesNamesFinder#getTables(Statement)}가 CTE 이름과 컬럼 alias는 자동으로 제외한 실제 테이블 FQN만 반환하므로
   * 그 문자열 셋만 검사하면 된다. 결과 형식 예: {@code "data.t"}, {@code "data.\"My Table\""}, {@code
   * "public.\"user\""}, {@code "t"}(스키마 없음).
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
  private void requireDataSchemaOnly(Statement statement) {
    Set<String> tables;
    try {
      tables = new TablesNamesFinder<>().getTables(statement);
    } catch (Exception e) {
      throw new UnsafeSqlException("SQL 테이블 분석 실패: " + e.getMessage(), e);
    }
    for (String fqn : tables) {
      // 스키마/테이블 이름의 양쪽 따옴표만 제거 (식별자 인용 보정)
      int dot = fqn.indexOf('.');
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
    Set<String> tables;
    try {
      tables = new TablesNamesFinder<>().getTables(statement);
    } catch (Exception e) {
      throw new UnsafeSqlException("SQL 테이블 분석 실패: " + e.getMessage(), e);
    }
    Set<String> result = new java.util.LinkedHashSet<>();
    for (String fqn : tables) {
      if (fqn.indexOf('.') < 0) {
        boolean quoted = fqn.length() >= 2 && fqn.startsWith("\"") && fqn.endsWith("\"");
        result.add(quoted ? stripQuotes(fqn) : fqn.toLowerCase());
      }
    }
    return result;
  }

  /**
   * AST 내 모든 함수 호출이 deny-list({@link #BLOCKED_FUNCTIONS})에 포함되지 않는지 검사한다.
   *
   * <p>{@code TablesNamesFinder}의 traversal 인프라를 재활용하되, {@link Function} 노드만 가로챈다.
   */
  private void requireNoBlockedFunctions(Statement statement) {
    BlockedFunctionFinder finder = new BlockedFunctionFinder();
    statement.accept(finder);
  }

  /**
   * {@link Function} 호출만 검사하는 visitor.
   *
   * <p>{@code TablesNamesFinder}의 {@code init()}은 protected라 외부에서 호출할 수 없으므로 생성자에서 초기화한다.
   */
  private static final class BlockedFunctionFinder extends TablesNamesFinder<Void> {
    BlockedFunctionFinder() {
      init(true);
    }

    @Override
    public <S> Void visit(Function function, S context) {
      String fnName = function.getName();
      if (fnName != null) {
        // 함수 이름은 점 표기(schema.fn)일 수 있으므로 마지막 토큰만 사용
        int dot = fnName.lastIndexOf('.');
        String simple = dot >= 0 ? fnName.substring(dot + 1) : fnName;
        // 따옴표 제거(식별자 인용 보정) — 최종 리뷰 지적(C2): 테이블 경로(stripQuotes 적용됨)와 달리
        // 여기는 원래 이 보정이 없어 SELECT "pg_sleep"(5), "current_setting"(...),
        // "resolve_trigger_tenant_by_token_hash"(...) 처럼 함수 이름을 따옴표로 감싸기만 해도
        // deny-list 전체가 무력화됐다(psql 실측: 인용 형태가 그대로 실행됨). PostgreSQL 은 함수 호출에서도
        // 따옴표 유무를 구분하지 않고 같은 함수로 해석하므로, 검증기도 똑같이 취급해야 한다.
        String unquoted = stripQuotes(simple);
        if (BLOCKED_FUNCTIONS.contains(unquoted.toLowerCase())) {
          throw new UnsafeSqlException("허용되지 않는 함수 호출: '" + fnName + "'. 시스템/네트워크 접근 함수는 차단됩니다.");
        }
      }
      return super.visit(function, context);
    }
  }

  /**
   * 최상위 {@code SELECT ... INTO <table>} / {@code SELECT ... INTO TEMP <table>} 형태를 거부한다.
   * (#385 최종 리뷰 지적 M4)
   *
   * <p>JSqlParser 는 이 형태를 {@link net.sf.jsqlparser.statement.select.PlainSelect}로 그대로 모델링해
   * {@link #requireDmlOrSelect}를 통과시키고, {@code TablesNamesFinder.getTables}는 INTO 대상 테이블을
   * 결과에 포함하지 않는다 — 즉 {@link #requireDataSchemaOnly}의 스키마 화이트리스트가 INTO 대상에는
   * 아예 적용되지 않는다. 실측: {@code SELECT * INTO public.pwned FROM data.t}가 검증기를 통과했고, 실제
   * DB 에서 {@code SELECT 1 AS x INTO data.zz_probe}가 테이블을 생성했다. {@code public} 스키마로의 INTO 는
   * GRANT 로만 막히는데, 그건 "AST 스키마 화이트리스트가 정본"이라는 이 클래스의 전제와 어긋난다. 더 나쁜 점 —
   * {@code SqlValidationUtils.detectQueryType}은 이 문장을 {@code SELECT}로 분류해 애널리틱스의
   * {@code readOnly=true}(MCP 도구) 게이트까지 통과시킨다. 사용자 SQL 경로(파이프라인/애드혹/애널리틱스) 어디에도
   * INTO 로 새 테이블을 만드는 정당한 용도가 없으므로 전면 거부한다.
   */
  private void requireNoSelectInto(Statement statement) {
    IntoTableFinder finder = new IntoTableFinder();
    statement.accept(finder);
  }

  /** {@link PlainSelect}의 INTO 대상만 검사하는 visitor. UNION/서브쿼리 내부까지 재귀적으로 방문한다. */
  private static final class IntoTableFinder extends TablesNamesFinder<Void> {
    IntoTableFinder() {
      init(true);
    }

    @Override
    public <S> Void visit(PlainSelect plainSelect, S context) {
      if ((plainSelect.getIntoTables() != null && !plainSelect.getIntoTables().isEmpty())
          || plainSelect.getIntoTempTable() != null) {
        throw new UnsafeSqlException(
            "SELECT ... INTO 는 허용되지 않습니다. 조회 결과로 새 테이블을 만들 수 없습니다.");
      }
      return super.visit(plainSelect, context);
    }
  }
}
