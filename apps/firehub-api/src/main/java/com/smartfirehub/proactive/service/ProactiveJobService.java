package com.smartfirehub.proactive.service;

import com.smartfirehub.notification.dto.NotificationEvent;
import com.smartfirehub.notification.service.SseEmitterRegistry;
import com.smartfirehub.proactive.dto.AnomalyEvent;
import com.smartfirehub.proactive.dto.CreateProactiveJobRequest;
import com.smartfirehub.proactive.dto.ProactiveJobExecutionResponse;
import com.smartfirehub.proactive.dto.ProactiveJobResponse;
import com.smartfirehub.proactive.dto.RecipientResponse;
import com.smartfirehub.proactive.dto.UpdateProactiveJobRequest;
import com.smartfirehub.proactive.exception.ProactiveJobAlreadyRunningException;
import com.smartfirehub.proactive.exception.ProactiveJobException;
import com.smartfirehub.proactive.exception.ProactiveJobNotFoundException;
import com.smartfirehub.proactive.repository.AnomalyEventRepository;
import com.smartfirehub.proactive.repository.ProactiveJobExecutionRepository;
import com.smartfirehub.proactive.repository.ProactiveJobRepository;
import com.smartfirehub.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
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
  private final ProactiveJobSchedulerService schedulerService;
  private final UserRepository userRepository;
  // 이상 탐지 이벤트 저장 Repository — anomaly_event 테이블에 이벤트 이력을 영속화한다
  private final AnomalyEventRepository anomalyEventRepository;
  // SSE 에미터 레지스트리 — 사용자에게 실시간 이상 탐지 알림을 전송한다
  private final SseEmitterRegistry sseEmitterRegistry;
  // Spring AOP @Async 프록시를 우회하는 self-call 문제를 방지하기 위해 별도 빈으로 분리 (이슈 #192)
  private final ProactiveJobAsyncRunner asyncRunner;

  // 동시 실행 방지: jobId -> running flag
  private final ConcurrentHashMap<Long, AtomicBoolean> runningJobs = new ConcurrentHashMap<>();

  // 이상 탐지 쿨다운: jobId -> 마지막 이상 탐지 실행 시각
  private final Map<Long, LocalDateTime> lastAnomalyExecution = new ConcurrentHashMap<>();

  public ProactiveJobService(
      ProactiveJobRepository proactiveJobRepository,
      ProactiveJobExecutionRepository executionRepository,
      @Lazy ProactiveJobSchedulerService schedulerService,
      UserRepository userRepository,
      AnomalyEventRepository anomalyEventRepository,
      SseEmitterRegistry sseEmitterRegistry,
      ProactiveJobAsyncRunner asyncRunner) {
    this.proactiveJobRepository = proactiveJobRepository;
    this.executionRepository = executionRepository;
    this.schedulerService = schedulerService;
    this.userRepository = userRepository;
    this.anomalyEventRepository = anomalyEventRepository;
    this.sseEmitterRegistry = sseEmitterRegistry;
    this.asyncRunner = asyncRunner;
    // asyncRunner가 runningJobs 맵을 공유하여 슬롯 해제가 동일한 맵에 반영되도록 한다
    asyncRunner.setRunningJobs(this.runningJobs);
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
            request.enabled(),
            request.config());
    ProactiveJobResponse job =
        proactiveJobRepository
            .findById(id, userId)
            .orElseThrow(() -> new ProactiveJobException("Job 생성 실패"));
    // enabled=false로 명시 생성된 잡은 스케줄러에 등록하지 않는다 (#220).
    // job.enabled()는 DB에서 다시 읽어온 값이므로 null이 아니다.
    if (Boolean.TRUE.equals(job.enabled())
        && job.cronExpression() != null
        && !job.cronExpression().isBlank()) {
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

  /**
   * 동시 실행 방지 슬롯을 획득한다. @Async 메서드 호출 전에 반드시 이 메서드를 먼저 호출해야 한다. 이미 실행 중인 경우
   * ProactiveJobAlreadyRunningException(→ 409 Conflict)을 던진다. 이 메서드는 동기(non-@Async)로 호출되어 예외가 HTTP
   * 호출자에게 정상 전파된다.
   */
  public void tryAcquireRunSlot(Long jobId) {
    AtomicBoolean running = runningJobs.computeIfAbsent(jobId, k -> new AtomicBoolean(false));
    if (!running.compareAndSet(false, true)) {
      throw new ProactiveJobAlreadyRunningException("Job이 이미 실행 중입니다: " + jobId);
    }
  }

  /**
   * 동시 실행 방지 슬롯을 강제 해제한다. executeJob 비동기 제출이 실패(RejectedExecutionException 등)한 경우 컨트롤러에서 호출하여 슬롯이
   * 영구 점유되는 것을 방지한다.
   */
  public void releaseRunSlot(Long jobId) {
    AtomicBoolean running = runningJobs.get(jobId);
    if (running != null) {
      running.set(false);
    }
  }

  /**
   * Proactive Job을 비동기로 실행한다.
   *
   * <p>실제 실행 로직은 {@link ProactiveJobAsyncRunner#executeJob}에 위임한다. Spring AOP @Async 프록시를 우회하는
   * self-call 문제를 방지하기 위해 별도 빈(asyncRunner)을 통해 호출한다 (이슈 #192).
   *
   * @param jobId 실행할 Proactive Job ID
   * @param userId 실행 요청 사용자 ID
   */
  public void executeJob(Long jobId, Long userId) {
    asyncRunner.executeJob(jobId, userId);
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

    // 중복 실행 슬롯 획득 후 비동기 실행 — 이미 실행 중이면 조용히 skip
    // asyncRunner.executeJob()을 호출하여 Spring AOP 프록시를 통한 @Async 실행을 보장한다 (이슈 #192)
    try {
      tryAcquireRunSlot(event.jobId());
    } catch (ProactiveJobAlreadyRunningException e) {
      log.info("Anomaly-triggered execution skipped for job {} (already running)", event.jobId());
      return;
    }
    try {
      asyncRunner.executeJob(event.jobId(), event.userId());
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
