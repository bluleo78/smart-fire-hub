package com.smartfirehub.proactive.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.notification.service.NotificationDispatcher;
import com.smartfirehub.proactive.dto.AnomalyEvent;
import com.smartfirehub.proactive.dto.ProactiveJobResponse;
import com.smartfirehub.proactive.dto.ProactiveResult;
import com.smartfirehub.proactive.dto.ReportTemplateResponse;
import com.smartfirehub.proactive.exception.ProactiveJobException;
import com.smartfirehub.proactive.repository.ProactiveJobExecutionRepository;
import com.smartfirehub.proactive.repository.ProactiveJobRepository;
import com.smartfirehub.proactive.repository.ReportTemplateRepository;
import com.smartfirehub.proactive.service.delivery.DeliveryChannel;
import com.smartfirehub.settings.model.AiBehaviorDefaults;
import com.smartfirehub.settings.model.AiCredential;
import com.smartfirehub.settings.model.UnknownAgentTypeException;
import com.smartfirehub.settings.service.AiCredentialService;
import com.smartfirehub.settings.service.SettingsService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * ProactiveJobAsyncRunner 의 AI 자격증명 분기(2026-09 타입형 전환) 단위 테스트.
 *
 * <p>(리뷰 라운드 1 지적) 이 클래스는 배경 잡(unattended)이라 잘못된 자격증명이 흘러도 사용자에게 보이는 오류가 없다 — 청구서에만 나타난다. {@code
 * executeJob()} 은 {@code @Async} 라 프록시를 거치지 않는 단위 테스트에서는 동기 호출로 검증한다({@code
 * PipelineAsyncRunnerTest} 와 같은 패턴). 무거운
 * 협력자(Repository/ContextCollector/AiClient/NotificationDispatcher)는 전부 Mockito 로 대체하고, 이 클래스가 직접
 * 만드는 스위치 하나(opencode 분기 / fail-closed)만 검증한다 — 나머지(템플릿 조회, 발송, 다음 실행 시각 계산 등)는 이 테스트의 관심사가 아니다.
 */
@ExtendWith(MockitoExtension.class)
class ProactiveJobAsyncRunnerTest {

  @Mock ProactiveJobRepository proactiveJobRepository;
  @Mock ProactiveJobExecutionRepository executionRepository;
  @Mock ReportTemplateRepository reportTemplateRepository;
  @Mock ProactiveContextCollector contextCollector;
  @Mock ProactiveAiClient aiClient;
  @Mock AiCredentialService aiCredentialService;
  @Mock SettingsService settingsService;
  @Mock NotificationDispatcher notificationDispatcher;
  @Mock ProactiveOwnerEligibility ownerEligibility;
  @Mock DeliveryChannel chatChannel;

  private ProactiveJobAsyncRunner runner;

  private static final Long JOB_ID = 10L;
  private static final Long USER_ID = 1L;
  private static final Long EXECUTION_ID = 100L;

  @BeforeEach
  void setUp() {
    // deliveryChannels 는 목 채널 하나짜리 실제 리스트 — Mockito 로 List<DeliveryChannel> 자체를 모킹하면
    // for-each 순회에서 iterator() 가 null 을 돌려줘 NPE 가 난다. 채널 하나는 WD-166 건너뜀 테스트가
    // 「전달하지 않았다」를 확인하려고 둔다(다른 테스트에서는 deliver 가 아무것도 하지 않는 목이다).
    runner =
        new ProactiveJobAsyncRunner(
            proactiveJobRepository,
            executionRepository,
            reportTemplateRepository,
            contextCollector,
            aiClient,
            aiCredentialService,
            settingsService,
            new ObjectMapper(),
            List.of(chatChannel),
            notificationDispatcher,
            ownerEligibility);
    // 기본은 자격 있는 주인(WD-166) — 건너뜀 테스트만 사유를 돌려주게 바꾼다
    lenient().when(ownerEligibility.findIneligibilityReason(USER_ID)).thenReturn(Optional.empty());
    lenient().when(chatChannel.type()).thenReturn("CHAT");

    ProactiveJobResponse job =
        new ProactiveJobResponse(
            JOB_ID,
            USER_ID,
            null,
            null,
            "테스트 잡",
            "요약해줘",
            "0 0 * * *",
            "Asia/Seoul",
            true,
            "SCHEDULE",
            Map.of(),
            null,
            null,
            null,
            null,
            null);
    when(proactiveJobRepository.findById(JOB_ID, USER_ID)).thenReturn(Optional.of(job));
    when(executionRepository.create(JOB_ID)).thenReturn(EXECUTION_ID);
    // 이상 탐지 실행(WD-171)은 4인자 수집을 쓰므로 이 기본 스텁을 쓰지 않는다 — lenient
    lenient().when(contextCollector.collectContext(any(), eq(JOB_ID), any())).thenReturn("{}");
  }

  /**
   * 옵션 3 폐기(2026-09-19, 이슈 #693, Ruling #32) — 이 테스트는 그 결정을 고정하던 이전 핀 테스트({@code apiKey=""}, {@code
   * oauthToken=null} 만 확인)를 뒤집는다. ai-agent 의 {@code buildOpenCodeConfig} 가 이제 provider 블록을 요청 바디로
   * 직접 받아 조립하므로, {@link ProactiveAiClient#execute} 가 실제 자격증명을 받아야 한다 — 예전처럼 빈 값을 보내면 그 provider 블록이
   * 빈 채로 조립돼 프로액티브 리포트 생성이 깨진다.
   *
   * <p>이슈 #695 이후 이 러너는 평면 문자열로 풀어내지 않고 {@link AiCredential} 을 그대로 넘긴다 — 바디 조립은 {@code applyTo()} 한
   * 곳이 맡는다. 그래서 여기서는 "해석된 자격증명이 손상 없이 그대로 전달되는가"와 "opencode 에서만 모델이 함께 실리는가"를 본다.
   */
  @Test
  void opencode_자격증명이면_에이전트에_자격증명과_모델이_그대로_전달된다() {
    AiCredential.Opencode credential =
        new AiCredential.Opencode("openai", "https://api.openai.com/v1", "medium", "sk-oai-secret");
    when(aiCredentialService.resolve()).thenReturn(credential);
    when(settingsService.getValue("ai.model")).thenReturn(Optional.of("openai/gpt-4o"));
    when(aiClient.execute(eq(USER_ID), anyString(), anyString(), any(), any(), any(), any()))
        .thenReturn(new ProactiveResult("제목", List.of(), null, null, "요약 내용"));

    runner.executeJob(JOB_ID, USER_ID);

    ArgumentCaptor<AiCredential> credentialCaptor = ArgumentCaptor.forClass(AiCredential.class);
    ArgumentCaptor<String> modelCaptor = ArgumentCaptor.forClass(String.class);
    verify(aiClient)
        .execute(
            eq(USER_ID),
            anyString(),
            anyString(),
            credentialCaptor.capture(),
            modelCaptor.capture(),
            any(),
            any());

    assertThat(credentialCaptor.getValue()).isEqualTo(credential);
    assertThat(modelCaptor.getValue()).isEqualTo("openai/gpt-4o");
  }

  /**
   * [WD-163] 실행은 작업 소유자가 쓸 수 있는 양식(기본·본인)만 읽는다 — 수정 전에 남의 양식이 연결된 작업이 그 양식의 섹션·지시문을 계속 끌어 쓰지 않고,
   * 삭제된 양식처럼 기본 형식(template=null)으로 돌아간다. 같은 stub 이 양식을 돌려주면 그대로 실리는 것을 양성 대조로 함께 본다.
   */
  @Test
  void 작업_소유자가_쓸_수_없는_양식이면_양식_없이_실행한다() {
    long templateId = 77L;
    ProactiveJobResponse job =
        new ProactiveJobResponse(
            JOB_ID,
            USER_ID,
            templateId,
            "남의 양식",
            "테스트 잡",
            "요약해줘",
            "0 0 * * *",
            "Asia/Seoul",
            true,
            "SCHEDULE",
            Map.of(),
            null,
            null,
            null,
            null,
            null);
    when(proactiveJobRepository.findById(JOB_ID, USER_ID)).thenReturn(Optional.of(job));
    when(aiCredentialService.resolve())
        .thenReturn(new AiCredential.Opencode("openai", "https://api.openai.com/v1", "", "sk"));
    when(settingsService.getValue("ai.model")).thenReturn(Optional.of("openai/gpt-4o"));
    when(aiClient.execute(eq(USER_ID), anyString(), anyString(), any(), any(), any(), any()))
        .thenReturn(new ProactiveResult("제목", List.of(), null, null, "요약 내용"));

    // 소유자 범위 밖 → 양식 없이
    when(reportTemplateRepository.findVisibleTo(templateId, USER_ID)).thenReturn(Optional.empty());
    runner.executeJob(JOB_ID, USER_ID);

    // 양성 대조: 소유자 범위 안 → 양식이 실린다
    when(reportTemplateRepository.findVisibleTo(templateId, USER_ID))
        .thenReturn(
            Optional.of(
                new ReportTemplateResponse(
                    templateId,
                    "내 양식",
                    null,
                    List.of(Map.of("key", "s")),
                    "formal",
                    USER_ID,
                    false,
                    LocalDateTime.now(),
                    LocalDateTime.now())));
    runner.executeJob(JOB_ID, USER_ID);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> templateCaptor = ArgumentCaptor.forClass(Map.class);
    verify(aiClient, times(2))
        .execute(
            eq(USER_ID), anyString(), anyString(), any(), any(), templateCaptor.capture(), any());
    assertThat(templateCaptor.getAllValues().get(0)).isNull();
    assertThat(templateCaptor.getAllValues().get(1)).containsEntry("style", "formal");
    // 소유자 범위 없이 id 로만 읽는 경로는 쓰지 않는다
    verify(reportTemplateRepository, never()).findById(anyLong());
  }

  /** [WD-171] 이상 탐지로 시작된 실행은 감지된 이상을 컨텍스트 수집에 넘긴다 — 예약·수동 실행(이벤트 없음)은 기존 수집을 그대로 쓴다(대조). */
  @Test
  void 이상_탐지_실행은_이상을_컨텍스트_수집에_넘긴다() {
    when(aiCredentialService.resolve())
        .thenReturn(new AiCredential.Opencode("openai", "https://api.openai.com/v1", "", "sk"));
    when(settingsService.getValue("ai.model")).thenReturn(Optional.of("openai/gpt-4o"));
    when(aiClient.execute(eq(USER_ID), any(), any(), any(), any(), any(), any()))
        .thenReturn(new ProactiveResult("제목", List.of(), null, null, "요약 내용"));
    AnomalyEvent event =
        new AnomalyEvent(
            JOB_ID, USER_ID, "m1", "실패율", 0.9, 0.1, 0.05, 16.0, "high", List.of(0.1, 0.12));
    when(contextCollector.collectContext(any(), eq(JOB_ID), any(), eq(event)))
        .thenReturn("{\"anomaly\":{}}");

    runner.executeJob(JOB_ID, USER_ID, event);

    verify(contextCollector).collectContext(any(), eq(JOB_ID), eq(USER_ID), eq(event));
    verify(aiClient)
        .execute(eq(USER_ID), anyString(), eq("{\"anomaly\":{}}"), any(), any(), any(), any());
  }

  /**
   * 모델 접두사 가드(이슈 #695) — {@code ai.model} 이 opencode 형식이 아니면(설정 안 함이라 기본값 {@code claude-sonnet-5} 로
   * 떨어지는 경우 포함) 호출 <b>전에</b> 막는다.
   *
   * <p>이전 동작은 빈 모델을 그대로 넘겨 ai-agent 의 고정 기본값(슬래시 없음)이 대신 실리게 두는 것이었고, 실패는 ai-agent 안쪽에서 원인 불명으로 났다.
   * 채팅·분류에는 이미 있던 검사가 이 경로에만 없었다 — 이제 세 경로가 같은 검사와 같은 문구를 쓴다. 배경 잡이라 사용자에게 보이는 오류가 없으므로, 분명한 설정 오류로
   * {@code execution.error} 에 남는 것이 중요하다.
   */
  @Test
  void opencode_ai모델이_형식에_맞지_않으면_호출전에_FAILED로_기록된다() {
    when(aiCredentialService.resolve())
        .thenReturn(new AiCredential.Opencode("openai", "https://api.openai.com/v1", "", "sk-oai"));
    when(settingsService.getValue("ai.model")).thenReturn(Optional.of(AiBehaviorDefaults.MODEL));

    assertThatThrownBy(() -> runner.executeJob(JOB_ID, USER_ID))
        .isInstanceOf(ProactiveJobException.class);

    verify(aiClient, never()).execute(any(), any(), any(), any(), any(), any(), any());
    verify(executionRepository).updateError(eq(EXECUTION_ID), anyString());
  }

  /**
   * [WD-134] 실패한 실행도 마지막 실행 시각과 다음 실행 시각을 갱신한다.
   *
   * <p>예전에는 성공 경로에서만 갱신해서, 계속 실패하는 예약 작업이 목록에서 「마지막 실행 -」·지난 시각의 「다음 실행」으로 남았다.
   */
  @Test
  void 실행이_실패해도_마지막_실행과_다음_실행_시각을_갱신한다() {
    // 스케줄에 등록된 활성 잡 — 등록 시점에 계산한 다음 실행 시각이 이미 지나간 상태
    LocalDateTime stale = LocalDateTime.of(2026, 1, 1, 0, 0);
    when(proactiveJobRepository.findById(JOB_ID, USER_ID))
        .thenReturn(Optional.of(job(true, stale)));
    when(aiCredentialService.resolve())
        .thenReturn(new AiCredential.Opencode("openai", "https://api.openai.com/v1", "", "sk-oai"));
    when(settingsService.getValue("ai.model")).thenReturn(Optional.of(AiBehaviorDefaults.MODEL));

    assertThatThrownBy(() -> runner.executeJob(JOB_ID, USER_ID))
        .isInstanceOf(ProactiveJobException.class);

    verify(executionRepository).updateError(eq(EXECUTION_ID), anyString());
    ArgumentCaptor<LocalDateTime> next = ArgumentCaptor.forClass(LocalDateTime.class);
    verify(proactiveJobRepository).updateLastExecuted(eq(JOB_ID), notNull(), next.capture());
    assertThat(next.getValue()).isAfter(stale);
  }

  /**
   * [WD-134] 비활성 잡을 「지금 실행」 해도 오지 않을 다음 실행 시각을 만들지 않는다.
   *
   * <p>스케줄에서 빠진 잡의 다음 실행 시각은 비어 있어야 상세에 「다음 실행」이 잘못 보이지 않고, 등록에 실패한 활성 잡의 「미등록」 표시(#354)도 남는다.
   */
  @Test
  void 스케줄에_없는_잡은_다음_실행_시각을_새로_만들지_않는다() {
    when(proactiveJobRepository.findById(JOB_ID, USER_ID))
        .thenReturn(Optional.of(job(false, null)));
    when(aiCredentialService.resolve())
        .thenReturn(new AiCredential.Opencode("openai", "https://api.openai.com/v1", "", "sk-oai"));
    when(settingsService.getValue("ai.model")).thenReturn(Optional.of(AiBehaviorDefaults.MODEL));

    assertThatThrownBy(() -> runner.executeJob(JOB_ID, USER_ID))
        .isInstanceOf(ProactiveJobException.class);

    verify(proactiveJobRepository).updateLastExecuted(eq(JOB_ID), notNull(), isNull());
  }

  /** 활성 여부와 다음 실행 시각만 다른 테스트용 잡. */
  private static ProactiveJobResponse job(boolean enabled, LocalDateTime nextExecuteAt) {
    return new ProactiveJobResponse(
        JOB_ID,
        USER_ID,
        null,
        null,
        "테스트 잡",
        "요약해줘",
        "0 0 * * *",
        "Asia/Seoul",
        enabled,
        "SCHEDULE",
        Map.of(),
        null,
        nextExecuteAt,
        null,
        null,
        null);
  }

  /** [WD-134] 실행 시각 갱신이 실패해도 이미 기록한 실행 결과(성공)를 실패로 뒤집지 않는다. */
  @Test
  void 실행_시각_갱신이_실패해도_성공한_실행은_그대로_끝난다() {
    when(aiCredentialService.resolve()).thenReturn(new AiCredential.Sdk("", "sk-test"));
    when(aiClient.execute(any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(
            new ProactiveResult(
                "리포트",
                List.of(new ProactiveResult.Section("summary", "요약", "내용", null, null)),
                null,
                null,
                null));
    doThrow(new RuntimeException("DB 끊김"))
        .when(proactiveJobRepository)
        .updateLastExecuted(any(), any(), any());

    runner.executeJob(JOB_ID, USER_ID);

    verify(executionRepository).updateResult(eq(EXECUTION_ID), eq("COMPLETED"), any(), any());
    verify(executionRepository, never()).updateError(any(), any());
  }

  /**
   * 불완전한 자격증명은 모델 검사보다 **먼저** 막힌다(리뷰 3b).
   *
   * <p>순서가 뒤집히면 providerId/baseUrl 이 비어 있는 테넌트가 "모델을 다시 선택하세요"라는 엉뚱한 안내를 받는다 — 고쳐야 할 것은 모델이 아니라 공급자
   * 설정이다. 배경 잡이라 그 문구가 {@code execution.error} 에 남는 것이 관리자가 얻는 유일한 단서다.
   */
  @Test
  void opencode_공급자설정이_비어있으면_모델안내가_아니라_공급자안내로_실패한다() {
    when(aiCredentialService.resolve()).thenReturn(new AiCredential.Opencode("", "", "", "sk-oai"));

    assertThatThrownBy(() -> runner.executeJob(JOB_ID, USER_ID))
        .isInstanceOf(ProactiveJobException.class);

    ArgumentCaptor<String> errorCaptor = ArgumentCaptor.forClass(String.class);
    verify(executionRepository).updateError(eq(EXECUTION_ID), errorCaptor.capture());
    assertThat(errorCaptor.getValue()).contains("공급자");
    assertThat(errorCaptor.getValue()).doesNotContain("모델을 다시 선택");
    verify(aiClient, never()).execute(any(), any(), any(), any(), any(), any(), any());
  }

  /** sdk 자격증명이면 모델을 아예 보내지 않는다 — ai-agent 라우트의 고정 기본값이 그대로 산다. */
  @Test
  void sdk_자격증명이면_모델을_보내지_않는다() {
    when(aiCredentialService.resolve()).thenReturn(new AiCredential.Sdk("", "sk-anthropic"));
    when(aiClient.execute(eq(USER_ID), anyString(), anyString(), any(), isNull(), any(), any()))
        .thenReturn(new ProactiveResult("제목", List.of(), null, null, "요약 내용"));

    runner.executeJob(JOB_ID, USER_ID);

    verify(aiClient).execute(eq(USER_ID), anyString(), anyString(), any(), isNull(), any(), any());
  }

  /**
   * fail-closed 가드: 알 수 없는 agentType(손으로 고친 행 등)을 만나면 {@code resolve()} 가 던지는 {@code
   * UnknownAgentTypeException} 을 삼키고 빈 자격증명({@code Sdk("","")})으로 계속 진행하면 안 된다. {@code
   * executeJob()} 은 {@code @Async} 라 예외를 호출자에게 전파하지 않는 것이 정상 설계이므로(catch(Exception e) 가
   * executionRepository.updateError() 로 FAILED 기록 후 ProactiveJobException 으로 재포장), 이 테스트는 그 재포장된
   * 예외와 FAILED 기록 자체를 확인한다 — aiClient.execute() 가 전혀 불리지 않았다는 것도 함께 확인해 "빈 자격증명으로 계속 진행"이 일어나지 않았음을
   * 증명한다.
   */
  @Test
  void 알수없는_유형이면_빈_자격증명으로_계속하지_않고_FAILED로_기록된다() {
    when(aiCredentialService.resolve()).thenThrow(new UnknownAgentTypeException("martian"));

    assertThatThrownBy(() -> runner.executeJob(JOB_ID, USER_ID))
        .isInstanceOf(ProactiveJobException.class);

    verify(aiClient, never()).execute(any(), any(), any(), any(), any(), any(), any());
    verify(executionRepository).updateError(eq(EXECUTION_ID), anyString());
    verify(executionRepository, never()).updateResult(any(), any(), any(), any());
  }

  /**
   * [WD-166] 작업 주인이 실행 자격을 잃었으면(정지·제거·권한 회수) 리포트를 만들지도 보내지도 않는다 — 컨텍스트 수집·AI 호출·채널 전달·알림 큐가 모두 0
   * 이고, 실행 이력에는 사유 문구로 FAILED 가 남는다. 예외를 던지지 않고(매분 예약이면 오류 스택이 폭주) 마지막·다음 실행 시각 갱신은 그대로 돈다.
   */
  @Test
  void 작업_주인이_자격을_잃었으면_만들지도_보내지도_않고_사유를_남긴다() {
    for (String reason :
        List.of(
            ProactiveOwnerEligibility.REASON_OWNER_INACTIVE,
            ProactiveOwnerEligibility.REASON_OWNER_NO_PERMISSION)) {
      clearInvocations(executionRepository, proactiveJobRepository);
      when(ownerEligibility.findIneligibilityReason(USER_ID)).thenReturn(Optional.of(reason));

      // 예외 없이 끝난다
      runner.executeJob(JOB_ID, USER_ID);

      verify(executionRepository).updateError(EXECUTION_ID, reason);
      verify(executionRepository, never()).updateResult(any(), any(), any(), any());
      verify(proactiveJobRepository).updateLastExecuted(eq(JOB_ID), notNull(), any());
    }
    verifyNoInteractions(contextCollector, aiClient, aiCredentialService, notificationDispatcher);
    verify(chatChannel, never()).deliver(any(), any(), any());
  }

  /** [WD-166] 양성 대조 — 자격 있는 주인의 작업은 그대로 실행되고 전달된다. */
  @Test
  void 자격_있는_주인의_작업은_그대로_실행되고_전달된다() {
    when(aiCredentialService.resolve()).thenReturn(new AiCredential.Sdk("", "sk-test"));
    when(aiClient.execute(any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(
            new ProactiveResult(
                "리포트",
                List.of(new ProactiveResult.Section("summary", "요약", "내용", null, null)),
                null,
                null,
                null));

    runner.executeJob(JOB_ID, USER_ID);

    verify(ownerEligibility).findIneligibilityReason(USER_ID);
    verify(executionRepository).updateResult(eq(EXECUTION_ID), eq("COMPLETED"), any(), any());
    verify(chatChannel).deliver(any(), eq(EXECUTION_ID), any());
  }
}
