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
 * @param cancelled 출력 잠금을 얻은 뒤 파이프라인 중지 요청을 확인해 롤백했으면 true(WD-482) — 출력은 손대지 않았고 api 는 스텝을 실패가 아니라
 *     취소로 기록한다. 실행 id 가 없는 요청에서는 언제나 false(api {@code SqlExecuteResult.cancelled} 와 같은 이름)
 */
public record SqlExecuteResponse(
    boolean success,
    List<Map<String, Object>> rows,
    List<String> columns,
    @JsonProperty("row_count") int rowCount,
    @JsonProperty("execution_log") String executionLog,
    String error,
    @JsonProperty("db_error") DbError dbError,
    boolean cancelled) {

  /** 잠금 뒤 중지 확인으로 롤백했을 때의 문구(WD-482, Python 실행기와 같은 문구). api 는 cancelled 로 판정하고 이 문구는 로그용이다. */
  public static final String CANCELLED_MESSAGE = "파이프라인 실행이 취소되어 출력 잠금을 얻은 뒤 SQL 을 실행하지 않았습니다";

  /** 취소 표시가 없는 응답(기존 7필드 모양) — 성공·실패 응답은 모두 이 생성자를 쓴다. */
  public SqlExecuteResponse(
      boolean success,
      List<Map<String, Object>> rows,
      List<String> columns,
      int rowCount,
      String executionLog,
      String error,
      DbError dbError) {
    this(success, rows, columns, rowCount, executionLog, error, dbError, false);
  }

  /** 잠금 뒤 중지 요청으로 롤백한 응답(WD-482) — 실패가 아니라 취소다(구조화 오류 없음). */
  public static SqlExecuteResponse cancelled(String executionLog) {
    return new SqlExecuteResponse(
        false, null, null, 0, executionLog, CANCELLED_MESSAGE, null, true);
  }

  /** 실패 응답(행·컬럼 없음). */
  public static SqlExecuteResponse failure(String error, DbError dbError, String executionLog) {
    return new SqlExecuteResponse(false, null, null, 0, executionLog, error, dbError);
  }
}
