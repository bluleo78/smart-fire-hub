package com.smartfirehub.proactive.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.notification.service.NotificationDispatcher;
import com.smartfirehub.proactive.dto.ProactiveJobResponse;
import com.smartfirehub.proactive.dto.ProactiveResult;
import com.smartfirehub.proactive.exception.ProactiveJobException;
import com.smartfirehub.proactive.repository.ProactiveJobExecutionRepository;
import com.smartfirehub.proactive.repository.ProactiveJobRepository;
import com.smartfirehub.proactive.repository.ReportTemplateRepository;
import com.smartfirehub.proactive.service.delivery.DeliveryChannel;
import com.smartfirehub.proactive.util.ProactiveConfigParser;
import com.smartfirehub.proactive.util.ProactiveCron;
import com.smartfirehub.proactive.util.ProactiveTime;
import com.smartfirehub.settings.model.AiCredential;
import com.smartfirehub.settings.service.AiCredentialService;
import com.smartfirehub.settings.service.SettingsService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * ProactiveJobService의 비동기 Job 실행을 담당하는 별도 Spring Bean.
 *
 * <p>같은 클래스 내 자기호출(self-invocation)로는 Spring AOP 프록시를 우회하여 {@code @Async}가 적용되지 않는 문제를 방지하기 위해 별도
 * 빈으로 분리한다 (이슈 #192). ProactiveJobService가 이 빈을 주입받아 호출함으로써 프록시를 통한 정상적인 비동기 실행이 보장된다.
 *
 * <p>참고: DataExportAsyncRunner(이슈 #167), PipelineAsyncRunner(이슈 #189)와 동일한 패턴.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProactiveJobAsyncRunner {

  private final ProactiveJobRepository proactiveJobRepository;
  private final ProactiveJobExecutionRepository executionRepository;
  private final ReportTemplateRepository reportTemplateRepository;
  private final ProactiveContextCollector contextCollector;
  private final ProactiveAiClient aiClient;
  private final AiCredentialService aiCredentialService;
  // opencode 자격증명일 때만 ai.model 을 읽는다 — OpencodeFields.model javadoc 참고
  // (buildOpenCodeConfig 가 top-level model 을 필수로 요구하게 되면서 새로 필요해졌다).
  private final SettingsService settingsService;
  private final ObjectMapper objectMapper;
  private final List<DeliveryChannel> deliveryChannels;
  private final NotificationDispatcher notificationDispatcher;

  // notification.outbox.enabled=true면 Dispatcher 경로, false면 기존 DeliveryChannel 직접 호출
  @Value("${notification.outbox.enabled:false}")
  private boolean notificationOutboxEnabled;

  // 동시 실행 방지 맵 — ProactiveJobService와 공유 (주입된 참조를 통해 접근)
  // ProactiveJobService가 setRunningJobs()로 참조를 주입한다
  private ConcurrentHashMap<Long, AtomicBoolean> runningJobs;

  /**
   * 동시 실행 방지 맵 참조를 설정한다.
   *
   * <p>ProactiveJobService 생성 시 자신의 runningJobs 맵을 이 빈에 주입하여, 슬롯 해제가 정확히 동일한 맵에 반영되도록 한다.
   *
   * @param runningJobs ProactiveJobService의 runningJobs 맵
   */
  public void setRunningJobs(ConcurrentHashMap<Long, AtomicBoolean> runningJobs) {
    this.runningJobs = runningJobs;
  }

  /**
   * Proactive Job을 비동기로 실행한다.
   *
   * <p>이 메서드는 {@code pipelineExecutor} 스레드풀에서 실행되므로 이벤트 리스너 스레드나 HTTP 요청 스레드를 블록하지 않는다. AI 호출, 컨텍스트
   * 수집, 결과 저장, 알림 발송을 순차 수행한다.
   *
   * @param jobId 실행할 Proactive Job ID
   * @param userId 실행 요청 사용자 ID
   */
  @Async("pipelineExecutor")
  public void executeJob(Long jobId, Long userId) {
    // 슬롯 획득은 tryAcquireRunSlot()에서 사전 수행됨.
    // @Async 특성상 이 메서드 내부에서 throw한 예외는 호출자에게 전파되지 않으므로
    // 중복 실행 방지 체크는 동기 컨텍스트(컨트롤러/이벤트 리스너)에서 미리 처리한다.
    AtomicBoolean running =
        runningJobs != null
            ? runningJobs.computeIfAbsent(jobId, k -> new AtomicBoolean(false))
            : new AtomicBoolean(false);

    Long executionId = executionRepository.create(jobId);
    executionRepository.updateStatus(executionId, "RUNNING", ProactiveTime.nowUtc(), null);

    try {
      ProactiveJobResponse job =
          proactiveJobRepository
              .findById(jobId, userId)
              .orElseThrow(() -> new ProactiveJobException("Job을 찾을 수 없습니다: " + jobId));

      // 컨텍스트 수집
      String context = contextCollector.collectContext(job.config(), jobId);

      // 템플릿 조회 (templateId가 있으면 sections/style 포함)
      Map<String, Object> template = null;
      if (job.templateId() != null) {
        var tmpl = reportTemplateRepository.findById(job.templateId());
        if (tmpl.isPresent()) {
          var t = tmpl.get();
          template = new HashMap<>();
          template.put("sections", t.sections());
          template.put("output_format", "structured");
          if (t.style() != null) {
            template.put("style", t.style());
          }
        }
      }

      // AI 설정 조회.
      // 배경 잡이지만 TenantScopedRunner 가 테넌트 컨텍스트를 세워 두므로 **그 잡 소유 테넌트의**
      // 자격증명으로 해석된다. resolve() 가 던지는 UnknownAgentTypeException 은 여기서 잡지
      // 않는다 — 아래 바깥의 catch(Exception e) 가 FAILED 로 기록하고 실행을 끝낸다. 조용히 빈
      // 자격증명으로 계속 진행하면 6b1c6383 과 같은 모양의 과금 혼입이 재발한다.
      String agentType;
      String apiKey;
      String oauthToken;
      ProactiveAiClient.OpencodeFields opencodeFields = ProactiveAiClient.OpencodeFields.NONE;
      switch (aiCredentialService.resolve()) {
        case AiCredential.Sdk sdk -> {
          agentType = "sdk";
          apiKey = sdk.apiKey();
          // sdk 는 OAuth 우선 — apiKey 와 함께 있어도 ai-agent 가 OAuth 를 선택한다.
          oauthToken = sdk.oauthToken().isBlank() ? null : sdk.oauthToken();
        }
        case AiCredential.Cli cli -> {
          agentType = "cli";
          apiKey = "";
          oauthToken = cli.oauthToken().isBlank() ? null : cli.oauthToken();
        }
        case AiCredential.CliApi cliApi -> {
          agentType = "cli-api";
          apiKey = cliApi.apiKey();
          oauthToken = null;
        }
        case AiCredential.Opencode oc -> {
          // 옵션 3 폐기(2026-09-19, 이슈 #693) — ai-agent 의 buildOpenCodeConfig 가 이제
          // provider 블록(baseURL/apiKey)을 요청 바디로 직접 받아 조립하므로, Opencode.apiKey
          // (OpenAI 호환 키)를 실제 값으로 싣는다. 예전 주석("빈 값을 보낸다")은 apiKey 를
          // Anthropic 용 필드로 오인한 것이었다 — 이 요청 자체가 opencode 전용이라 그 구분이
          // 성립하지 않는다(AiAgentProxyService.resolveChatCredential 과 같은 정정).
          agentType = "opencode";
          apiKey = oc.apiKey();
          oauthToken = null;
          // ai.model 을 함께 싣는다 — 없으면 ai-agent 의 고정 기본값(슬래시 없음)이 대신 실려
          // buildOpenCodeConfig 가 "providerId/modelId 형식이어야 합니다" 로 throw 한다.
          String opencodeModel = settingsService.getValue("ai.model").orElse("");
          opencodeFields = new ProactiveAiClient.OpencodeFields(
              oc.providerId(), oc.baseUrl(), oc.reasoningEffort(), opencodeModel);
        }
      }

      // AI 실행
      ProactiveResult result =
          aiClient.execute(
              userId,
              job.prompt(),
              context,
              apiKey,
              agentType,
              oauthToken,
              template,
              job.config(),
              opencodeFields);

      // 발송 전 결과 검증 (이슈 #350) — 내용이 없거나 본문이 에이전트 실패 메시지인 결과를
      // COMPLETED로 기록하면 오류 원문이 그대로 CHAT/EMAIL로 나간다. 검증 실패 시 예외를 던져
      // 아래 catch에서 FAILED로 기록하게 하고, DeliveryChannel 발송은 아예 수행하지 않는다.
      var rejection = ProactiveResultValidator.findRejectionReason(result);
      if (rejection.isPresent()) {
        // 내부 사유(오류 원문 포함 가능)는 로그에만 남기고, 저장·노출 메시지는 번역된 문구를 쓴다.
        log.error(
            "Proactive job {} produced an undeliverable result: {}", jobId, rejection.get());
        throw new ProactiveJobException(ProactiveResultValidator.USER_FACING_FAILURE_MESSAGE);
      }

      // 결과 저장
      Map<String, Object> resultMap = objectMapper.convertValue(result, new TypeReference<>() {});
      executionRepository.updateResult(executionId, "COMPLETED", resultMap, ProactiveTime.nowUtc());

      // DeliveryChannel 호출 (config.channels 필터링)
      List<String> configChannels = ProactiveConfigParser.getChannelTypes(job.config());
      // notification.outbox.enabled=true → 새 Dispatcher 경로 (비동기 Outbox + Worker).
      // false → 기존 직접 호출 경로 유지 (회귀 안전, Stage 1 마이그레이션 중).
      List<String> deliveredChannels = new ArrayList<>();
      if (notificationOutboxEnabled) {
        try {
          com.smartfirehub.notification.NotificationRequest notifRequest =
              ProactiveJobNotificationMapper.toRequest(job, executionId, result);
          notificationDispatcher.enqueue(notifRequest);
          // Outbox 경로에서는 즉시 발송 전이라 deliveredChannels를 확정할 수 없음.
          // 실제 성공 채널 집계는 outbox status aggregation view(Task 13 이후)로 대체.
          for (String t : configChannels) deliveredChannels.add(t);
        } catch (Exception e) {
          log.warn(
              "NotificationDispatcher enqueue failed for job {}: {}", jobId, e.getMessage(), e);
        }
      } else {
        for (DeliveryChannel channel : deliveryChannels) {
          if (configChannels.isEmpty() || configChannels.contains(channel.type())) {
            try {
              channel.deliver(job, executionId, result);
              deliveredChannels.add(channel.type());
            } catch (Exception e) {
              log.warn(
                  "DeliveryChannel {} failed for job {}: {}",
                  channel.type(),
                  jobId,
                  e.getMessage());
            }
          }
        }
      }

      // 실제 전달된 채널 목록을 DB에 저장
      if (!deliveredChannels.isEmpty()) {
        executionRepository.updateDeliveredChannels(executionId, deliveredChannels);
      }

      // 마지막 실행 시간 업데이트
      // 마지막 실행 시각과 함께 다음 실행 예정 시각도 갱신한다 (#348).
      // 스케줄 등록 시점에 계산한 값은 이번 실행으로 소진됐으므로 여기서 다시 계산해야
      // 목록의 "다음 실행"이 이미 지나간 시각을 가리키지 않는다.
      proactiveJobRepository.updateLastExecuted(
          jobId,
          ProactiveTime.nowUtc(),
          ProactiveCron.nextExecuteAtUtc(job.cronExpression(), job.timezone()));

      log.info("Proactive job {} executed successfully", jobId);

    } catch (Exception e) {
      log.error("Proactive job {} execution failed", jobId, e);
      executionRepository.updateError(executionId, e.getMessage());
      throw new ProactiveJobException("Job 실행 실패: " + e.getMessage(), e);
    } finally {
      running.set(false);
    }
  }
}
