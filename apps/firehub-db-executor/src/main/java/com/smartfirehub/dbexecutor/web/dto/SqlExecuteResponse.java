package com.smartfirehub.dbexecutor.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * {@code POST /execute/sql} 응답. 필드 이름은 api {@code ExecutorClient.SqlExecuteResult} 와 같은 snake_case
 * 다(Python executor 관례: 오류도 HTTP 200 + {@code success=false}).
 *
 * @param success 커밋까지 끝났는지
 * @param rows 선행 문장이 없고 SELECT·WITH 일 때만 결과 행(컬럼명 → 변환된 값), 그 외 null
 * @param columns rows 와 같은 조건에서 정규화된 컬럼명, 그 외 null
 * @param rowCount SELECT 면 읽은 행 수, DML 이면 본문이 반영한 행 수, 실패면 0
 * @param executionLog 한국어 실행 요약(문장 수·영향 행 수)
 * @param error 실패 사유(한국어 검증·시간 초과 문구 또는 드라이버 메시지)
 * @param dbError Tibero 서버 오류일 때만 채운다
 */
public record SqlExecuteResponse(
    boolean success,
    List<Map<String, Object>> rows,
    List<String> columns,
    @JsonProperty("row_count") int rowCount,
    @JsonProperty("execution_log") String executionLog,
    String error,
    @JsonProperty("db_error") DbError dbError) {

  /** 실패 응답(행·컬럼 없음). */
  public static SqlExecuteResponse failure(String error, DbError dbError, String executionLog) {
    return new SqlExecuteResponse(false, null, null, 0, executionLog, error, dbError);
  }
}
