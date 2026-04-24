package com.smartfirehub.proactive.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.notification.dto.NotificationEvent;
import com.smartfirehub.notification.service.NotificationDispatcher;
import com.smartfirehub.notification.service.SseEmitterRegistry;
import com.smartfirehub.proactive.dto.AnomalyEvent;
import com.smartfirehub.proactive.dto.CreateProactiveJobRequest;
import com.smartfirehub.proactive.dto.ProactiveJobExecutionResponse;
import com.smartfirehub.proactive.dto.ProactiveJobResponse;
import com.smartfirehub.proactive.dto.ProactiveResult;
import com.smartfirehub.proactive.dto.RecipientResponse;
import com.smartfirehub.proactive.dto.UpdateProactiveJobRequest;
import com.smartfirehub.proactive.exception.ProactiveJobException;
import com.smartfirehub.proactive.exception.ProactiveJobNotFoundException;
import com.smartfirehub.proactive.repository.AnomalyEventRepository;
import com.smartfirehub.proactive.repository.ProactiveJobExecutionRepository;
import com.smartfirehub.proactive.repository.ProactiveJobRepository;
import com.smartfirehub.proactive.repository.ProactiveMessageRepository;
import com.smartfirehub.proactive.repository.ReportTemplateRepository;
import com.smartfirehub.proactive.service.delivery.DeliveryChannel;
import com.smartfirehub.proactive.util.ProactiveConfigParser;
import com.smartfirehub.settings.service.SettingsService;
import com.smartfirehub.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
public class ProactiveJobService {

  private final ProactiveJobRepository proactiveJobRepository;
  private final ProactiveJobExecutionRepository executionRepository;
  private final ProactiveMessageRepository messageRepository;
  private final ReportTemplateRepository reportTemplateRepository;
  private final ProactiveContextCollector contextCollector;
  private final ProactiveAiClient aiClient;
  private final SettingsService settingsService;
  private final ObjectMapper objectMapper;
  private final ProactiveJobSchedulerService schedulerService;
  private final List<DeliveryChannel> deliveryChannels;
  private final UserRepository userRepository;
  // 이상 탐지 이벤트 저장 Repository — anomaly_event 테이블에 이벤트 이력을 영속화한다
  private final AnomalyEventRepository anomalyEventRepository;
  // SSE 에미터 레지스트리 — 사용자에게 실시간 이상 탐지 알림을 전송한다
  private final SseEmitterRegistry sseEmitterRegistry;
  // Outbox 기반 알림 Dispatcher (feature flag ON일 때 사용)
  private final NotificationDispatcher notificationDispatcher;
  // notification.outbox.enabled=true면 Dispatcher 경로, false면 기존 DeliveryChannel 직접 호출
  private final boolean notificationOutboxEnabled;

  // 동시 실행 방지: jobId -> running flag
  private final ConcurrentHashMap<Long, AtomicBoolean> runningJobs = new ConcurrentHashMap<>();

  // 이상 탐지 쿨다운: jobId -> 마지막 이상 탐지 실행 시각
  private final Map<Long, LocalDateTime> lastAnomalyExecution = new ConcurrentHashMap<>();

  public ProactiveJobService(
      ProactiveJobRepository proactiveJobRepository,
      ProactiveJobExecutionRepository executionRepository,
      ProactiveMessageRepository messageRepository,
      ReportTemplateRepository reportTemplateRepository,
      ProactiveContextCollector contextCollector,
      ProactiveAiClient aiClient,
      SettingsService settingsService,
      ObjectMapper objectMapper,
      @Lazy ProactiveJobSchedulerService schedulerService,
      List<DeliveryChannel> deliveryChannels,
      UserRepository userRepository,
      AnomalyEventRepository anomalyEventRepository,
      SseEmitterRegistry sseEmitterRegistry,
      NotificationDispatcher notificationDispatcher,
      @Value("${notification.outbox.enabled:false}") boolean notificationOutboxEnabled) {
    this.proactiveJobRepository = proactiveJobRepository;
    this.executionRepository = executionRepository;
    this.messageRepository = messageRepository;
    this.reportTemplateRepository = reportTemplateRepository;
    this.contextCollector = contextCollector;
    this.aiClient = aiClient;
    this.settingsService = settingsService;
    this.objectMapper = objectMapper;
    this.schedulerService = schedulerService;
    this.deliveryChannels = deliveryChannels;
    this.userRepository = userRepository;
    this.anomalyEventRepository = anomalyEventRepository;
    this.sseEmitterRegistry = sseEmitterRegistry;
    this.notificationDispatcher = notificationDispatcher;
    this.notificationOutboxEnabled = notificationOutboxEnabled;
  }

  @Transactional(readOnly = true)
  public List<ProactiveJobResponse> getJobs(Long userId) {
    return proactiveJobRepository.findByUserId(userId);
  }

  @Transactional(readOnly = true)
  public ProactiveJobResponse getJob(Long id, Long userId) {
    // 존재하지 않는 Job 조회 시 404 반환 (#41)
    return proactiveJobRepository
        .findById(id, userId)
        .orElseThrow(() -> new ProactiveJobNotFoundException("Proactive Job을 찾을 수 없습니다: " + id));
  }

  @Transactional
  public ProactiveJobResponse createJob(CreateProactiveJobRequest request, Long userId) {
    Long id =
        proactiveJobRepository.create(
            userId,
            request.name(),
            request.prompt(),
            request.templateId(),
            request.cronExpression(),
            request.timezone(),
            request.config());
    ProactiveJobResponse job =
        proactiveJobRepository
            .findById(id, userId)
            .orElseThrow(() -> new ProactiveJobException("Job 생성 실패"));
    if (job.cronExpression() != null && !job.cronExpression().isBlank()) {
      schedulerService.registerSchedule(job.id(), job.cronExpression(), job.timezone());
    }
    return job;
  }

  @Transactional
  public void updateJob(Long id, UpdateProactiveJobRequest request, Long userId) {
    proactiveJobRepository.update(
        id,
        userId,
        request.name(),
        request.prompt(),
        request.templateId(),
        request.cronExpression(),
        request.timezone(),
        request.enabled(),
        request.config());
    ProactiveJobResponse updated = proactiveJobRepository.findById(id, userId).orElse(null);
    if (updated != null) {
      schedulerService.rescheduleJob(updated);
    }
  }

  @Transactional
  public void deleteJob(Long id, Long userId) {
    schedulerService.unregisterSchedule(id);
    proactiveJobRepository.delete(id, userId);
  }

  @Transactional
  public void toggleJob(Long id, Long userId, boolean enabled) {
    proactiveJobRepository.update(id, userId, null, null, null, null, null, enabled, null);
    ProactiveJobResponse job = proactiveJobRepository.findById(id, userId).orElse(null);
    if (job != null) {
      if (enabled && job.cronExpression() != null && !job.cronExpression().isBlank()) {
        schedulerService.registerSchedule(job.id(), job.cronExpression(), job.timezone());
      } else {
        schedulerService.unregisterSchedule(id);
      }
    }
  }

  @Transactional(readOnly = true)
  public List<ProactiveJobExecutionResponse> getExecutions(
      Long jobId, Long userId, int limit, int offset) {
    // job 소유권 확인
    getJob(jobId, userId);
    return executionRepository.findByJobId(jobId, limit, offset);
  }

  @Transactional(readOnly = true)
  public ProactiveJobExecutionResponse getExecution(Long executionId) {
    return executionRepository
        .findById(executionId)
        .orElseThrow(() -> new ProactiveJobException("Execution을 찾을 수 없습니다: " + executionId));
  }

  @Async("pipelineExecutor")
  public void executeJob(Long jobId, Long userId) {
    AtomicBoolean running = runningJobs.computeIfAbsent(jobId, k -> new AtomicBoolean(false));
    if (!running.compareAndSet(false, true)) {
      throw new ProactiveJobException("Job이 이미 실행 중입니다: " + jobId);
    }

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
          com.smartfirehub.notification.NotificationRequest request =
              ProactiveJobNotificationMapper.toRequest(job, executionId, result);
          notificationDispatcher.enqueue(request);
          // Outbox 경로에서는 즉시 발송 전이라 deliveredChannels를 확정할 수 없음.
          // 실제 성공 채널 집계는 outbox status aggregation view(Task 13 이후)로 대체.
          for (String t : configChannels) deliveredChannels.add(t);
        } catch (Exception e) {
          log.warn("NotificationDispatcher enqueue failed for job {}: {}", jobId, e.getMessage(), e);
        }
      } else {
        for (DeliveryChannel channel : deliveryChannels) {
          if (configChannels.isEmpty() || configChannels.contains(channel.type())) {
            try {
              channel.deliver(job, executionId, result);
              deliveredChannels.add(channel.type());
            } catch (Exception e) {
              log.warn(
                  "DeliveryChannel {} failed for job {}: {}", channel.type(), jobId, e.getMessage());
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

  @Transactional(readOnly = true)
  public List<RecipientResponse> searchRecipients(String search) {
    return userRepository.findAllPaginated(search, 0, 20).stream()
        .map(u -> new RecipientResponse(u.id(), u.name(), u.email()))
        .toList();
  }

  /**
   * 특정 작업의 이상 탐지 이벤트 이력을 조회한다. 최근 순(detected_at DESC)으로 limit 건을 반환한다.
   *
   * @param jobId 조회할 proactive_job ID
   * @param limit 최대 반환 건수
   * @return 이상 탐지 이벤트 목록
   */
  @Transactional(readOnly = true)
  public List<AnomalyEventRepository.AnomalyEventRecord> getAnomalyEvents(Long jobId, int limit) {
    return anomalyEventRepository.findByJobId(jobId, limit);
  }

  // ── 이상 탐지 이벤트 처리 ──

  @EventListener
  @Async("pipelineExecutor")
  public void onAnomalyDetected(AnomalyEvent event) {
    if (isInCooldown(event.jobId())) {
      log.info(
          "Anomaly detected for job {} but in cooldown, skipping (metric={})",
          event.jobId(),
          event.metricName());
      return;
    }

    log.info(
        "Anomaly detected for job {}, executing with anomaly context"
            + " (metric={}, value={}, deviation={})",
        event.jobId(),
        event.metricName(),
        event.currentValue(),
        event.deviation());

    // 이상 탐지 이벤트를 DB에 영속화한다 — 이력 조회 API에서 활용된다
    // 저장 실패가 job 실행을 막지 않도록 예외를 포획한다
    try {
      anomalyEventRepository.save(event);
    } catch (Exception e) {
      log.warn("Failed to save anomaly event for job {}: {}", event.jobId(), e.getMessage());
    }

    // 해당 사용자에게 SSE를 통해 실시간 이상 탐지 알림을 전송한다
    // 연결이 없거나 전송 실패해도 job 실행에는 영향을 주지 않는다
    try {
      var notification =
          new NotificationEvent(
              UUID.randomUUID().toString(),
              "ANOMALY_DETECTED",
              "WARNING",
              "이상 탐지",
              String.format(
                  "메트릭 '%s'에서 이상이 감지되었습니다 (%.2fσ 편차)", event.metricName(), event.deviation()),
              "PROACTIVE_JOB",
              event.jobId(),
              Map.of(
                  "metricId", event.metricId(),
                  "metricName", event.metricName(),
                  "currentValue", event.currentValue(),
                  "deviation", event.deviation()),
              LocalDateTime.now());
      sseEmitterRegistry.broadcast(event.userId(), notification);
    } catch (Exception e) {
      log.warn(
          "Failed to send SSE notification for anomaly event (job={}): {}",
          event.jobId(),
          e.getMessage());
    }

    try {
      executeJob(event.jobId(), event.userId());
      recordCooldown(event.jobId());
    } catch (Exception e) {
      log.warn("Anomaly-triggered execution failed for job {}: {}", event.jobId(), e.getMessage());
    }
  }

  private boolean isInCooldown(Long jobId) {
    LocalDateTime lastExec = lastAnomalyExecution.get(jobId);
    if (lastExec == null) return false;

    int cooldownMinutes = getCooldownMinutes(jobId);
    return LocalDateTime.now().isBefore(lastExec.plusMinutes(cooldownMinutes));
  }

  private void recordCooldown(Long jobId) {
    lastAnomalyExecution.put(jobId, LocalDateTime.now());
  }

  private int getCooldownMinutes(Long jobId) {
    try {
      ProactiveJobResponse job = proactiveJobRepository.findById(jobId).orElse(null);
      if (job != null && job.config() != null) {
        Object anomalyConfig = job.config().get("anomaly");
        if (anomalyConfig instanceof Map<?, ?> anomalyMap) {
          Object cooldown = anomalyMap.get("cooldownMinutes");
          if (cooldown instanceof Number n) {
            return n.intValue();
          }
        }
      }
    } catch (Exception e) {
      log.debug("Failed to read cooldown config for job {}: {}", jobId, e.getMessage());
    }
    return 60; // 기본 쿨다운: 60분
  }
}
