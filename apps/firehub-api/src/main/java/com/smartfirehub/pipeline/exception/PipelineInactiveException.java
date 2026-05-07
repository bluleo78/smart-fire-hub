package com.smartfirehub.pipeline.exception;

/** 비활성화된 파이프라인을 수동 실행하려 할 때 발생하는 예외 (409 Conflict 반환용, #187) */
public class PipelineInactiveException extends RuntimeException {
  public PipelineInactiveException(String message) {
    super(message);
  }
}
