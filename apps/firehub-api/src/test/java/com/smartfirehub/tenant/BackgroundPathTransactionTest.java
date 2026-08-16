package com.smartfirehub.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.document.repository.DocumentFileRepository;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.proactive.repository.ReportTemplateRepository;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Repository;
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
  @Autowired private ApplicationContext applicationContext;
  // 아래 3개는 행동 단언(배경 스레드 형태에서 실제로 행이 보이는가)이 쓰는 리포지토리다.
  // 메타데이터 단언은 더 이상 손 등재 필드를 쓰지 않고 컨텍스트에서 전수 발견한다.
  @Autowired private DatasetRepository datasetRepository;
  @Autowired private DocumentFileRepository documentFileRepository;
  @Autowired private ReportTemplateRepository reportTemplateRepository;

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

  // ── 전 리포지토리 트랜잭션 경계 가드 (P2-g: 손 등재 → 전수 발견형) ────────
  //
  // 배선 자체를 직접 단언한다: 각 리포지토리가 트랜잭션 프록시이고 클래스 레벨 @Transactional 을
  // 갖는가. 이 조건이 깨지면 배경 스레드에서 GUC 가 주입되지 않아 예외도 로그도 없이 조용히 0행이 된다.
  //
  // P2-g 이전에는 이 자리에 손으로 등재한 Map.ofEntries 22개가 있었다 — 옵트인이라 새 리포지토리가
  // @Transactional 없이 들어와도 아무것도 빨개지지 않았다(@Repository 는 51개였다). 이제
  // ApplicationContext 에서 @Repository 빈을 전수 발견하고, 예외는 아래 세 허용목록으로만 낸다.
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

  // ⚠ 세 허용목록에 적는 이름은 **발견된 타깃 클래스의 단순 이름**이다. 인터페이스로 감싼
  // 리포지토리(notification 패키지 5개)는 구현체 이름으로 발견되므로 `...RepositoryImpl` 로 적어야
  // 한다 — 인터페이스 이름으로 적으면 양방향 단언이 모두 빨개진다.

  /** RLS 테이블을 아예 만지지 않아 GUC 가 필요 없는 리포지토리. (C)-1 */
  private static final Set<String> NO_RLS_TABLE_REPOSITORIES =
      Set.of(
          // login_attempt / refresh_token / settings 는 테넌트 개념이 없는 전역 테이블이다.
          "LoginAttemptRepository",
          "RefreshTokenRepository",
          "SettingsRepository",
          // membership / tenant 는 테넌시 자체를 정의하는 메타 테이블이라 RLS 대상이 아니다
          // (자기 자신을 정책으로 가리면 테넌트 해석이 불가능해진다).
          "MembershipRepository",
          "TenantRepository");

  /**
   * RLS 테이블을 만지지만 현재 모든 호출자가 요청 스코프 + 트랜잭션 안에 있는 리포지토리. (C)-2
   *
   * <p>⚠ <b>이 목록은 "검토 끝난 안전 목록" 이 아니라 "우연히 안전한 목록" 이다.</b> {@code role} /
   * {@code role_permission} / {@code user_role} 은 엄연히 RLS 대상이고, {@code UserRepository} /
   * {@code RoleRepository} 의 쓰기({@code addRole} / {@code removeRole} / {@code setRoles} /
   * {@code countActiveAdmins} / {@code hasAdminRole})가 오늘 동작하는 이유는 <b>현재 호출자가 전부
   * 요청 스코프라는 우연</b>일 뿐 구조적 보장이 아니다. 배경(잡·스케줄러·워커·리스너) 호출자가 하나만
   * 생겨도 조회는 조용히 0행, 삽입은 {@code tenant_id} NOT NULL 위반(23502)으로 깨진다.
   *
   * <p>따라서 여기 있는 리포지토리에 배경 호출자를 추가하려면, 목록에서 빼고 해당 리포지토리에
   * 클래스 레벨 {@code @Transactional} 을 붙이는 것이 먼저다.
   */
  private static final Set<String> REQUEST_SCOPED_ONLY_REPOSITORIES =
      Set.of(
          "AnalyticsDashboardRepository",
          "ChartRepository",
          "DashboardWidgetRepository",
          "SavedQueryRepository",
          "DatasetCategoryRepository",
          "DatasetFavoriteRepository",
          "DatasetTagRepository",
          "QueryHistoryRepository",
          "DatasetSearchRepository",
          "FileDatasetConfigRepository",
          "PermissionRepository",
          "RoleRepository",
          "UserRepository");

  /**
   * 설계상 RLS 를 우회하는 {@code SECURITY DEFINER} 해석기. (C)-3
   *
   * <p>웹훅·Slack 진입점은 테넌트를 <b>아직 모르는 상태</b>에서 식별자로 테넌트를 역해석해야 한다.
   * GUC 가 비어 있는 것이 정상이므로, 트랜잭션을 열어 GUC 를 주입하면 오히려 자기 자신을 가려
   * 0행이 된다 — 여기에 {@code @Transactional} 을 붙이는 것은 수정이 아니라 회귀다.
   */
  private static final Set<String> SECURITY_DEFINER_RESOLVERS =
      Set.of("SlackWorkspaceTenantResolver", "TriggerTenantResolver");

  /** 세 허용목록의 합집합 — 클래스 레벨 @Transactional 이 없어도 되는 리포지토리 전부. */
  private static Set<String> allowedWithoutTransactional() {
    return Stream.of(
            NO_RLS_TABLE_REPOSITORIES, REQUEST_SCOPED_ONLY_REPOSITORIES, SECURITY_DEFINER_RESOLVERS)
        .flatMap(Set::stream)
        .collect(Collectors.toUnmodifiableSet());
  }

  /**
   * 컨텍스트의 <b>모든</b> {@code @Repository} 빈이 클래스 레벨 @Transactional 을 유지하는지.
   *
   * <p>허용목록에 사유와 함께 등재된 것만 예외다. 새 리포지토리가 애노테이션 없이 들어오면
   * 자동으로 빨개진다 — 손 등재 시절의 구조적 구멍(옵트인)이 이 뒤집기로 막힌다.
   *
   * <p>단언은 양방향이다: 순방향({@code isSubsetOf})은 "목록 밖 신규 0건", 역방향
   * ({@code containsAll})은 "목록이 낡았으면 빨개짐" — 허용목록에 있는데 이미 @Transactional 이
   * 붙었으면 목록에서 지우라는 뜻이다. 역방향이 곧 발견 로직 자체의 자기검증도 겸한다
   * (발견이 0건이면 {@code isSubsetOf} 는 공허하게 참이 되지만 {@code containsAll} 은 실패한다).
   */
  @Test
  void everyRepositoryBeanKeepsClassLevelTransactional() {
    // 목(@MockitoBean)으로 대체된 빈은 프로덕션 배선의 증거가 아니므로 제외한다.
    // 이름 패턴 매칭은 취약하니 Mockito 의 공개 API 로 판별한다.
    List<Object> realRepositoryBeans =
        applicationContext.getBeansWithAnnotation(Repository.class).values().stream()
            .filter(bean -> !Mockito.mockingDetails(bean).isMock())
            .toList();

    // ★ 발견 개수 단언이 먼저다 — 발견이 0건이면 아래 isSubsetOf 가 공허하게 통과한다.
    assertThat(realRepositoryBeans)
        .as("@Repository 빈 발견 실패 — 배선이 잘못됐다")
        .hasSizeGreaterThanOrEqualTo(50);

    List<String> withoutClassLevelTransactional =
        realRepositoryBeans.stream()
            .filter(
                bean ->
                    !AnnotatedElementUtils.hasAnnotation(
                        AopUtils.getTargetClass(bean), Transactional.class))
            .map(bean -> AopUtils.getTargetClass(bean).getSimpleName())
            .sorted()
            .toList();

    // 애노테이션이 있어도 프록시가 아니면 적용되지 않는다 — 배선 자체를 함께 본다.
    List<String> annotatedButNotProxied =
        realRepositoryBeans.stream()
            .filter(
                bean ->
                    AnnotatedElementUtils.hasAnnotation(
                        AopUtils.getTargetClass(bean), Transactional.class))
            .filter(bean -> !AopUtils.isAopProxy(bean))
            .map(bean -> AopUtils.getTargetClass(bean).getSimpleName())
            .sorted()
            .toList();
    assertThat(annotatedButNotProxied)
        .as("@Transactional 이 있는데 트랜잭션 프록시가 아니다 — 애노테이션이 적용되지 않는다")
        .isEmpty();

    Set<String> allowed = allowedWithoutTransactional();
    // 순방향: @Transactional 이 없는 리포지토리는 허용목록 안에만 있어야 한다.
    assertThat(withoutClassLevelTransactional)
        .as(
            "클래스 레벨 @Transactional 이 없는 리포지토리가 허용목록 밖에 있다 — 배경 스레드에서"
                + " GUC 가 주입되지 않아 조회는 조용히 0행, 삽입은 tenant_id NOT NULL 위반이 된다."
                + " 배경 호출자가 있으면 @Transactional 을 붙이고, 없으면 사유와 함께 허용목록에 등재하라")
        .isSubsetOf(allowed);
    // 역방향(staleness): 허용목록에 있는데 이미 @Transactional 이 붙었으면 목록에서 지워라.
    assertThat(withoutClassLevelTransactional)
        .as("허용목록이 낡았다 — 이미 @Transactional 이 붙었거나 사라진 리포지토리가 목록에 남아 있다")
        .containsAll(allowed);
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
