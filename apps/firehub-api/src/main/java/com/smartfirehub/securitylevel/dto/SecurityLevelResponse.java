package com.smartfirehub.securitylevel.dto;

import com.smartfirehub.securitylevel.access.LevelPolicy;

/** 등급 정의 응답(설정 화면·배지·등급 선택 UI 공용). */
public record SecurityLevelResponse(
    Long id,
    String name,
    int rank,
    boolean isDefault,
    boolean allowlistRequired,
    boolean adminBypass,
    String exportPolicy,
    String aiPolicy,
    String sharePolicy,
    boolean auditAccess) {

  public static SecurityLevelResponse of(LevelPolicy p) {
    return new SecurityLevelResponse(
        p.id(),
        p.name(),
        p.rank(),
        p.isDefault(),
        p.allowlistRequired(),
        p.adminBypass(),
        p.exportPolicy().name(),
        p.aiPolicy().name(),
        p.sharePolicy().name(),
        p.auditAccess());
  }
}
