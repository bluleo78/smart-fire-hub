package com.smartfirehub.user.exception;

import com.smartfirehub.global.exception.CodedApiException;
import org.springframework.http.HttpStatus;

/**
 * 비밀번호 변경 시 현재 비밀번호가 틀렸다 — 400 + {@link #CODE}(WD-572).
 *
 * <p>왜 코드가 필요한가: 같은 400 이 「새 비밀번호가 현재와 같음」({@link SameAsCurrentPasswordException})에도 쓰인다. 웹은 현재
 * 비밀번호가 틀린 경우에만 그 칸으로 포커스를 옮기므로, 문구가 아니라 고정 코드로 구분하게 한다. 상태·문구는 예전 IllegalArgumentException 과
 * 같다(#27).
 */
public class WrongCurrentPasswordException extends CodedApiException {
  /** 현재 비밀번호 불일치를 뜻하는 기계용 오류 코드. */
  public static final String CODE = "WRONG_CURRENT_PASSWORD";

  /** 사용자에게 보이는 문구 — 웹 화면이 그대로 보여 준다. */
  public static final String MESSAGE = "현재 비밀번호가 올바르지 않습니다";

  public WrongCurrentPasswordException() {
    super(HttpStatus.BAD_REQUEST, CODE, MESSAGE);
  }
}
