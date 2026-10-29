package com.smartfirehub.global.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.security.access.annotation.Secured;
import org.springframework.security.access.prepost.PostAuthorize;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * 앱 코드에 Spring 메서드 보안 어노테이션이 없는지 지킨다(WD-433).
 *
 * <p>이 앱은 {@code @EnableMethodSecurity} 를 켜지 않고 권한을 {@link
 * RequirePermission}(PermissionInterceptor)으로만 판정한다. 그래서 {@code @PreAuthorize} 등을 달면 <b>아무 검사도 하지
 * 않는데</b> 코드만 보면 막힌 것처럼 보인다 — 실제로 알림 관리 API 가 이 상태로 모든 사용자에게 열려 있었다. 메서드 보안을 켜도 authority 에
 * 역할(ROLE_*)이 없어 관리자까지 403 이 되므로, 두 방식을 섞지 않도록 이 테스트가 막는다.
 */
class MethodSecurityAnnotationGuardTest {

  /** 평가되지 않는 메서드 보안 어노테이션 목록. */
  private static final List<Class<? extends Annotation>> INERT =
      List.of(PreAuthorize.class, PostAuthorize.class, Secured.class);

  @Test
  void appDoesNotUseInertMethodSecurityAnnotations() throws Exception {
    // 컨트롤러뿐 아니라 서비스 등 모든 빈에서도 똑같이 평가되지 않으므로 앱의 모든 클래스(추상·인터페이스 포함)를 본다.
    ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false) {
          @Override
          protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
            return true;
          }
        };
    scanner.addIncludeFilter((reader, factory) -> true);

    var classes = scanner.findCandidateComponents("com.smartfirehub");
    // 스캔이 비면(패키지·필터가 바뀌어) 아무것도 검사하지 않고 통과하므로 클래스를 실제로 찾았는지부터 본다.
    assertThat(classes).hasSizeGreaterThan(100);

    ClassLoader loader = getClass().getClassLoader();
    List<String> violations = new ArrayList<>();
    for (var candidate : classes) {
      // 정적 초기화를 돌리지 않고 메타데이터만 읽는다.
      Class<?> type = Class.forName(candidate.getBeanClassName(), false, loader);
      Stream.concat(Stream.of((AnnotatedElement) type), Stream.of(type.getDeclaredMethods()))
          .filter(el -> INERT.stream().anyMatch(el::isAnnotationPresent))
          .forEach(el -> violations.add(el.toString()));
    }

    assertThat(violations)
        .as("@PreAuthorize/@PostAuthorize/@Secured 는 평가되지 않는다 — @RequirePermission 을 쓰세요")
        .isEmpty();
  }
}
