package com.smartfirehub.notification.repository;

import java.util.Optional;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/**
 * Slack 인바운드 웹훅(permitAll)의 테넌트 해석기. RLS 를 우회하는 SECURITY DEFINER 함수(V106)를
 * 호출해 {@code team_id} 에서 (워크스페이스 id, 테넌트 id) 만 얻는다.
 *
 * <p><b>왜 우회가 불가피한가 — 닭과 달걀이다.</b> 웹훅은 Bearer 헤더가 없어 테넌트를 모르는데,
 * 테넌트를 알려 줄 유일한 행({@code slack_workspace})이 RLS 대상이라 테넌트 없이는 읽히지 않는다.
 * 그래서 이 한 번만 우회로 <b>정수 두 개</b>를 얻고, 봇 토큰을 포함한 실제 데이터는 그 테넌트
 * 컨텍스트 안에서 정상 RLS 조회로 다시 읽는다({@code SlackInboundService.dispatch} 참조).
 * 이 클래스는 {@code TriggerTenantResolver}(V95, 외부 트리거)와 같은 형태이며, 두 곳이 시스템에서
 * RLS 우회 함수를 호출하는 전부다.
 *
 * <p><b>이 우회는 두 전제에 의존한다 — 어느 쪽이든 깨지면 Slack 인바운드가 전멸한다:</b>
 *
 * <ol>
 *   <li>{@code slack_workspace} 와 함수의 소유자가 모두 {@code app} 이고 런타임 롤
 *       {@code app_tenant} 는 소유자가 아니다 — RLS 가 소유자에게 적용되지 않는다는 것이 우회의
 *       원리다.
 *   <li>{@code slack_workspace} 에 {@code FORCE ROW LEVEL SECURITY} 가 걸려 있지 않다 — FORCE 는
 *       소유자에게까지 정책을 적용하므로 definer 함수도 0행을 받는다.
 * </ol>
 *
 * <p>해지된 워크스페이스({@code revoked_at IS NOT NULL})는 함수 안에서 걸러진다 — 해지된 팀은
 * 테넌트조차 알려주지 않는다.
 *
 * <p>메서드에 {@code @Transactional} 을 붙이지 않는다 — 이 조회는 테넌트 컨텍스트가 <b>아직 없는</b>
 * 시점에 불리고, definer 함수는 RLS 를 우회하므로 GUC 가 필요 없다.
 */
@Repository
public class SlackWorkspaceTenantResolver {

  private final DSLContext dsl;

  public SlackWorkspaceTenantResolver(DSLContext dsl) {
    this.dsl = dsl;
  }

  /** 해석 결과. 워크스페이스 행을 들고 다니지 않는다 — 노출면을 id 두 개로 제한한다. */
  public record SlackWorkspaceRef(long workspaceId, long tenantId) {}

  /**
   * Slack {@code team_id} 로 테넌트를 해석한다.
   *
   * <p>{@code team_id} 는 전역 유니크로 남겨 두었으므로([R1]) 결과는 0행 아니면 1행이다. 여러
   * 테넌트가 같은 팀을 설치할 수 있게 접었다면 이 해석이 원리적으로 다의가 되어 봇 토큰이
   * 갈라졌을 것이다.
   */
  public Optional<SlackWorkspaceRef> resolveByTeamId(String teamId) {
    if (teamId == null || teamId.isEmpty()) {
      return Optional.empty();
    }
    return dsl
        .resultQuery(
            "select workspace_id, tenant_id from resolve_slack_workspace_tenant_by_team_id(?)",
            teamId)
        .fetchOptional()
        .map(record -> new SlackWorkspaceRef(record.get(0, Long.class), record.get(1, Long.class)));
  }
}
