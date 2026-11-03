package com.smartfirehub.global.architecture;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
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
 * <p>플랫폼 DB 의 PG 전용 기능(잠금·테넌트 세션·LISTEN/NOTIFY·오류 판정·벡터 기능 플래그)은 {@code global.platformdb} 인터페이스와
 * {@code ..platformdb.postgres..} 구현에 있다. 허용 목록의 사유 — {@code TENANT_DATA_PATH}(테넌트 데이터 경로), {@code
 * PG_DATA_STORE_INFRA}(R2: PG 데이터 저장소를 세우는 코드라 Tibero 설치본에서 안 쓰임), {@code PLATFORM_DB_WIRING}(플랫폼
 * DataSource 배선, 영구), {@code PG_DRIVER_OOS}(범위 밖 PG 데이터 경로의 드라이버 사용), {@code
 * PREDICATE_BUILDERS_WD485}(조건 조립기)·{@code REPOSITORIES_OUTSIDE_PACKAGE}(repository 패키지 밖 리포지토리)는
 * WD-485 에서 줄인다. 면제는 패키지 기준이며 이름({@code *Repository})만으로는 면제되지 않는다.
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

  /**
   * PG 데이터 저장소 인프라(R2) — 테넌트 스키마·롤 프로비저닝과 파이썬 읽기 권한 동기화. PG 데이터 저장소(테넌트 스키마·롤·GRANT)를 세우는 코드라
   * Tibero 설치본에서는 쓰이지 않는다. Tibero 데이터 저장소 쪽 {@code TiberoPythonReadGrantSync} 는 저장소 행 조회에
   * DSLContext 를 쓴다. 인터페이스로 추상화해도 이득이 없어 영구 분류한다.
   */
  static final Set<String> PG_DATA_STORE_INFRA =
      Set.of(
          "com.smartfirehub.global.config.TenantPipelineDataSourceRegistry",
          "com.smartfirehub.global.tenant.TenantPipelineRoleBootstrap",
          "com.smartfirehub.global.tenant.TenantPipelineRoleProvisioner",
          "com.smartfirehub.global.tenant.TenantSchemaProvisioner",
          "com.smartfirehub.securitylevel.pythonread.PythonReadGrantBackend",
          "com.smartfirehub.securitylevel.pythonread.PythonReadGrantSync",
          "com.smartfirehub.securitylevel.pythonread.TiberoPythonReadGrantSync");

  /**
   * 플랫폼 DB 배선 — 플랫폼 {@code @Primary} DataSource·트랜잭션 매니저·DSLContext 를 만드는 설정이라 DSLContext 를 다루는 것이
   * 존재 이유다. 영구 면제.
   */
  static final Set<String> PLATFORM_DB_WIRING =
      Set.of("com.smartfirehub.global.config.PipelineSandboxDataSourceConfig");

  /**
   * PG JDBC 드라이버({@code org.postgresql..}) 직접 사용 중 {@code TENANT_DATA_PATH}·{@code
   * PG_DATA_STORE_INFRA} 에 없는 범위 밖(OOS) 클래스 — 사용자 SQL 오류 메시지 가공·결과 값 변환처럼 PG 데이터 경로(§0.2
   * Adhoc*·PipelineSqlErrorMessages)에 속한다. 오류 판정 공유 번역기는 {@code DataExceptionTranslator}(R6)다.
   */
  static final Set<String> PG_DRIVER_OOS =
      Set.of(
          "com.smartfirehub.dataset.service.DataExceptionTranslator",
          "com.smartfirehub.global.util.AdhocGeometricValues",
          "com.smartfirehub.global.util.AdhocResultValues",
          "com.smartfirehub.global.util.AdhocSqlErrorMessages",
          "com.smartfirehub.pipeline.service.PipelineSqlErrorMessages");

  /** 플랫폼 DB 인터페이스의 PG 구현체 패키지(WD-486) — 이 패키지는 PG 전용 SQL·드라이버를 쓰는 것이 존재 이유라 목록이 아닌 패키지로 면제한다. */
  static final String PLATFORM_DB_POSTGRES_PACKAGE = "..platformdb.postgres..";

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

  /** DSLContext 규칙의 허용 목록 전체(PG 드라이버 규칙 전용 목록은 제외). */
  private static Set<String> dslAllowed() {
    Set<String> all = new HashSet<>();
    all.addAll(TENANT_DATA_PATH);
    all.addAll(PG_DATA_STORE_INFRA);
    all.addAll(PLATFORM_DB_WIRING);
    all.addAll(PREDICATE_BUILDERS_WD485);
    all.addAll(REPOSITORIES_OUTSIDE_PACKAGE);
    return all;
  }

  /** PG 드라이버 규칙의 허용 목록 전체(DSLContext 전용 목록은 제외). */
  private static Set<String> driverAllowed() {
    Set<String> all = new HashSet<>();
    all.addAll(TENANT_DATA_PATH);
    all.addAll(PG_DATA_STORE_INFRA);
    all.addAll(PG_DRIVER_OOS);
    return all;
  }

  /** 존재 검사 대상 — DSLContext 허용 목록 + PG 드라이버 허용 목록. */
  private static Set<String> allAllowed() {
    Set<String> all = dslAllowed();
    all.addAll(driverAllowed());
    return all;
  }

  /** 클래스 이름이 목록에 있거나 그 중첩·익명 클래스({@code Name$...})인가. */
  private static DescribedPredicate<JavaClass> inList(Set<String> names) {
    return new DescribedPredicate<>("are allowlisted classes") {
      @Override
      public boolean test(JavaClass c) {
        String name = c.getName();
        return names.stream().anyMatch(a -> name.equals(a) || name.startsWith(a + "$"));
      }
    };
  }

  /**
   * repository 패키지·플랫폼 DB PG 구현 패키지에 있거나 허용 목록(중첩·익명 클래스 {@code Name$...} 포함)에 있는 클래스인가. 면제는 패키지 기준
   * — 서비스 안의 중첩 클래스 이름(FooService$XReader)으로 면제를 얻지 못하게 한다. PG 구현 패키지는 목록이 아닌 패키지 규칙으로 면제한다.
   */
  private static DescribedPredicate<JavaClass> exempt() {
    return resideInAPackage("..repository..")
        .or(resideInAPackage(PLATFORM_DB_POSTGRES_PACKAGE))
        .or(inList(dslAllowed()));
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

  /**
   * PG JDBC 드라이버({@code org.postgresql..})는 PG 구현 패키지·테넌트 데이터 경로·PG 데이터 저장소 인프라·범위 밖 목록에서만 쓴다. 플랫폼
   * 서비스가 PG 드라이버 타입(PSQLException 등)에 직접 기대면 Tibero 로 옮길 때 깨진다(WD-486).
   */
  @Test
  void onlyPgImplsDependOnPostgresDriver() {
    noClasses()
        .that()
        .resideOutsideOfPackage(PLATFORM_DB_POSTGRES_PACKAGE)
        .and(DescribedPredicate.not(inList(driverAllowed())))
        .should()
        .dependOnClassesThat()
        .resideInAPackage("org.postgresql..")
        .because("WD-486: PG 드라이버 타입은 PG 구현 패키지·PG 데이터 경로에만 둔다")
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
