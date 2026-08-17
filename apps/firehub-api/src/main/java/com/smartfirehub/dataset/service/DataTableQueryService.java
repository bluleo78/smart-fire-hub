package com.smartfirehub.dataset.service;

import com.smartfirehub.dataset.dto.SqlQueryResponse;
import com.smartfirehub.global.util.SqlValidationUtils;
import com.smartfirehub.pipeline.service.validator.SqlValidator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DataTableQueryService {

  private final DSLContext dsl;

  /**
   * 데이터셋 애드혹 쿼리 전용 검증기 인스턴스 — {@link SqlValidator#forAdhocDataSchemaQueries()}로 직접
   * 생성한다(팩터리 도입 근거는 R1, #385). 아래 {@code SET LOCAL search_path = 'data'}(단일 스키마)가
   * 미한정 허용의 안전 전제다 — 두 스키마를 세우면 안 된다.
   */
  private final SqlValidator sqlValidator = SqlValidator.forAdhocDataSchemaQueries();

  /**
   * Execute user-supplied SQL against the data schema. Security: rejects DDL, multi-statement
   * input, and restricts search_path to data schema. Must be called within a @Transactional context
   * for SET LOCAL to be effective.
   */
  public SqlQueryResponse executeQuery(String sql, int maxRows) {
    // Delegate comment stripping and keyword validation to SqlValidationUtils
    String stripped = SqlValidationUtils.stripAndValidate(sql);
    String queryType = SqlValidationUtils.detectQueryType(stripped);
    String cleanSql = SqlValidationUtils.removeTrailingSemicolon(stripped);

    // 부분문자열 대조(예: upperSql.contains("PUBLIC.")) 는 폐기했다 — PostgreSQL 이 허용하는 동등 표기
    // 변형(따옴표로 감싼 "public", 점 주변 공백 등)에 뚫린다(#385 R3 실측). AST 기반 스키마 화이트리스트가
    // 정본이며, 아래 SqlValidator 가 스키마 허용목록·차단 함수(set_config 등)를 함께 검사한다.
    //
    // 파싱 자체가 안 되는 문자열도 여기서 거부한다(폴백 없음) — "파싱 실패 시 DB 실행으로 넘긴다"는 대안은
    // 검토했으나 채택하지 않았다(#385 Task 2 판정과 동일한 이유). 그 폴백은 검증기 전체(스키마·차단 함수
    // 검사 포함)를 건너뛰므로, 공격자가 스키마 위반 쿼리에 JSqlParser 가 못 읽는(그러나 Postgres 는 실행하는)
    // 구문을 일부러 섞어 검증 자체를 우회할 새 경로가 된다 — "이 R3 변형들은 전부 파싱된다"는 관찰은 이
    // 순간의 테스트 케이스에만 성립하고 일반적으로 참임을 보장하지 못한다. 대가: 순수 오타(예: "FORM" 대신
    // "FROM")도 이제 error 필드가 아니라 예외로 거부되고, query_history 에 실패 이력이 남지 않는다
    // (DatasetDataService#executeQuery 가 이 예외를 잡지 않기 때문) — 이는 보안과 무관한 UX 회귀이지만
    // 검증 우회 가능성보다 낫다고 판단했다.
    sqlValidator.validate(cleanSql);

    long startTime = System.currentTimeMillis();

    // Restrict search_path to data schema only — prevents access to public schema tables
    dsl.execute("SET LOCAL search_path = 'data'");
    dsl.execute("SET LOCAL statement_timeout = '30s'");

    // Use SAVEPOINT so that SQL errors don't abort the outer transaction.
    // PostgreSQL marks the entire transaction as aborted on any error,
    // preventing subsequent commands (like saving query history).
    // Rolling back to a savepoint clears the error state.
    dsl.execute("SAVEPOINT user_query");

    try {
      SqlQueryResponse response;
      if ("SELECT".equals(queryType)) {
        // Apply LIMIT for SELECT queries (check for actual LIMIT clause, not substring match)
        String limitedSql = cleanSql;
        if (!limitedSql.toUpperCase().matches("(?s).*\\bLIMIT\\s+\\d+.*")) {
          limitedSql = limitedSql + " LIMIT " + maxRows;
        }

        var result = dsl.fetch(limitedSql);
        long executionTimeMs = System.currentTimeMillis() - startTime;

        // Filter out system columns (id, import_id, created_at)
        Set<String> systemColumns = Set.of("id", "import_id", "created_at");

        List<String> columns = new ArrayList<>();
        List<Integer> visibleIndices = new ArrayList<>();
        for (int i = 0; i < result.fields().length; i++) {
          String colName = result.fields()[i].getName();
          if (!systemColumns.contains(colName)) {
            columns.add(colName);
            visibleIndices.add(i);
          }
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (var record : result) {
          Map<String, Object> row = new HashMap<>();
          for (int idx : visibleIndices) {
            row.put(record.field(idx).getName(), record.get(idx));
          }
          rows.add(row);
        }

        response =
            new SqlQueryResponse(queryType, columns, rows, rows.size(), executionTimeMs, null);
      } else {
        // DML: INSERT, UPDATE, DELETE
        int affectedRows = dsl.execute(cleanSql);
        long executionTimeMs = System.currentTimeMillis() - startTime;
        response =
            new SqlQueryResponse(
                queryType, List.of(), List.of(), affectedRows, executionTimeMs, null);
      }
      dsl.execute("RELEASE SAVEPOINT user_query");
      return response;
    } catch (Exception e) {
      long executionTimeMs = System.currentTimeMillis() - startTime;
      // Rollback to savepoint to clear the aborted transaction state
      dsl.execute("ROLLBACK TO SAVEPOINT user_query");
      String errorMessage = e.getMessage();
      return new SqlQueryResponse(
          queryType, List.of(), List.of(), 0, executionTimeMs, errorMessage);
    } finally {
      // Restore search_path so subsequent operations in the same transaction
      // (e.g. QueryHistoryRepository.save) can access public schema tables
      try {
        dsl.execute("SET LOCAL search_path TO public, data");
      } catch (Exception ignored) {
        // May fail if connection is broken; non-critical since transaction will end
      }
    }
  }
}
