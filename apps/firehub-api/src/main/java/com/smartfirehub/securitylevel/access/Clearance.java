package com.smartfirehub.securitylevel.access;

import java.util.Set;

/**
 * 사용자 1명의 열람 자격 스냅샷(스펙 §2.3) — 불변.
 *
 * <p>왜 불변 값인가: 파이프라인 러너(@Async)·메트릭 폴러(@Scheduled)에는 SecurityContext 가 없다. 자격을 명시 인자로 넘겨야 같은 가드를
 * 요청·비요청 경로가 공유할 수 있다.
 *
 * @param rank 보유한(ACTIVE 멤버십의) 역할들의 max_security_level rank 최댓값. 역할 0개면 {@link #NO_RANK}
 * @param tenantAdmin 시스템 ADMIN 역할 보유 여부(admin_bypass 판정용)
 * @param permissions 현재 테넌트의 권한 코드(EXPORT 판정용)
 */
public record Clearance(
    long userId,
    long tenantId,
    int rank,
    Set<Long> roleIds,
    boolean tenantAdmin,
    Set<String> permissions) {

  /** 역할이 하나도 없는 사용자 — 어떤 등급도 볼 수 없다. */
  public static final int NO_RANK = Integer.MIN_VALUE;

  public Clearance {
    roleIds = Set.copyOf(roleIds);
    permissions = Set.copyOf(permissions);
  }

  /** 아무것도 볼 수 없는 자격(사용자 미상·비활성 등 fail-closed 기본값). */
  public static Clearance none(long userId, long tenantId) {
    return new Clearance(userId, tenantId, NO_RANK, Set.of(), false, Set.of());
  }

  /** 역할 자격 변경 미리보기용 — rank 만 바꾼 사본. */
  public Clearance withRank(int newRank) {
    return new Clearance(userId, tenantId, newRank, roleIds, tenantAdmin, permissions);
  }
}
