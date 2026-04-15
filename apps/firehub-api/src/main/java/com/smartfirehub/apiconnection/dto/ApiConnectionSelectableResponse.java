package com.smartfirehub.apiconnection.dto;

/**
 * 일반 사용자용 slim DTO.
 * 파이프라인 스텝 드롭다운에 노출.
 * 민감 필드(authConfig, healthCheckPath, last*) 제외.
 */
public record ApiConnectionSelectableResponse(
    Long id,
    String name,
    String authType,
    String baseUrl) {}
