package com.smartfirehub.user.exception;

import com.smartfirehub.global.exception.CodedApiException;
import org.springframework.http.HttpStatus;

/**
 * 비활성화된 계정의 로그인·토큰 갱신·워크스페이스 선택을 막는다 — 401 + {@link #CODE}.
 *
 * <p>같은 401 인 자격증명 오류와 코드로 구분된다(WD-278). 비밀번호가 맞을 때만 던지므로 계정 상태가 비밀번호를 모르는 사람에게 드러나지 않는다. 응답은 공용
 * {@link CodedApiException} 처리기가 만든다.
 */
public class UserDeactivatedException extends CodedApiException {
  /** 비활성 계정을 뜻하는 기계용 오류 코드. */
  public static final String CODE = "ACCOUNT_DEACTIVATED";

  /** 로그인·토큰 갱신·워크스페이스 선택·운영자 로그인이 모두 같은 문구를 쓴다 — 사용자가 할 일(관리자 문의)까지 적는다. */
  public static final String MESSAGE = "비활성화된 계정입니다. 관리자에게 문의하세요.";

  public UserDeactivatedException() {
    super(HttpStatus.UNAUTHORIZED, CODE, MESSAGE);
  }
}
