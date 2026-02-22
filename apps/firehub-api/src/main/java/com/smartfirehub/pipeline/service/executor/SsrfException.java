package com.smartfirehub.pipeline.service.executor;

public class SsrfException extends RuntimeException {
  public SsrfException(String message) {
    super(message);
  }

  public SsrfException(String message, Throwable cause) {
    super(message, cause);
  }
}
