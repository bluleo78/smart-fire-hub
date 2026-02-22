package com.smartfirehub.pipeline.exception;

public class CyclicTriggerDependencyException extends RuntimeException {
  public CyclicTriggerDependencyException(String message) {
    super(message);
  }
}
