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
    executionRepository.updateStatus(executionId, "RUNNING", LocalDateTime.now(), null);

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

      // AI 설정 조회
      Map<String, String> aiSettings = settingsService.getAsMap("ai.");
      String apiKey = settingsService.getDecryptedApiKey().orElse("");
      String agentType = aiSettings.getOrDefault("ai.agent_type", "sdk");
      String cliOauthToken = null;
      if ("cli".equals(agentType)) {
        cliOauthToken = settingsService.getDecryptedCliOauthToken().orElse(null);
      }

      // AI 실행
      ProactiveResult result =
          aiClient.execute(
              userId,
              job.prompt(),
              context,
              apiKey,
              agentType,
              cliOauthToken,
              template,
              job.config());

      // 결과 저장
      Map<String, Object> resultMap = objectMapper.convertValue(result, new TypeReference<>() {});
      executionRepository.updateResult(executionId, "COMPLETED", resultMap, LocalDateTime.now());

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
      proactiveJobRepository.updateLastExecuted(jobId, LocalDateTime.now(), null);

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
