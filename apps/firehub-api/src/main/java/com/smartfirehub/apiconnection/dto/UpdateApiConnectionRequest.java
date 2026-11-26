package com.smartfirehub.apiconnection.dto;

import com.smartfirehub.global.validation.DescriptionLimits;
import com.smartfirehub.global.validation.TextRules;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;

/**
 * API 연결 수정 요청 DTO. 모든 필드 Optional — null이면 해당 필드 미변경. @Pattern은 null일 때 통과하므로 별도 null 체크 불필요. 이름은
 * 생략(null)은 되지만 빈·공백만의 값으로는 바꿀 수 없다(WD-1080).
 */
public record UpdateApiConnectionRequest(
    // WD-1125: NUL(U+0000) 문자는 검증 단계에서 칸 오류로 막는다(이유는 TextRules.NO_NUL)
    @Size(max = 100)
        @Pattern(regexp = TextRules.HAS_TEXT, message = TextRules.NAME_BLANK_MESSAGE)
        @Pattern(regexp = TextRules.NO_NUL, message = TextRules.NUL_MESSAGE)
        String name,
    @Size(max = DescriptionLimits.MAX_LENGTH, message = DescriptionLimits.API_CONNECTION_TOO_LONG)
        @Pattern(regexp = TextRules.NO_NUL, message = TextRules.NUL_MESSAGE)
        String description,
    String authType,
    Map<String, String> authConfig,
    @Pattern(regexp = "^https?://.+|^$", message = "http:// 또는 https://로 시작하는 URL이어야 합니다")
        @Size(max = 500)
        @Pattern(regexp = TextRules.NO_NUL, message = TextRules.NUL_MESSAGE)
        String baseUrl,
    @Pattern(regexp = "^/.*|^$", message = "경로는 /로 시작해야 합니다")
        @Size(max = 500)
        @Pattern(regexp = TextRules.NO_NUL, message = TextRules.NUL_MESSAGE)
        String healthCheckPath) {}
