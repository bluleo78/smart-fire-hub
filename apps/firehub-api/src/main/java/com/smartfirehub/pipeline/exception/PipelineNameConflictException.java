package com.smartfirehub.pipeline.exception;

/** 파이프라인 이름 중복 시 발생하는 예외 (409 Conflict 반환용) */
public class PipelineNameConflictException extends RuntimeException {
  public PipelineNameConflictException(String message) {
    super(message);
  }
}
