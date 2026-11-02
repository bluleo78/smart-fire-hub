package com.smartfirehub.global.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 플랫폼 DB 접근을 리포지토리로 모으는 규칙을 동결한다(WD-484).
 *
 * <p>무엇을: 프로덕션 클래스 중 면제(리포지토리 패키지 {@code ..repository..} 또는 허용 목록)가 아닌 클래스가 {@code
 * org.jooq.DSLContext} 또는 jOOQ 생성 코드({@code com.smartfirehub.jooq..})에 의존하면 실패한다. 필드·파라미터·호출·정적
 * import 상수 접근 모두 의존으로 잡힌다.
 *
 * <p>왜: 서비스가 쿼리를 직접 쥐면 저장소(PG/Tibero)를 바꿀 때 서비스 전체를 고쳐야 한다. 현재 위반 클래스는 아래 허용 목록으로 동결하고, 이후 작업이 옮긴
 * 클래스를 목록에서 지운다. 목록은 줄기만 하고 늘지 않아야 한다 — 새 클래스를 넣으려면 이 파일을 고쳐야 하므로 리뷰에서 드러난다.
 *
 * <p>WD-484 완료: 이동 대상 14개 클래스(서비스·게이트에 직접 박힌 쿼리)를 모두 리포지토리로 옮겨 그 목록을 없앴다. 남은 네 그룹(테넌트 데이터 경로·PG 기능
 * 인프라 WD-486·조건 조립기 WD-485·repository 패키지 밖 리포지토리)은 각 후속 작업에서 줄인다. 면제는 패키지 기준이며 이름({@code
 * *Repository})만으로는 면제되지 않는다.
 *
 * <p>그래프 가져오기는 {@code SqlGateArchitectureTest} 와 같은 옵션을 쓴다(테스트·jooq 생성 코드 제외, 끝나면 해제). 두 클래스는 순차로
 * 돌고 각자 놓으므로 힙에 동시에 두 벌이 남지 않는다. 힙 사정은 그 클래스 주석 참고.
 */
class PlatformDbAccessArchitectureTest {

  /**
   * 테넌트 데이터 경로 — 사용자 데이터 테이블·행·검색 색인·애드혹 SQL 지원. Tibero 설치본에서는 PG 테넌트 저장소 자체가 비활성이라 PG 전용 jOOQ 코드를
   * 리포지토리로 옮겨도 이득이 없어 이번 범위에서 제외한다.
   */
  static final Set<String> TENANT_DATA_PATH =
      Set.of(
          "com.smartfirehub.analytics.service.AnalyticsQueryExecutionService",
          "com.smartfirehub.dataimport.service.StagingTableCleanupService",
          "com.smartfirehub.dataset.rowsearch.PgRowSearchIndex",
          "com.smartfirehub.dataset.service.DataTableQueryService",
          "com.smartfirehub.dataset.service.PostgresDataRowStore",
          "com.smartfirehub.dataset.service.PostgresDataTableStore",
          "com.smartfirehub.global.util.AdhocMultiDimArrays",
          "com.smartfirehub.global.util.AdhocSqlStatements",
          "com.smartfirehub.global.util.AdhocTemporalValues",
          "com.smartfirehub.global.util.TemporalSafeFetch",
          "com.smartfirehub.pipeline.service.IncrementalCursorService",
          "com.smartfirehub.pipeline.service.SqlColumnProbe",
          "com.smartfirehub.pipeline.service.SqlScriptExecutor",
          "com.smartfirehub.pipeline.service.VerbatimSql");

  /** PG 기능 인프라 — 테넌트 스키마·롤 프로비저닝, 파이프라인 샌드박스, 벡터·HNSW 등 PG 전용 기능 기반(WD-486 에서 다룬다). */
  static final Set<String> PG_INFRA_WD486 =
      Set.of(
          "com.smartfirehub.auth.service.CurrentTransactionTenant",
          "com.smartfirehub.embedding.HnswSearch",
          "com.smartfirehub.embedding.VectorTables",
          "com.smartfirehub.global.config.PipelineSandboxDataSourceConfig",
          "com.smartfirehub.global.config.TenantPipelineDataSourceRegistry",
          "com.smartfirehub.global.tenant.TenantPipelineRoleBootstrap",
          "com.smartfirehub.global.tenant.TenantPipelineRoleProvisioner",
          "com.smartfirehub.global.tenant.TenantProvisioningService",
          "com.smartfirehub.global.tenant.TenantSchemaProvisioner",
          "com.smartfirehub.securitylevel.pythonread.PythonReadGrantBackend",
          "com.smartfirehub.securitylevel.pythonread.PythonReadGrantSync",
          "com.smartfirehub.securitylevel.pythonread.TiberoPythonReadGrantSync");

  /**
   * 조건 조립기 — 접근 가능 데이터셋·그래프 술어를 jOOQ {@code Condition} 으로 만들어 호출자 쿼리에 끼운다. 생성 테이블 참조를 쓰는 설계 자체를 바꿔야
   * 하므로 WD-485 로 미룬다(DSLContext 는 없앤다).
   */
  static final Set<String> PREDICATE_BUILDERS_WD485 =
      Set.of(
          "com.smartfirehub.ontology.graphread.GraphReadGate",
          "com.smartfirehub.securitylevel.access.DatasetAccessGuard");

  /**
   * 이름은 리포지토리지만 repository 패키지 밖에 있는 클래스 — 이름만으로 면제하면 서비스가 이름으로 규칙을 피할 수 있어 명시 목록으로 둔다. 패키지 정리는
   * WD-485.
   */
  static final Set<String> REPOSITORIES_OUTSIDE_PACKAGE =
      Set.of(
          "com.smartfirehub.dataset.rowsearch.SearchColumnRepository",
          "com.smartfirehub.dataset.rowsearch.SearchIndexStateRepository",
          "com.smartfirehub.dataset.rowsearch.SearchSourceReader",
          "com.smartfirehub.dataset.search.DatasetEmbeddingRepository",
          "com.smartfirehub.dataset.search.DatasetMetaReader",
          "com.smartfirehub.dataset.search.DatasetSearchRepository",
          "com.smartfirehub.dataset.storage.TenantDataStoreRepository",
          "com.smartfirehub.embedding.reembed.EmbeddingReembedStateRepository",
          "com.smartfirehub.ontology.binding.DatasetOntologyRepository",
          "com.smartfirehub.ontology.element.OntologyElementRepository",
          "com.smartfirehub.ontology.graphread.GraphOntologySourceRepository");

  /**
   * 프로덕션 클래스 그래프. 클래스 단위로 한 번만 가져오고 끝나면 놓는다 — {@code static final} 로 붙잡으면 전체 백엔드 스위트가 같은 JVM 에서 도는
   * 동안 큰 그래프가 끝까지 남아 힙 부족에 가까워진다(SqlGateArchitectureTest 주석 참고).
   */
  private static JavaClasses PROD;

  @BeforeAll
  static void importProductionClasses() {
    PROD =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            // jOOQ 생성 코드는 가져오지 않는다(의존 대상 이름 판정에는 가져오지 않아도 충분하다).
            .withImportOption(location -> !location.contains("/com/smartfirehub/jooq/"))
            .importPackages("com.smartfirehub");
  }

  @AfterAll
  static void releaseProductionClasses() {
    PROD = null;
  }

  private static Set<String> allAllowed() {
    Set<String> all = new HashSet<>();
    all.addAll(TENANT_DATA_PATH);
    all.addAll(PG_INFRA_WD486);
    all.addAll(PREDICATE_BUILDERS_WD485);
    all.addAll(REPOSITORIES_OUTSIDE_PACKAGE);
    return all;
  }

  /** repository 패키지에 있거나 허용 목록(중첩·익명 클래스 {@code Name$...} 포함)에 있는 클래스인가. */
  private static DescribedPredicate<JavaClass> exempt() {
    Set<String> allowed = allAllowed();
    return new DescribedPredicate<>("reside in repository packages or are allowlisted classes") {
      @Override
      public boolean test(JavaClass c) {
        String name = c.getName();
        // 면제는 패키지 기준 — 서비스 안의 중첩 클래스 이름(FooService$XReader)으로 면제를 얻지 못하게 한다.
        String pkg = c.getPackageName();
        if (pkg.endsWith(".repository") || pkg.contains(".repository.")) {
          return true;
        }
        return allowed.stream().anyMatch(a -> name.equals(a) || name.startsWith(a + "$"));
      }
    };
  }

  /** 허용 목록 밖의 클래스는 DSLContext·jOOQ 생성 코드에 의존하지 않는다. */
  @Test
  void onlyRepositoriesDependOnDslContextOrGeneratedJooq() {
    noClasses()
        .that(DescribedPredicate.not(exempt()))
        .should()
        .dependOnClassesThat()
        .areAssignableTo("org.jooq.DSLContext")
        .orShould()
        .dependOnClassesThat()
        .resideInAPackage("com.smartfirehub.jooq..")
        .because("WD-484: 플랫폼 DB 쿼리는 리포지토리에 둔다 — 새 예외는 허용 목록(리뷰 대상)에 추가")
        .check(PROD);
  }

  /** 허용 목록의 모든 FQCN 이 실제 클래스여야 한다 — 이름이 바뀌어 목록이 조용히 무의미해지는 것을 막는다. */
  @Test
  void allowlistedClassesExist() {
    for (String fqcn : allAllowed()) {
      assertThat(PROD.contain(fqcn)).as("허용 목록의 클래스가 존재해야 한다: %s", fqcn).isTrue();
    }
  }
}
