package com.smartfirehub.dataset.exception;

public class SqlQueryException extends RuntimeException {
  public SqlQueryException(String message) {
    super(message);
  }

  public SqlQueryException(String message, Throwable cause) {
    super(message, cause);
  }
}
