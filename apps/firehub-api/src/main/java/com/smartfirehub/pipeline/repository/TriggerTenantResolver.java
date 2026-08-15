package com.smartfirehub.pipeline.repository;

import java.util.Optional;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/**
 * 비인증 트리거 경로의 테넌트 해석기. RLS 를 우회하는 SECURITY DEFINER 함수(V95)를 호출해 외부
 * 식별자에서 (트리거 id, 테넌트 id) 만 얻는다. 이 클래스가 시스템에서 RLS 우회 함수를 호출하는
 * 유일한 지점이며, 얻은 테넌트로 컨텍스트를 세운 뒤의 처리는 전부 RLS 아래에서 돈다.
 *
 * <p><b>이 우회는 두 전제에 의존한다 — 어느 쪽이든 깨지면 모든 외부 트리거가 전멸한다:</b>
 *
 * <ol>
 *   <li>{@code pipeline_trigger} 의 소유자와 함수의 소유자가 모두 {@code app} 이고, 런타임 롤
 *       {@code app_tenant} 는 소유자가 아니다 — RLS 는 테이블 소유자에게 적용되지 않는다는 것이
 *       우회의 원리다. 소유권을 옮기면 우회가 사라진다.
 *   <li>{@code pipeline_trigger} 에 {@code FORCE ROW LEVEL SECURITY} 가 걸려 있지 않다 — FORCE 는
 *       소유자에게까지 정책을 적용하므로 definer 함수도 0행을 받는다. <b>"일관성" 을 이유로 정책
 *       마이그레이션에서 FORCE 를 켜지 마라.</b>
 * </ol>
 *
 * <p>두 전제는 {@code TriggerTenantResolverTest} 가 카탈로그로 고정한다.
 *
 * <p>생성된 jOOQ 레코드에는 함수가 없어 plain DSL({@code resultQuery})로 호출한다.
 *
 * <p>메서드에 {@code @Transactional} 을 붙이지 않는다 — 이 조회는 테넌트 컨텍스트가 <b>아직 없는</b>
 * 시점에 불리고, definer 함수는 RLS 를 우회하므로 GUC 가 필요 없다. 원자성을 확보할 대상도 없다.
 */
@Repository
public class TriggerTenantResolver {

  private final DSLContext dsl;

  public TriggerTenantResolver(DSLContext dsl) {
    this.dsl = dsl;
  }

  /** 외부 식별자 해석 결과. 트리거 행 전체를 들고 다니지 않는다 — 노출면을 id 두 개로 제한한다. */
  public record TriggerRef(long triggerId, long tenantId) {}

  /**
   * API 트리거 토큰 해시로 테넌트를 해석한다.
   *
   * <p>인자는 raw 토큰이 아니라 <b>이미 sha256 된 값</b>이다 — 해싱 위치는 기존과 동일하게
   * {@code TriggerService} 에 남긴다. 비활성 트리거는 함수 안에서 걸러져 빈 결과가 된다.
   */
  public Optional<TriggerRef> resolveByApiToken(String tokenHash) {
    return fetchRef(
        "select trigger_id, tenant_id from resolve_trigger_tenant_by_token_hash(?)", tokenHash);
  }

  /** 웹훅 식별자(UUID)로 테넌트를 해석한다. 비활성 트리거는 테넌트조차 노출하지 않는다. */
  public Optional<TriggerRef> resolveByWebhookId(String webhookId) {
    return fetchRef(
        "select trigger_id, tenant_id from resolve_trigger_tenant_by_webhook_id(?)", webhookId);
  }

  /** 두 해석 함수의 공통 호출부. 식별자가 전역 유니크(V94)라 결과는 0행 또는 1행이다. */
  private Optional<TriggerRef> fetchRef(String sql, String identifier) {
    if (identifier == null || identifier.isEmpty()) {
      return Optional.empty();
    }
    return dsl
        .resultQuery(sql, identifier)
        .fetchOptional()
        .map(record -> new TriggerRef(record.get(0, Long.class), record.get(1, Long.class)));
  }
}
