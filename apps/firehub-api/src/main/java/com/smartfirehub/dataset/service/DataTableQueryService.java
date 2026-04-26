package com.smartfirehub.dataset.service;

import com.smartfirehub.dataset.dto.SqlQueryResponse;
import com.smartfirehub.global.util.SqlValidationUtils;
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
   * Execute user-supplied SQL against the data schema. Security: rejects DDL, multi-statement
   * input, and restricts search_path to data schema. Must be called within a @Transactional context
   * for SET LOCAL to be effective.
   */
  public SqlQueryResponse executeQuery(String sql, int maxRows) {
    // Delegate comment stripping and keyword validation to SqlValidationUtils
    String stripped = SqlValidationUtils.stripAndValidate(sql);
    String queryType = SqlValidationUtils.detectQueryType(stripped);
    String cleanSql = SqlValidationUtils.removeTrailingSemicolon(stripped);

    long startTime = System.currentTimeMillis();

    // public/시스템 스키마 직접 참조 차단 — 비밀번호 해시·시스템 카탈로그 접근 방지 (#95)
    String upperSql = cleanSql.toUpperCase();
    if (upperSql.contains("PUBLIC.")
        || upperSql.contains("INFORMATION_SCHEMA")
        || upperSql.contains("PG_CATALOG")
        || upperSql.contains("PG_READ_FILE")
        || upperSql.contains("PG_EXECUTE")) {
      return new SqlQueryResponse(
          "UNKNOWN", List.of(), List.of(), 0, 0, "보안 정책상 public 스키마 또는 시스템 스키마에 직접 접근할 수 없습니다.");
    }

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
