package com.smartfirehub.securitylevel.dto;

import com.smartfirehub.securitylevel.access.LevelPolicy.AiPolicy;
import com.smartfirehub.securitylevel.access.LevelPolicy.ExportPolicy;
import com.smartfirehub.securitylevel.access.LevelPolicy.SharePolicy;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 등급 추가·수정 요청.
 *
 * @param seedAllowlistFromViewers allowlist_required 를 켤 때 "현재 열람 가능한 역할로 허용 목록 채우기"(목업 기본 체크).
 *     null 은 false 로 본다.
 * @param expected 수정 때 화면이 편집을 시작하며 읽은 등급 값(WD-440). 지금 값과 다르면 409 EDIT_CONFLICT. null 이면 비교하지
 *     않는다(추가·기준 없이 부르는 호출부·「내 변경으로 덮어쓰기」).
 */
public record SecurityLevelRequest(
    @NotBlank @Size(max = 50) String name,
    // WD-598: 세 플래그는 원시 boolean 이면 본문에서 빠질 때 조용히 false 로 저장돼 허용 목록 강제·관리자 우회·열람 감사가
    // 경고 없이 꺼진다. 래퍼 + @NotNull 로 빠지거나 null 이면 400 으로 거부한다(정책 3종과 같은 규칙).
    @NotNull Boolean allowlistRequired,
    @NotNull Boolean adminBypass,
    @NotNull ExportPolicy exportPolicy,
    @NotNull AiPolicy aiPolicy,
    @NotNull SharePolicy sharePolicy,
    @NotNull Boolean auditAccess,
    Boolean seedAllowlistFromViewers,
    SecurityLevelBaseline expected) {

  /** 기준 값 없이 만드는 요청 — 추가 요청과 기준 없이 부르는 호출부용. */
  public SecurityLevelRequest(
      String name,
      Boolean allowlistRequired,
      Boolean adminBypass,
      ExportPolicy exportPolicy,
      AiPolicy aiPolicy,
      SharePolicy sharePolicy,
      Boolean auditAccess,
      Boolean seedAllowlistFromViewers) {
    this(
        name,
        allowlistRequired,
        adminBypass,
        exportPolicy,
        aiPolicy,
        sharePolicy,
        auditAccess,
        seedAllowlistFromViewers,
        null);
  }
}
