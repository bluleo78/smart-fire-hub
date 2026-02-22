package com.smartfirehub.dataset.service;

import com.smartfirehub.dataset.dto.SqlQueryResponse;
import com.smartfirehub.dataset.exception.SqlQueryException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;

@Service
public class DataTableQueryService {

  private final DSLContext dsl;

  private static final Set<String> ALLOWED_KEYWORDS =
      Set.of("SELECT", "INSERT", "UPDATE", "DELETE", "WITH");

  public DataTableQueryService(DSLContext dsl) {
    this.dsl = dsl;
  }

  /**
   * Execute user-supplied SQL against the data schema. Security: rejects DDL, multi-statement
   * input, and restricts search_path to data schema. Must be called within a @Transactional context
   * for SET LOCAL to be effective.
   */
  public SqlQueryResponse executeQuery(String sql, int maxRows) {
    // Strip SQL comments (block and line) to prevent DDL bypass via comment injection
    // (?s) enables DOTALL mode so .* matches across newlines in block comments
    String stripped =
        sql.strip()
            .replaceAll("(?s)/\\*.*?\\*/", " ") // block comments (multiline-safe)
            .replaceAll("--[^\n]*", " ") // line comments
            .strip();

    // Reject multi-statement SQL (semicolons not at the very end)
    String withoutTrailingSemicolon = stripped.replaceAll(";\\s*$", "");
    if (withoutTrailingSemicolon.contains(";")) {
      throw new SqlQueryException("Multiple statements are not allowed.");
    }

    String firstWord = stripped.split("\\s+")[0].toUpperCase();

    if (!ALLOWED_KEYWORDS.contains(firstWord)) {
      throw new SqlQueryException(
          "Only SELECT, INSERT, UPDATE, DELETE, and WITH statements are allowed.");
    }

    // WITH (CTE) can precede SELECT, INSERT, UPDATE, DELETE — detect the main verb
    // Use word boundary (\b) to avoid false positives on column/table names
    String queryType;
    if ("WITH".equals(firstWord)) {
      String upper = withoutTrailingSemicolon.toUpperCase();
      if (upper.matches("(?s).*\\bINSERT\\b.*")) queryType = "INSERT";
      else if (upper.matches("(?s).*\\bUPDATE\\b.*")) queryType = "UPDATE";
      else if (upper.matches("(?s).*\\bDELETE\\b.*")) queryType = "DELETE";
      else queryType = "SELECT";
    } else {
      queryType = firstWord;
    }

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
        String limitedSql = withoutTrailingSemicolon;
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
        int affectedRows = dsl.execute(withoutTrailingSemicolon);
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
