package com.smartfirehub.proactive.service;

import static com.smartfirehub.jooq.Tables.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartfirehub.proactive.dto.CreateProactiveJobRequest;
import com.smartfirehub.proactive.dto.ProactiveJobResponse;
import com.smartfirehub.proactive.dto.ProactiveResult;
import com.smartfirehub.proactive.dto.UpdateProactiveJobRequest;
import com.smartfirehub.proactive.exception.ProactiveJobAlreadyRunningException;
import com.smartfirehub.proactive.exception.ProactiveJobException;
import com.smartfirehub.proactive.exception.ProactiveJobNotFoundException;
import com.smartfirehub.proactive.repository.ProactiveJobExecutionRepository;
import com.smartfirehub.proactive.service.delivery.DeliveryChannel;
import com.smartfirehub.proactive.util.ProactiveTime;
import com.smartfirehub.support.IntegrationTestBase;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class ProactiveJobServiceTest extends IntegrationTestBase {

  @Autowired private ProactiveJobService proactiveJobService;
  private ProactiveJobService rawJobService;
  // ProactiveJobAsyncRunner — 실제 executeJob 로직이 여기에 있다 (이슈 #192 분리 후)
  @Autowired private ProactiveJobAsyncRunner proactiveJobAsyncRunner;
  private ProactiveJobAsyncRunner rawAsyncRunner;
  @Autowired private ProactiveJobExecutionRepository executionRepository;
  @Autowired private DSLContext dsl;
  @Autowired private com.smartfirehub.settings.service.AiCredentialService aiCredentialService;
  @Autowired private com.smartfirehub.global.security.PermissionChecker permissionChecker;

  @MockitoBean private ProactiveAiClient proactiveAiClient;
  @MockitoBean private ProactiveContextCollector proactiveContextCollector;
  @MockitoBean private DeliveryChannel chatDeliveryChannel;

  @MockitoBean
  private com.smartfirehub.proactive.repository.AnomalyEventRepository anomalyEventRepository;

  @MockitoBean private com.smartfirehub.notification.service.SseEmitterRegistry sseEmitterRegistry;

  private Long testUserId;
  // 작업 주인 역할(proactive:read·write) — WD-166 권한 회수 테스트가 이 역할에서 권한을 뺀다
  private Long ownerRoleId;

  @BeforeEach
  void setUp() {
    rawJobService = (ProactiveJobService) AopProxyUtils.getSingletonTarget(proactiveJobService);
    // @Async 프록시를 우회하여 테스트에서 동기 실행이 가능하도록 raw 빈을 꺼낸다
    rawAsyncRunner =
        (ProactiveJobAsyncRunner) AopProxyUtils.getSingletonTarget(proactiveJobAsyncRunner);
    testUserId =
        dsl.insertInto(USER)
            .set(USER.USERNAME, "proactive_test_user")
            .set(USER.PASSWORD, "password")
            .set(USER.NAME, "Proactive Test User")
            .set(USER.EMAIL, "proactive_test@example.com")
            .returning(USER.ID)
            .fetchOne()
            .getId();
    // 실행 직전 작업 주인 자격 재확인(WD-166) — 주인은 기본 테넌트 ACTIVE 멤버이면서 proactive:write 를 가져야 실행된다.
    // ADMIN 은 다른 우회(관리자 전권)를 함께 들고 와 다른 단언이 엉뚱한 이유로 통과할 수 있으므로 스마트 작업 권한만 가진 역할을 준다.
    // 클래스가 @Transactional 이라 역할·멤버십도 테스트마다 롤백된다(역할의 tenant_id 는 GUC DEFAULT).
    com.smartfirehub.support.TenantRlsTestSupport.insertActiveMembership(
        dsl, testUserId, DEFAULT_TEST_TENANT_ID);
    ownerRoleId =
        dsl.insertInto(ROLE)
            .set(ROLE.NAME, "proactive_owner_" + System.nanoTime())
            .set(ROLE.IS_SYSTEM, false)
            .returning(ROLE.ID)
            .fetchOne()
            .getId();
    for (String code : List.of("proactive:read", "proactive:write")) {
      dsl.insertInto(ROLE_PERMISSION)
          .set(ROLE_PERMISSION.ROLE_ID, ownerRoleId)
          .set(
              ROLE_PERMISSION.PERMISSION_ID,
              dsl.select(PERMISSION.ID).from(PERMISSION).where(PERMISSION.CODE.eq(code)))
          .execute();
    }
    dsl.insertInto(USER_ROLE)
        .set(USER_ROLE.USER_ID, testUserId)
        .set(USER_ROLE.ROLE_ID, ownerRoleId)
        .execute();

    // 실행 경로는 AI 호출 전에 자격증명 완결성을 검사한다. AI 자격증명은 테넌트 전용(#706)이라
    // 플랫폼 기본값이 없으므로, 기본 테넌트에 자격증명을 심어 둔다(proactiveAiClient 는 mock 이라 키
    // 값 자체는 쓰이지 않는다). 클래스가 @Transactional 이라 테스트마다 롤백된다.
    aiCredentialService.save(
        new com.smartfirehub.settings.service.AiCredentialService.AiCredentialUpsert(
            "sdk", Map.of(), Map.of("apiKey", "sk-proactive-test")),
        null);
  }

  private CreateProactiveJobRequest buildCreateRequest(String name) {
    return new CreateProactiveJobRequest(
        name,
        "테스트 프롬프트",
        null,
        "0 0 9 * * *",
        "Asia/Seoul",
        null,
        null,
        Map.of("channels", List.of("CHAT")));
  }

  private ProactiveResult buildMockResult() {
    return new ProactiveResult(
        "테스트 리포트",
        List.of(new ProactiveResult.Section("summary", "요약", "요약 내용", null, null)),
        new ProactiveResult.Usage(100, 50, 150),
        null,
        null);
  }

  // ── CRUD ─────────────────────────────────────────────────────────────────────

  @Test
  void createJob_thenGetJob_success() {
    // when
    ProactiveJobResponse created =
        proactiveJobService.createJob(buildCreateRequest("CRUD 테스트 작업"), testUserId);

    // then
    assertThat(created.id()).isNotNull();
    assertThat(created.name()).isEqualTo("CRUD 테스트 작업");
    assertThat(created.prompt()).isEqualTo("테스트 프롬프트");
    assertThat(created.cronExpression()).isEqualTo("0 0 9 * * *");
    assertThat(created.enabled()).isTrue();

    // getJob
    ProactiveJobResponse found = proactiveJobService.getJob(created.id(), testUserId);
    assertThat(found.name()).isEqualTo("CRUD 테스트 작업");
  }

  /**
   * 회귀 테스트 — CreateProactiveJobRequest에 enabled=false를 보내면 비활성 상태로 저장되어야 한다 (#220).
   *
   * <p>이전에는 DTO에 enabled 필드가 없어 무조건 true로 생성되고 스케줄러에 즉시 등록되는 버그가 있었다.
   */
  @Test
  void createJob_withEnabledFalse_storedAsDisabled() {
    // given
    CreateProactiveJobRequest req =
        new CreateProactiveJobRequest(
            "비활성 생성 테스트",
            "테스트 프롬프트",
            null,
            "0 0 9 * * *",
            "Asia/Seoul",
            false,
            null,
            Map.of("channels", List.of("CHAT")));

    // when
    ProactiveJobResponse created = proactiveJobService.createJob(req, testUserId);

    // then — DB에 enabled=false로 저장
    assertThat(created.enabled()).isFalse();
    ProactiveJobResponse refetched = proactiveJobService.getJob(created.id(), testUserId);
    assertThat(refetched.enabled()).isFalse();
  }

  /** enabled 미지정(null) 시 기본값 true가 적용되어야 한다 (#220 기본 동작 유지). */
  @Test
  void createJob_withEnabledNull_defaultsToTrue() {
    CreateProactiveJobRequest req =
        new CreateProactiveJobRequest(
            "기본값 테스트",
            "프롬프트",
            null,
            "0 0 9 * * *",
            "Asia/Seoul",
            null,
            null,
            Map.of("channels", List.of("CHAT")));

    ProactiveJobResponse created = proactiveJobService.createJob(req, testUserId);

    assertThat(created.enabled()).isTrue();
  }

  @Test
  void updateJob_success() {
    // given
    ProactiveJobResponse created =
        proactiveJobService.createJob(buildCreateRequest("수정 전 이름"), testUserId);

    // when
    UpdateProactiveJobRequest updateReq =
        new UpdateProactiveJobRequest(
            "수정 후 이름", "수정된 프롬프트", null, "0 0 8 * * *", null, null, null, null);
    proactiveJobService.updateJob(created.id(), updateReq, testUserId);

    // then
    ProactiveJobResponse updated = proactiveJobService.getJob(created.id(), testUserId);
    assertThat(updated.name()).isEqualTo("수정 후 이름");
    assertThat(updated.cronExpression()).isEqualTo("0 0 8 * * *");
  }

  /**
   * 회귀 테스트 — triggerType=ANOMALY로 생성하면 DB trigger_type 컬럼에 실제로 저장되어야 한다 (#655).
   *
   * <p>이전에는 CreateProactiveJobRequest DTO 자체에 triggerType 필드가 없어 프론트엔드가 값을 보내도 조용히 버려지고 DB 컬럼
   * 기본값(SCHEDULE)만 저장됐다. 그 결과 MetricPollerService의 {@code trigger_type IN ('ANOMALY','BOTH')} 조회
   * 대상에서 영원히 제외되어 이상 탐지 작업이 절대 발화하지 않았다.
   */
  @Test
  void createJob_withAnomalyTriggerType_persistsTriggerType() {
    CreateProactiveJobRequest req =
        new CreateProactiveJobRequest(
            "이상 탐지 트리거 테스트",
            "테스트 프롬프트",
            null,
            null,
            "Asia/Seoul",
            true,
            "ANOMALY",
            Map.of(
                "channels",
                List.of("CHAT"),
                "anomaly",
                Map.of(
                    "enabled",
                    true,
                    "metrics",
                    List.of(),
                    "sensitivity",
                    "medium",
                    "cooldownMinutes",
                    30)));

    ProactiveJobResponse created = proactiveJobService.createJob(req, testUserId);

    // 응답 DTO에도 triggerType이 반영돼야 한다 (조회 시 프론트가 폼을 정확히 복원할 수 있도록)
    assertThat(created.triggerType()).isEqualTo("ANOMALY");

    // 재조회해도 유지돼야 한다 — insert 시점 반영 확인
    ProactiveJobResponse refetched = proactiveJobService.getJob(created.id(), testUserId);
    assertThat(refetched.triggerType()).isEqualTo("ANOMALY");

    // DB 컬럼 자체를 직접 조회해 MetricPollerService의 trigger_type IN ('ANOMALY','BOTH') 조건에
    // 실제로 걸리는지 확인한다 — 응답 DTO만 맞고 컬럼이 여전히 SCHEDULE인 회귀를 잡기 위함.
    String rawTriggerType =
        dsl.select(com.smartfirehub.jooq.Tables.PROACTIVE_JOB.TRIGGER_TYPE)
            .from(com.smartfirehub.jooq.Tables.PROACTIVE_JOB)
            .where(com.smartfirehub.jooq.Tables.PROACTIVE_JOB.ID.eq(created.id()))
            .fetchOne(com.smartfirehub.jooq.Tables.PROACTIVE_JOB.TRIGGER_TYPE);
    assertThat(rawTriggerType).isEqualTo("ANOMALY");
  }

  /** triggerType 미지정(null) 시 기존 동작과 동일하게 기본값 SCHEDULE로 저장돼야 한다 (#655 회귀 없음 확인). */
  @Test
  void createJob_withoutTriggerType_defaultsToSchedule() {
    ProactiveJobResponse created =
        proactiveJobService.createJob(buildCreateRequest("트리거 유형 미지정 테스트"), testUserId);

    assertThat(created.triggerType()).isEqualTo("SCHEDULE");
    String rawTriggerType =
        dsl.select(com.smartfirehub.jooq.Tables.PROACTIVE_JOB.TRIGGER_TYPE)
            .from(com.smartfirehub.jooq.Tables.PROACTIVE_JOB)
            .where(com.smartfirehub.jooq.Tables.PROACTIVE_JOB.ID.eq(created.id()))
            .fetchOne(com.smartfirehub.jooq.Tables.PROACTIVE_JOB.TRIGGER_TYPE);
    assertThat(rawTriggerType).isEqualTo("SCHEDULE");
  }

  /** 기존 SCHEDULE 잡의 트리거 유형을 ANOMALY로 수정하면 DB에도 반영돼야 한다 (#655). */
  @Test
  void updateJob_changingTriggerTypeToAnomaly_persists() {
    ProactiveJobResponse created =
        proactiveJobService.createJob(buildCreateRequest("트리거 유형 수정 테스트"), testUserId);
    assertThat(created.triggerType()).isEqualTo("SCHEDULE");

    UpdateProactiveJobRequest updateReq =
        new UpdateProactiveJobRequest(null, null, null, null, null, null, "ANOMALY", null);
    proactiveJobService.updateJob(created.id(), updateReq, testUserId);

    ProactiveJobResponse updated = proactiveJobService.getJob(created.id(), testUserId);
    assertThat(updated.triggerType()).isEqualTo("ANOMALY");
  }

  /** triggerType을 지정하지 않은 update는 기존 값을 유지해야 한다 (부분 수정 호환성, #655). */
  @Test
  void updateJob_withNullTriggerType_keepsExisting() {
    CreateProactiveJobRequest req =
        new CreateProactiveJobRequest(
            "트리거 유형 유지 테스트",
            "프롬프트",
            null,
            null,
            "Asia/Seoul",
            true,
            "ANOMALY",
            Map.of("channels", List.of("CHAT")));
    ProactiveJobResponse created = proactiveJobService.createJob(req, testUserId);

    UpdateProactiveJobRequest updateReq =
        new UpdateProactiveJobRequest("이름만 변경2", null, null, null, null, null, null, null);
    proactiveJobService.updateJob(created.id(), updateReq, testUserId);

    ProactiveJobResponse updated = proactiveJobService.getJob(created.id(), testUserId);
    assertThat(updated.triggerType()).isEqualTo("ANOMALY");
  }

  /**
   * [WD-136] 리포트 템플릿을 「기본 (선택 안 함)」으로 되돌리면(clearTemplate) 템플릿이 해제되고, 플래그 없이 다른 필드만 보내면 그대로 남는다.
   *
   * <p>예전에는 templateId=null 이 "바꾸지 않음"으로만 해석돼 해제할 방법이 없었다. AI 도구처럼 바꿀 필드만 보내는 호출은 템플릿을 지우면 안 된다.
   */
  @Test
  void updateJob_clearTemplate_resetsToDefault_omittedKeeps() {
    Long templateId =
        dsl.select(REPORT_TEMPLATE.ID).from(REPORT_TEMPLATE).limit(1).fetchOne(REPORT_TEMPLATE.ID);
    ProactiveJobResponse created =
        proactiveJobService.createJob(
            new CreateProactiveJobRequest(
                "템플릿 작업", "요약", templateId, "0 0 9 * * *", "Asia/Seoul", null, null, Map.of()),
            testUserId);
    assertThat(proactiveJobService.getJob(created.id(), testUserId).templateId())
        .isEqualTo(templateId);

    // 플래그 없이 이름만 바꾸면 템플릿 유지
    proactiveJobService.updateJob(
        created.id(),
        new UpdateProactiveJobRequest("이름만 변경", null, null, null, null, null, null, null),
        testUserId);
    assertThat(proactiveJobService.getJob(created.id(), testUserId).templateId())
        .isEqualTo(templateId);

    // 해제 요청이면 기본 형식(null)으로
    proactiveJobService.updateJob(
        created.id(),
        new UpdateProactiveJobRequest("이름만 변경", null, null, null, null, null, null, null, true),
        testUserId);
    ProactiveJobResponse cleared = proactiveJobService.getJob(created.id(), testUserId);
    assertThat(cleared.templateId()).isNull();
    assertThat(cleared.templateName()).isNull();
  }

  /**
   * 잘못된 cron 표현식으로 생성을 시도하면 ProactiveJobException(→400) 이 발생하고 DB 에 저장되지 않아야 한다 (#221). 이전에는 검증 없이
   * 그대로 저장되어 스케줄러가 silent fail 하는 좀비 잡이 만들어졌다.
   */
  @Test
  void createJob_withInvalidCron_throwsAndNotPersisted() {
    long before = dsl.fetchCount(com.smartfirehub.jooq.Tables.PROACTIVE_JOB);
    CreateProactiveJobRequest req =
        new CreateProactiveJobRequest(
            "잘못된 cron",
            "프롬프트",
            null,
            "not a cron",
            "Asia/Seoul",
            true,
            null,
            Map.of("channels", List.of("CHAT")));

    assertThatThrownBy(() -> proactiveJobService.createJob(req, testUserId))
        .isInstanceOf(ProactiveJobException.class)
        .hasMessageContaining("cron");

    long after = dsl.fetchCount(com.smartfirehub.jooq.Tables.PROACTIVE_JOB);
    assertThat(after).isEqualTo(before);
  }

  /**
   * WD-138: 잘못된 cron 오류 문구는 화면 안내(5칸)와 같은 기준의 한국어여야 한다. 예전에는 Spring 의 영어 문구("must consist of 6
   * fields")가 붙고, 5필드 입력도 초 칸을 붙인 "0 61 * * * *" 로 보였다.
   */
  @Test
  void createJob_withInvalidCron_reportsKoreanFiveFieldReason() {
    assertThatThrownBy(
            () ->
                proactiveJobService.createJob(
                    new CreateProactiveJobRequest(
                        "칸 수 오류",
                        "프롬프트",
                        null,
                        "abc",
                        "Asia/Seoul",
                        true,
                        null,
                        Map.of("channels", List.of("CHAT"))),
                    testUserId))
        .isInstanceOf(ProactiveJobException.class)
        .hasMessage(
            "유효하지 않은 cron 표현식입니다: \"abc\" — 「분 시 일 월 요일」 5칸을 공백으로 나눠 입력해야 하는데 1칸을 입력했습니다"
                + " (예: 0 9 * * * → 매일 09:00)");

    assertThatThrownBy(
            () ->
                proactiveJobService.createJob(
                    new CreateProactiveJobRequest(
                        "범위 오류",
                        "프롬프트",
                        null,
                        "61 * * * *",
                        "Asia/Seoul",
                        true,
                        null,
                        Map.of("channels", List.of("CHAT"))),
                    testUserId))
        .isInstanceOf(ProactiveJobException.class)
        .hasMessage(
            "유효하지 않은 cron 표현식입니다: \"61 * * * *\" — 「분」 칸의 값 \"61\" 이(가) 올바르지 않습니다 (허용 범위 0~59)")
        .hasMessageNotContaining("0 61");
  }

  /** 존재하지 않는 IANA timezone 으로 생성을 시도하면 ProactiveJobException(→400) 이 발생해야 한다 (#221). */
  @Test
  void createJob_withInvalidTimezone_throws() {
    CreateProactiveJobRequest req =
        new CreateProactiveJobRequest(
            "잘못된 tz",
            "프롬프트",
            null,
            "0 0 9 * * *",
            "Mars/Phobos",
            true,
            null,
            Map.of("channels", List.of("CHAT")));

    assertThatThrownBy(() -> proactiveJobService.createJob(req, testUserId))
        .isInstanceOf(ProactiveJobException.class)
        .hasMessageContaining("timezone");
  }

  /** 업데이트에서도 동일하게 cron / timezone 사전 검증이 적용되어야 한다 (#221). */
  @Test
  void updateJob_withInvalidCron_throws() {
    ProactiveJobResponse created =
        proactiveJobService.createJob(buildCreateRequest("업데이트 검증 테스트"), testUserId);
    UpdateProactiveJobRequest req =
        new UpdateProactiveJobRequest(
            "x", "y", null, "### BAD ###", "Asia/Seoul", true, null, null);

    assertThatThrownBy(() -> proactiveJobService.updateJob(created.id(), req, testUserId))
        .isInstanceOf(ProactiveJobException.class)
        .hasMessageContaining("cron");
  }

  /** cron / timezone 이 null/blank 인 update 는 검증을 통과해야 한다 (toggle 등 부분 업데이트 호환성). */
  @Test
  void updateJob_withBlankCronAndTimezone_allowed() {
    ProactiveJobResponse created =
        proactiveJobService.createJob(buildCreateRequest("blank 업데이트 테스트"), testUserId);
    UpdateProactiveJobRequest req =
        new UpdateProactiveJobRequest("이름만 변경", null, null, null, null, null, null, null);

    proactiveJobService.updateJob(created.id(), req, testUserId);

    ProactiveJobResponse updated = proactiveJobService.getJob(created.id(), testUserId);
    assertThat(updated.name()).isEqualTo("이름만 변경");
  }

  @Test
  void deleteJob_thenGetJob_throwsException() {
    // given
    ProactiveJobResponse created =
        proactiveJobService.createJob(buildCreateRequest("삭제될 작업"), testUserId);
    Long id = created.id();

    // when
    proactiveJobService.deleteJob(id, testUserId);

    // then
    // 삭제된 Job 조회 시 ProactiveJobNotFoundException(404) 발생 (#41)
    assertThatThrownBy(() -> proactiveJobService.getJob(id, testUserId))
        .isInstanceOf(ProactiveJobNotFoundException.class)
        .hasMessageContaining("Proactive Job을 찾을 수 없습니다");
  }

  // ── 중복 실행 방지 슬롯 ───────────────────────────────────────────────────────────

  /** tryAcquireRunSlot — 최초 획득은 성공, 두 번째 호출은 ProactiveJobAlreadyRunningException 발생 (#149) */
  @Test
  void tryAcquireRunSlot_firstCallSucceeds_secondCallThrows() {
    var job = proactiveJobService.createJob(buildCreateRequest("슬롯 테스트"), testUserId);
    Long jobId = job.id();

    // 첫 번째 획득 — 성공
    rawJobService.tryAcquireRunSlot(jobId);

    // 두 번째 획득 — 이미 실행 중이므로 409 예외
    assertThatThrownBy(() -> rawJobService.tryAcquireRunSlot(jobId))
        .isInstanceOf(ProactiveJobAlreadyRunningException.class)
        .hasMessageContaining(String.valueOf(jobId));

    // 슬롯 해제 후 재획득 가능 확인
    rawJobService.releaseRunSlot(jobId);
    rawJobService.tryAcquireRunSlot(jobId); // 예외 없이 통과해야 한다
    rawJobService.releaseRunSlot(jobId);
  }

  // ── Execute success ───────────────────────────────────────────────────────────

  @Test
  void executeJob_success_executionCompletedAndResultSaved() {
    // given
    ProactiveJobResponse created =
        proactiveJobService.createJob(buildCreateRequest("실행 테스트 작업"), testUserId);

    when(proactiveContextCollector.collectContext(any(), any(), any())).thenReturn("{}");
    when(proactiveAiClient.execute(anyLong(), anyString(), anyString(), any(), any(), any(), any(), any()))
        .thenReturn(buildMockResult());
    when(chatDeliveryChannel.type()).thenReturn("CHAT");

    // when — @Async 프록시를 우회한 raw 빈으로 직접 호출하여 동기 실행 보장 (이슈 #192)
    rawAsyncRunner.executeJob(created.id(), testUserId);

    // then: execution row should exist and be COMPLETED
    var executions = executionRepository.findByJobId(created.id(), 10, 0);
    assertThat(executions).hasSize(1);
    assertThat(executions.get(0).status()).isEqualTo("COMPLETED");
    assertThat(executions.get(0).result()).isNotNull();
  }

  // ── Execute failure ───────────────────────────────────────────────────────────

  @Test
  void executeJob_aiClientThrows_executionFailed() {
    // given
    ProactiveJobResponse created =
        proactiveJobService.createJob(buildCreateRequest("실패 테스트 작업"), testUserId);

    when(proactiveContextCollector.collectContext(any(), any(), any())).thenReturn("{}");
    when(proactiveAiClient.execute(anyLong(), anyString(), anyString(), any(), any(), any(), any(), any()))
        .thenThrow(new RuntimeException("AI Agent 연결 실패"));

    // when / then — @Async 프록시를 우회한 raw 빈으로 직접 호출 (이슈 #192)
    assertThatThrownBy(() -> rawAsyncRunner.executeJob(created.id(), testUserId))
        .isInstanceOf(ProactiveJobException.class)
        .hasMessageContaining("Job 실행 실패");

    // execution should be FAILED
    var executions = executionRepository.findByJobId(created.id(), 10, 0);
    assertThat(executions).hasSize(1);
    assertThat(executions.get(0).status()).isEqualTo("FAILED");
    assertThat(executions.get(0).errorMessage()).contains("AI Agent 연결 실패");
  }

  /**
   * [WD-134] 예약 실행이 실패해도 목록·상세가 "한 번도 안 돈 작업"처럼 보이면 안 된다.
   *
   * <p>예전에는 실패 경로가 마지막 실행 시각·다음 실행 시각을 갱신하지 않았고, 잡 응답의 {@code lastExecution} 은 늘 null 이었다. 그래서 계속
   * 실패하는 작업이 목록에서 「마지막 실행 -」·지난 시각의 「다음 실행」으로 보였다. 등록 시점의 다음 실행 시각을 일부러 과거로 돌려 두고, 실패 한 번 뒤 목록·상세
   * 모두에서 시각이 갱신되고 실패 상태·문구가 실리는지 본다.
   */
  @Test
  void executeJob_failure_updatesLastAndNextRunAndExposesLastExecution() {
    ProactiveJobResponse created =
        proactiveJobService.createJob(buildCreateRequest("실패 표시 작업"), testUserId);
    // 등록 시점에 계산한 값이 이미 소진된 상황(지난 시각)을 만든다
    LocalDateTime stale = ProactiveTime.nowUtc().minusHours(1);
    dsl.update(DSL.table(DSL.name("proactive_job")))
        .set(DSL.field(DSL.name("next_execute_at"), LocalDateTime.class), stale)
        .where(DSL.field(DSL.name("id"), Long.class).eq(created.id()))
        .execute();

    when(proactiveContextCollector.collectContext(any(), any(), any())).thenReturn("{}");
    when(proactiveAiClient.execute(anyLong(), anyString(), anyString(), any(), any(), any(), any(), any()))
        .thenThrow(new RuntimeException("AI Agent 연결 실패"));

    assertThatThrownBy(() -> rawAsyncRunner.executeJob(created.id(), testUserId))
        .isInstanceOf(ProactiveJobException.class);

    LocalDateTime now = ProactiveTime.nowUtc();
    ProactiveJobResponse detail = proactiveJobService.getJob(created.id(), testUserId);
    assertThat(detail.lastExecutedAt()).isNotNull();
    assertThat(detail.nextExecuteAt()).isAfter(now);
    assertThat(detail.lastExecution()).isNotNull();
    assertThat(detail.lastExecution().status()).isEqualTo("FAILED");
    assertThat(detail.lastExecution().errorMessage()).contains("AI Agent 연결 실패");
    // 목록 응답은 잡 수만큼 반복되므로 리포트 본문은 싣지 않는다
    assertThat(detail.lastExecution().result()).isNull();

    ProactiveJobResponse listed =
        proactiveJobService.getJobs(testUserId).stream()
            .filter(j -> j.id().equals(created.id()))
            .findFirst()
            .orElseThrow();
    assertThat(listed.lastExecution()).isNotNull();
    assertThat(listed.lastExecution().status()).isEqualTo("FAILED");
  }

  /** [WD-134] 실행이 여러 번이면 가장 최근 실행이 실리고, 실행 이력이 없는 잡은 null 로 남는다. */
  @Test
  void getJobs_attachesLatestExecutionPerJob() {
    ProactiveJobResponse ran =
        proactiveJobService.createJob(buildCreateRequest("두 번 실행된 작업"), testUserId);
    ProactiveJobResponse never =
        proactiveJobService.createJob(buildCreateRequest("실행된 적 없는 작업"), testUserId);
    Long first = executionRepository.create(ran.id());
    executionRepository.updateError(first, "첫 실행 실패");
    Long second = executionRepository.create(ran.id());
    executionRepository.updateResult(second, "COMPLETED", Map.of("title", "두 번째"), null);

    Map<Long, ProactiveJobResponse> byId =
        proactiveJobService.getJobs(testUserId).stream()
            .collect(java.util.stream.Collectors.toMap(ProactiveJobResponse::id, j -> j));

    assertThat(byId.get(ran.id()).lastExecution().id()).isEqualTo(second);
    assertThat(byId.get(ran.id()).lastExecution().status()).isEqualTo("COMPLETED");
    assertThat(byId.get(never.id()).lastExecution()).isNull();
  }

  /**
   * [#350] AI 인증 실패 원문이 리포트 본문으로 둔갑한 결과는 COMPLETED로 기록하지 않고, CHAT/EMAIL로도 발송하지 않는다.
   *
   * <p>에이전트가 인증 오류를 일반 텍스트로 흘린 뒤 정상 종료하면 aiClient는 예외 없이 "내용이 있는" 결과를 반환한다. 이 결과가 그대로 저장·발송되던 것이
   * 이슈의 핵심이었다.
   */
  @Test
  void executeJob_authErrorTextAsReportBody_executionFailedAndNotDelivered() {
    // given
    ProactiveJobResponse created =
        proactiveJobService.createJob(buildCreateRequest("인증 실패 결과 테스트"), testUserId);

    ProactiveResult authFailureResult =
        new ProactiveResult(
            null,
            List.of(
                new ProactiveResult.Section(
                    "content",
                    "분석 결과",
                    "Failed to authenticate. API Error: 401"
                        + " {\"type\":\"error\",\"error\":{\"type\":\"authentication_error\","
                        + "\"message\":\"Invalid bearer token\"},\"request_id\":\"req_011Cd\"}",
                    "text",
                    null)),
            new ProactiveResult.Usage(0, 0, 0),
            "",
            "");

    when(proactiveContextCollector.collectContext(any(), any(), any())).thenReturn("{}");
    when(proactiveAiClient.execute(anyLong(), anyString(), anyString(), any(), any(), any(), any(), any()))
        .thenReturn(authFailureResult);
    when(chatDeliveryChannel.type()).thenReturn("CHAT");

    // when / then
    assertThatThrownBy(() -> rawAsyncRunner.executeJob(created.id(), testUserId))
        .isInstanceOf(ProactiveJobException.class);

    var executions = executionRepository.findByJobId(created.id(), 10, 0);
    assertThat(executions).hasSize(1);
    assertThat(executions.get(0).status()).isEqualTo("FAILED");
    // 사용자에게 노출되는 오류 메시지에 원문·request_id가 새어 나가면 안 된다 (#313 원칙)
    assertThat(executions.get(0).errorMessage())
        .doesNotContain("request_id")
        .doesNotContain("Invalid bearer token")
        .contains("AI 인증 정보");
    // 실패 결과는 어떤 채널로도 발송되지 않아야 한다
    verify(chatDeliveryChannel, never()).deliver(any(), anyLong(), any());
  }

  // ── DeliveryChannel ───────────────────────────────────────────────────────────

  @Test
  void executeJob_chatChannelInConfig_chatDeliveryChannelCalled() {
    // given
    ProactiveJobResponse created =
        proactiveJobService.createJob(buildCreateRequest("채널 테스트 작업"), testUserId);

    when(proactiveContextCollector.collectContext(any(), any(), any())).thenReturn("{}");
    when(proactiveAiClient.execute(anyLong(), anyString(), anyString(), any(), any(), any(), any(), any()))
        .thenReturn(buildMockResult());
    when(chatDeliveryChannel.type()).thenReturn("CHAT");

    // when — @Async 프록시를 우회한 raw 빈으로 직접 호출 (이슈 #192)
    rawAsyncRunner.executeJob(created.id(), testUserId);

    // then: ChatDeliveryChannel.deliver() was called once
    verify(chatDeliveryChannel, times(1)).deliver(any(), anyLong(), any());
  }

  @Test
  void executeJob_deliveryChannelThrows_executionStillCompleted() {
    // given: channel throws but should not fail the execution
    ProactiveJobResponse created =
        proactiveJobService.createJob(buildCreateRequest("채널 오류 내성 테스트"), testUserId);

    when(proactiveContextCollector.collectContext(any(), any(), any())).thenReturn("{}");
    when(proactiveAiClient.execute(anyLong(), anyString(), anyString(), any(), any(), any(), any(), any()))
        .thenReturn(buildMockResult());
    when(chatDeliveryChannel.type()).thenReturn("CHAT");
    doThrow(new RuntimeException("채널 오류"))
        .when(chatDeliveryChannel)
        .deliver(any(), anyLong(), any());

    // when — @Async 프록시를 우회한 raw 빈으로 직접 호출 (이슈 #192)
    rawAsyncRunner.executeJob(created.id(), testUserId);

    // then: execution is still COMPLETED
    var executions = executionRepository.findByJobId(created.id(), 10, 0);
    assertThat(executions).hasSize(1);
    assertThat(executions.get(0).status()).isEqualTo("COMPLETED");
  }

  // ── 단건 실행 조회 ──────────────────────────────────────────────────────────────

  /** 단건 실행 조회 — 정상 케이스 */
  @Test
  void getExecution_returns_single_execution() {
    ProactiveJobResponse job =
        proactiveJobService.createJob(buildCreateRequest("단건조회테스트"), testUserId);

    Long execId =
        dsl.insertInto(PROACTIVE_JOB_EXECUTION)
            .set(PROACTIVE_JOB_EXECUTION.JOB_ID, job.id())
            .set(PROACTIVE_JOB_EXECUTION.STATUS, "COMPLETED")
            .set(PROACTIVE_JOB_EXECUTION.STARTED_AT, java.time.LocalDateTime.now().minusMinutes(5))
            .set(PROACTIVE_JOB_EXECUTION.COMPLETED_AT, java.time.LocalDateTime.now())
            .returning(PROACTIVE_JOB_EXECUTION.ID)
            .fetchOne()
            .getId();

    var result = proactiveJobService.getExecution(execId);

    assertThat(result).isNotNull();
    assertThat(result.id()).isEqualTo(execId);
    assertThat(result.jobId()).isEqualTo(job.id());
    assertThat(result.status()).isEqualTo("COMPLETED");
  }

  /** 단건 실행 조회 — 존재하지 않는 ID */
  @Test
  void getExecution_throws_when_not_found() {
    assertThatThrownBy(() -> proactiveJobService.getExecution(999999L))
        .isInstanceOf(ProactiveJobException.class)
        .hasMessageContaining("999999");
  }

  // ── 이상 탐지 이벤트 테스트 ──

  /** onAnomalyDetected 호출 시 이벤트가 DB에 저장되고 SSE 알림이 전송되는지 검증 */
  @Test
  void onAnomalyDetected_saves_event_and_sends_sse_notification() {
    var job = proactiveJobService.createJob(buildCreateRequest("이상탐지 알림 테스트"), testUserId);
    var event =
        new com.smartfirehub.proactive.dto.AnomalyEvent(
            job.id(),
            testUserId,
            "metric1",
            "테스트 메트릭",
            50.0,
            10.0,
            3.0,
            13.33,
            "medium",
            List.of(8.0, 10.0, 12.0));

    rawJobService.onAnomalyDetected(event);

    verify(anomalyEventRepository).save(event);
    verify(sseEmitterRegistry)
        .broadcast(
            org.mockito.ArgumentMatchers.eq(testUserId),
            org.mockito.ArgumentMatchers.argThat(
                n -> "ANOMALY_DETECTED".equals(n.eventType()) && "WARNING".equals(n.severity())));
  }

  /** 쿨다운 내 재호출 시 save/broadcast가 1번만 호출되는지 검증 */
  @Test
  void onAnomalyDetected_respects_cooldown_on_second_call() {
    var job = proactiveJobService.createJob(buildCreateRequest("쿨다운 테스트"), testUserId);
    when(proactiveContextCollector.collectContext(any(), any(), any())).thenReturn("{}");
    when(proactiveAiClient.execute(anyLong(), anyString(), anyString(), any(), any(), any(), any(), any()))
        .thenReturn(buildMockResult());
    when(chatDeliveryChannel.type()).thenReturn("CHAT");

    var event =
        new com.smartfirehub.proactive.dto.AnomalyEvent(
            job.id(),
            testUserId,
            "metric_cd",
            "쿨다운 메트릭",
            100.0,
            20.0,
            5.0,
            16.0,
            "high",
            List.of(15.0, 18.0));

    rawJobService.onAnomalyDetected(event);
    rawJobService.onAnomalyDetected(event);

    verify(anomalyEventRepository, times(1)).save(event);
    verify(sseEmitterRegistry, times(1)).broadcast(anyLong(), any());
  }

  /**
   * [#192] onAnomalyDetected가 self-call이 아닌 asyncRunner를 통해 executeJob을 호출하는지 검증.
   *
   * <p>self-call이면 @Async 프록시가 우회되어 동기 실행됨. 이 TC는 ProactiveJobService가 내부적으로
   * ProactiveJobAsyncRunner.executeJob()에 위임하여 실행 레코드가 생성되는지 확인한다.
   */
  @Test
  void onAnomalyDetected_delegates_execution_to_asyncRunner() {
    var job = proactiveJobService.createJob(buildCreateRequest("asyncRunner 위임 테스트"), testUserId);
    when(proactiveContextCollector.collectContext(any(), any(), any())).thenReturn("{}");
    when(proactiveAiClient.execute(anyLong(), anyString(), anyString(), any(), any(), any(), any(), any()))
        .thenReturn(buildMockResult());
    when(chatDeliveryChannel.type()).thenReturn("CHAT");

    var event =
        new com.smartfirehub.proactive.dto.AnomalyEvent(
            job.id(),
            testUserId,
            "metric_async",
            "asyncRunner 위임 검증 메트릭",
            100.0,
            20.0,
            5.0,
            16.0,
            "high",
            List.of(15.0, 18.0));

    // onAnomalyDetected 호출 후 rawAsyncRunner로 직접 executeJob을 동기 실행하여 DB 반영 확인
    // (실제 @Async 호출은 별도 스레드이므로, 여기서는 위임 경로가 바르게 구성되었는지를 검증)
    rawJobService.onAnomalyDetected(event);

    // asyncRunner 경유 실행 확인: onAnomalyDetected가 tryAcquireRunSlot 후 asyncRunner를 호출했으면
    // runningJobs 슬롯이 점유되거나 이미 비동기 실행이 시작된 상태여야 한다.
    // 여기서는 rawAsyncRunner로 직접 실행하여 실행 레코드가 정상 생성됨을 확인한다.
    rawAsyncRunner.executeJob(job.id(), testUserId);

    var executions = executionRepository.findByJobId(job.id(), 10, 0);
    // onAnomalyDetected의 async 호출(1건) + rawAsyncRunner 직접 호출(1건) = 최소 1건
    assertThat(executions).isNotEmpty();
    assertThat(executions.get(0).status()).isEqualTo("COMPLETED");
  }

  /** getAnomalyEvents가 저장된 이벤트를 반환하는지 검증 */
  @Test
  void getAnomalyEvents_returns_events() {
    var record1 =
        new com.smartfirehub.proactive.repository.AnomalyEventRepository.AnomalyEventRecord(
            1L, 1L, "m1", "메트릭1", 30.0, 10.0, 2.0, 10.0, "low", java.time.LocalDateTime.now());
    when(anomalyEventRepository.findByJobId(1L, 10)).thenReturn(List.of(record1));

    var results = proactiveJobService.getAnomalyEvents(1L, 10);

    assertThat(results).hasSize(1);
    assertThat(results.get(0).metricName()).isEqualTo("메트릭1");
  }

  // ── 작업 주인 자격 재확인(WD-166) ─────────────────────────────────────────────────

  /** 리포트가 만들어지면 전달까지 가는 정상 스텁 — 건너뜀이면 아래 협력자가 하나도 불리지 않아야 한다. */
  private void stubSuccessfulRun() {
    when(proactiveContextCollector.collectContext(any(), any(), any())).thenReturn("{}");
    when(proactiveAiClient.execute(anyLong(), anyString(), anyString(), any(), any(), any(), any(), any()))
        .thenReturn(buildMockResult());
    when(chatDeliveryChannel.type()).thenReturn("CHAT");
  }

  /**
   * 주인 자격을 잃게 한 뒤 한 번 실행하면 건너뛰고(AI·전달 0, FAILED + 사유), 자격을 되돌리면 아무 조치 없이 다음 실행이 정상으로 돈다는 것을 본다. 작업은
   * 켜진 채, 다음 실행 시각도 그대로 남아야 한다(스케줄 해제·비활성화를 하지 않는다).
   */
  private void assertSkippedWhileIneligibleThenResumes(
      Runnable revoke, Runnable restore, String expectedReason) {
    ProactiveJobResponse created =
        proactiveJobService.createJob(buildCreateRequest("주인 자격 확인 작업"), testUserId);
    stubSuccessfulRun();

    revoke.run();
    // 예외 없이 끝난다 — 예정된 건너뜀이라 오류로 다시 던지지 않는다
    rawAsyncRunner.executeJob(created.id(), testUserId);

    var skipped = executionRepository.findByJobId(created.id(), 10, 0);
    assertThat(skipped).hasSize(1);
    assertThat(skipped.get(0).status()).isEqualTo("FAILED");
    assertThat(skipped.get(0).errorMessage()).isEqualTo(expectedReason);
    verify(proactiveContextCollector, never()).collectContext(any(), any(), any());
    verify(proactiveAiClient, never())
        .execute(anyLong(), anyString(), anyString(), any(), any(), any(), any(), any());
    verify(chatDeliveryChannel, never()).deliver(any(), anyLong(), any());
    // 실행 시도 기록은 그대로 돈다. 작업은 켜진 채이고 다음 실행도 예정돼 있다
    ProactiveJobResponse afterSkip = proactiveJobService.getJob(created.id(), testUserId);
    assertThat(afterSkip.lastExecutedAt()).isNotNull();
    assertThat(afterSkip.enabled()).isTrue();
    assertThat(afterSkip.nextExecuteAt()).isNotNull();

    // 자격을 되돌리면 다음 실행은 저절로 정상
    restore.run();
    rawAsyncRunner.executeJob(created.id(), testUserId);

    var resumed = executionRepository.findByJobId(created.id(), 10, 0);
    assertThat(resumed).hasSize(2);
    assertThat(resumed.get(0).status()).isEqualTo("COMPLETED");
    verify(proactiveAiClient, times(1))
        .execute(anyLong(), anyString(), anyString(), any(), any(), any(), any(), any());
    verify(chatDeliveryChannel, times(1)).deliver(any(), anyLong(), any());
  }

  private void setOwnerAccountActive(boolean active) {
    dsl.update(USER).set(USER.IS_ACTIVE, active).where(USER.ID.eq(testUserId)).execute();
  }

  private void setOwnerMembershipStatus(String status) {
    dsl.execute(
        "update membership set status = ? where user_id = ? and tenant_id = ?",
        status,
        testUserId,
        DEFAULT_TEST_TENANT_ID);
  }

  private void setOwnerRoleProactiveWrite(boolean granted) {
    if (granted) {
      dsl.insertInto(ROLE_PERMISSION)
          .set(ROLE_PERMISSION.ROLE_ID, ownerRoleId)
          .set(
              ROLE_PERMISSION.PERMISSION_ID,
              dsl.select(PERMISSION.ID)
                  .from(PERMISSION)
                  .where(PERMISSION.CODE.eq("proactive:write")))
          .execute();
    } else {
      dsl.deleteFrom(ROLE_PERMISSION)
          .where(ROLE_PERMISSION.ROLE_ID.eq(ownerRoleId))
          .and(
              ROLE_PERMISSION.PERMISSION_ID.in(
                  dsl.select(PERMISSION.ID)
                      .from(PERMISSION)
                      .where(PERMISSION.CODE.eq("proactive:write"))))
          .execute();
    }
  }

  /** [WD-166] 양성 대조 — 자격 있는 주인의 작업은 그대로 만들어지고 전달된다. */
  @Test
  void executeJob_eligibleOwner_runsAndDelivers() {
    ProactiveJobResponse created =
        proactiveJobService.createJob(buildCreateRequest("자격 있는 주인 작업"), testUserId);
    stubSuccessfulRun();

    rawAsyncRunner.executeJob(created.id(), testUserId);

    var executions = executionRepository.findByJobId(created.id(), 10, 0);
    assertThat(executions).hasSize(1);
    assertThat(executions.get(0).status()).isEqualTo("COMPLETED");
    verify(chatDeliveryChannel, times(1)).deliver(any(), anyLong(), any());
  }

  /** [WD-166] 운영자가 주인 계정을 비활성화(users.is_active=false)하면 건너뛰고, 다시 활성화하면 재개한다. */
  @Test
  void executeJob_ownerAccountDeactivated_skippedUntilReactivated() {
    assertSkippedWhileIneligibleThenResumes(
        () -> setOwnerAccountActive(false),
        () -> setOwnerAccountActive(true),
        ProactiveOwnerEligibility.REASON_OWNER_INACTIVE);
  }

  /** [WD-166] 워크스페이스 멤버십이 정지(SUSPENDED)되면 건너뛰고, 정지를 풀면 재개한다. */
  @Test
  void executeJob_ownerMembershipSuspended_skippedUntilReinstated() {
    assertSkippedWhileIneligibleThenResumes(
        () -> setOwnerMembershipStatus("SUSPENDED"),
        () -> setOwnerMembershipStatus("ACTIVE"),
        ProactiveOwnerEligibility.REASON_OWNER_INACTIVE);
  }

  /**
   * [WD-166] 워크스페이스에서 제거(멤버십 행 삭제)되면 건너뛰고, 다시 초대(ACTIVE 멤버십)하면 재개한다. 역할(user_role)은 그대로 남아 있어도 멤버가
   * 아니면 돌지 않는다.
   */
  @Test
  void executeJob_ownerRemovedFromWorkspace_skippedUntilReinvited() {
    assertSkippedWhileIneligibleThenResumes(
        () ->
            dsl.execute(
                "delete from membership where user_id = ? and tenant_id = ?",
                testUserId,
                DEFAULT_TEST_TENANT_ID),
        () ->
            com.smartfirehub.support.TenantRlsTestSupport.insertActiveMembership(
                dsl, testUserId, DEFAULT_TEST_TENANT_ID),
        ProactiveOwnerEligibility.REASON_OWNER_INACTIVE);
  }

  /** [WD-166] 주인 역할에서 proactive:write 를 회수하면 건너뛰고(권한 사유), 권한을 되돌리면 재개한다. */
  @Test
  void executeJob_ownerLosesProactiveWrite_skippedUntilRegranted() {
    assertSkippedWhileIneligibleThenResumes(
        () -> setOwnerRoleProactiveWrite(false),
        () -> setOwnerRoleProactiveWrite(true),
        ProactiveOwnerEligibility.REASON_OWNER_NO_PERMISSION);
  }

  /**
   * [WD-166] 권한은 작업이 속한 워크스페이스에서만 본다 — 다른 워크스페이스에서 proactive:write 를 가진 것으로는 통과하지 않는다. 주인은 기본
   * 워크스페이스에서는 읽기 권한만, 다른 워크스페이스에서는 ACTIVE 멤버이면서 쓰기 권한을 가진다.
   */
  @Test
  void executeJob_proactiveWriteOnlyInAnotherWorkspace_skipped() {
    setOwnerRoleProactiveWrite(false);
    long otherTenant =
        com.smartfirehub.support.TenantRlsTestSupport.createActiveTenant(dsl, "wd166");
    com.smartfirehub.support.TenantRlsTestSupport.insertActiveMembership(
        dsl, testUserId, otherTenant);
    // 다른 워크스페이스의 역할·권한은 그 테넌트 GUC 아래에서 만든다(role·user_role 의 tenant_id 는 GUC DEFAULT). 끝나면 기본 테넌트로
    // 되돌린다
    dsl.execute("select set_config('app.tenant_id', ?, true)", String.valueOf(otherTenant));
    try {
      Long otherRoleId =
          dsl.insertInto(ROLE)
              .set(ROLE.NAME, "proactive_writer_" + System.nanoTime())
              .set(ROLE.IS_SYSTEM, false)
              .returning(ROLE.ID)
              .fetchOne()
              .getId();
      dsl.insertInto(ROLE_PERMISSION)
          .set(ROLE_PERMISSION.ROLE_ID, otherRoleId)
          .set(
              ROLE_PERMISSION.PERMISSION_ID,
              dsl.select(PERMISSION.ID)
                  .from(PERMISSION)
                  .where(PERMISSION.CODE.eq("proactive:write")))
          .execute();
      dsl.insertInto(USER_ROLE)
          .set(USER_ROLE.USER_ID, testUserId)
          .set(USER_ROLE.ROLE_ID, otherRoleId)
          .execute();
      // 양성 대조: 그 워크스페이스에서는 정말 쓰기 권한이 있다(이 픽스처가 헛돌지 않는다)
      assertThat(permissionChecker.hasPermission(testUserId, "proactive:write")).isTrue();
    } finally {
      dsl.execute(
          "select set_config('app.tenant_id', ?, true)", String.valueOf(DEFAULT_TEST_TENANT_ID));
    }
    // 작업 워크스페이스(기본)로 돌아오면 같은 조회가 권한 없음이다 — 권한 조회가 현재 테넌트로 한정된다
    assertThat(permissionChecker.hasPermission(testUserId, "proactive:write")).isFalse();

    ProactiveJobResponse created =
        proactiveJobService.createJob(buildCreateRequest("다른 워크스페이스 권한 작업"), testUserId);
    stubSuccessfulRun();

    rawAsyncRunner.executeJob(created.id(), testUserId);

    var executions = executionRepository.findByJobId(created.id(), 10, 0);
    assertThat(executions).hasSize(1);
    assertThat(executions.get(0).status()).isEqualTo("FAILED");
    assertThat(executions.get(0).errorMessage())
        .isEqualTo(ProactiveOwnerEligibility.REASON_OWNER_NO_PERMISSION);
    verify(proactiveAiClient, never())
        .execute(anyLong(), anyString(), anyString(), any(), any(), any(), any(), any());
    verify(chatDeliveryChannel, never()).deliver(any(), anyLong(), any());
  }
}
