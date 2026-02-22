package com.smartfirehub.pipeline.service.executor;

public class ApiCallException extends RuntimeException {

  public ApiCallException(String message) {
    super(message);
  }

  public ApiCallException(String message, Throwable cause) {
    super(message, cause);
  }
}
