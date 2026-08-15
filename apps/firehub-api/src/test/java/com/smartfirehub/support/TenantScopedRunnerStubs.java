package com.smartfirehub.support;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.smartfirehub.global.tenant.TenantScopedRunner;
import java.util.function.Consumer;

/**
 * 목으로 잡은 {@link TenantScopedRunner} 스텁 모음.
 *
 * <p>P2-b 에서 배경 잡들이 ACTIVE 테넌트를 순회하게 되면서, 순회와 무관한 로직(임계값·라우팅 등)만
 * 보는 단위 테스트들이 저마다 같은 {@code doAnswer} 를 복붙하기 시작했다. 시그니처가 바뀌면 그 스텁을
 * 전부 찾아다녀야 하므로 한곳에 모은다.
 */
public final class TenantScopedRunnerStubs {

  private TenantScopedRunnerStubs() {}

  /**
   * 순회를 "테넌트 1건" 으로 단순화한다 — 스텁하지 않으면 목이 아무 동작도 하지 않아 콜백 본문이
   * 통째로 실행되지 않는다. 실제 다중 테넌트 순회 검증은 통합 테스트가 담당한다.
   */
  @SuppressWarnings("unchecked")
  public static void stubSingleTenantIteration(TenantScopedRunner mock, long tenantId) {
    doAnswer(
            invocation -> {
              ((Consumer<Long>) invocation.getArgument(0)).accept(tenantId);
              return null;
            })
        .when(mock)
        .forEachActiveTenant(any());
  }
}
