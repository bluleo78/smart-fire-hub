package com.smartfirehub.securitylevel.dto;

import jakarta.validation.constraints.NotNull;

/** 데이터셋 등급 변경. 하향이면 reason(10자 이상) 필수(스펙 §4.7). */
public record ChangeDatasetLevelRequest(@NotNull Long securityLevelId, String reason) {}
