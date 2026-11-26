package com.smartfirehub.platform.dto;

/**
 * 테넌트의 표시용 라벨(slug·이름) — 감사 details 처럼 멤버 수 집계가 필요 없는 호출처가 쓰는 가벼운 조회 결과(WD-1180).
 */
public record TenantLabel(String slug, String name) {}
