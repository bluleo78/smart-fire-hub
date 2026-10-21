package com.smartfirehub.pipeline.exception;

/** 이미 끝난(COMPLETED/FAILED/CANCELLED) 실행을 취소하려 할 때 발생하는 예외 — 409 Conflict(WD-153). */
public class ExecutionAlreadyFinishedException extends RuntimeException {
  public ExecutionAlreadyFinishedException() {
    super("이미 끝난 실행은 취소할 수 없습니다");
  }
}
