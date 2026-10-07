package com.smartfirehub.securitylevel.service;

import org.springframework.stereotype.Component;

/** allowlist_required 켜기 시 빈 허용 목록 시드(스펙 §4.7). 본문은 Task 9. */
@Component
public class AllowlistSeeder {

  /**
   * 허용 목록이 비어 있는 해당 등급 데이터셋에 현재 열람 가능한 역할을 채운다.
   *
   * @return 추가한 허용 항목 수
   */
  public int seedEmptyAllowlistsWithViewerRoles(long levelId, long actor) {
    return 0;
  }
}
