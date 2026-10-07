package com.smartfirehub.securitylevel.dto;

import jakarta.validation.constraints.NotNull;

/** 역할 자격 변경 요청. */
public record RoleClearanceRequest(@NotNull Long securityLevelId) {}
