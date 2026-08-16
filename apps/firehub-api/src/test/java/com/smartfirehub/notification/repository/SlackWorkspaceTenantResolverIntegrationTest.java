package com.smartfirehub.notification.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@code resolve_slack_workspace_tenant_by_team_id}(V106) 해석기의 <b>동작</b> 검증.
 *
 * <p>반환 형태(컬럼 2개)와 함수 속성(STABLE 등)은 {@code ChannelDomainColumnTest} 가 카탈로그로
 * 고정하므로 여기서 중복하지 않는다. 여기서 보는 것은 세 가지다: 등록된 팀이 테넌트로 풀리는가,
 * 해지된 워크스페이스가 <b>테넌트조차 알려주지 않는가</b>, 없는 팀이 0행인가.
 *
 * <p><b>EXECUTE 권한은 이 호출 자체가 검증한다.</b> 애플리케이션 {@code DSLContext} 는 런타임 롤
 * ({@code app_tenant})로 접속하므로, {@code GRANT EXECUTE ... TO app_tenant} 가 빠지면 이 테스트가
 * 권한 오류로 빨개진다. 카탈로그 권한 단언을 따로 두지 않는 이유다.
 *
 * <p><b>스크래치 테넌트를 쓰고 team_id 에 고유 접미사를 붙인다.</b> {@code team_id} 는 전역
 * 유니크([R1])이고 테스트 DB 는 다른 워크트리와 공유하므로, 고정 문자열을 쓰면 동시 실행에서
 * 비결정적으로 충돌한다.
 */
class SlackWorkspaceTenantResolverIntegrationTest extends IntegrationTestBase {

  @Autowired private SlackWorkspaceTenantResolver resolver;
  @Autowired private DSLContext dsl;

  private long tenantId;
  private String teamId;

  @BeforeEach
  void createScratchTenant() {
    tenantId = TenantRlsTestSupport.createActiveTenant(dsl, "slack-resolver");
    teamId = "T-RESOLVER-" + TenantRlsTestSupport.nextTenantId();
    TenantContext.set(tenantId);
  }

  @AfterEach
  void cleanupScratchTenant() {
    inTenantFixture(tenantId, () -> TenantRlsTestSupport.deleteChannelCascade(dsl, tenantId));
    TenantRlsTestSupport.deleteTenants(dsl, tenantId);
  }

  /**
   * 워크스페이스 픽스처를 심는다. {@code tenant_id} 는 직접 싣지 않고 GUC 파생 DEFAULT 에 맡긴다 —
   * 앱이 직접 실으면 GUC 와 어긋날 여지가 생겨 해석 단언이 무의미해진다.
   */
  private void insertWorkspace(boolean revoked) {
    inTenantFixture(
        tenantId,
        () ->
            dsl.execute(
                "insert into slack_workspace"
                    + " (team_id, team_name, bot_user_id, bot_token_enc, signing_secret_enc,"
                    + " revoked_at)"
                    // revoked_at 은 null 바인딩 시 타입 추론이 안 돼 캐스트가 필요하다.
                    + " values (?, '해석기 테스트', 'B-TEST', 'enc-bot', 'enc-sign', ?::timestamptz)",
                teamId,
                revoked ? java.time.OffsetDateTime.now().toString() : null));
  }

  @Test
  void resolveByTeamId_returnsOwningTenant() {
    insertWorkspace(false);

    // 컨텍스트를 비운 채로 부른다 — 웹훅이 도착하는 실제 조건이다. 컨텍스트를 남겨 두면
    // definer 우회 없이도 통과해 단언이 공허해진다.
    TenantContext.clear();
    var ref = resolver.resolveByTeamId(teamId);

    assertThat(ref).as("등록된 팀은 테넌트로 풀려야 한다").isPresent();
    assertThat(ref.get().tenantId()).isEqualTo(tenantId);
    assertThat(ref.get().workspaceId()).isPositive();
  }

  @Test
  void resolveByTeamId_revokedWorkspace_resolvesNothing() {
    insertWorkspace(true);

    TenantContext.clear();

    assertThat(resolver.resolveByTeamId(teamId))
        .as("해지된 워크스페이스는 테넌트조차 알려주지 않아야 한다")
        .isEmpty();
  }

  @Test
  void resolveByTeamId_unknownTeam_resolvesNothing() {
    TenantContext.clear();

    assertThat(resolver.resolveByTeamId(teamId + "-NOPE")).isEmpty();
    // null·빈 문자열은 DB 왕복 없이 걸러진다.
    assertThat(resolver.resolveByTeamId(null)).isEmpty();
    assertThat(resolver.resolveByTeamId("")).isEmpty();
  }
}
