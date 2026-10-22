package com.smartfirehub.dbexecutor.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * {@code POST /execute/probe} 응답(snake_case).
 *
 * @param success 탐지 성공 여부
 * @param columns 결과 컬럼, 실패면 null
 * @param error 실패 사유
 * @param dbError Tibero 서버 오류일 때만
 */
public record ProbeResponse(
    boolean success,
    List<ProbeColumn> columns,
    String error,
    @JsonProperty("db_error") DbError dbError) {

  /** 실패 응답. */
  public static ProbeResponse failure(String error, DbError dbError) {
    return new ProbeResponse(false, null, error, dbError);
  }
}
