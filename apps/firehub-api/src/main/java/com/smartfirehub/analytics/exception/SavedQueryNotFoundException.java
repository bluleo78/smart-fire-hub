package com.smartfirehub.analytics.exception;

public class SavedQueryNotFoundException extends RuntimeException {
  public SavedQueryNotFoundException(String message) {
    super(message);
  }
}
