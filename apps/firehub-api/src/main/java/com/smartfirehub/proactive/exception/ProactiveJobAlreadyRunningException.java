package com.smartfirehub.proactive.exception;

/** Job이 이미 실행 중일 때 중복 실행 시도를 거부하기 위한 예외. GlobalExceptionHandler에서 409 Conflict로 매핑된다. */
public class ProactiveJobAlreadyRunningException extends ProactiveJobException {

  /** 중복 실행을 뜻하는 기계용 오류 코드 — web lib/api-error.ts 의 JOB_ALREADY_RUNNING_CODE 와 같은 값(WD-368). */
  public static final String CODE = "PROACTIVE_JOB_ALREADY_RUNNING";

  /**
   * 사용자에게 그대로 보이는 안내 — 예전 「Job이 이미 실행 중입니다: 1」은 영어 용어와 내부 작업 번호가 화면에 드러났다(WD-368). 목록·상세 화면이 같은 문구를
   * 쓰도록 서버가 정한다.
   */
  public static final String MESSAGE = "이미 실행 중인 작업입니다. 끝나면 실행 이력에서 결과를 확인하세요";

  public ProactiveJobAlreadyRunningException() {
    super(MESSAGE);
  }
}
