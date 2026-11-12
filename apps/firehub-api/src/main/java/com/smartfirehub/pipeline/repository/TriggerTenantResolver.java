package com.smartfirehub.pipeline.repository;

import java.util.Optional;

/**
 * 비인증 트리거 경로의 테넌트 해석기. 외부 식별자(API 토큰 해시·웹훅 id)에서 (트리거 id, 테넌트 id) 만 얻는다. 구현은 {@code
 * JooqTriggerTenantResolver}(V95 의 RLS 우회 SECURITY DEFINER 함수 호출)와 {@code
 * MyBatisTriggerTenantResolver}(Tibero 는 {@code FH_TENANT} 함수로 테넌트를 얻고 RLS 아래에서 트리거 id 를 다시 읽는다 —
 * WD-708)다. 얻은 테넌트로 컨텍스트를 세운 뒤의 처리는 전부 RLS 아래에서 돈다.
 *
 * <p>우회가 기대는 두 전제(소유자·FORCE RLS 미적용)는 구현 주석에 있고 {@code TriggerTenantResolverTest} 가 카탈로그로 고정한다.
 *
 * <p>구현 메서드에 {@code @Transactional} 을 붙이지 않는다 — 테넌트 컨텍스트가 <b>아직 없는</b> 시점에 불리기 때문이다.
 */
public interface TriggerTenantResolver {

  /** 외부 식별자 해석 결과. 트리거 행 전체를 들고 다니지 않는다 — 노출면을 id 두 개로 제한한다. */
  record TriggerRef(long triggerId, long tenantId) {}

  /**
   * API 트리거 토큰 해시로 테넌트를 해석한다.
   *
   * <p>인자는 raw 토큰이 아니라 <b>이미 sha256 된 값</b>이다 — 해싱 위치는 기존과 동일하게 {@code TriggerService} 에 남긴다. 꺼진
   * 트리거도 해석한다(V140, WD-152) — 맞는 토큰을 가진 호출자에게 "꺼져 있음"을 알려 주려면 테넌트를 세워 트리거를 읽어야 한다. 해석 결과(id 두 개)는
   * 호출자에게 나가지 않으므로 외부에 드러나는 것은 응답의 "꺼져 있음" 한 가지뿐이다.
   */
  Optional<TriggerRef> resolveByApiToken(String tokenHash);

  /**
   * 웹훅 식별자(UUID)로 테넌트를 해석한다. 꺼진 트리거도 해석한다(V140, WD-152). 꺼진 웹훅의 "꺼져 있음"은 서명검증을 통과한 뒤에만 응답에 드러난다 —
   * 판정은 {@code ExternalTriggerController} 가 한다.
   */
  Optional<TriggerRef> resolveByWebhookId(String webhookId);
}
