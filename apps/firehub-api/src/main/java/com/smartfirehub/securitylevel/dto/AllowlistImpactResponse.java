package com.smartfirehub.securitylevel.dto;

/** 허용 목록 켜기 확인 다이얼로그용 — "이 등급 데이터셋 N개의 허용 목록이 비어 있어 저장 즉시 아무도 볼 수 없게 됩니다." */
public record AllowlistImpactResponse(long datasetsWithoutAllowlist) {}
