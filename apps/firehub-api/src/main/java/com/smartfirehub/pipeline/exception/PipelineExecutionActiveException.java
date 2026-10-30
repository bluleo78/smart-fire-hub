package com.smartfirehub.pipeline.exception;

import com.smartfirehub.global.exception.CodedApiException;
import org.springframework.http.HttpStatus;

/**
 * 대기(PENDING)·실행 중(RUNNING)인 실행이 있는 파이프라인을 지우려 할 때 발생하는 예외 — 409 + {@link #CODE}(WD-437).
 *
 * <p>지우면 실행 기록은 FK CASCADE 로 사라지는데, 이미 돌고 있는 러너는 계속 돌아 출력 데이터셋을 바꾸고(REPLACE 포함) 대기 중 실행은 나중에 시작해
 * 버린다 — 기록 없이 데이터만 바뀌므로 먼저 실행을 취소하게 한다. 문구는 화면 토스트·삭제 확인 창·AI 도구 설명이 같은 문장을 쓴다.
 */
public class PipelineExecutionActiveException extends CodedApiException {
  /** 진행 중 실행 때문에 삭제할 수 없음을 뜻하는 기계용 오류 코드. */
  public static final String CODE = "PIPELINE_EXECUTION_ACTIVE";

  /** 취소 요청이 없는 진행 중 실행이 있을 때 — 사용자가 먼저 취소해야 한다. */
  public static final String MESSAGE = "실행 중에는 삭제할 수 없습니다. 실행을 취소한 뒤 삭제하세요";

  /** 진행 중 실행이 모두 이미 취소를 요청했을 때 — 다시 취소하라고 하면 헛걸음이라 기다리라고 안내한다. */
  public static final String CANCELLING_MESSAGE = "실행 취소가 진행 중입니다. 취소가 끝난 뒤 다시 삭제하세요";

  /**
   * @param cancelling 진행 중 실행이 모두 취소 요청 상태면 true(안내 문구가 달라진다)
   */
  public PipelineExecutionActiveException(boolean cancelling) {
    super(HttpStatus.CONFLICT, CODE, cancelling ? CANCELLING_MESSAGE : MESSAGE);
  }
}
