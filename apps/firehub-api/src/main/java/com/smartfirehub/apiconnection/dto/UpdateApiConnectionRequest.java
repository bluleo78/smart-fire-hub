package com.smartfirehub.apiconnection.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;

/** API 연결 수정 요청 DTO. 모든 필드 Optional — null이면 해당 필드 미변경. @Pattern은 null일 때 통과하므로 별도 null 체크 불필요. */
public record UpdateApiConnectionRequest(
    @Size(max = 100) String name,
    String description,
    String authType,
    Map<String, String> authConfig,
    @Pattern(regexp = "^https?://.+|^$", message = "http:// 또는 https://로 시작하는 URL이어야 합니다")
        @Size(max = 500)
        String baseUrl,
    @Pattern(regexp = "^/.*|^$", message = "경로는 /로 시작해야 합니다") @Size(max = 500)
        String healthCheckPath) {}
