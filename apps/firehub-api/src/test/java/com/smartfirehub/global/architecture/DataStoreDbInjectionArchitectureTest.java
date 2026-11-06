package com.smartfirehub.global.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.global.datastore.DataStoreDb;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * 데이터 저장소 경로 클래스(테넌트 데이터 경로 + PG 데이터 저장소 인프라)가 생성자로 DSLContext·DataSource 를 받을 때
 * {@code @DataStoreDb} 또는 {@code @Qualifier("schemaOwnerDataSource")} 를 붙였는지 리플렉션으로 검사한다(WD-494).
 *
 * <p>왜: 한정자 없이 주입받으면 Tibero 플랫폼 DB 설치본에서 {@code @Primary}(플랫폼 DB)가 주입돼 사용자 테이블이 플랫폼 DB 로 향한다. PG
 * 설치본에서는 두 빈이 같은 인스턴스라 동작 차이가 없어 컴파일·기존 테스트로는 잡히지 않는다.
 */
class DataStoreDbInjectionArchitectureTest {

  /**
   * 플랫폼 테이블과 데이터 저장소를 함께 다루던 6개 클래스 — 플랫폼 테이블(jOOQ 생성 {@code com.smartfirehub.jooq..})은 리포지토리로만
   * 읽는다(WD-494 Task 3). 데이터 저장소 쪽은 {@code @DataStoreDb} DSLContext 로 읽는다.
   */
  private static final List<String> MIXED_CLASSES =
      List.of(
          "com.smartfirehub.securitylevel.pythonread.PythonReadGrantSync",
          "com.smartfirehub.securitylevel.pythonread.PythonReadGrantBackend",
          "com.smartfirehub.securitylevel.pythonread.TiberoPythonReadGrantSync",
          "com.smartfirehub.dataimport.service.StagingTableCleanupService",
          "com.smartfirehub.global.tenant.TenantPipelineRoleBootstrap",
          "com.smartfirehub.analytics.service.AnalyticsQueryExecutionService");

  private static final String OWNER_QUALIFIER = "schemaOwnerDataSource";

  @Test
  void 데이터_저장소_경로_클래스의_DSLContext_DataSource_생성자_파라미터는_한정자가_붙어_있다() throws Exception {
    Set<String> targets = new HashSet<>(PlatformDbAccessArchitectureTest.TENANT_DATA_PATH);
    targets.addAll(PlatformDbAccessArchitectureTest.PG_DATA_STORE_INFRA);

    List<String> violations = new ArrayList<>();
    for (String name : targets) {
      Class<?> type = Class.forName(name);
      for (Constructor<?> ctor : type.getDeclaredConstructors()) {
        for (Parameter p : ctor.getParameters()) {
          if (!isDslOrDataSource(p.getType())) continue;
          if (!qualified(p)) {
            violations.add(name + " 생성자 파라미터 " + p.getType().getSimpleName() + " " + p.getName());
          }
        }
      }
    }
    assertThat(violations).as("@DataStoreDb 또는 @Qualifier(schemaOwnerDataSource) 누락").isEmpty();
  }

  @Test
  void 플랫폼_데이터_혼재_6개_클래스는_jOOQ_생성_테이블에_의존하지_않는다() throws Exception {
    Class<?>[] classes = new Class<?>[MIXED_CLASSES.size()];
    for (int i = 0; i < classes.length; i++) {
      classes[i] = Class.forName(MIXED_CLASSES.get(i));
    }
    JavaClasses imported = new ClassFileImporter().importClasses(classes);
    ArchRuleDefinition.noClasses()
        .should()
        .dependOnClassesThat()
        .resideInAPackage("com.smartfirehub.jooq..")
        .check(imported);
  }

  private static boolean isDslOrDataSource(Class<?> t) {
    return t == DSLContext.class || t == DataSource.class;
  }

  private static boolean qualified(Parameter p) {
    if (p.isAnnotationPresent(DataStoreDb.class)) return true;
    Qualifier q = p.getAnnotation(Qualifier.class);
    return q != null && OWNER_QUALIFIER.equals(q.value());
  }
}
