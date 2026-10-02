package com.smartfirehub.global.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/**
 * 공통 오류 응답.
 *
 * @param code 클라이언트가 분기에 쓰는 기계용 코드(예: PASSWORD_CHANGE_REQUIRED). 대부분의 오류는
 *     코드가 없으므로 null 이면 JSON 에서 생략한다 — 기존 응답 모양을 바꾸지 않기 위해서다.
 */
public record ErrorResponse(
    int status,
    String error,
    String message,
    Map<String, String> errors,
    String timestamp,
    String path,
    @JsonInclude(JsonInclude.Include.NON_NULL) String code) {

  /** 코드가 없는 기존 호출처(ExternalTriggerController 7곳)용 보조 생성자. */
  public ErrorResponse(
      int status,
      String error,
      String message,
      Map<String, String> errors,
      String timestamp,
      String path) {
    this(status, error, message, errors, timestamp, path, null);
  }
}
