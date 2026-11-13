package com.smartfirehub.global.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.global.platformdb.ConditionalOnPostgresPlatformDb;
import com.smartfirehub.global.platformdb.mybatis.PlatformMyBatisRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Repository;

/**
 * 플랫폼 리포지토리 구현 짝 검사(WD-706).
 *
 * <p>무엇을: {@code ..repository.jooq..} 의 {@code @Repository} jOOQ 구현이 구현하는 리포지토리 인터페이스마다, {@link
 * PlatformMyBatisRepository} MyBatis 구현이 있거나 {@link #PENDING} 에 (묶음 번호와 함께) 올라 있어야 한다.
 *
 * <p>왜: Tibero 설치본은 jOOQ 구현을 빼고 MyBatis 구현만 쓴다. 인터페이스에 MyBatis 구현이 빠진 채 남으면 Tibero 기동 때 주입 실패로 터진다.
 * 도메인 묶음(WD-706~712)이 하나씩 짝을 만들 때마다 이 목록에서 지운다. 짝이 생겼는데 목록에 남은 항목은 실패시켜 목록이 낡지 않게 한다.
 *
 * <p>범위 한계: 이 검사는 {@code ..repository.jooq..} 의 {@code @Repository} 클래스와 그 {@code ..repository}
 * 인터페이스만 본다. 빈 PENDING 이 WD-491 완료를 뜻하지는 않는다 — jOOQ {@code @Component} 클래스(예:
 * dataset/repository/jooq/JooqDatasetMetaReader)와 {@code DSLContext} 를 직접 쓰는 서비스는 검사 밖이며, 이후 묶음과
 * WD-494 가 따로 추적한다.
 */
class PlatformRepositoryPairTest {

  /**
   * 아직 MyBatis 구현이 없는 인터페이스 → 맡은 묶음. 묶음은 ADR 0001 「WD-491 쪼개기」 표의 도메인 기준이다. 해당 묶음이 짝을 만들 때 자기 항목을
   * 지운다.
   *
   * <p>묶음 ⑤(WD-710) 뒤로 비어 있다 — 이제 새 jOOQ 리포지토리는 MyBatis 짝과 함께 들어와야 하고, 짝 없이 들이려면 여기에 묶음(이슈)과 함께
   * 올린다. 비어 있어도 아래 검사는 그대로 돈다(PENDING 관련 두 검사는 항목이 없어 통과, 짝·PG 전용 검사는 전체 jOOQ 구현을 본다).
   */
  static final Map<Class<?>, String> PENDING = pending();

  private static Map<Class<?>, String> pending() {
    Map<Class<?>, String> m = new LinkedHashMap<>();
    return Collections.unmodifiableMap(m);
  }

  /** 애노테이션별 스캔 결과 캐시 — 클래스패스 전체 스캔이 비싸 호출마다 반복하지 않는다. */
  private static final Map<Class<?>, List<Class<?>>> SCAN_CACHE = new java.util.HashMap<>();

  /**
   * 프로덕션 클래스 중 주어진 애노테이션이 붙은 것(시험용 클래스는 {@code com.smartfirehub} 밖에 두므로 걸리지 않는다). 애노테이션당 한 번만 스캔한다.
   */
  private static synchronized List<Class<?>> annotated(
      Class<? extends java.lang.annotation.Annotation> a) throws ClassNotFoundException {
    List<Class<?>> cached = SCAN_CACHE.get(a);
    if (cached != null) {
      return cached;
    }
    ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new AnnotationTypeFilter(a));
    List<Class<?>> result = new ArrayList<>();
    for (BeanDefinition bd : scanner.findCandidateComponents("com.smartfirehub")) {
      result.add(Class.forName(bd.getBeanClassName()));
    }
    SCAN_CACHE.put(a, result);
    return result;
  }

  /** {@code ..repository.jooq..} 의 {@code @Repository} 구현 클래스. */
  private static List<Class<?>> jooqImpls() throws ClassNotFoundException {
    return annotated(Repository.class).stream()
        .filter(c -> c.getPackageName().contains(".repository.jooq"))
        .toList();
  }

  /** {@code com.smartfirehub..repository} 패키지(하위 제외)에 있는 인터페이스인가. */
  private static boolean isRepositoryInterface(Class<?> i) {
    return i.getPackageName().startsWith("com.smartfirehub.")
        && i.getPackageName().endsWith(".repository");
  }

  /** jOOQ 구현이 구현하는 리포지토리 인터페이스 → 그 jOOQ 구현들. */
  private static Map<Class<?>, List<Class<?>>> jooqByInterface() throws ClassNotFoundException {
    Map<Class<?>, List<Class<?>>> map = new LinkedHashMap<>();
    for (Class<?> impl : jooqImpls()) {
      for (Class<?> i : impl.getInterfaces()) {
        if (isRepositoryInterface(i)) {
          map.computeIfAbsent(i, k -> new ArrayList<>()).add(impl);
        }
      }
    }
    return map;
  }

  /** 인터페이스를 구현하는 {@code @PlatformMyBatisRepository} 클래스. */
  private static List<Class<?>> mybatisImpls(Class<?> iface) throws ClassNotFoundException {
    return annotated(PlatformMyBatisRepository.class).stream()
        .filter(iface::isAssignableFrom)
        .toList();
  }

  /** 모든 플랫폼 리포지토리 인터페이스는 MyBatis 짝이 있거나 PENDING 에 있어야 한다. */
  @Test
  void everyPlatformRepositoryHasMyBatisPairOrIsPending() throws ClassNotFoundException {
    // PENDING 이 비면 이 검사만 남는다 — 스캔이 깨져 jOOQ 구현을 하나도 못 찾으면 헛통과하므로 대상이 있는지 먼저 본다.
    assertThat(jooqByInterface()).as("짝 검사 대상 jOOQ 리포지토리를 찾지 못했다 — 스캔 범위를 확인").isNotEmpty();
    Set<String> missing = new TreeSet<>();
    for (Class<?> iface : jooqByInterface().keySet()) {
      if (mybatisImpls(iface).isEmpty() && !PENDING.containsKey(iface)) {
        missing.add(iface.getName());
      }
    }
    assertThat(missing)
        .as(
            "MyBatis 구현(@PlatformMyBatisRepository)도 PENDING 항목도 없는 인터페이스 — 구현을 만들거나 PENDING 에 묶음과 함께 올린다")
        .isEmpty();
  }

  /** 짝이 생긴 인터페이스는 PENDING 에서 지워야 한다. */
  @Test
  void pendingEntriesHaveNoPair() throws ClassNotFoundException {
    Set<String> stale = new TreeSet<>();
    for (Class<?> iface : PENDING.keySet()) {
      if (!mybatisImpls(iface).isEmpty()) {
        stale.add(iface.getName());
      }
    }
    assertThat(stale).as("MyBatis 짝이 이미 있다 — PENDING 목록에서 지우세요").isEmpty();
  }

  /** PENDING 항목은 실제 jOOQ 구현이 있는 인터페이스여야 한다 — 이름이 바뀌어 목록이 헛돌지 않게. */
  @Test
  void pendingEntriesAreRealPlatformRepositories() throws ClassNotFoundException {
    Set<Class<?>> real = jooqByInterface().keySet();
    Set<String> bogus = new TreeSet<>();
    for (Class<?> iface : PENDING.keySet()) {
      if (!real.contains(iface)) {
        bogus.add(iface.getName());
      }
    }
    assertThat(bogus).as("jOOQ @Repository 구현이 없는 PENDING 항목").isEmpty();
  }

  /** 짝이 있는 인터페이스의 jOOQ 구현은 PG 전용이어야 한다 — Tibero 설치본에서 두 구현이 함께 주입 후보가 되면 안 된다. */
  @Test
  void pairedJooqImplsArePostgresOnly() throws ClassNotFoundException {
    Set<String> offenders = new TreeSet<>();
    for (Map.Entry<Class<?>, List<Class<?>>> e : jooqByInterface().entrySet()) {
      if (mybatisImpls(e.getKey()).isEmpty()) {
        continue;
      }
      for (Class<?> jooq : e.getValue()) {
        if (!jooq.isAnnotationPresent(ConditionalOnPostgresPlatformDb.class)) {
          offenders.add(jooq.getName());
        }
      }
    }
    assertThat(offenders)
        .as("MyBatis 짝이 있는 jOOQ 구현에 @ConditionalOnPostgresPlatformDb 가 없다")
        .isEmpty();
  }

  /** MyBatis 구현은 {@code ..repository.mybatis..} 패키지에 둔다. */
  @Test
  void mybatisImplsLiveInMybatisPackage() throws ClassNotFoundException {
    Set<String> offenders = new TreeSet<>();
    for (Class<?> c : annotated(PlatformMyBatisRepository.class)) {
      if (!c.getPackageName().contains(".repository.mybatis")) {
        offenders.add(c.getName());
      }
    }
    assertThat(offenders)
        .as("@PlatformMyBatisRepository 클래스는 ..repository.mybatis.. 에 둔다")
        .isEmpty();
  }
}
