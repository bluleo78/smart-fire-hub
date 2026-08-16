package com.smartfirehub.notification.auth.exception;

/**
 * Slack 앱 설치(OAuth 콜백)가 RLS 정책에 막혔을 때 던진다.
 *
 * <p><b>언제 나는가.</b> {@code slack_workspace.team_id} 유니크는 <b>전역</b>이다(접으면
 * {@code team_id → tenant} 해석이 다의가 되므로 일부러 전역). {@code upsertFromOAuth} 는
 * {@code ON CONFLICT (team_id) DO UPDATE} 인데, <b>충돌 판정은 유니크 인덱스가 하고 인덱스는 RLS 와
 * 무관하게 모든 행을 본다.</b> 그래서 이미 <b>다른 테넌트</b>가 설치해 둔 팀을 설치하면 그 남의 행과
 * 충돌해 DO UPDATE 로 내려가고, 정책이 그 행을 보여 주지 않아 PostgreSQL 이 {@code 42501}
 * (insufficient_privilege)로 거절한다. {@code 23505} 는 이 경로에서 절대 발생하지 않는다.
 *
 * <p><b>왜 별도 예외인가.</b> {@code 42501} 을 전역에서 통째로 4xx 로 바꾸면 컨텍스트 없이 도는
 * 배경 경로의 쓰기 같은 <b>진짜 배선 결함</b>까지 조용히 묻힌다 — 그건 500 으로 시끄럽게 터지는 것이
 * 맞다. 그래서 OAuth 설치 경로에서만 이 예외로 좁혀 변환한다.
 *
 * <p><b>{@code IllegalStateException} 을 상속하지 않는다.</b> 그쪽은
 * {@code MissingTenantScopeException}(테넌트 컨텍스트 부재 = 배선 결함) 계열이고, 이 예외는 권한
 * 거부다. 섞으면 배선 결함이 설치 충돌과 같은 채널로 묻힌다.
 */
public class SlackWorkspaceInstallDeniedException extends RuntimeException {

  /** 거부된 Slack 팀 ID. <b>서버 로그 전용</b> — 응답 바디에 실으면 오라클이 된다. */
  private final String teamId;

  public SlackWorkspaceInstallDeniedException(String teamId, Throwable cause) {
    super("slack workspace install denied by row-level security: teamId=" + teamId, cause);
    this.teamId = teamId;
  }

  public String getTeamId() {
    return teamId;
  }
}
