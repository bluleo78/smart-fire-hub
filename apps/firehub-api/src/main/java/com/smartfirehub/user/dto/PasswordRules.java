package com.smartfirehub.user.dto;

import java.nio.charset.StandardCharsets;

/**
 * 비밀번호 규칙 — 가입(SignupRequest)·변경(ChangePasswordRequest)·멤버 추가(AddMemberRequest)·운영자 계정 생성
 * (CreatePlatformAccountRequest)이 함께 쓴다.
 *
 * <p>WD-270: 길이 위반에 기본 Bean Validation 문구가 화면에 그대로 떴다. 네 DTO 가 같은 규칙·한국어 문구를 쓰도록 한 곳에 둔다(애너테이션 값은
 * 컴파일 시 상수여야 해서 상수로 공유한다). 화면 상한은 web·admin 의 PASSWORD_MAX_BYTES 와 맞춘다.
 *
 * <p>WD-605: 상한을 글자 수(128자)가 아닌 <b>UTF-8 72바이트</b>로 바꿨다. 저장·검증에 쓰는 BCrypt 가 입력의 앞 72바이트만 해시하므로, 그보다
 * 긴 비밀번호는 뒤쪽이 비밀번호 구실을 못 했다(100자 비밀번호를 앞 72자만으로 로그인 가능, 한글은 약 24자까지만 유효). 해시 방식은 바꾸지 않고 "설정하는"
 * 경로에서만 막는다 — 로그인·현재 비밀번호 확인은 길이를 보지 않아 예전에 길게 만든 비밀번호도 계속 쓸 수 있다(BCrypt 가 예전처럼 앞 72바이트로 비교).
 * 72바이트면 글자 수도 72자 이하라 별도 글자 수 상한은 두지 않는다.
 */
public final class PasswordRules {

  public static final int MIN_LENGTH = 8;

  /** BCrypt 가 실제로 쓰는 입력 길이(UTF-8 바이트) — 이보다 길면 뒤가 버려진다(WD-605). */
  public static final int MAX_BYTES = 72;

  public static final String MIN_LENGTH_MESSAGE = "비밀번호는 8자 이상이어야 합니다";

  /** 변경 화면은 "새 비밀번호" 칸이라 주어를 맞춘다. */
  public static final String NEW_PASSWORD_MIN_LENGTH_MESSAGE = "새 비밀번호는 8자 이상이어야 합니다";

  /** 바이트 상한 문구 — 사용자는 바이트를 모르므로 영문·한글 기준 글자 수를 함께 알린다(한글 1자 = 3바이트). */
  public static final String MAX_BYTES_MESSAGE = "비밀번호는 72바이트(영문·숫자 72자, 한글 24자) 이하여야 합니다";

  public static final String NEW_PASSWORD_MAX_BYTES_MESSAGE =
      "새 비밀번호는 72바이트(영문·숫자 72자, 한글 24자) 이하여야 합니다";

  /** 소문자·대문자·숫자 각 1자 이상. */
  public static final String PATTERN = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).+$";

  public static final String PATTERN_MESSAGE = "비밀번호는 대문자, 소문자, 숫자를 각각 1자 이상 포함해야 합니다";

  private PasswordRules() {}

  /**
   * 비밀번호가 BCrypt 상한(UTF-8 {@value #MAX_BYTES}바이트) 안인지. null 은 true — 필수 여부는 {@code @NotBlank} 가
   * 맡는다.
   *
   * <p>UTF-8 은 1자가 최소 1바이트라 글자 수가 상한을 넘으면 인코딩 없이 바로 거부한다(아주 긴 입력에 불필요한 배열을 만들지 않는다).
   */
  public static boolean withinMaxBytes(String password) {
    if (password == null) {
      return true;
    }
    if (password.length() > MAX_BYTES) {
      return false;
    }
    return password.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES;
  }
}
