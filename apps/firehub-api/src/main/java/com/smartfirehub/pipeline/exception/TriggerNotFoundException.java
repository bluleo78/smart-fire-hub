package com.smartfirehub.pipeline.exception;

/**
 * 트리거를 찾지 못했을 때 던진다 — 전역 핸들러가 404 로 바꾸고 message 를 그대로 내보낸다.
 *
 * <p>WD-226: message 는 화면 토스트와 AI 챗에 그대로 실리므로 한국어로 쓰고, 내부 id 는 넣지 않는다. 문구가 호출처마다 흩어지지 않도록 기본 문구
 * 생성자만 둔다.
 */
public class TriggerNotFoundException extends RuntimeException {

  /** 트리거가 없거나 이 파이프라인의 트리거가 아닐 때. */
  public static final String MESSAGE = "트리거를 찾을 수 없습니다. 이미 삭제되었을 수 있습니다.";

  /** 트리거를 찾지 못했다는 기본 문구로 만든다. */
  public TriggerNotFoundException() {
    super(MESSAGE);
  }
}
