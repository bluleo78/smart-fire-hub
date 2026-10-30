package com.smartfirehub.securitylevel.dto;

import com.smartfirehub.securitylevel.access.LevelPolicy;
import com.smartfirehub.securitylevel.access.LevelPolicy.AiPolicy;
import com.smartfirehub.securitylevel.access.LevelPolicy.ExportPolicy;
import com.smartfirehub.securitylevel.access.LevelPolicy.SharePolicy;

/**
 * 등급 편집 화면이 편집을 시작할 때 읽은 등급 값(WD-440) — 수정 요청에 함께 실어 서버의 지금 값과 다르면 409 로 거부한다.
 *
 * <p>왜 수정 시각이 아니라 값 자체인가: 등급 응답·정책 스냅숏({@link LevelPolicy})에는 수정 시각이 없고, 화면이 바꿀 수 있는 필드는 이 일곱 개뿐이다.
 * 이 값이 같으면 그사이 무엇이 바뀌었든(순서·기본 지정) 이 저장이 되돌리는 것이 없다.
 */
public record SecurityLevelBaseline(
    String name,
    boolean allowlistRequired,
    boolean adminBypass,
    ExportPolicy exportPolicy,
    AiPolicy aiPolicy,
    SharePolicy sharePolicy,
    boolean auditAccess) {

  /** 서버의 지금 등급 값이 이 기준과 같은지 — 화면이 본 뒤로 다른 관리자가 바꾸지 않았는지. */
  public boolean matches(LevelPolicy current) {
    return name.equals(current.name())
        && allowlistRequired == current.allowlistRequired()
        && adminBypass == current.adminBypass()
        && exportPolicy == current.exportPolicy()
        && aiPolicy == current.aiPolicy()
        && sharePolicy == current.sharePolicy()
        && auditAccess == current.auditAccess();
  }
}
