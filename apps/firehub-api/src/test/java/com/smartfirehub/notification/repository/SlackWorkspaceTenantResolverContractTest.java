package com.smartfirehub.notification.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.notification.repository.SlackWorkspaceTenantResolver.SlackWorkspaceRef;
import com.smartfirehub.user.repository.TenantPairContractBase;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SlackWorkspaceTenantResolver 계약 테스트(Review Focus 1, 스펙 D2) — 구현마다 하위 클래스로 실행한다(WD-709). 기존
 * SlackWorkspaceTenantResolverIntegrationTest(PG 함수 카탈로그 시험)와 별개다.
 *
 * <p>해석기는 테넌트 컨텍스트가 <b>없는</b> 비인증 웹훅 경로에서 불리므로 호출은 {@code noTenant} 로 한다. PG 는 정책 우회 함수가 (워크스페이스,
 * 테넌트) 를 한 번에 주고, Tibero 는 {@code FH_TENANT} 함수가 테넌트만 준 뒤 새 트랜잭션에서 그 테넌트를 심고 RLS 아래에서 워크스페이스 id 를
 * 다시 읽는다. 결과가 같아야 하고 호출자 트랜잭션의 테넌트는 바뀌지 않아야 한다.
 */
abstract class SlackWorkspaceTenantResolverContractTest extends TenantPairContractBase {

  protected abstract SlackWorkspaceTenantResolver resolver();

  /** 호출자 트랜잭션의 테넌트 불변 시험에 쓰는 같은 DB 의 워크스페이스 리포지토리. */
  protected abstract SlackWorkspaceRepository workspaceRepository();

  private String teamA;
  private String teamB;
  private String teamRevoked;
  private long wsA;
  private long wsB;

  @BeforeEach
  void createWorkspacesInTwoTenants() {
    String uniq = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    teamA = "TA" + uniq;
    teamB = "TB" + uniq;
    teamRevoked = "TR" + uniq;
    wsA = fx.createSlackWorkspace(tenantA, teamA, false);
    wsB = fx.createSlackWorkspace(tenantB, teamB, false);
    fx.createSlackWorkspace(tenantA, teamRevoked, true);
  }

  private Optional<SlackWorkspaceRef> resolve(String teamId) {
    return fx.noTenant(() -> resolver().resolveByTeamId(teamId));
  }

  @Test
  @DisplayName("두 테넌트의 각 team_id 가 자기 (워크스페이스, 테넌트) 로만 해석된다")
  void eachTeamToOwnTenant() {
    assertThat(resolve(teamA)).contains(new SlackWorkspaceRef(wsA, tenantA));
    assertThat(resolve(teamB)).contains(new SlackWorkspaceRef(wsB, tenantB));
  }

  @Test
  @DisplayName("폐기된 워크스페이스·없는 team_id·빈 값·null 은 empty 이다")
  void revokedUnknownEmptyAndNull() {
    assertThat(resolve(teamRevoked)).isEmpty();
    assertThat(resolve(teamA + "-none")).isEmpty();
    assertThat(resolve("")).isEmpty();
    assertThat(resolve(null)).isEmpty();
  }

  @Test
  @DisplayName("호출자가 테넌트 A 트랜잭션 안에서 B 를 해석해도 그 트랜잭션의 테넌트·조회 결과는 그대로다")
  void callerTransactionTenantUnchanged() {
    boolean[] checks =
        inA(
            () -> {
              Optional<SlackWorkspaceRef> ref = resolver().resolveByTeamId(teamB);
              return new boolean[] {
                ref.equals(Optional.of(new SlackWorkspaceRef(wsB, tenantB))),
                Long.valueOf(tenantA).equals(TenantContext.get()),
                workspaceRepository().findById(wsA).isPresent(),
                workspaceRepository().findById(wsB).isEmpty()
              };
            });

    assertThat(checks).containsExactly(true, true, true, true);
  }
}
