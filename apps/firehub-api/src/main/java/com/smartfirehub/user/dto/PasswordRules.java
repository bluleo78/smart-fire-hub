package com.smartfirehub.user.dto;

/**
 * 비밀번호 규칙 — 가입(SignupRequest)·변경(ChangePasswordRequest)·멤버 추가(AddMemberRequest)가 함께 쓴다.
 *
 * <p>WD-270: 길이 위반에 기본 Bean Validation 문구("크기가 8에서 128 사이여야 합니다")가 화면에 그대로 떴다. 세 DTO 가 같은 규칙·한국어
 * 문구를 쓰도록 한 곳에 둔다(애너테이션 값은 컴파일 시 상수여야 해서 상수로 공유한다). 화면 상한은 web 의 PASSWORD_MAX_LENGTH 와 맞춘다.
 */
public final class PasswordRules {

  public static final int MIN_LENGTH = 8;

  public static final int MAX_LENGTH = 128;

  public static final String SIZE_MESSAGE = "비밀번호는 8~128자여야 합니다";

  /** 변경 화면은 "새 비밀번호" 칸이라 주어를 맞춘다. */
  public static final String NEW_PASSWORD_SIZE_MESSAGE = "새 비밀번호는 8~128자여야 합니다";

  /** 소문자·대문자·숫자 각 1자 이상. */
  public static final String PATTERN = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).+$";

  public static final String PATTERN_MESSAGE = "비밀번호는 대문자, 소문자, 숫자를 각각 1자 이상 포함해야 합니다";

  private PasswordRules() {}
}
