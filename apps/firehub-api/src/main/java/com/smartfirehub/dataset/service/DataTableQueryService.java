package com.smartfirehub.dataset.service;

import com.smartfirehub.dataset.dto.SqlQueryResponse;
import com.smartfirehub.global.util.SqlValidationUtils;
import com.smartfirehub.pipeline.exception.UnsafeSqlException;
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
   * 데이터셋 애드혹 쿼리 전용 검증기 인스턴스. {@code data} 스키마만 허용하고 미한정(스키마 없음) 테이블 참조도 허용한다.
   *
   * <p>스프링 빈으로 등록된 {@link SqlValidator}(무인자 = 파이프라인 정책)와는 다른 문맥 정책이라 별도 인스턴스를 직접 생성한다. 아래 {@code
   * SET LOCAL search_path = 'data'}(단일 스키마)가 미한정 허용의 안전 전제다 — 두 스키마를 세우면 안 된다. 검증기는 불변(생성자만
   * 정책을 갖고 이후 상태가 없음)이라 필드로 재사용해도 스레드 안전하다. (#385)
   */
  private final SqlValidator sqlValidator = new SqlValidator("data", true);

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
    // 단, JSqlParser 가 애초에 파싱하지 못하는 문자열(순수 SQL 문법 오류)은 보안 위반이 아니라 사용자
    // 오타이므로 여기서 거부하지 않고 기존처럼 DB 실행 단계로 넘긴다 — Postgres 가 더 정확한 오류 메시지를
    // 내고, 이 메서드 자체가 실패를 error 필드로 반환하는 계약을 유지해야 호출자(DatasetDataService)의
    // query_history 실패 기록 로직이 계속 동작한다(파서 단계에서 예외를 던지면 이 메서드 밖으로 전파돼 이력이
    // 남지 않는다). 이 폴백이 새 우회 경로가 되지 않는 이유: 위 스키마·함수 위반 사례(동등 표기 변형 포함)는
    // 전부 유효한 SQL 문법이라 항상 파싱에 성공하므로 이 catch 로 빠지지 않는다 — 오직 진짜로 파싱 불가능한
    // 입력만 폴백된다.
    try {
      sqlValidator.validate(cleanSql);
    } catch (UnsafeSqlException e) {
      if (!(e.getCause() instanceof net.sf.jsqlparser.JSQLParserException)) {
        throw e;
      }
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
