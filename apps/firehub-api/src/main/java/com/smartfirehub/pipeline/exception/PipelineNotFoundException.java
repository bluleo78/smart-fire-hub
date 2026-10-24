package com.smartfirehub.pipeline.exception;

/**
 * 파이프라인·실행 기록·스텝을 찾지 못했을 때 던진다 — 전역 핸들러가 404 로 바꾸고 message 를 그대로 내보낸다.
 *
 * <p>WD-226: message 는 화면 토스트와 AI 챗에 그대로 실리므로 한국어로 쓰고, 내부 id 는 넣지 않는다. 문구가 호출처마다 흩어지지 않도록 생성자·팩토리로만
 * 만들게 한다(임의 문자열 생성자는 두지 않는다). 남의 테넌트·다른 파이프라인의 자원과 없는 자원을 같은 문구로 답해 존재를 드러내지 않는 정책은 그대로다.
 */
public class PipelineNotFoundException extends RuntimeException {

  /** 파이프라인이 없을 때 — 웹의 없는 URL 진입 화면 문구(「파이프라인을 찾을 수 없습니다.」)와 앞부분을 맞춘다. */
  public static final String PIPELINE_MESSAGE = "파이프라인을 찾을 수 없습니다. 이미 삭제되었을 수 있습니다.";

  /** 실행 기록이 없거나 이 파이프라인의 실행이 아닐 때. */
  public static final String EXECUTION_MESSAGE = "실행 기록을 찾을 수 없습니다. 이미 삭제되었을 수 있습니다.";

  /** 스텝이 없거나 이 파이프라인의 스텝이 아닐 때. */
  public static final String STEP_MESSAGE = "스텝을 찾을 수 없습니다. 이미 삭제되었을 수 있습니다.";

  /** 파이프라인을 찾지 못했다는 기본 문구로 만든다. */
  public PipelineNotFoundException() {
    this(PIPELINE_MESSAGE);
  }

  private PipelineNotFoundException(String message) {
    super(message);
  }

  /** 실행 기록을 찾지 못했을 때의 예외. */
  public static PipelineNotFoundException execution() {
    return new PipelineNotFoundException(EXECUTION_MESSAGE);
  }

  /** 스텝을 찾지 못했을 때의 예외. */
  public static PipelineNotFoundException step() {
    return new PipelineNotFoundException(STEP_MESSAGE);
  }
}
