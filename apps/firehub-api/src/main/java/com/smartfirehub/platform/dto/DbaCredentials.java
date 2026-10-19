package com.smartfirehub.platform.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 외부 DB 에 테넌트 계정을 대신 만들어 줄 DBA 자격(WD-73). 요청 처리 동안 메모리에만 존재하고 저장하지 않는다.
 *
 * <p>비밀번호가 로그·예외 메시지로 새지 않도록 {@code toString()} 은 비밀번호를 가린다.
 */
public record DbaCredentials(
    @NotBlank @Size(max = 128) String username, @NotBlank @Size(max = 256) String password) {

  /** 레코드 기본 toString 은 모든 필드를 찍으므로 재정의해 비밀번호를 가린다. */
  @Override
  public String toString() {
    return "DbaCredentials[username=" + username + ", password=****]";
  }
}
