package com.smartfirehub.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 멤버 추가 전 이메일 존재 확인 요청(WD-751).
 *
 * <p>GET 쿼리가 아니라 POST 본문으로 받는 이유: 이메일(개인정보)이 접근 로그·프록시 로그의 URL 에 남지 않게 한다. 형식 검증은 {@link
 * AddMemberRequest#email()} 과 같다 — 판정과 실제 추가가 같은 입력 규칙을 따라야 결과가 갈리지 않는다.
 *
 * @param email 확인할 이메일(= 계정 username). 정규화(trim·소문자)는 서비스가 멤버 추가와 같은 코드로 한다
 */
public record MemberLookupRequest(
    // 50 = "user".username 컬럼 길이. AddMemberRequest.email 과 같은 규칙
    @NotBlank(message = "이메일은 필수입니다")
        @Email(message = "올바른 이메일 형식이 아닙니다")
        @Size(max = 50, message = "이메일은 50자 이하여야 합니다")
        String email) {}
