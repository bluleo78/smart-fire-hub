package com.smartfirehub.securitylevel.dto;

import com.smartfirehub.securitylevel.access.LevelPolicy;

/** 데이터셋 응답에 싣는 등급 요약. 웹은 이 값으로 배지 색(정책 파생)과 「보안」 탭 정책 칩을 그린다(스펙 §5). */
public record SecurityLevelSummary(
    Long id,
    String name,
    int rank,
    boolean allowlistRequired,
    String exportPolicy,
    String aiPolicy,
    String sharePolicy,
    boolean auditAccess) {

  /** 등급 정책 스냅샷을 응답용 요약으로 옮긴다. */
  public static SecurityLevelSummary of(LevelPolicy p) {
    return new SecurityLevelSummary(
        p.id(),
        p.name(),
        p.rank(),
        p.allowlistRequired(),
        p.exportPolicy().name(),
        p.aiPolicy().name(),
        p.sharePolicy().name(),
        p.auditAccess());
  }
}
