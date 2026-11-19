package com.smartfirehub.pipeline.exception;

import com.smartfirehub.global.exception.CodedApiException;
import org.springframework.http.HttpStatus;

/**
 * 대기(PENDING)·실행 중(RUNNING)인 실행이 이미 있는 파이프라인을 수동으로 또 실행하려 할 때 발생하는 예외 — 409 + {@link #CODE}(WD-825).
 *
 * <p>같은 파이프라인의 수동 실행이 동시에 둘 접수돼 같은 출력 데이터셋을 함께 바꾸는 것을 막는다. 수동 실행만 막고, 트리거 실행은 기존 동시 실행 정책을 따른다.
 * 문구는 화면 토스트·AI 도구 설명이 같은 문장을 쓴다.
 */
public class PipelineAlreadyRunningException extends CodedApiException {
  /** 이미 진행 중인 실행 때문에 수동 실행할 수 없음을 뜻하는 기계용 오류 코드. */
  public static final String CODE = "PIPELINE_ALREADY_RUNNING";

  /** 취소 요청이 없는 진행 중 실행이 있을 때 — 끝나길 기다리거나 먼저 취소해야 한다. */
  public static final String MESSAGE = "이미 실행 중인 파이프라인입니다. 끝나면 실행 이력에서 결과를 확인하세요.";

  /** 진행 중 실행이 모두 이미 취소를 요청했을 때 — 다시 취소하라고 하면 헛걸음이라 기다리라고 안내한다. */
  public static final String CANCELLING_MESSAGE = "실행 취소가 진행 중입니다. 취소가 끝난 뒤 다시 실행하세요.";

  /**
   * @param cancelling 진행 중 실행이 모두 취소 요청 상태면 true(안내 문구가 달라진다)
   */
  public PipelineAlreadyRunningException(boolean cancelling) {
    super(HttpStatus.CONFLICT, CODE, cancelling ? CANCELLING_MESSAGE : MESSAGE);
  }
}
