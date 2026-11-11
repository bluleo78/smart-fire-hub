package com.smartfirehub.global.platformdb.mybatis;

import com.smartfirehub.global.platformdb.PlatformDbType;
import java.beans.Introspector;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.context.annotation.AnnotationConfigUtils;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.filter.AnnotationTypeFilter;

/**
 * {@link PlatformMyBatisRepository} 클래스를 빈으로 등록하는 등록기(WD-706). {@link MyBatisPlatformConfig} 가
 * {@code @Import} 하므로 MyBatis 배선이 켜진 컨텍스트에서만 돈다.
 *
 * <p>빈 이름은 {@code Introspector.decapitalize(단순이름)}(예 {@code myBatisUserRepository}) — 계약 테스트가 이름으로
 * 꺼낸다. {@code firehub.platform-db.type=tibero} 면 주입 후보(서비스가 인터페이스로 받는다), 아니면 {@code
 * autowireCandidate=false}(테스트 프로필: 서비스는 여전히 jOOQ 구현을 받는다).
 */
public class PlatformMyBatisRepositoryRegistrar
    implements ImportBeanDefinitionRegistrar, EnvironmentAware, ResourceLoaderAware {

  /** 스캔 기준 패키지 속성(시험용). 기본은 {@value #DEFAULT_SCAN_PACKAGE}. */
  public static final String SCAN_PACKAGE_PROPERTY =
      "firehub.platform-db.mybatis.repository-scan-package";

  static final String DEFAULT_SCAN_PACKAGE = "com.smartfirehub";

  private Environment environment;
  private ResourceLoader resourceLoader;

  @Override
  public void setEnvironment(Environment environment) {
    this.environment = environment;
  }

  /** 스캐너가 애플리케이션의 리소스 로더(클래스로더 포함)를 쓰도록 받아 둔다. */
  @Override
  public void setResourceLoader(ResourceLoader resourceLoader) {
    this.resourceLoader = resourceLoader;
  }

  @Override
  public void registerBeanDefinitions(
      AnnotationMetadata importingClassMetadata, BeanDefinitionRegistry registry) {
    boolean tibero = "tibero".equalsIgnoreCase(environment.getProperty(PlatformDbType.PROPERTY));
    String basePackage = environment.getProperty(SCAN_PACKAGE_PROPERTY, DEFAULT_SCAN_PACKAGE);
    ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false, environment);
    if (resourceLoader != null) {
      scanner.setResourceLoader(resourceLoader);
    }
    // 구현은 항상 repository/mybatis 패키지에 둔다 — 그 밖의 클래스 파일은 읽지도 않게 스캔 범위를 좁힌다.
    scanner.setResourcePattern("**/repository/mybatis/*.class");
    scanner.addIncludeFilter(new AnnotationTypeFilter(PlatformMyBatisRepository.class));
    for (BeanDefinition bd : scanner.findCandidateComponents(basePackage)) {
      String className = bd.getBeanClassName();
      String simpleName = className.substring(className.lastIndexOf('.') + 1);
      // @Lazy·@Primary·@DependsOn·@Role 등 공통 애노테이션을 일반 컴포넌트 스캔처럼 정의에 반영한다.
      if (bd instanceof AnnotatedBeanDefinition annotated) {
        AnnotationConfigUtils.processCommonDefinitionAnnotations(annotated);
      }
      // 스캔이 만든 정의는 단일 생성자 자동 주입으로 만들어진다. 주입 후보 여부만 종류별로 가른다.
      ((AbstractBeanDefinition) bd).setAutowireCandidate(tibero);
      registry.registerBeanDefinition(Introspector.decapitalize(simpleName), bd);
    }
  }
}
