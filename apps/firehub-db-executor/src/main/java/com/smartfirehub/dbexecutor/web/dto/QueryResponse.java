package com.smartfirehub.dbexecutor.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * {@code POST /execute/query} 응답. 오류도 HTTP 200 + {@code success=false} 다(기존 엔드포인트와 같은 관례).
 *
 * @param success 조회가 끝났는지
 * @param columns 정규화(소문자)된 컬럼명, 실패면 null
 * @param rows 결과 행(컬럼명 → 변환된 값), 실패면 null
 * @param rowCount 읽은 행 수, 실패면 0
 * @param truncated 행 수가 {@code maxRows} 에 닿았는지(더 있을 수 있음)
 * @param queryType 문장 종류 — 이 창구는 조회만 받으므로 성공이면 SELECT, 실패면 UNKNOWN
 * @param affectedRows 영향 행 수 — 조회라 항상 0
 * @param executionTimeMs 실행 시간(ms)
 * @param error 실패 사유(한국어 문구 또는 드라이버 메시지)
 * @param dbError Tibero 서버 오류일 때만 채운다
 */
public record QueryResponse(
    boolean success,
    List<String> columns,
    List<Map<String, Object>> rows,
    @JsonProperty("row_count") int rowCount,
    boolean truncated,
    @JsonProperty("query_type") String queryType,
    @JsonProperty("affected_rows") int affectedRows,
    @JsonProperty("execution_time_ms") long executionTimeMs,
    String error,
    @JsonProperty("db_error") DbError dbError) {

  /** 실패 응답(행·컬럼 없음). */
  public static QueryResponse failure(String error, DbError dbError) {
    return new QueryResponse(false, null, null, 0, false, "UNKNOWN", 0, 0L, error, dbError);
  }
}
