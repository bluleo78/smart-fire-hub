package com.smartfirehub.proactive.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.proactive.dto.CreateProactiveJobRequest;
import com.smartfirehub.proactive.dto.ProactiveJobExecutionResponse;
import com.smartfirehub.proactive.dto.ProactiveJobResponse;
import com.smartfirehub.proactive.dto.ProactiveResult;
import com.smartfirehub.proactive.dto.RecipientResponse;
import com.smartfirehub.proactive.dto.UpdateProactiveJobRequest;
import com.smartfirehub.proactive.exception.ProactiveJobException;
import com.smartfirehub.proactive.repository.ProactiveJobExecutionRepository;
import com.smartfirehub.proactive.repository.ProactiveJobRepository;
import com.smartfirehub.proactive.repository.ProactiveMessageRepository;
import com.smartfirehub.proactive.service.delivery.DeliveryChannel;
import com.smartfirehub.proactive.util.ProactiveConfigParser;
import com.smartfirehub.settings.service.SettingsService;
import com.smartfirehub.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
public class ProactiveJobService {

  private final ProactiveJobRepository proactiveJobRepository;
  private final ProactiveJobExecutionRepository executionRepository;
  private final ProactiveMessageRepository messageRepository;
  private final ProactiveContextCollector contextCollector;
  private final ProactiveAiClient aiClient;
  private final SettingsService settingsService;
  private final ObjectMapper objectMapper;
  private final ProactiveJobSchedulerService schedulerService;
  private final List<DeliveryChannel> deliveryChannels;
  private final UserRepository userRepository;

  // 동시 실행 방지: jobId -> running flag
  private final ConcurrentHashMap<Long, AtomicBoolean> runningJobs = new ConcurrentHashMap<>();

  public ProactiveJobService(
      ProactiveJobRepository proactiveJobRepository,
      ProactiveJobExecutionRepository executionRepository,
      ProactiveMessageRepository messageRepository,
      ProactiveContextCollector contextCollector,
      ProactiveAiClient aiClient,
      SettingsService settingsService,
      ObjectMapper objectMapper,
      @Lazy ProactiveJobSchedulerService schedulerService,
      List<DeliveryChannel> deliveryChannels,
      UserRepository userRepository) {
    this.proactiveJobRepository = proactiveJobRepository;
    this.executionRepository = executionRepository;
    this.messageRepository = messageRepository;
    this.contextCollector = contextCollector;
    this.aiClient = aiClient;
    this.settingsService = settingsService;
    this.objectMapper = objectMapper;
    this.schedulerService = schedulerService;
    this.deliveryChannels = deliveryChannels;
    this.userRepository = userRepository;
  }

  @Transactional(readOnly = true)
  public List<ProactiveJobResponse> getJobs(Long userId) {
    return proactiveJobRepository.findByUserId(userId);
  }

  @Transactional(readOnly = true)
  public ProactiveJobResponse getJob(Long id, Long userId) {
    return proactiveJobRepository
        .findById(id, userId)
        .orElseThrow(() -> new ProactiveJobException("Proactive Job을 찾을 수 없습니다: " + id));
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
      String context = contextCollector.collectContext(job.config());

      // AI API 키 조회
      String apiKey = settingsService.getDecryptedApiKey().orElse("");

      // AI 실행
      ProactiveResult result =
          aiClient.execute(userId, job.prompt(), context, apiKey, job.config());

      // 결과 저장
      Map<String, Object> resultMap = objectMapper.convertValue(result, new TypeReference<>() {});
      executionRepository.updateResult(executionId, "COMPLETED", resultMap, LocalDateTime.now());

      // DeliveryChannel 호출 (config.channels 필터링)
      List<String> configChannels = ProactiveConfigParser.getChannelTypes(job.config());
      List<String> deliveredChannels = new ArrayList<>();
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
}
