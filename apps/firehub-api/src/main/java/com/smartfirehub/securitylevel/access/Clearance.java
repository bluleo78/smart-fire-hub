package com.smartfirehub.securitylevel.access;

import java.util.Set;

/** 사용자 1명의 열람 자격 스냅샷(스펙 §2.3). Task 3 에서 생성 경로가 붙는다. */
public record Clearance(
    long userId,
    long tenantId,
    int rank,
    Set<Long> roleIds,
    boolean tenantAdmin,
    Set<String> permissions) {

  /** 역할이 하나도 없는 사용자 — 어떤 등급도 볼 수 없다. */
  public static final int NO_RANK = Integer.MIN_VALUE;
}
