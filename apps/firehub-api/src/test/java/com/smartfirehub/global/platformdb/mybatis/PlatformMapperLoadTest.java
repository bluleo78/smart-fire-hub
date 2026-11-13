package com.smartfirehub.global.platformdb.mybatis;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.ibatis.annotations.Flush;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

/**
 * 매퍼 로드 시험(WD-731) — DB 없이 운영 팩토리 조립 경로({@link MyBatisPlatformConfig#sqlSessionFactory(
 * javax.sql.DataSource, String, Resource[])})로 PostgreSQL·Tibero 두 databaseId 기준 팩토리를 만들어, 문장 누락·이름
 * 충돌·미해결 include/resultMap 을 일반 회귀(PG 테스트)에서 잡는다.
 *
 * <p>왜: 지금까지는 Tibero 실서버 시험이 팩토리를 띄울 때만 이런 오류가 드러났다(WD-709 {@code tenantIdRow} resultMap 이름 충돌).
 * 실서버 시험은 일반 회귀에서 돌지 않으므로, 한 벤더 판만 빠진 문장이나 겹친 id 가 머지까지 숨는다. 팩토리 조립은 DB 연결이 필요 없어 여기서 바로 돌린다.
 *
 * <p>운영 jar 와 같은 XML 집합을 보려고 시험 자원(src/test/resources/mybatis/test)의 매퍼 XML 은 뺀다 — 운영 XML 이 시험 전용
 * 조각에 기대면 여기서는 통과하고 운영 기동에서 터지기 때문이다.
 */
class PlatformMapperLoadTest {

  /** 시험하는 databaseId. 허용 목록 키의 접두어도 이 중 하나여야 한다. */
  private static final List<String> VENDORS = List.of("postgresql", "tibero");

  /** 운영 자원에만 있는 매퍼 XML — 이 파일이 놓인 클래스패스 루트를 운영 자원 루트로 본다. */
  private static final String MAIN_ANCHOR = "mybatis/common/PlatformSql.xml";

  /** 시험 자원에만 있는 매퍼 XML — 걸러졌는지 단언한다. */
  private static final List<String> TEST_ONLY_XML =
      List.of("DatasetVisibilityTestMapper.xml", "PlatformSqlFragmentsTestMapper.xml");

  /**
   * 문장이 없어도 되는 매퍼 메서드. 키 {@code "<vendor>:<FQN.method>"}, 값은 그 벤더에서 호출되지 않는 이유. 실제로 문장이 생기면 이 항목은
   * 낡은 것이라 시험이 실패한다.
   */
  private static final Map<String, String> ALLOWED_MISSING =
      Map.of(
          // 세 항목 공통 근거: PG 해석 문장(SECURITY DEFINER 함수 결과 집합)은 OUT 인자가 없어 파라미터 객체의 tenantId 를 채우지
          // 않는다. 구현(MyBatisSlackWorkspaceTenantResolver·MyBatisTriggerTenantResolver)은 행이 없으면
          // tenantId == null
          // 로 곧장 빈 값을 돌려주므로, 재조회 문장은 Tibero(OUT 인자로 테넌트만 받는 경로)에서만 불린다.
          "postgresql:com.smartfirehub.notification.repository.mybatis.SlackWorkspaceTenantResolverMapper.selectWorkspaceIdByTeamId",
          "Tibero 재조회 전용 — PG 는 함수가 워크스페이스 id 까지 준다",
          "postgresql:com.smartfirehub.pipeline.repository.mybatis.TriggerTenantResolverMapper.selectTriggerIdByTokenHash",
          "Tibero 재조회 전용 — PG 는 함수가 트리거 id 까지 준다",
          "postgresql:com.smartfirehub.pipeline.repository.mybatis.TriggerTenantResolverMapper.selectTriggerIdByWebhookId",
          "Tibero 재조회 전용 — PG 는 함수가 트리거 id 까지 준다");

  /** {@code @PlatformMapper} 인터페이스(운영 {@code @MapperScan} 과 같은 기준). 한 번만 스캔한다. */
  private static final List<Class<?>> MAPPERS = scanPlatformMappers();

  /** 벤더별 로드 결과 캐시 — 두 시험이 같은 벤더를 두 번 파싱하지 않게 한다. */
  private static final Map<String, Loaded> LOADED = new ConcurrentHashMap<>();

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"postgresql", "tibero"})
  @DisplayName("모든 매퍼 XML 이 예외 없이 로드된다")
  void allMapperXmlLoad(String vendor) {
    Loaded loaded = loaded(vendor);

    // 경로 패턴이 틀려 XML 을 하나도 못 찾으면 거짓 통과가 되므로 수를 단언한다.
    assertThat(loaded.xmlCount()).as("로드한 운영 매퍼 XML 수").isPositive();
    // 강제 해석 전에 찍어 둔 미완성 항목 — 모든 XML·매퍼를 읽은 뒤에도 남은 것은 해소할 수 없는 참조다.
    assertThat(loaded.incomplete())
        .as("강제 해석 전 미완성 항목 수")
        .allSatisfy((k, n) -> assertThat(n).isZero());
    assertThat(loaded.buildFailure()).as("미완성 문장 강제 해석 실패").isNull();
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"postgresql", "tibero"})
  @DisplayName("@PlatformMapper 인터페이스의 모든 메서드에 문장이 있다")
  void everyMapperMethodHasStatement(String vendor) {
    Configuration c = loaded(vendor).configuration();
    // 스캔이 0개면 아무것도 검사하지 않고 통과하므로 수를 단언한다.
    assertThat(MAPPERS).as("@PlatformMapper 인터페이스 수").isNotEmpty();

    Set<String> missing = new TreeSet<>();
    for (Class<?> mapper : MAPPERS) {
      for (Method m : mapper.getMethods()) {
        // MapperProxy 는 default 메서드를 직접 부르고, @Flush 는 문장 없이 flushStatements 로 간다 — 문장이 필요 없다.
        if (m.isDefault()
            || Modifier.isStatic(m.getModifiers())
            || m.isAnnotationPresent(Flush.class)) {
          continue;
        }
        // 애너테이션 문장(@Select 등)도 팩토리에 등록되므로 같은 규칙으로 확인한다(databaseId 가 달린 애너테이션 포함).
        if (!resolvable(c, mapper, m.getName(), m.getDeclaringClass())) {
          missing.add(vendor + ":" + mapper.getName() + "." + m.getName());
        }
      }
    }

    Set<String> unexpected = new TreeSet<>(missing);
    unexpected.removeAll(ALLOWED_MISSING.keySet());
    assertThat(unexpected).as("문장이 없는 매퍼 메서드(%s)", vendor).isEmpty();

    // 허용 목록 키는 시험하는 벤더 접두어여야 한다 — 오타 접두어("pg:")는 영원히 검사되지 않는 예외가 된다.
    assertThat(ALLOWED_MISSING.keySet())
        .as("허용 목록 키 접두어")
        .allSatisfy(k -> assertThat(VENDORS).anyMatch(v -> k.startsWith(v + ":")));
    // 허용 목록에 있는데 문장이 생긴 항목은 낡은 예외라 지워야 한다.
    assertThat(ALLOWED_MISSING.keySet())
        .filteredOn(k -> k.startsWith(vendor + ":"))
        .as("낡은 허용 목록 항목(%s)", vendor)
        .allMatch(missing::contains);
  }

  /**
   * 로드 결과.
   *
   * @param incomplete 강제 해석 전 미완성 항목 수(문장·resultMap·cache-ref·애너테이션 메서드)
   * @param buildFailure 강제 해석({@code getMappedStatementNames})이 던진 예외, 없으면 null
   */
  private record Loaded(
      Configuration configuration,
      int xmlCount,
      Map<String, Integer> incomplete,
      RuntimeException buildFailure) {}

  private static Loaded loaded(String vendor) {
    return LOADED.computeIfAbsent(vendor, PlatformMapperLoadTest::load);
  }

  /**
   * 운영 조립 경로로 팩토리를 만든다. 파싱 예외(이름 충돌 등)는 그대로 던져 시험을 실패시킨다. XML 네임스페이스로 묶이지 않은 매퍼는 {@code
   * MapperFactoryBean} 처럼 {@code addMapper} 로 더해 애너테이션 문장을 등록한다. 미완성 항목을 찍은 뒤 강제 해석한다.
   */
  private static Loaded load(String vendor) {
    Resource[] xml = mainMapperXml();
    // 연결을 열지 않는 DataSource — 조립 경로는 databaseId 를 인자로 받으므로 DB 에 붙지 않는다.
    Configuration c =
        MyBatisPlatformConfig.sqlSessionFactory(new SimpleDriverDataSource(), vendor, xml)
            .getConfiguration();
    for (Class<?> mapper : MAPPERS) {
      if (!c.hasMapper(mapper)) {
        c.addMapper(mapper);
      }
    }
    Map<String, Integer> incomplete = new LinkedHashMap<>();
    incomplete.put("statements", c.getIncompleteStatements().size());
    incomplete.put("resultMaps", c.getIncompleteResultMaps().size());
    incomplete.put("cacheRefs", c.getIncompleteCacheRefs().size());
    incomplete.put("methods", c.getIncompleteMethods().size());
    RuntimeException buildFailure = null;
    try {
      c.getMappedStatementNames();
    } catch (RuntimeException e) {
      buildFailure = e;
    }
    return new Loaded(c, xml.length, incomplete, buildFailure);
  }

  /**
   * {@code MAPPER_LOCATIONS} 중 운영 자원 루트의 XML 만 고른다. 시험 클래스패스에는 시험 자원 XML 도 잡히므로, 걸러졌는지·운영 XML 이
   * 남았는지를 여기서 바로 확인한다.
   */
  private static Resource[] mainMapperXml() {
    try {
      PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
      String anchor = resolver.getResource("classpath:" + MAIN_ANCHOR).getURL().toString();
      String mainRoot = anchor.substring(0, anchor.length() - MAIN_ANCHOR.length());
      Resource[] all = resolver.getResources(MyBatisPlatformConfig.MAPPER_LOCATIONS);
      List<Resource> main = new ArrayList<>();
      for (Resource r : all) {
        if (r.getURL().toString().startsWith(mainRoot)) {
          main.add(r);
        }
      }
      assertThat(Arrays.stream(all).map(Resource::getFilename))
          .as("시험 클래스패스의 시험 전용 XML")
          .containsAll(TEST_ONLY_XML);
      assertThat(main.stream().map(Resource::getFilename))
          .as("운영 XML 집합")
          .doesNotContainAnyElementsOf(TEST_ONLY_XML);
      return main.toArray(Resource[]::new);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * MyBatis {@code MapperMethod.SqlCommand.resolveMappedStatement} 와 같은 해소 규칙 — 매퍼 FQN 으로 먼저 찾고,
   * 없으면 메서드를 선언한 인터페이스까지 이어지는 상위 인터페이스를 차례로 찾는다.
   */
  private static boolean resolvable(
      Configuration c, Class<?> mapperInterface, String methodName, Class<?> declaringClass) {
    if (c.hasStatement(mapperInterface.getName() + "." + methodName)) {
      return true;
    }
    if (mapperInterface.equals(declaringClass)) {
      return false;
    }
    for (Class<?> superInterface : mapperInterface.getInterfaces()) {
      if (declaringClass.isAssignableFrom(superInterface)
          && resolvable(c, superInterface, methodName, declaringClass)) {
        return true;
      }
    }
    return false;
  }

  /** {@code com.smartfirehub} 아래 {@link PlatformMapper} 인터페이스를 찾는다. */
  private static List<Class<?>> scanPlatformMappers() {
    ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false) {
          @Override
          protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
            // 기본 구현은 구체 클래스만 고른다 — 매퍼는 인터페이스라 인터페이스를 고르게 바꾼다.
            return beanDefinition.getMetadata().isInterface();
          }
        };
    scanner.addIncludeFilter(new AnnotationTypeFilter(PlatformMapper.class));
    List<Class<?>> result = new ArrayList<>();
    for (BeanDefinition bd : scanner.findCandidateComponents("com.smartfirehub")) {
      try {
        result.add(Class.forName(bd.getBeanClassName()));
      } catch (ClassNotFoundException e) {
        throw new IllegalStateException(e);
      }
    }
    return result;
  }
}
