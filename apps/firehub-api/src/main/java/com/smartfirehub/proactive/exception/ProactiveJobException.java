package com.smartfirehub.proactive.exception;

public class ProactiveJobException extends RuntimeException {

  public ProactiveJobException(String message) {
    super(message);
  }

  public ProactiveJobException(String message, Throwable cause) {
    super(message, cause);
  }
}
