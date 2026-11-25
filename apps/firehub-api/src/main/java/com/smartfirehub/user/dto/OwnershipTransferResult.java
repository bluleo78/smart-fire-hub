package com.smartfirehub.user.dto;

import java.util.List;

/**
 * 소유권 이전 결과(WD-1132). 호출자(웹 경로·콘솔)가 감사 메타를 만드는 재료다.
 *
 * @param fromUserIds 소유자에서 ADMIN 으로 내려간 사용자 id(소유자가 없던 테넌트면 빈 목록)
 * @param toUserId 새 소유자 id
 * @param grantedAdminUserIds 이번에 ADMIN 역할을 새로 받은 사용자 id 전부(새 소유자 + 역할이 없던 옛 소유자) — 호출자가 ROLE_ASSIGN
 *     감사를 사용자별로 남기는 재료다(WD-117 과 같은 추적 규칙)
 */
public record OwnershipTransferResult(
    List<Long> fromUserIds, Long toUserId, List<Long> grantedAdminUserIds) {

  /** 새 소유자가 이번에 ADMIN 역할을 새로 받았는가 — grantedAdminUserIds 에서 파생하므로 따로 저장하지 않는다. */
  public boolean grantedAdmin() {
    return grantedAdminUserIds.contains(toUserId);
  }
}
