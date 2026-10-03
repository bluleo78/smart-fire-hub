package com.smartfirehub.global.exception;

import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * HTTP 상태와 기계용 오류 코드를 함께 싣는 예외.
 *
 * <p>왜 필요한가: 기존 예외는 타입 → 상태로만 매핑되고 본문에 코드가 없다. 웹은 같은 403 이라도 "권한 없음"(토스트) 과 "비밀번호 변경 필요"(화면 이동) 를
 * 다르게 처리해야 하므로, 메시지 문자열이 아니라 고정 코드로 구분하게 한다. 코드 문자열은 계획의 Global Constraints 목록만 쓴다.
 */
public class CodedApiException extends RuntimeException {

  private final HttpStatus status;
  private final String code;
  private final Map<String, String> details;

  public CodedApiException(HttpStatus status, String code, String message) {
    this(status, code, message, null);
  }

  /**
   * @param details 응답 {@code errors} 맵에 실을 부가 정보(예: 정지 멤버의 userId — 웹이 상세 링크를 만든다). 없으면 null.
   */
  public CodedApiException(
      HttpStatus status, String code, String message, Map<String, String> details) {
    super(message);
    this.status = status;
    this.code = code;
    this.details = details;
  }

  /** 응답 {@code errors} 에 실을 부가 정보(없으면 null). */
  public Map<String, String> details() {
    return details;
  }

  /** 응답 HTTP 상태. */
  public HttpStatus status() {
    return status;
  }

  /** 응답 본문 {@code code} 값. */
  public String code() {
    return code;
  }
}
