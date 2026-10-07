package com.smartfirehub.securitylevel.dto;

import java.util.List;

/** 허용 목록 추가 후보. dataset:grant 보유자는 user:read·role:read 가 없을 수 있어 전용 조회로 이름만 준다(판단 사항 15). */
public record GrantCandidatesResponse(List<UserCandidate> users, List<RoleCandidate> roles) {

  public record UserCandidate(Long id, String name, String email) {}

  public record RoleCandidate(Long id, String name) {}
}
