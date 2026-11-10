package com.smartfirehub.pipeline.service.executor;

/** 다시 보내도 같은 API 호출 실패 — 메시지는 사용자 문구다({@link SafeUserMessage}, WD-693). 실행기는 재시도하지 않는다. */
public class ApiCallException extends RuntimeException implements SafeUserMessage {

  public ApiCallException(String message) {
    super(message);
  }

  public ApiCallException(String message, Throwable cause) {
    super(message, cause);
  }
}
