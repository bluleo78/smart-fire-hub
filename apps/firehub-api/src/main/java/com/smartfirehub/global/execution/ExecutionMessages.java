package com.smartfirehub.global.execution;

/**
 * 실행 정리기들이 함께 쓰는 사용자 문구. 파이프라인(WD-230)과 프로액티브(WD-258) 정리기가 같은 「중단됨」 문구를 남기되, 프로액티브가 파이프라인 정리기 클래스에
 * 의존하지 않게 공용 위치에 둔다.
 */
public final class ExecutionMessages {

  /** 하트비트가 끊겨(주인 JVM 이 죽어) 정리기가 닫은 실행의 오류 문구. */
  public static final String INTERRUPTED = "api 가 재시작되어 실행이 중단되었습니다";

  private ExecutionMessages() {}
}
