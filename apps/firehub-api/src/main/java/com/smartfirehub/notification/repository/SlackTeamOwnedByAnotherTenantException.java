package com.smartfirehub.notification.repository;

/**
 * 슬랙 team 이 이미 다른 테넌트에 설치돼 있어 upsert 가 team_id 유일 키에 막혔음을 알리는 신호(WD-730).
 *
 * <p>Tibero MyBatis 구현만 던진다 — RLS 로 다른 테넌트 행이 안 보여 MERGE 가 INSERT 로 내려가다 {@code
 * slack_workspace_team_id_key} 에 걸리고, 같은 테넌트 동시 설치용 재시도 뒤에도 남은 경우다. PG 는 같은 상황을 RLS 거부(42501)로 내므로
 * 이 신호를 내지 않는다. 서비스({@code SlackOAuthService})가 PG 와 같은 설치 거절 예외로 바꾼다. 다른 제약의 중복이나 같은 테넌트 경합 소진은 이
 * 신호가 아니라 원래의 {@code DuplicateKeyException} 으로 올라가 시끄럽게 터진다.
 */
public class SlackTeamOwnedByAnotherTenantException extends RuntimeException {

  /** 충돌한 슬랙 팀 ID — 서버 로그 전용. */
  private final String teamId;

  public SlackTeamOwnedByAnotherTenantException(String teamId, Throwable cause) {
    super("slack team is owned by another tenant: teamId=" + teamId, cause);
    this.teamId = teamId;
  }

  public String getTeamId() {
    return teamId;
  }
}
