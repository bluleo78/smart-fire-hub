package com.smartfirehub.pipeline.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.pipeline.repository.TriggerTenantResolver.TriggerRef;
import com.smartfirehub.user.repository.TenantPairContractBase;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * TriggerTenantResolver 계약 테스트(Review Focus 2) — 구현마다 하위 클래스로 실행한다(WD-708).
 *
 * <p>해석기는 테넌트 컨텍스트가 <b>없는</b> 비인증 경로에서 불리므로 호출은 {@code noTenant} 로 한다. PG 는 정책 우회 함수가 (트리거, 테넌트) 를
 * 한 번에 돌려주고, Tibero 는 {@code FH_TENANT} 함수가 테넌트만 돌려준 뒤 새 트랜잭션에서 그 테넌트를 심고 RLS 아래에서 트리거 id 를 다시
 * 읽는다(스펙 D2). 어느 쪽이든 결과가 같아야 하고, 호출자가 트랜잭션 안에 있어도 그 트랜잭션의 테넌트는 바뀌지 않아야 한다.
 */
public abstract class TriggerTenantResolverContractTest extends TenantPairContractBase {

  protected abstract TriggerTenantResolver resolver();

  /** 호출자 트랜잭션의 테넌트 불변 시험에 쓰는 같은 DB 의 트리거 리포지토리. */
  protected abstract TriggerRepository triggerRepository();

  private long apiA;
  private long hookA;
  private long apiB;
  private long hookB;

  @BeforeEach
  void createTriggersInTwoTenants() {
    long pipelineA = fx.createPipeline(tenantA, prefix + "-pa", true);
    long pipelineB = fx.createPipeline(tenantB, prefix + "-pb", true);
    apiA = fx.createTrigger(pipelineA, prefix + "-apiA", "API", tokenConfig("hA"));
    hookA = fx.createTrigger(pipelineA, prefix + "-hookA", "WEBHOOK", hookConfig("wA"));
    apiB = fx.createTrigger(pipelineB, prefix + "-apiB", "API", tokenConfig("hB"));
    hookB = fx.createTrigger(pipelineB, prefix + "-hookB", "WEBHOOK", hookConfig("wB"));
    // 꺼진 트리거도 해석한다(V140) — 켜짐 판정은 테넌트를 세운 뒤 호출자가 한다.
    fx.setTriggerEnabled(apiB, false);
    fx.setTriggerEnabled(hookB, false);
  }

  private String tokenConfig(String suffix) {
    return "{\"tokenHash\":\"" + prefix + suffix + "\"}";
  }

  private String hookConfig(String suffix) {
    return "{\"webhookId\":\"" + prefix + suffix + "\"}";
  }

  private Optional<TriggerRef> byToken(String value) {
    return fx.noTenant(() -> resolver().resolveByApiToken(value));
  }

  private Optional<TriggerRef> byWebhook(String value) {
    return fx.noTenant(() -> resolver().resolveByWebhookId(value));
  }

  @Test
  @DisplayName("토큰 해시 — 두 테넌트의 각 토큰이 자기 (트리거, 테넌트) 로만 해석되고, 꺼진 트리거도 해석된다")
  void resolveByApiToken_eachToOwnTenant() {
    assertThat(byToken(prefix + "hA")).contains(new TriggerRef(apiA, tenantA));
    assertThat(byToken(prefix + "hB")).contains(new TriggerRef(apiB, tenantB));
  }

  @Test
  @DisplayName("웹훅 id — 두 테넌트의 각 웹훅이 자기 (트리거, 테넌트) 로만 해석되고, 꺼진 트리거도 해석된다")
  void resolveByWebhookId_eachToOwnTenant() {
    assertThat(byWebhook(prefix + "wA")).contains(new TriggerRef(hookA, tenantA));
    assertThat(byWebhook(prefix + "wB")).contains(new TriggerRef(hookB, tenantB));
  }

  @Test
  @DisplayName("없는 값·빈 값·null 은 빈 값이고, 다른 종류의 식별자로는 해석되지 않는다")
  void unknownEmptyAndWrongKind() {
    assertThat(byToken(prefix + "none")).isEmpty();
    assertThat(byWebhook(prefix + "none")).isEmpty();
    assertThat(byToken("")).isEmpty();
    assertThat(byWebhook("")).isEmpty();
    assertThat(byToken(null)).isEmpty();
    assertThat(byWebhook(null)).isEmpty();
    // 웹훅 id 를 토큰으로, 토큰을 웹훅 id 로 넣어도 맞히지 않는다(유형을 거른다).
    assertThat(byToken(prefix + "wA")).isEmpty();
    assertThat(byWebhook(prefix + "hA")).isEmpty();
  }

  @Test
  @DisplayName("호출자가 테넌트 A 트랜잭션 안에서 B 를 해석해도 그 트랜잭션의 테넌트·조회 결과는 그대로다")
  void callerTransactionTenantUnchanged() {
    boolean[] checks =
        inA(
            () -> {
              Optional<TriggerRef> ref = resolver().resolveByWebhookId(prefix + "wB");
              Optional<TriggerRef> tokenRef = resolver().resolveByApiToken(prefix + "hB");
              return new boolean[] {
                ref.equals(Optional.of(new TriggerRef(hookB, tenantB))),
                tokenRef.equals(Optional.of(new TriggerRef(apiB, tenantB))),
                Long.valueOf(tenantA).equals(TenantContext.get()),
                triggerRepository().findById(apiA).isPresent(),
                triggerRepository().findById(apiB).isEmpty()
              };
            });

    assertThat(checks).containsExactly(true, true, true, true, true);
  }
}
