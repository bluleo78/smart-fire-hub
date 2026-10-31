package com.smartfirehub.user.exception;

import com.smartfirehub.global.exception.CodedApiException;
import org.springframework.http.HttpStatus;

/**
 * 사용자를 찾지 못했을 때 던진다 — {@link CodedApiException} 이라 전역 핸들러가 404 로 바꾸고 message(와 code)를 그대로 내보낸다.
 *
 * <p>WD-288: message 는 화면 토스트에 그대로 실리므로 한국어로 쓰고, 내부 id 는 넣지 않는다(예전 「User not found: 26」). 문구가
 * 호출처마다 흩어지지 않도록 기본 생성자·팩토리로만 만든다(임의 문자열 생성자는 두지 않는다). 없는 사용자와 남의 테넌트 사용자를 같은 문구로 답해 계정 존재를
 * 드러내지 않는 정책은 그대로다.
 */
public class UserNotFoundException extends CodedApiException {

  /** 사용자(계정)가 없을 때 — 내 프로필·비밀번호·운영자 콘솔 계정 관리. */
  public static final String MESSAGE = "사용자를 찾을 수 없습니다.";

  /** 사용자 관리 대상이 이 워크스페이스의 멤버가 아닐 때 — 다른 관리자가 그사이 제거한 경우가 대부분이라 그렇게 안내한다. */
  public static final String NOT_IN_WORKSPACE_MESSAGE =
      "이 워크스페이스에서 찾을 수 없는 사용자입니다. 이미 제거되었을 수 있습니다.";

  /**
   * 워크스페이스 멤버가 아니라는 404 의 기계용 코드 — 웹 사용자 상세가 다른 404(없는 경로 등)와 가려 「제거된 멤버」로 안내하고 목록으로 돌아갈 때
   * 쓴다(웹 api-error.ts 의 같은 값).
   */
  public static final String NOT_IN_WORKSPACE_CODE = "USER_NOT_IN_WORKSPACE";

  /** 사용자를 찾지 못했다는 기본 문구로 만든다(code 없음). */
  public UserNotFoundException() {
    this(null, MESSAGE);
  }

  private UserNotFoundException(String code, String message) {
    super(HttpStatus.NOT_FOUND, code, message);
  }

  /** 사용자 관리 대상이 현재 워크스페이스의 멤버가 아닐 때의 예외. */
  public static UserNotFoundException notInWorkspace() {
    return new UserNotFoundException(NOT_IN_WORKSPACE_CODE, NOT_IN_WORKSPACE_MESSAGE);
  }
}
