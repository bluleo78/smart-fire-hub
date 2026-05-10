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
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.springframework.stereotype.Component;

/**
 * 파이프라인 SQL 스텝의 안전 정책 검증기.
 *
 * <p>허용 규칙:
 *
 * <ul>
 *   <li>정확히 1개의 SQL 스테이트먼트 (trailing 세미콜론 외 추가 금지)
 *   <li>최상위 형태가 SELECT / INSERT / UPDATE / DELETE 중 하나
 *   <li>참조하는 모든 테이블이 명시적으로 {@code data.<name>} 형식
 *   <li>위험 함수({@code pg_read_file}, {@code lo_import}, {@code dblink_connect} 등) 호출 금지
 * </ul>
 *
 * <p>이중 방어 — DB 역할({@code pipeline_executor})이 시스템 함수/스키마를 차단하지만, 애플리케이션 레이어에서 조기 차단하여 명확한 에러를 제공한다.
 * (#136)
 */
@Slf4j
@Component
public class SqlValidator {

  private static final String ALLOWED_SCHEMA = "data";

  /**
   * SELECT 본문 등에서 호출 가능한 위험 함수 deny-list.
   *
   * <p>AST 통과(SELECT 형태)이지만 실제로는 파일/네트워크/DB 카탈로그를 노출하는 함수들. DB 역할이 EXECUTE 권한을 갖지 않더라도 애플리케이션
   * 레이어에서 조기 차단하여 명확한 에러를 제공한다.
   */
  private static final Set<String> BLOCKED_FUNCTIONS =
      Set.of(
          "pg_read_file",
          "pg_read_binary_file",
          "pg_ls_dir",
          "pg_stat_file",
          "lo_import",
          "lo_export",
          "dblink",
          "dblink_connect",
          "dblink_connect_u",
          "dblink_exec",
          "current_setting",
          "set_config");

  /** 검증 실패 시 {@link UnsafeSqlException}을 던진다. */
  public void validate(String scriptContent) {
    if (scriptContent == null || scriptContent.isBlank()) {
      throw new UnsafeSqlException("SQL 스크립트가 비어 있습니다.");
    }

    Statement statement = parseSingleStatement(scriptContent);
    requireDmlOrSelect(statement);
    requireDataSchemaOnly(statement);
    requireNoBlockedFunctions(statement);
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
   * <p>{@link TablesNamesFinder#getTables(Statement)}가 CTE 이름과 컬럼 alias는 자동으로 제외한 실제 테이블 FQN만
   * 반환하므로 그 문자열 셋만 검사하면 된다. 결과 형식 예: {@code "data.t"}, {@code "data.\"My Table\""},
   * {@code "public.\"user\""}, {@code "t"}(스키마 없음).
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
        throw new UnsafeSqlException(
            "테이블 참조에 스키마가 없습니다: '" + name + "'. data." + name + " 형식으로 명시하세요.");
      }
      String schema = stripQuotes(fqn.substring(0, dot));
      String name = stripQuotes(fqn.substring(dot + 1));
      if (!ALLOWED_SCHEMA.equalsIgnoreCase(schema)) {
        throw new UnsafeSqlException(
            "허용되지 않는 스키마 참조: '" + schema + "." + name + "'. data 스키마만 사용할 수 있습니다.");
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
        if (BLOCKED_FUNCTIONS.contains(simple.toLowerCase())) {
          throw new UnsafeSqlException(
              "허용되지 않는 함수 호출: '" + fnName + "'. 시스템/네트워크 접근 함수는 차단됩니다.");
        }
      }
      return super.visit(function, context);
    }
  }
}
