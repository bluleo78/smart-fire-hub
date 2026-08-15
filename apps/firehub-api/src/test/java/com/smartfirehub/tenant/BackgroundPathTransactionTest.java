package com.smartfirehub.tenant;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.smartfirehub.apiconnection.repository.ApiConnectionRepository;
import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.document.repository.DocumentFileRepository;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.graphingest.repository.GraphIngestRepository;
import com.smartfirehub.graphreview.repository.ReviewItemRepository;
import com.smartfirehub.job.repository.AsyncJobRepository;
import com.smartfirehub.mapping.repository.MappingRepository;
import com.smartfirehub.ontology.binding.DatasetOntologyRepository;
import com.smartfirehub.ontology.element.OntologyElementRepository;
import com.smartfirehub.ontology.repository.OntologyRepository;
import com.smartfirehub.pipeline.repository.PipelineExecutionRepository;
import com.smartfirehub.pipeline.repository.PipelineRepository;
import com.smartfirehub.pipeline.repository.PipelineStepRepository;
import com.smartfirehub.pipeline.repository.TriggerEventRepository;
import com.smartfirehub.pipeline.repository.TriggerRepository;
import com.smartfirehub.ai.repository.AiSessionRepository;
import com.smartfirehub.proactive.repository.AnomalyEventRepository;
import com.smartfirehub.proactive.repository.MetricSnapshotRepository;
import com.smartfirehub.proactive.repository.ProactiveJobExecutionRepository;
import com.smartfirehub.proactive.repository.ProactiveJobRepository;
import com.smartfirehub.proactive.repository.ProactiveMessageRepository;
import com.smartfirehub.proactive.repository.ReportTemplateRepository;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.Map;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 배경 잡 스레드의 형태를 그대로 재현해, 리포지토리 접근이 실제로 데이터를 본다는 것을 단언한다.
 *
 * <p>왜 이 테스트가 필요한가: RLS 격리 값은 트랜잭션-로컬 GUC 이고, 그 GUC 는 {@code
 * TenantAwareTransactionManager.doBegin} 에서만 주입된다. {@code TenantContext.set()} 은 ThreadLocal 만
 * 바꾼다 — <b>그 뒤에 트랜잭션이 열리지 않으면 GUC 는 영원히 비어 있고 RLS 가 전 행을 차단한다.</b>
 * JobRunr 잡은 요청 컨텍스트도 앰비언트 트랜잭션도 없이 실행되므로 정확히 이 상황에 놓인다.
 *
 * <p><b>이 클래스에는 클래스 레벨 {@code @Transactional} 이 없다.</b> 그것이 이 테스트의 전부다 —
 * 붙이는 순간 테스트 트랜잭션이 GUC 를 공급해서 운영에는 없는 조건을 만들고, 결함이 보이지 않게 된다.
 * 기존 {@code BackgroundTenantPropagationTest} 는 시그니처만 리플렉션으로 확인하므로 이 결함을
 * 구조적으로 잡지 못한다.
 */
class BackgroundPathTransactionTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private DatasetRepository datasetRepository;
  @Autowired private DocumentFileRepository documentFileRepository;
  @Autowired private PipelineRepository pipelineRepository;
  @Autowired private PipelineStepRepository pipelineStepRepository;
  @Autowired private PipelineExecutionRepository pipelineExecutionRepository;
  @Autowired private TriggerRepository triggerRepository;
  @Autowired private TriggerEventRepository triggerEventRepository;
  @Autowired private ApiConnectionRepository apiConnectionRepository;
  @Autowired private AsyncJobRepository asyncJobRepository;
  @Autowired private ReportTemplateRepository reportTemplateRepository;
  @Autowired private OntologyRepository ontologyRepository;
  @Autowired private OntologyElementRepository ontologyElementRepository;
  @Autowired private DatasetOntologyRepository datasetOntologyRepository;
  @Autowired private MappingRepository mappingRepository;
  @Autowired private GraphIngestRepository graphIngestRepository;
  @Autowired private ReviewItemRepository reviewItemRepository;
  @Autowired private ProactiveJobRepository proactiveJobRepository;
  @Autowired private ProactiveJobExecutionRepository proactiveJobExecutionRepository;
  @Autowired private ProactiveMessageRepository proactiveMessageRepository;
  @Autowired private MetricSnapshotRepository metricSnapshotRepository;
  @Autowired private AnomalyEventRepository anomalyEventRepository;
  @Autowired private AiSessionRepository aiSessionRepository;

  private TransactionTemplate tx;
  private long tenantId;
  private Long userId;
  private Long datasetId;
  private Long documentFileId;
  private Long reportTemplateId;

  @BeforeEach
  void seed() {
    tx = new TransactionTemplate(transactionManager);
    tenantId = TenantRlsTestSupport.createActiveTenant(dsl, "bg-path");
    userId = TenantRlsTestSupport.insertUser(dsl, "bguser");
    // 픽스처는 트랜잭션 안에서 만든다(그래야 tenant_id DEFAULT 가 채워진다).
    TenantRlsTestSupport.runInTenantTransaction(
        tx,
        tenantId,
        () -> {
          datasetId = insertDataset();
          documentFileId = insertDocumentFile(datasetId);
          reportTemplateId = insertReportTemplate();
        });
  }

  @AfterEach
  void cleanup() {
    TenantRlsTestSupport.runInTenantTransaction(
        tx,
        tenantId,
        () -> {
          dsl.deleteFrom(table(name("document_file"))).execute();
          dsl.deleteFrom(table(name("dataset"))).execute();
          // 내가 심은 행만 지운다(공유 테스트 DB) — report_template 은 테넌트 1에도 실데이터가 있다.
          dsl.deleteFrom(table(name("report_template")))
              .where(field(name("id"), Long.class).eq(reportTemplateId))
              .execute();
        });
    TenantRlsTestSupport.deleteUser(dsl, userId);
    TenantRlsTestSupport.deleteTenants(dsl, tenantId);
    TenantContext.clear();
  }

  @Test
  void datasetIsReadableFromBackgroundThreadShape() {
    // 잡 스레드 재현: 컨텍스트만 세우고, 앰비언트 트랜잭션 없이 리포지토리를 호출한다.
    TenantContext.set(tenantId);
    try {
      assertThat(datasetRepository.findById(datasetId))
          .as(
              "배경 잡 형태(트랜잭션 없음)에서 데이터셋이 안 보이면, 임포트 잡이 'Dataset not found'"
                  + " 로 실패한다 — 리포지토리가 자기 트랜잭션을 열어야 한다")
          .isPresent();
    } finally {
      TenantContext.clear();
    }
  }

  @Test
  void documentFileIsReadableFromBackgroundThreadShape() {
    // processIngestion 첫 문장이 이 형태다: findById(...).orElseThrow()
    TenantContext.set(tenantId);
    try {
      assertThat(documentFileRepository.findById(documentFileId))
          .as("배경 잡 형태에서 document_file 이 안 보이면 문서가 PENDING 에 영구히 고착된다")
          .isPresent();
    } finally {
      TenantContext.clear();
    }
  }

  /**
   * V99 로 RLS 가 걸린 {@code report_template} 이 배경 잡 형태에서 읽히는지.
   *
   * <p>{@code ProactiveJobAsyncRunner.executeJob} 이 정확히 이 형태다: {@code @Async} 만 있고
   * 트랜잭션이 없는 스레드에서 {@code reportTemplateRepository.findById(...)} 를 부른다. 리포지토리가
   * 자기 트랜잭션을 열지 않으면 GUC 가 비어 0행이 되고, 예외 없이 {@code template = null} 이 되어
   * 사용자의 sections·style 없는 리포트가 만들어진다(최종 리뷰 Critical-1).
   */
  @Test
  void reportTemplateIsReadableFromBackgroundThreadShape() {
    TenantContext.set(tenantId);
    try {
      assertThat(reportTemplateRepository.findById(reportTemplateId))
          .as("배경 잡 형태에서 report_template 이 안 보이면 리포트가 양식 없이 생성된다")
          .isPresent();
    } finally {
      TenantContext.clear();
    }
  }

  @Test
  void contextlessBackgroundReadStillSeesNothing() {
    // fail-closed 는 유지되어야 한다 — 리포지토리가 트랜잭션을 열더라도, 테넌트가 없으면
    // GUC 가 비어 정책이 전 행을 차단해야 한다.
    TenantContext.clear();
    assertThat(datasetRepository.findById(datasetId))
        .as("테넌트 없이 보이면 fail-open 이다")
        .isEmpty();
  }

  // ── P2-b: pipeline/apiconnection/job 리포지토리 트랜잭션 경계 ────────────
  //
  // 배선 자체를 직접 단언한다: 각 리포지토리가 트랜잭션 프록시이고 클래스 레벨 @Transactional 을
  // 갖는가. 이 조건이 깨지면 배경 스레드에서 GUC 가 주입되지 않아 예외도 로그도 없이 조용히 0행이 된다.
  //
  // ★ 이 메타데이터 단언을 "정책 오기 전의 임시방편" 으로 오해하지 마라 — 정반대다.
  // (원래 이 자리에는 "이 테이블들에는 아직 RLS 가 없어 행 가시성으로는 증명할 수 없으니 임시로
  //  메타데이터를 본다" 는 주석이 있었다. P2-e 최종 리뷰가 그 주석이 낡았을 뿐 아니라 방향이
  //  반대라는 것을 실측으로 잡았다.)
  //
  // 정책이 생긴 지금도 행위 단언이 이 자리를 대신할 수 없다. 여기 등록된 리포지토리들의 행위
  // 테스트가 **전부 클래스 레벨 @Transactional 을 달고 있기 때문이다**(P2-e 6개 기준:
  // MetricSnapshotRepositoryTest, AnomalyEventRepositoryTest, ProactiveJobExecutionRepositoryTest,
  // ProactiveMessageRepositoryTest, ProactiveJobSchedulerServiceTest, ProactiveJobServiceTest,
  // AiSessionServiceTest — 저장소 전체로는 58개 클래스에 걸친 기존 상태다).
  // 테스트 트랜잭션이 GUC 를 공급하고 리포지토리의 REQUIRED 가 거기 합류하므로,
  // **프로덕션 리포지토리에서 @Transactional 을 지워도 그 테스트들은 전부 초록이다.**
  //
  // 즉 이 메타데이터 단언이 해당 리포지토리들의 **유일한 가드**다. "이제 정책이 있으니 행위
  // 단언으로 바꾸자" 며 걷어내면 가드가 통째로 사라진다. 걷어내려면 먼저 그 행위 테스트들에서
  // 클래스 레벨 @Transactional 을 떼어내야 한다.

  /**
   * 배경 경로가 쓰는 리포지토리들이 클래스 레벨 @Transactional 을 유지하는지.
   *
   * <p>ReportTemplateRepository 만 예외적으로 이미 RLS 대상이라 위
   * {@link #reportTemplateIsReadableFromBackgroundThreadShape()} 의 행위 단언도 함께 걸려 있다.
   * 나머지 7개는 아직 정책이 없어 메타데이터 단언만 가능하다.
   *
   * <p>애노테이션을 지우면 이 테스트가 실패한다 — 호출 결과만 보는 단언은 RLS 가 없는 지금
   * 언제나 통과하므로 가드가 되지 못한다. V96 이후에는 격리 자체가 이 배선에 달려 있다.
   */
  @Test
  void backgroundPathRepositoriesKeepClassLevelTransactional() {
    // Map.of 는 최대 10쌍까지만 오버로드가 있다. P2-d 에서 온톨로지·그래프 리포지토리가 계속
    // 추가되면서 그 한계를 넘으므로 Map.ofEntries 로 바꾼다(쌍 개수 제한 없음).
    Map<String, Object> repositories =
        Map.ofEntries(
            entry("PipelineRepository", pipelineRepository),
            entry("PipelineStepRepository", pipelineStepRepository),
            entry("PipelineExecutionRepository", pipelineExecutionRepository),
            entry("TriggerRepository", triggerRepository),
            entry("TriggerEventRepository", triggerEventRepository),
            entry("ApiConnectionRepository", apiConnectionRepository),
            entry("AsyncJobRepository", asyncJobRepository),
            // report_template 은 V99 로 RLS 대상이 됐는데 유일한 배경 호출자
            // (ProactiveJobAsyncRunner.executeJob)에는 트랜잭션이 없었다. 이 맵에 없었기 때문에
            // 그 결함이 이 band 를 통과했다(최종 리뷰 Critical-1).
            entry("ReportTemplateRepository", reportTemplateRepository),
            // ── P2-d(Task 2): 온톨로지 코어 ──
            // OntologyService 에는 트랜잭션 경계가 없고 MCP 툴 핸들러도 마찬가지다. V102 가 정책을
            // 켜면 GUC 미주입으로 조회는 조용히 0행, 생성은 tenant_id NOT NULL 위반이 된다.
            entry("OntologyRepository", ontologyRepository),
            entry("OntologyElementRepository", ontologyElementRepository),
            // ── P2-d(Task 3): 바인딩·매핑·적재이력 ──
            // MappingRepository 가 특히 위험하다 — save/activate 는 서비스 레벨 트랜잭션이 있어
            // 쓰기는 성공하는데 get 만 0행이 되어 "저장했는데 미매핑으로 보인다"로 나타난다.
            entry("DatasetOntologyRepository", datasetOntologyRepository),
            entry("MappingRepository", mappingRepository),
            entry("GraphIngestRepository", graphIngestRepository),
            // ── P2-d(Task 4): 그래프 검수 인박스 ──
            // 호출 지점이 10곳으로 가장 넓고 파손 형태가 세 가지다(추출 중단 / 중복 검수 항목
            // 재생성 / update 0행인데 검수자에게는 성공으로 보고).
            entry("ReviewItemRepository", reviewItemRepository),
            // ── P2-e(Task 3·4): 프로액티브 트리 + AI 세션 ──
            // 배경 경로가 유난히 넓다: 부팅 재등록(@PostConstruct), 크론 발화(스케줄러 풀),
            // 메트릭 폴러(@Scheduled), 이상탐지 리스너(@Async), outbox 워커(@Scheduled).
            // 전부 앰비언트 트랜잭션이 없어 리포지토리가 스스로 열지 않으면 V104 이후
            // 조회는 조용히 0행, 삽입은 tenant_id NOT NULL 위반이 된다.
            entry("ProactiveJobRepository", proactiveJobRepository),
            entry("ProactiveJobExecutionRepository", proactiveJobExecutionRepository),
            // proactive_message 는 두 경로가 쓴다 — 살아 있는 ChatDeliveryChannel(pipelineExecutor,
            // 컨텍스트만 있음)과 플래그로 잠든 ChatChannel(워커 스레드). 전자의 정합성은 전적으로
            // 이 애노테이션에 달려 있다.
            entry("ProactiveMessageRepository", proactiveMessageRepository),
            entry("MetricSnapshotRepository", metricSnapshotRepository),
            entry("AnomalyEventRepository", anomalyEventRepository),
            entry("AiSessionRepository", aiSessionRepository));

    repositories.forEach(
        (label, bean) -> {
          Class<?> targetClass = AopUtils.getTargetClass(bean);
          assertThat(AnnotatedElementUtils.hasAnnotation(targetClass, Transactional.class))
              .as("%s 에 클래스 레벨 @Transactional 이 없다 — 배경 스레드에서 GUC 가 주입되지 않는다", label)
              .isTrue();
          assertThat(AopUtils.isAopProxy(bean))
              .as("%s 가 트랜잭션 프록시가 아니다 — 애노테이션이 있어도 적용되지 않는다", label)
              .isTrue();
        });
  }

  /**
   * 이 테스트 클래스 자체에 트랜잭션이 없어야 한다 — 잡 스레드의 형태를 재현하기 위함이다.
   *
   * <p>클래스 레벨 @Transactional 을 붙이면 테스트 트랜잭션이 GUC 를 공급해 운영에 없는 조건이
   * 만들어지고, 이 파일이 잡아야 할 결함이 영원히 보이지 않게 된다.
   */
  @Test
  void testClassItselfRunsWithoutAmbientTransaction() {
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
  }

  // ── 픽스처 ────────────────────────────────────────────────────────────


  private Long insertDataset() {
    long suffix = TenantRlsTestSupport.nextTenantId();
    return dsl.insertInto(table(name("dataset")))
        .set(field(name("name"), String.class), "배경경로-" + suffix)
        .set(field(name("table_name"), String.class), "tbl_bg_" + suffix)
        .set(field(name("storage_type"), String.class), "DOCUMENT")
        .set(field(name("origin_type"), String.class), "SOURCE")
        .set(field(name("created_by"), Long.class), userId)
        .returning(field(name("id"), Long.class))
        .fetchOne()
        .get(field(name("id"), Long.class));
  }

  /** V99 로 RLS 가 걸린 report_template 행. tenant_id 는 GUC DEFAULT 로 채워진다. */
  private Long insertReportTemplate() {
    long suffix = TenantRlsTestSupport.nextTenantId();
    return dsl.insertInto(table(name("report_template")))
        .set(field(name("name"), String.class), "배경경로양식-" + suffix)
        .set(field(name("description"), String.class), "배경 잡 형태 검증용")
        .set(field(name("sections"), JSONB.class), JSONB.valueOf("[]"))
        .set(field(name("style"), String.class), "formal")
        .set(field(name("user_id"), Long.class), userId)
        .returning(field(name("id"), Long.class))
        .fetchOne()
        .get(field(name("id"), Long.class));
  }

  private Long insertDocumentFile(Long dsId) {
    long suffix = TenantRlsTestSupport.nextTenantId();
    return dsl.insertInto(table(name("document_file")))
        .set(field(name("dataset_id"), Long.class), dsId)
        .set(field(name("original_name"), String.class), "bg-" + suffix + ".pdf")
        .set(field(name("mime_type"), String.class), "application/pdf")
        .set(field(name("file_size"), Long.class), 10L)
        .set(field(name("storage_path"), String.class), "/tmp/bg-" + suffix)
        .set(field(name("status"), String.class), "PENDING")
        .set(field(name("uploaded_by"), Long.class), userId)
        .returning(field(name("id"), Long.class))
        .fetchOne()
        .get(field(name("id"), Long.class));
  }
}
