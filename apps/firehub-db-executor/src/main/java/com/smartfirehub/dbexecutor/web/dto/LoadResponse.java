package com.smartfirehub.dbexecutor.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * {@code POST /execute/load} 응답(snake_case, 오류도 HTTP 200 + {@code success=false}).
 *
 * @param success 커밋까지 끝났는지
 * @param rowsLoaded 적재한 행 수(실패면 0 — 한 트랜잭션이라 일부만 남지 않는다)
 * @param error 실패 사유(한국어 검증·시간 초과 문구 또는 드라이버 메시지)
 * @param dbError Tibero 서버 오류일 때만 채운다
 */
public record LoadResponse(
    boolean success,
    @JsonProperty("rows_loaded") int rowsLoaded,
    String error,
    @JsonProperty("db_error") DbError dbError) {

  /** 실패 응답. */
  public static LoadResponse failure(String error, DbError dbError) {
    return new LoadResponse(false, 0, error, dbError);
  }
}
