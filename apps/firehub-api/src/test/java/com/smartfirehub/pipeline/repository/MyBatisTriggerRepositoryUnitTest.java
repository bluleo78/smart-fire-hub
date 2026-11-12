package com.smartfirehub.pipeline.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.global.platformdb.PlatformLocks;
import com.smartfirehub.pipeline.repository.jooq.JooqTriggerRepository;
import com.smartfirehub.pipeline.repository.jooq.JooqTriggerTenantResolver;
import com.smartfirehub.pipeline.repository.mybatis.MyBatisTriggerRepository;
import com.smartfirehub.pipeline.repository.mybatis.MyBatisTriggerTenantResolver;
import com.smartfirehub.pipeline.repository.mybatis.TriggerMapper;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * MyBatis 트리거 리포지토리·해석기의 DB 없이 볼 수 있는 계약(WD-708). Tibero 계약 시험은 잠금 창구를 가짜로 쓰므로 잠금 키는 여기서 고정하고,
 * REQUIRES_NEW·MANDATORY 전파는 롤백 시나리오 대신 jOOQ 구현과 애노테이션 속성이 같은지로 고정한다(트랜잭션 프록시는 그 속성만 읽는다).
 */
class MyBatisTriggerRepositoryUnitTest {

  @Test
  @DisplayName("lockChainGraph 는 jOOQ 판과 같은 pipeline-chain-graph:<테넌트> 키로 트랜잭션 잠금을 건다")
  void lockChainGraph_sameKeyAsJooq() {
    PlatformLocks locks = mock(PlatformLocks.class);
    MyBatisTriggerRepository repository =
        new MyBatisTriggerRepository(mock(TriggerMapper.class), new ObjectMapper(), locks);

    repository.lockChainGraph(42L);

    verify(locks).xactLock("pipeline-chain-graph:42");
  }

  @Test
  @DisplayName("모든 공개 메서드의 트랜잭션 전파가 jOOQ 구현과 같다(클래스 REQUIRED, REQUIRES_NEW·MANDATORY 변형)")
  void transactionAttributesMatchJooq() {
    for (Method m : TriggerRepository.class.getMethods()) {
      assertThat(propagation(MyBatisTriggerRepository.class, m))
          .as(m.getName())
          .isEqualTo(propagation(JooqTriggerRepository.class, m))
          .isNotNull();
    }
    assertThat(propagation(MyBatisTriggerRepository.class, method("mergeNextFireTime")))
        .isEqualTo(Propagation.REQUIRES_NEW);
    assertThat(propagation(MyBatisTriggerRepository.class, method("lockChainGraph")))
        .isEqualTo(Propagation.MANDATORY);
  }

  @Test
  @DisplayName("해석기는 jOOQ 판처럼 @Transactional 이 없다 — 테넌트 컨텍스트가 아직 없는 시점에 불린다")
  void resolverHasNoTransactional() {
    for (Class<?> c :
        new Class<?>[] {MyBatisTriggerTenantResolver.class, JooqTriggerTenantResolver.class}) {
      assertThat(AnnotatedElementUtils.hasAnnotation(c, Transactional.class))
          .as(c.getName())
          .isFalse();
      for (Method m : c.getDeclaredMethods()) {
        assertThat(AnnotatedElementUtils.hasAnnotation(m, Transactional.class))
            .as(c.getSimpleName() + "." + m.getName())
            .isFalse();
      }
    }
  }

  private static Method method(String name) {
    return Arrays.stream(TriggerRepository.class.getMethods())
        .filter(m -> m.getName().equals(name))
        .findFirst()
        .orElseThrow();
  }

  /** 구현 메서드 → 클래스 순으로 찾은 @Transactional 의 전파(없으면 null). */
  private static Propagation propagation(Class<?> impl, Method ifaceMethod) {
    try {
      Method m = impl.getMethod(ifaceMethod.getName(), ifaceMethod.getParameterTypes());
      Transactional t = AnnotatedElementUtils.findMergedAnnotation(m, Transactional.class);
      if (t == null) {
        t = AnnotatedElementUtils.findMergedAnnotation(impl, Transactional.class);
      }
      return t == null ? null : t.propagation();
    } catch (NoSuchMethodException e) {
      throw new IllegalStateException(e);
    }
  }
}
