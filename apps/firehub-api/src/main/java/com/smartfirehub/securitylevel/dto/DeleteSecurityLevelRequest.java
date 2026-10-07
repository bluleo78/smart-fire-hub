package com.smartfirehub.securitylevel.dto;

/** 등급 삭제 — 사용 중이면 이동 대상 필수, 하향 이동이면 사유(10자 이상) 필수(스펙 §4.7). */
public record DeleteSecurityLevelRequest(Long reassignToLevelId, String reason) {}
