package com.smartfirehub.dataimport.exception;

import java.util.List;

public class ImportValidationException extends RuntimeException {
  private final List<String> errors;

  public ImportValidationException(String message, List<String> errors) {
    super(message);
    this.errors = errors;
  }

  public List<String> getErrors() {
    return errors;
  }
}
