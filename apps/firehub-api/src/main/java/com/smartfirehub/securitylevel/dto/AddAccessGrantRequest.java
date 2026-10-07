package com.smartfirehub.securitylevel.dto;

/** 허용 항목 추가 — userId 와 roleId 중 정확히 하나. */
public record AddAccessGrantRequest(Long userId, Long roleId) {}
