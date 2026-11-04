package com.smartfirehub.notification.repository;

import java.util.Optional;

/**
 * Slack 인바운드 웹훅(permitAll)의 테넌트 해석기. Slack {@code team_id} 에서 (워크스페이스 id, 테넌트 id) 만 얻는다. 구현은 {@code
 * JooqSlackWorkspaceTenantResolver}(V106 의 RLS 우회 SECURITY DEFINER 함수 호출)다.
 *
 * <p>웹훅은 테넌트를 모르는 상태로 들어오므로 이 해석만 RLS 를 우회해 <b>정수 두 개</b>를 얻고, 봇 토큰을 포함한 실제 데이터는 그 테넌트 컨텍스트 안에서 정상
 * RLS 조회로 다시 읽는다({@code SlackInboundService.dispatch} 참조). 우회가 기대는 두 전제(소유자·FORCE RLS 미적용)는 구현 주석에
 * 있다. 해지된 워크스페이스는 테넌트조차 알려주지 않는다.
 *
 * <p>구현 메서드에 {@code @Transactional} 을 붙이지 않는다 — 테넌트 컨텍스트가 <b>아직 없는</b> 시점에 불리기 때문이다.
 */
public interface SlackWorkspaceTenantResolver {

  /** 해석 결과. 워크스페이스 행을 들고 다니지 않는다 — 노출면을 id 두 개로 제한한다. */
  record SlackWorkspaceRef(long workspaceId, long tenantId) {}

  /**
   * Slack {@code team_id} 로 테넌트를 해석한다.
   *
   * <p>{@code team_id} 는 전역 유니크로 남겨 두었으므로([R1]) 결과는 0행 아니면 1행이다. 여러 테넌트가 같은 팀을 설치할 수 있게 접었다면 이 해석이
   * 원리적으로 다의가 되어 봇 토큰이 갈라졌을 것이다. {@code teamId} 가 null·빈 문자열이면 비어 있는 결과를 돌려준다.
   */
  Optional<SlackWorkspaceRef> resolveByTeamId(String teamId);
}
