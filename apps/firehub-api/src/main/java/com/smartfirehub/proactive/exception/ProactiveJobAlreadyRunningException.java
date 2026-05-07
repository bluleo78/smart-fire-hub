package com.smartfirehub.proactive.exception;

/**
 * Job이 이미 실행 중일 때 중복 실행 시도를 거부하기 위한 예외.
 * GlobalExceptionHandler에서 409 Conflict로 매핑된다.
 */
public class ProactiveJobAlreadyRunningException extends ProactiveJobException {

  public ProactiveJobAlreadyRunningException(String message) {
    super(message);
  }
}
