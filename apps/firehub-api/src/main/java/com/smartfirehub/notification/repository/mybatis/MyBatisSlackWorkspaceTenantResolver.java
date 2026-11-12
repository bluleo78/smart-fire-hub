package com.smartfirehub.notification.repository.mybatis;

import com.smartfirehub.global.platformdb.PlatformTenantSession;
import com.smartfirehub.global.platformdb.mybatis.PlatformMyBatisRepository;
import com.smartfirehub.notification.repository.SlackWorkspaceTenantResolver;
import com.smartfirehub.notification.repository.mybatis.SlackWorkspaceTenantResolverMapper.RefRow;
import com.smartfirehub.notification.repository.mybatis.SlackWorkspaceTenantResolverMapper.TeamLookup;
import java.util.List;
import java.util.Optional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * SlackWorkspaceTenantResolver 의 MyBatis 구현(WD-709, 스펙 D2) — {@code
 * JooqSlackWorkspaceTenantResolver} 와 같은 결과. {@code MyBatisTriggerTenantResolver} 와 같은 모양이다.
 *
 * <ul>
 *   <li>PG: SECURITY DEFINER 함수가 (워크스페이스 id, 테넌트 id) 를 한 번에 준다.
 *   <li>Tibero: {@code FH_TENANT.resolve_slack_workspace_tenant_by_team_id}(V156.1)는 테넌트 id 만 준다 —
 *       새 마이그레이션 없이, 얻은 테넌트로 <b>새 트랜잭션</b>(REQUIRES_NEW)을 열어 {@link
 *       PlatformTenantSession#applyToCurrentTransaction} 으로 테넌트를 심은 뒤 워크스페이스 id 를 RLS 아래에서 다시 읽는다.
 * </ul>
 *
 * <p>REQUIRES_NEW 인 이유: 호출자가 트랜잭션 안에 있어도 테넌트를 심는 일이 그 트랜잭션에 새지 않게 하려는 것이다. 클래스·메서드에
 * {@code @Transactional} 을 붙이지 않는다 — 테넌트 컨텍스트가 <b>아직 없는</b> 시점에 불린다. 두 조회 사이에 워크스페이스가 해지되면 빈 값(없는
 * 팀과 같은 응답)이다 — 받아들인다.
 */
@PlatformMyBatisRepository
public class MyBatisSlackWorkspaceTenantResolver implements SlackWorkspaceTenantResolver {

  private final SlackWorkspaceTenantResolverMapper mapper;
  private final PlatformTenantSession tenantSession;

  /** 재조회 전용 새 트랜잭션(REQUIRES_NEW) — 플랫폼 트랜잭션 매니저(테넌트 인식) 위. */
  private final TransactionTemplate rereadTx;

  public MyBatisSlackWorkspaceTenantResolver(
      SlackWorkspaceTenantResolverMapper mapper,
      PlatformTenantSession tenantSession,
      PlatformTransactionManager transactionManager) {
    this.mapper = mapper;
    this.tenantSession = tenantSession;
    this.rereadTx = new TransactionTemplate(transactionManager);
    this.rereadTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  @Override
  public Optional<SlackWorkspaceRef> resolveByTeamId(String teamId) {
    if (teamId == null || teamId.isEmpty()) {
      return Optional.empty();
    }
    TeamLookup q = new TeamLookup(teamId);
    List<RefRow> rows = mapper.resolveByTeamId(q);
    // PG: 함수가 워크스페이스 id 까지 채운 행을 준다.
    if (rows != null && !rows.isEmpty() && rows.get(0).workspaceId() != null) {
      RefRow row = rows.get(0);
      return Optional.of(new SlackWorkspaceRef(row.workspaceId(), row.tenantId()));
    }
    // Tibero: OUT 인자의 테넌트만 온다 — 없으면 해석 실패(없는 팀·해지된 팀).
    Long tenantId = q.getTenantId();
    if (tenantId == null) {
      return Optional.empty();
    }
    Long workspaceId =
        rereadTx.execute(
            status -> {
              tenantSession.applyToCurrentTransaction(tenantId);
              return mapper.selectWorkspaceIdByTeamId(teamId);
            });
    return workspaceId == null
        ? Optional.empty()
        : Optional.of(new SlackWorkspaceRef(workspaceId, tenantId));
  }
}
