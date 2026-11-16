package com.smartfirehub.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 멤버 추가 요청(WD-2).
 *
 * <p>이름·임시 비밀번호는 <b>새 계정일 때만 필수</b>다(WD-751). 기존 계정이면 둘 다 무시하므로 웹은 보내지 않는다. 그래서 여기서는
 * {@code @NotBlank} 를 두지 않고, 값이 있을 때의 형식(길이·정책)만 본다 — null 은 통과한다. 필수 여부는 계정 존재를 아는 {@code
 * UserService.addMember} 가 판정한다(없거나 공백뿐이면 400 NEW_ACCOUNT_FIELDS_REQUIRED).
 *
 * @param email 이메일 = 신규 계정의 username(현행 가입 규칙과 동일)
 * @param name 새 계정의 표시 이름. 새 계정일 때만 필수, 기존 계정이면 무시(이름 불변)
 * @param temporaryPassword 새 계정일 때만 필수. 기존 계정이면 무시(비밀번호 불변). 정책은 가입과 동일
 * @param roleIds 현재 테넌트 역할 id. null/빈 목록 허용 — USER 는 서버가 항상 더한다
 */
public record AddMemberRequest(
    // 50 = "user".username·name 컬럼 길이(V1 VARCHAR(50)). 이메일이 곧 username 이라 50 을 넘기면 DB 오류(500)가 난다.
    @NotBlank(message = "이메일은 필수입니다")
        @Email(message = "올바른 이메일 형식이 아닙니다")
        @Size(max = 50, message = "이메일은 50자 이하여야 합니다")
        String email,
    // 새 계정일 때만 필수(WD-751) — 필수 판정은 서비스가 한다. 값이 있으면 길이만 본다.
    @Size(max = 50, message = "이름은 50자 이하여야 합니다") String name,
    // 새 계정일 때만 필수(WD-751). 값이 있으면 정책을 본다 — 길이: 하한은 글자 수, 상한은 BCrypt 가 실제로 쓰는 UTF-8 72바이트 (WD-605)
    @Size(min = PasswordRules.MIN_LENGTH, message = PasswordRules.MIN_LENGTH_MESSAGE)
        @PasswordMaxBytes
        @Pattern(regexp = PasswordRules.PATTERN, message = PasswordRules.PATTERN_MESSAGE)
        String temporaryPassword,
    List<Long> roleIds) {}
