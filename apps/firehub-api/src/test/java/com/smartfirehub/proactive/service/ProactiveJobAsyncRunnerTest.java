package com.smartfirehub.proactive.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.notification.service.NotificationDispatcher;
import com.smartfirehub.proactive.dto.ProactiveJobResponse;
import com.smartfirehub.proactive.dto.ProactiveResult;
import com.smartfirehub.proactive.exception.ProactiveJobException;
import com.smartfirehub.proactive.repository.ProactiveJobExecutionRepository;
import com.smartfirehub.proactive.repository.ProactiveJobRepository;
import com.smartfirehub.proactive.repository.ReportTemplateRepository;
import com.smartfirehub.settings.model.AiCredential;
import com.smartfirehub.settings.model.UnknownAgentTypeException;
import com.smartfirehub.settings.service.AiCredentialService;
import com.smartfirehub.settings.service.SettingsService;
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
 * <p>(리뷰 라운드 1 지적) 이 클래스는 배경 잡(unattended)이라 잘못된 자격증명이 흘러도 사용자에게
 * 보이는 오류가 없다 — 청구서에만 나타난다. {@code executeJob()} 은 {@code @Async} 라 프록시를
 * 거치지 않는 단위 테스트에서는 동기 호출로 검증한다({@code PipelineAsyncRunnerTest} 와 같은
 * 패턴). 무거운 협력자(Repository/ContextCollector/AiClient/NotificationDispatcher)는 전부
 * Mockito 로 대체하고, 이 클래스가 직접 만드는 스위치 하나(opencode 분기 / fail-closed)만
 * 검증한다 — 나머지(템플릿 조회, 발송, 다음 실행 시각 계산 등)는 이 테스트의 관심사가 아니다.
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

  private ProactiveJobAsyncRunner runner;

  private static final Long JOB_ID = 10L;
  private static final Long USER_ID = 1L;
  private static final Long EXECUTION_ID = 100L;

  @BeforeEach
  void setUp() {
    // deliveryChannels 는 빈 리스트로 고정 — 발송 경로는 이 테스트의 관심사가 아니다(Mockito 로
    // List<DeliveryChannel> 자체를 모킹하면 for-each 순회에서 iterator() 가 null 을 돌려줘 NPE
    // 가 난다 — 실제 빈 리스트를 쓴다).
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
            List.of(),
            notificationDispatcher);

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
    when(contextCollector.collectContext(any(), eq(JOB_ID))).thenReturn("{}");
  }

  /**
   * 옵션 3 폐기(2026-09-19, 이슈 #693, Ruling #32) — 이 테스트는 그 결정을 고정하던 이전 핀
   * 테스트({@code apiKey=""}, {@code oauthToken=null} 만 확인)를 뒤집는다. ai-agent 의
   * {@code buildOpenCodeConfig} 가 이제 provider 블록을 요청 바디로 직접 받아 조립하므로,
   * {@link ProactiveAiClient#execute} 가 실제 apiKey 와 providerId/baseUrl/reasoningEffort
   * (OpencodeFields)를 받아야 한다 — 예전처럼 빈 값을 보내면 그 provider 블록이 빈 채로
   * 조립돼 프로액티브 리포트 생성이 깨진다.
   */
  @Test
  void opencode_자격증명이면_에이전트에_provider_필드가_모두_전달된다() {
    when(aiCredentialService.resolve())
        .thenReturn(
            new AiCredential.Opencode(
                "openai", "https://api.openai.com/v1", "medium", "sk-oai-secret"));
    when(settingsService.getValue("ai.model")).thenReturn(Optional.of("openai/gpt-4o"));
    when(aiClient.execute(
            eq(USER_ID), anyString(), anyString(), anyString(), anyString(), any(), any(), any(), any()))
        .thenReturn(
            new ProactiveResult("제목", List.of(), null, null, "요약 내용"));

    runner.executeJob(JOB_ID, USER_ID);

    ArgumentCaptor<String> apiKeyCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> agentTypeCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> oauthTokenCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<ProactiveAiClient.OpencodeFields> opencodeFieldsCaptor =
        ArgumentCaptor.forClass(ProactiveAiClient.OpencodeFields.class);
    verify(aiClient)
        .execute(
            eq(USER_ID),
            anyString(),
            anyString(),
            apiKeyCaptor.capture(),
            agentTypeCaptor.capture(),
            oauthTokenCaptor.capture(),
            any(),
            any(),
            opencodeFieldsCaptor.capture());

    assertThat(agentTypeCaptor.getValue()).isEqualTo("opencode");
    // Opencode.apiKey(OpenAI 호환 키)를 실제 값으로 싣는다 — 더 이상 Anthropic 필드로 오인해
    // 비우지 않는다.
    assertThat(apiKeyCaptor.getValue()).isEqualTo("sk-oai-secret");
    assertThat(oauthTokenCaptor.getValue()).isNull();
    assertThat(opencodeFieldsCaptor.getValue().providerId()).isEqualTo("openai");
    assertThat(opencodeFieldsCaptor.getValue().baseUrl()).isEqualTo("https://api.openai.com/v1");
    assertThat(opencodeFieldsCaptor.getValue().reasoningEffort()).isEqualTo("medium");
    assertThat(opencodeFieldsCaptor.getValue().model()).isEqualTo("openai/gpt-4o");
    // 실행이 정상 완료로 기록됐는지도 함께 확인 — opencode 분기가 예외를 던지지 않는다는 뜻.
    verify(executionRepository).updateResult(eq(EXECUTION_ID), eq("COMPLETED"), any(), any());
  }

  /**
   * ai.model 이 저장돼 있지 않으면(플랫폼 기본값도 없는 극단 상황) {@code OpencodeFields.model} 은
   * 빈 문자열이어야 한다 — {@code Optional.orElse("")} 가 {@code Optional.orElseThrow()} 등으로
   * 바뀌어 예외를 던지면 안 된다(이 브랜치는 여전히 정상 완료해야 하고, 빈 값 처리는
   * ProactiveAiClient.execute 의 책임이다).
   */
  @Test
  void opencode_ai모델이_없으면_OpencodeFields의_model이_빈문자열이다() {
    when(aiCredentialService.resolve())
        .thenReturn(
            new AiCredential.Opencode("openai", "https://api.openai.com/v1", "", "sk-oai"));
    when(settingsService.getValue("ai.model")).thenReturn(Optional.empty());
    when(aiClient.execute(
            eq(USER_ID), anyString(), anyString(), anyString(), anyString(), any(), any(), any(), any()))
        .thenReturn(new ProactiveResult("제목", List.of(), null, null, "요약 내용"));

    runner.executeJob(JOB_ID, USER_ID);

    ArgumentCaptor<ProactiveAiClient.OpencodeFields> opencodeFieldsCaptor =
        ArgumentCaptor.forClass(ProactiveAiClient.OpencodeFields.class);
    verify(aiClient)
        .execute(
            eq(USER_ID),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            any(),
            any(),
            any(),
            opencodeFieldsCaptor.capture());

    assertThat(opencodeFieldsCaptor.getValue().model()).isEmpty();
  }

  /** sdk 자격증명이면 OpencodeFields.NONE(빈 값)이 전달돼야 한다 — opencode 전용 필드가 새지 않는다. */
  @Test
  void sdk_자격증명이면_opencodeFields는_NONE이_전달된다() {
    when(aiCredentialService.resolve()).thenReturn(new AiCredential.Sdk("", "sk-anthropic"));
    when(aiClient.execute(
            eq(USER_ID), anyString(), anyString(), anyString(), anyString(), any(), any(), any(), any()))
        .thenReturn(new ProactiveResult("제목", List.of(), null, null, "요약 내용"));

    runner.executeJob(JOB_ID, USER_ID);

    ArgumentCaptor<ProactiveAiClient.OpencodeFields> opencodeFieldsCaptor =
        ArgumentCaptor.forClass(ProactiveAiClient.OpencodeFields.class);
    verify(aiClient)
        .execute(
            eq(USER_ID),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            any(),
            any(),
            any(),
            opencodeFieldsCaptor.capture());

    assertThat(opencodeFieldsCaptor.getValue()).isEqualTo(ProactiveAiClient.OpencodeFields.NONE);
  }

  /**
   * fail-closed 가드: 알 수 없는 agentType(손으로 고친 행 등)을 만나면 {@code resolve()} 가 던지는
   * {@code UnknownAgentTypeException} 을 삼키고 빈 자격증명({@code Sdk("","")})으로 계속 진행하면
   * 안 된다. {@code executeJob()} 은 {@code @Async} 라 예외를 호출자에게 전파하지 않는 것이
   * 정상 설계이므로(catch(Exception e) 가 executionRepository.updateError() 로 FAILED 기록 후
   * ProactiveJobException 으로 재포장), 이 테스트는 그 재포장된 예외와 FAILED 기록 자체를
   * 확인한다 — aiClient.execute() 가 전혀 불리지 않았다는 것도 함께 확인해 "빈 자격증명으로
   * 계속 진행"이 일어나지 않았음을 증명한다.
   */
  @Test
  void 알수없는_유형이면_빈_자격증명으로_계속하지_않고_FAILED로_기록된다() {
    when(aiCredentialService.resolve()).thenThrow(new UnknownAgentTypeException("martian"));

    assertThatThrownBy(() -> runner.executeJob(JOB_ID, USER_ID))
        .isInstanceOf(ProactiveJobException.class);

    verify(aiClient, never())
        .execute(any(), any(), any(), any(), any(), any(), any(), any(), any());
    verify(executionRepository).updateError(eq(EXECUTION_ID), anyString());
    verify(executionRepository, never()).updateResult(any(), any(), any(), any());
  }
}
