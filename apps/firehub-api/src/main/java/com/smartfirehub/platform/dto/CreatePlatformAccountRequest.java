package com.smartfirehub.platform.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 운영자 콘솔 계정 생성 요청(WD-46).
 *
 * <p>검증 규칙은 워크스페이스 멤버 추가({@code AddMemberRequest}, WD-2)와 같다 — 같은 {@code "user"} 행을 같은 규칙으로 만들기
 * 때문이다. 역할·테넌트 필드는 없다: 이 경로는 소속 없는 계정만 만든다(소속은 테넌트 Owner 지정이나 멤버 추가가 맡는다).
 *
 * @param email 이메일 = 로그인 아이디(서버가 trim + 소문자로 정규화)
 * @param temporaryPassword 임시 비밀번호. 첫 로그인 때 변경이 강제된다
 */
public record CreatePlatformAccountRequest(
    // 50 = "user".username·name 컬럼 길이(V1 VARCHAR(50)). 이메일이 곧 username 이라 50 을 넘기면 DB 오류(500)가 난다.
    @NotBlank(message = "이메일은 필수입니다")
        @Email(message = "올바른 이메일 형식이 아닙니다")
        @Size(max = 50, message = "이메일은 50자 이하여야 합니다")
        String email,
    @NotBlank(message = "이름은 필수입니다") @Size(max = 50, message = "이름은 50자 이하여야 합니다") String name,
    @NotBlank(message = "임시 비밀번호는 필수입니다")
        @Size(min = 8, max = 128, message = "비밀번호는 8~128자여야 합니다")
        @Pattern(
            regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).+$",
            message = "비밀번호는 대문자, 소문자, 숫자를 각각 1자 이상 포함해야 합니다")
        String temporaryPassword) {}
