package com.smartfirehub.apiconnection.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;

/**
 * API 연결 생성 요청 DTO.
 * baseUrl은 필수, healthCheckPath는 선택(null 허용).
 */
public record CreateApiConnectionRequest(
    @NotBlank @Size(max = 100) String name,
    String description,
    @NotBlank String authType,
    Map<String, String> authConfig,
    @NotBlank
    @Pattern(regexp = "^https?://.+", message = "http:// 또는 https://로 시작하는 URL이어야 합니다")
    @Size(max = 500)
    String baseUrl,
    @Pattern(regexp = "^/.*", message = "경로는 /로 시작해야 합니다")
    @Size(max = 500)
    String healthCheckPath) {}
