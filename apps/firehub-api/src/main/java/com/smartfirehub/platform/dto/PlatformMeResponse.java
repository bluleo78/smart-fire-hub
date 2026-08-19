package com.smartfirehub.platform.dto;

import java.util.List;

/** 현재 운영자 세션 정보. 테넌트 정보는 담지 않는다 — 운영자 토큰에는 테넌트가 없다. */
public record PlatformMeResponse(
    Long userId, String username, String name, List<String> permissions) {}
