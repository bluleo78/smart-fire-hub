package com.smartfirehub.apiconnection.dto;

import com.smartfirehub.global.validation.DescriptionLimits;
import com.smartfirehub.global.validation.TextRules;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;

/** API 연결 생성 요청 DTO. baseUrl은 필수, healthCheckPath는 선택(null 허용). */
public record CreateApiConnectionRequest(
    // @NotBlank 는 NBSP 계열을 글자로 봐, 다듬은 뒤 빈 이름이 저장되던 것을 막는다(WD-1085, WD-1081 과 같은 방식).
    // WD-1114: 빈 값("")도 공백뿐인 값과 같은 문구로 안내하려고 @NotBlank 문구를 TextRules 로 맞춘다
    // WD-1125: NUL(U+0000) 문자는 검증 단계에서 칸 오류로 막는다(이유는 TextRules.NO_NUL)
    @NotBlank(message = TextRules.NAME_BLANK_MESSAGE)
        @Size(max = 100)
        @Pattern(regexp = TextRules.HAS_TEXT, message = TextRules.NAME_BLANK_MESSAGE)
        @Pattern(regexp = TextRules.NO_NUL, message = TextRules.NUL_MESSAGE)
        String name,
    @Size(max = DescriptionLimits.MAX_LENGTH, message = DescriptionLimits.API_CONNECTION_TOO_LONG)
        @Pattern(regexp = TextRules.NO_NUL, message = TextRules.NUL_MESSAGE)
        String description,
    @NotBlank String authType,
    Map<String, String> authConfig,
    @NotBlank
        @Pattern(regexp = "^https?://.+", message = "http:// 또는 https://로 시작하는 URL이어야 합니다")
        @Size(max = 500)
        @Pattern(regexp = TextRules.NO_NUL, message = TextRules.NUL_MESSAGE)
        String baseUrl,
    @Pattern(regexp = "^/.*", message = "경로는 /로 시작해야 합니다")
        @Size(max = 500)
        @Pattern(regexp = TextRules.NO_NUL, message = TextRules.NUL_MESSAGE)
        String healthCheckPath) {}
