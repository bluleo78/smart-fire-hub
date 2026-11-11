package com.smartfirehub.user.repository;

import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;

/**
 * 테넌트 둘(A·B)을 쓰는 리포지토리 계약 테스트의 공통 뼈대(WD-707). 묶음 ② 계약 테스트마다 복제돼 있던 tenantA/tenantB 필드·생성
 * {@code @BeforeEach}·테넌트 범위 실행 도우미를 한 곳에 모았다.
 *
 * <p>{@link AccessContractBase} 에 직접 넣지 않은 이유: 그 기반을 쓰는 묶음 ① 계약 테스트(테넌트를 따로 만들거나 쓰지 않는다)마다 테넌트 둘이 더
 * 생기고, 같은 이름의 private 도우미와 접근 제한이 부딪힌다. 상위 {@code @BeforeEach}(픽스처 생성)가 먼저 돌므로 여기서 {@code fx} 를 쓸 수
 * 있고, 하위 클래스의 추가 준비(사용자 등)는 이 메서드 뒤에 돈다.
 */
public abstract class TenantPairContractBase extends AccessContractBase {

  /** 시험 대상 테넌트. */
  protected long tenantA;

  /** 다른 테넌트 — 정책·명시 조건이 행을 가리는지 확인할 때 쓴다. */
  protected long tenantB;

  /** 시험마다 새 테넌트 둘을 만든다(픽스처 정리 때 함께 지운다). */
  @BeforeEach
  void createTenantPair() {
    tenantA = fx.createTenant();
    tenantB = fx.createTenant();
  }

  /** 테넌트 A 범위(트랜잭션 포함)에서 호출하고 결과를 돌려준다. */
  protected <T> T inA(Supplier<T> call) {
    return fx.inTenant(tenantA, call);
  }

  /** 테넌트 B 범위(트랜잭션 포함)에서 호출하고 결과를 돌려준다. */
  protected <T> T inB(Supplier<T> call) {
    return fx.inTenant(tenantB, call);
  }

  /** 결과 없는 호출을 테넌트 A 범위에서 실행한다. */
  protected void runA(Runnable call) {
    inA(
        () -> {
          call.run();
          return null;
        });
  }
}
