package com.smartfirehub.securitylevel.sql;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;

import com.smartfirehub.analytics.service.AnalyticsQueryExecutionService;
import com.smartfirehub.dataset.service.DataTableQueryService;
import com.smartfirehub.global.util.AdhocSqlStatements;
import com.smartfirehub.pipeline.service.PipelineAsyncRunner;
import com.smartfirehub.pipeline.service.SqlColumnProbe;
import com.smartfirehub.pipeline.service.SqlScriptExecutor;
import com.smartfirehub.pipeline.service.executor.ExecutorClient;
import com.smartfirehub.proactive.service.MetricPollerService;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 사용자 SQL 실행 지점의 허용 호출자를 동결한다(스펙 §4.1, §7.1 "실행 지점 누락").
 *
 * <p>왜 싱크를 다섯 종류나 묶는가: 애드혹 실행은 executor 활성 시 AdhocSqlStatements 를 거치지 않고 ExecutorClient 로 간다(판단 사항
 * 3). 관문(GuardedSqlExecutor)만 막으면 executor 경로가 열린 채 남는다. 새 호출자를 추가하려면 이 파일을 고쳐야 하므로 리뷰에서 반드시 드러난다.
 *
 * <p>실행 서비스 자신이 허용 목록에 들어간 이유: 테스트용 문자열 오버로드({@code execute(String, …)}·{@code executeQuery(String,
 * …)})가 정규화 후 같은 이름의 {@code NormalizedSql} 오버로드로 위임하는 자기 호출도 호출로 집계된다. 그 문자열 오버로드는 판정 없이 정규화만 하므로
 * 프로덕션에서 아무도 부르면 안 된다 — 별도 규칙({@code stringOverloads_haveNoProductionCallers})이 막는다.
 *
 * <p>모든 규칙은 호출뿐 아니라 메서드 참조도 접근으로 센다({@link #onlyBeAccessedBy}).
 *
 * <p>파이프라인 싱크는 세 규칙으로 쪼갰다 — ArchUnit 의 {@code that().and().or()} 체인은 왼쪽부터 결합되어 한 규칙에 묶으면 앞 두 싱크가
 * 검사에서 빠진다.
 */
class SqlGateArchitectureTest {

  /**
   * 프로덕션 클래스 그래프. 클래스 단위로 한 번만 가져오고 끝나면 놓는다 — {@code static final} 로 붙잡으면 전체 백엔드 스위트가 같은 JVM 에서 도는
   * 동안 이 큰 그래프가 끝까지 남아 테스트 JVM(기본 힙 512MB)이 힙 부족에 가까워진다. 외부 라이브러리 추적은 test resources 의
   * archunit.properties 가 끈다(실측: 끄기 전 pre-commit 전체 스위트가 OutOfMemoryError 로 죽었다).
   */
  private static JavaClasses PROD;

  @BeforeAll
  static void importProductionClasses() {
    PROD =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            // jOOQ 생성 코드(com.smartfirehub.jooq)는 SQL 싱크를 부르지 않는다 — 가져오지 않아 힙을 아낀다.
            .withImportOption(location -> !location.contains("/com/smartfirehub/jooq/"))
            .importPackages("com.smartfirehub");
  }

  @AfterAll
  static void releaseProductionClasses() {
    PROD = null;
  }

  /**
   * 메서드에 대한 <b>모든 접근</b>(호출 + 메서드 참조 {@code obj::method})이 허용 클래스(중첩·익명·람다 포함)에서만 오는지 본다.
   *
   * <p>왜 ArchUnit 의 {@code onlyBeCalled()} 가 아닌가: 그것은 호출만 보고 메서드 참조는 보지 않는다 — {@code BiFunction<…>
   * f = dataTableQueryService::executeQuery} 한 줄로 관문을 우회해도 초록이었다(실측). {@link
   * JavaMethod#getAccessesToSelf()} 는 호출과 참조를 함께 돌려준다. 허용 목록이 비면 "프로덕션 접근 금지"다.
   */
  private static ArchCondition<JavaMethod> onlyBeAccessedBy(Class<?>... allowed) {
    Set<String> names = Arrays.stream(allowed).map(Class::getName).collect(Collectors.toSet());
    return new ArchCondition<>("only be accessed (called or referenced) by " + names) {
      @Override
      public void check(JavaMethod method, ConditionEvents events) {
        for (JavaAccess<?> access : method.getAccessesToSelf()) {
          String origin = access.getOriginOwner().getName();
          boolean ok = names.stream().anyMatch(n -> origin.equals(n) || origin.startsWith(n + "$"));
          if (!ok) {
            events.add(SimpleConditionEvent.violated(access, access.getDescription()));
          }
        }
      }
    };
  }

  /** 데이터셋 /query 실행 서비스 — 관문만 부른다. */
  @Test
  void datasetQueryExecution_onlyThroughGate() {
    methods()
        .that()
        .areDeclaredIn(DataTableQueryService.class)
        .and()
        .haveName("executeQuery")
        .should(onlyBeAccessedBy(GuardedSqlExecutor.class, DataTableQueryService.class))
        .check(PROD);
  }

  /** 애널리틱스 실행 서비스(애드혹·저장 쿼리·차트·대시보드) — 관문만 부른다. */
  @Test
  void analyticsExecution_onlyThroughGate() {
    methods()
        .that()
        .areDeclaredIn(AnalyticsQueryExecutionService.class)
        .and()
        .haveName("execute")
        .should(onlyBeAccessedBy(GuardedSqlExecutor.class, AnalyticsQueryExecutionService.class))
        .check(PROD);
  }

  /**
   * 판정 없이 정규화만 하는 문자열 오버로드는 프로덕션 호출자가 없어야 한다 — 관문이 실수로 이쪽을 부르면 판정 문자열과 실행 문자열이 다시 갈라진다(같은 정규화를 두 번
   * 하게 되어 "판정 = 실행"이 구조가 아니라 우연이 된다).
   */
  @Test
  void stringOverloads_haveNoProductionCallers() {
    methods()
        .that()
        .areDeclaredIn(DataTableQueryService.class)
        .and()
        .haveName("executeQuery")
        .and()
        .haveRawParameterTypes(String.class, int.class)
        .should(onlyBeAccessedBy())
        .check(PROD);
    methods()
        .that()
        .areDeclaredIn(AnalyticsQueryExecutionService.class)
        .and()
        .haveName("execute")
        .and()
        .haveRawParameterTypes(String.class, int.class, boolean.class)
        .should(onlyBeAccessedBy())
        .check(PROD);
  }

  /** AdhocSqlStatements 자신: 내부 헬퍼(rollbackToSavepointOrRethrow 등)의 자기 호출 허용. */
  @Test
  void adhocStatements_onlyFromTheTwoExecutionServices() {
    methods()
        .that()
        .areDeclaredIn(AdhocSqlStatements.class)
        .and()
        .haveNameMatching("fetch|execute")
        .should(
            onlyBeAccessedBy(
                DataTableQueryService.class,
                AnalyticsQueryExecutionService.class,
                AdhocSqlStatements.class))
        .check(PROD);
  }

  @Test
  void executorQuery_onlyFromAnalyticsAndMetricPoller() {
    methods()
        .that()
        .areDeclaredIn(ExecutorClient.class)
        .and()
        .haveName("executeQuery")
        .should(onlyBeAccessedBy(AnalyticsQueryExecutionService.class, MetricPollerService.class))
        .check(PROD);
  }

  /** 파이프라인 SQL 스텝 실행(executor) — 러너만. ExecutorClient 자신은 오버로드 위임 때문에 허용. */
  @Test
  void executorSql_onlyFromRunner() {
    methods()
        .that()
        .areDeclaredIn(ExecutorClient.class)
        .and()
        .haveName("executeSql")
        .should(onlyBeAccessedBy(PipelineAsyncRunner.class, ExecutorClient.class))
        .check(PROD);
  }

  /** 파이프라인 SQL 스크립트 직접 실행 — 러너만. SqlScriptExecutor 자신은 오버로드 위임 때문에 허용. */
  @Test
  void sqlScriptExecutor_onlyFromRunner() {
    methods()
        .that()
        .areDeclaredIn(SqlScriptExecutor.class)
        .and()
        .haveName("execute")
        .should(onlyBeAccessedBy(PipelineAsyncRunner.class, SqlScriptExecutor.class))
        .check(PROD);
  }

  /** 파이프라인 출력 컬럼 탐지(사용자 SQL 을 LIMIT 0 으로 실행) — 러너만. */
  @Test
  void sqlColumnProbe_onlyFromRunner() {
    methods()
        .that()
        .areDeclaredIn(SqlColumnProbe.class)
        .and()
        .haveName("columnsWithTypes")
        .should(onlyBeAccessedBy(PipelineAsyncRunner.class))
        .check(PROD);
  }
}
