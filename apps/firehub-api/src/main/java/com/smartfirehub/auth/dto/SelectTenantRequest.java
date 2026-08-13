package com.smartfirehub.auth.dto;

import jakarta.validation.constraints.NotNull;

/** 활성 테넌트 선택/전환 요청. */
public record SelectTenantRequest(@NotNull Long tenantId) {}
