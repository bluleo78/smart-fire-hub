package com.smartfirehub.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 멤버 추가 요청(WD-2).
 *
 * @param email 이메일 = 신규 계정의 username(현행 가입 규칙과 동일)
 * @param temporaryPassword 신규 계정일 때만 쓰인다. 기존 계정이면 무시(비밀번호 불변). 정책은 가입과 동일
 * @param roleIds 현재 테넌트 역할 id. null/빈 목록 허용 — USER 는 서버가 항상 더한다
 */
public record AddMemberRequest(
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
        String temporaryPassword,
    List<Long> roleIds) {}
