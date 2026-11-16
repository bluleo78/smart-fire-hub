package com.smartfirehub.platform.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.smartfirehub.user.dto.PasswordMaxBytes;
import com.smartfirehub.user.dto.PasswordRules;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 운영자 콘솔의 테넌트 멤버 추가 요청(WD-750).
 *
 * <p>두 경로가 있다.
 *
 * <ul>
 *   <li><b>기존 계정 경로</b> — {@code userId} 가 있으면 그 계정을 붙인다. email·name·temporaryPassword 는 무시한다. 콘솔이
 *       검색 결과에서 고른 계정은 id 로 보내야 한다: {@code "user".username} 과 {@code email} 은 별개 컬럼이라(email 은
 *       nullable) email 로 username 을 찾으면 username ≠ email 인 계정을 못 찾고 엉뚱한 새 계정을 만든다.
 *   <li><b>새 계정/이메일 경로</b> — {@code userId} 가 없으면 email·name·temporaryPassword 가 필수다. 이메일(=
 *       username)로 기존 계정을 찾고, 없으면 새로 만든다.
 * </ul>
 *
 * <p>형식 검증(@Email·@Size·비밀번호 규칙)은 워크스페이스 멤버 추가({@code AddMemberRequest}, WD-2)와 같고, 값이 있을 때만
 * 적용된다(Bean Validation 의 형식 애너테이션은 null 을 통과시킨다). 「필수」는 경로에 따라 달라 필드의 {@code @NotBlank} 대신 아래
 * {@code @AssertTrue} 메서드로 건다 — 응답은 기존과 같은 400 VALIDATION 모양(필드별 문구)이다. 역할은 id 목록이 아니라 {@code
 * admin} 하나로 받는다: 운영자는 대상 테넌트의 역할 id 를 모르고, 콘솔이 줄 수 있는 선택은 「기본 USER」와 「USER + ADMIN」 둘뿐이다.
 *
 * @param userId 기존 계정 id. 있으면 기존 계정 경로
 * @param email 이메일 = 신규 계정의 username. 서버가 trim + 소문자로 정규화한다(userId 경로에서는 무시)
 * @param name 신규 계정일 때의 이름. 기존 계정이면 무시(이름 불변)
 * @param temporaryPassword 신규 계정일 때만 쓰인다. 기존 계정이면 무시(비밀번호 불변)
 * @param admin true 면 USER 에 더해 ADMIN 역할도 준다(생략 시 false)
 */
public record PlatformAddMemberRequest(
    Long userId,
    // 50 = "user".username·name 컬럼 길이(V1 VARCHAR(50)). 이메일이 곧 username 이라 50 을 넘기면 DB 오류(500)가 난다.
    @Email(message = "올바른 이메일 형식이 아닙니다") @Size(max = 50, message = "이메일은 50자 이하여야 합니다")
        String email,
    @Size(max = 50, message = "이름은 50자 이하여야 합니다") String name,
    // 길이: 하한은 글자 수, 상한은 BCrypt 가 실제로 쓰는 UTF-8 72바이트 (WD-605)
    @Size(min = PasswordRules.MIN_LENGTH, message = PasswordRules.MIN_LENGTH_MESSAGE)
        @PasswordMaxBytes
        @Pattern(regexp = PasswordRules.PATTERN, message = PasswordRules.PATTERN_MESSAGE)
        String temporaryPassword,
    boolean admin) {

  /**
   * 이메일 앞뒤 공백을 검증 <b>전에</b> 걷어 낸다.
   *
   * <p>{@code @Email} 은 공백이 섞인 값을 형식 오류(400)로 거절한다. 운영자가 다른 곳에서 붙여 넣은 {@code " Foo@X.com "} 도 기존 계정
   * {@code foo@x.com} 과 같은 사람으로 받아야 하므로(대소문자는 서비스가 소문자로 맞춘다), Bean Validation 이 보는 값 자체를 trim 한다.
   * null 은 그대로 둬 아래 「필수」 검사가 거절하게 한다.
   */
  public PlatformAddMemberRequest {
    email = email == null ? null : email.trim();
  }

  /** 기존 계정 경로인지 — userId 가 있으면 email·name·temporaryPassword 를 보지 않는다. */
  @JsonIgnore
  public boolean byUserId() {
    return userId != null;
  }

  /** 새 계정/이메일 경로면 이메일 필수. 키는 {@code emailRequired} 로 응답 errors 에 실린다. */
  @JsonIgnore
  @AssertTrue(message = "이메일은 필수입니다")
  public boolean isEmailRequired() {
    return byUserId() || hasText(email);
  }

  /** 새 계정/이메일 경로면 이름 필수. */
  @JsonIgnore
  @AssertTrue(message = "이름은 필수입니다")
  public boolean isNameRequired() {
    return byUserId() || hasText(name);
  }

  /** 새 계정/이메일 경로면 임시 비밀번호 필수. */
  @JsonIgnore
  @AssertTrue(message = "임시 비밀번호는 필수입니다")
  public boolean isTemporaryPasswordRequired() {
    return byUserId() || hasText(temporaryPassword);
  }

  /** {@code @NotBlank} 와 같은 기준 — null·빈 문자열·공백만이면 없는 것으로 본다. */
  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }
}
