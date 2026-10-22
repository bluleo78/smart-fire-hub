package com.smartfirehub.apiconnection.exception;

/**
 * API 연결 입력·검증 오류(지원하지 않는 인증 방식, 잘못된 기준 URL, 내부 주소 거절 등) — HTTP 400 으로 나간다(WD-180).
 *
 * <p>연결이 없음은 하위 클래스 {@link ApiConnectionNotFoundException}(404)으로 던진다. 메시지는 화면·AI 챗에 그대로 보이므로 한국어
 * 안내 문장으로 쓴다.
 */
public class ApiConnectionException extends RuntimeException {
  public ApiConnectionException(String message) {
    super(message);
  }
}
