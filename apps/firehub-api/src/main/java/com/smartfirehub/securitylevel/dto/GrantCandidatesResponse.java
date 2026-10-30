package com.smartfirehub.securitylevel.dto;

import java.util.List;

/** 허용 목록 추가 후보. dataset:grant 보유자는 user:read·role:read 가 없을 수 있어 전용 조회로 이름만 준다(판단 사항 15). */
public record GrantCandidatesResponse(List<UserCandidate> users, List<RoleCandidate> roles) {

  /**
   * 사용자 후보.
   *
   * @param clearance 유효 열람 등급(보유 역할 등급의 최댓값) — 역할이 없으면 null (WD-292)
   * @param sufficientClearance 열람 등급이 이 데이터셋 등급 이상인가 — false 면 허용 목록에 넣어도 볼 수 없다 (WD-292)
   */
  public record UserCandidate(
      Long id,
      String name,
      String email,
      SubjectClearance clearance,
      boolean sufficientClearance) {}

  /**
   * 역할 후보.
   *
   * @param clearance 역할의 열람 등급(max_security_level) (WD-292)
   * @param sufficientClearance 역할 열람 등급이 이 데이터셋 등급 이상인가 (WD-292)
   */
  public record RoleCandidate(
      Long id, String name, SubjectClearance clearance, boolean sufficientClearance) {}
}
