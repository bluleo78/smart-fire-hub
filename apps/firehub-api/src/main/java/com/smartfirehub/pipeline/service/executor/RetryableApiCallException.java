package com.smartfirehub.pipeline.service.executor;

import com.smartfirehub.global.exception.ExternalServiceException;

/**
 * 다시 보내면 나아질 수 있는 API 호출 실패(WD-693) — {@link RedirectFollower} 가 낸다. 메시지는 이미 사용자 문구다({@link
 * SafeUserMessage}).
 *
 * <ul>
 *   <li>재시도할 HTTP 상태(5xx·408·429) — {@link ApiCallErrorMessages#httpStatus} 문구(「대상 서버 오류(HTTP 503)」
 *       등).
 *   <li>홉 하나의 전송 오류(DNS·연결·시간 초과) — 그 홉의 호스트로 분류한 {@link ApiCallErrorMessages#describe} 문구(인증 비밀은
 *       가린다). 예전에는 호출부가 처음 호스트로 분류해, 리다이렉트 뒤 홉의 DNS 실패도 처음 호스트를 말했다.
 * </ul>
 *
 * <p><b>왜 따로 두는가.</b> {@link ExternalServiceException} 은 다른 패키지도 쓰는 범용 예외라 메시지에 무엇이 담길지 이 패키지가 보장하지
 * 못한다. {@link ApiCallErrorMessages#describe} 는 이 타입(표지)의 메시지만 그대로 쓰고, 그 밖의 {@code
 * ExternalServiceException} 은 일반 분류(URL 가리기 포함)를 거치게 한다. 상위 타입은 그대로 {@code
 * ExternalServiceException} 이라 실행기의 재시도 판단(ApiCallException·SsrfException 은 즉시 실패, 그 밖은 재시도)이 바뀌지
 * 않는다.
 */
public class RetryableApiCallException extends ExternalServiceException implements SafeUserMessage {

  public RetryableApiCallException(String message) {
    super(message);
  }

  /** 전송 오류용 — 원래 예외를 원인으로 남긴다(디버그 로그·분류용). */
  public RetryableApiCallException(String message, Throwable cause) {
    super(message, cause);
  }
}
