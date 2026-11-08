package com.smartfirehub.user.exception;

import com.smartfirehub.global.exception.CodedApiException;
import org.springframework.http.HttpStatus;

/**
 * 새 비밀번호가 현재 비밀번호와 같다 — 400 + {@link #CODE}(WD-572).
 *
 * <p>같은 값으로 「변경」하면 임시 비밀번호가 그대로인데 변경 강제 표식만 꺼지므로 서버가 최종 판정한다(WD-2 리뷰 지적 1). 현재 비밀번호 불일치({@link
 * WrongCurrentPasswordException})와 코드로 구분된다. 상태·문구는 예전 IllegalArgumentException 과 같다.
 */
public class SameAsCurrentPasswordException extends CodedApiException {
  /** 새 비밀번호가 현재와 같음을 뜻하는 기계용 오류 코드. */
  public static final String CODE = "SAME_AS_CURRENT_PASSWORD";

  /** 사용자에게 보이는 문구. */
  public static final String MESSAGE = "새 비밀번호는 현재 비밀번호와 달라야 합니다";

  public SameAsCurrentPasswordException() {
    super(HttpStatus.BAD_REQUEST, CODE, MESSAGE);
  }
}
