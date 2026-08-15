package com.smartfirehub.proactive.service;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.global.tenant.TenantScopedRunner;
import com.smartfirehub.proactive.dto.ProactiveJobResponse;
import com.smartfirehub.proactive.repository.ProactiveJobRepository;
import com.smartfirehub.proactive.util.ProactiveCron;
import jakarta.annotation.PostConstruct;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class ProactiveJobSchedulerService {

  private final TaskScheduler taskScheduler;
  private final ProactiveJobRepository proactiveJobRepository;
  private final ProactiveJobService proactiveJobService;
  private final TenantScopedRunner tenantScopedRunner;
  private final ConcurrentHashMap<Long, ScheduledFuture<?>> scheduledTasks =
      new ConcurrentHashMap<>();

  public ProactiveJobSchedulerService(
      ProactiveJobRepository proactiveJobRepository,
      TenantScopedRunner tenantScopedRunner,
      @Lazy ProactiveJobService proactiveJobService) {
    this.proactiveJobRepository = proactiveJobRepository;
    this.tenantScopedRunner = tenantScopedRunner;
    this.proactiveJobService = proactiveJobService;

    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(4);
    scheduler.setThreadNamePrefix("proactive-");
    scheduler.initialize();
    this.taskScheduler = scheduler;
  }

  /**
   * 기동 시 활성 프로액티브 잡의 크론을 전부 재등록한다.
   *
   * <p><b>왜 테넌트를 순회하는가</b>: {@code @PostConstruct} 에는 원 HTTP 요청이 없어 승계할 테넌트가
   * 없다. V104 로 {@code proactive_job} 에 RLS 가 걸리면 컨텍스트 없는 조회는 <b>0행</b>이 되고, 그
   * 결과 크론이 하나도 등록되지 않은 채 부팅이 <b>성공</b>한다 — 예외도 에러 로그도 없이 프로액티브
   * 기능 전체가 조용히 죽는다. 선례: {@code TriggerSchedulerService.reloadAllSchedules}.
   *
   * <p>{@link TenantScopedRunner} 는 ThreadLocal 만 세우고 트랜잭션은 열지 않는다. 여기서 쓰는
   * {@link ProactiveJobRepository} 는 클래스 레벨 {@code @Transactional} 이라 호출마다 트랜잭션이 열려
   * GUC 가 주입된다 — 조회({@code findAllEnabled})뿐 아니라 {@code registerSchedule} 안의 <b>쓰기</b>
   * ({@code updateNextExecuteAt})도 같은 이유로 이 순회 안에 있어야 한다.
   */
  @PostConstruct
  public void reloadAllSchedules() {
    tenantScopedRunner.forEachActiveTenant(
        tenantId -> {
          List<ProactiveJobResponse> jobs = proactiveJobRepository.findAllEnabled(tenantId);
          log.info("Reloading {} enabled proactive jobs (tenant={})", jobs.size(), tenantId);

          for (ProactiveJobResponse job : jobs) {
            try {
              if (job.cronExpression() != null && !job.cronExpression().isBlank()) {
                registerSchedule(job.id(), job.cronExpression(), job.timezone());
              }
            } catch (Exception e) {
              log.error(
                  "Failed to register proactive job schedule {} (tenant={}): {}",
                  job.id(),
                  tenantId,
                  e.getMessage());
            }
          }
        });
  }

  /**
   * 크론 스케줄을 등록하고 다음 실행 예정 시각을 DB 에 반영한다.
   *
   * <p><b>등록 시점의 테넌트를 캡처한다</b>: 이 스케줄러 풀({@code ThreadPoolTaskScheduler})에는
   * {@code TaskDecorator} 가 없고, 발화는 몇 시간 뒤 다른 풀 스레드에서 일어난다. 캡처하지 않으면
   * 발화 스레드에 테넌트가 없어 RLS 가 걸린 테이블이 전부 0행이 된다. 호출 경로는 모두 컨텍스트가
   * 있다 — 기동 재등록은 테넌트 순회 안이고, 생성·수정·토글은 HTTP 요청 스코프다.
   *
   * <p>{@code require()} 를 {@code compute()} 람다 <b>밖</b>에서 부르는 이유는 선례
   * ({@code TriggerSchedulerService.registerSchedule})와 같다: 맵의 bin lock 을 잡은 채 던지면 이미
   * {@code cancel} 된 기존 스케줄이 대체 없이 사라진다.
   */
  public void registerSchedule(Long jobId, String cronExpression, String timezone) {
    final long tenantId = TenantContext.require();
    boolean registered = doRegister(jobId, tenantId, cronExpression, timezone);

    // 다음 실행 예정 시각을 DB에 반영한다 (#348).
    // 등록/해제 경로(생성·수정·활성화 토글·부팅 시 reloadAllSchedules)가 모두 이 메서드를 지나므로
    // 여기 한 곳만 채우면 "한 번도 실행되지 않은 잡"까지 포함해 전 행이 값을 갖는다.
    // compute() 람다 밖에서 쓴다 — ConcurrentHashMap 의 bin lock 을 잡은 채 DB I/O 를 하지 않기 위함.
    proactiveJobRepository.updateNextExecuteAt(
        jobId, registered ? ProactiveCron.nextExecuteAtUtc(cronExpression, timezone) : null);
  }

  /**
   * 실제 스케줄 등록. 등록에 성공하면 true.
   *
   * <p>DB 갱신과 분리한 이유는 {@link ConcurrentHashMap#compute} 람다 안에서 I/O 를 피하기 위함이다.
   */
  private boolean doRegister(Long jobId, long tenantId, String cronExpression, String timezone) {
    return scheduledTasks.compute(
        jobId,
        (id, existing) -> {
          if (existing != null) {
            existing.cancel(false);
          }
          String tz = timezone != null && !timezone.isBlank() ? timezone : "Asia/Seoul";
          try {
            // CronTrigger 는 6필드만 수용하는데 DB에는 5필드(Unix 표준)와 6필드가 섞여 있다(#347).
            // 원시 문자열을 그대로 넘기면 5필드 레거시 잡이 등록에 실패해 enabled=true 인 채로
            // 영구 미실행 상태가 된다(#354). 다음 실행 시각 계산(nextExecuteAtUtc)과 같은
            // 정규화 규칙을 쓰게 하여 "표시값 = 실제 발화 시각" 불변식도 유지한다(#348).
            CronTrigger cronTrigger =
                new CronTrigger(ProactiveCron.normalize(cronExpression), ZoneId.of(tz));
            ScheduledFuture<?> future =
                taskScheduler.schedule(
                    () -> TenantContext.runScoped(tenantId, () -> runScheduledJob(jobId)),
                    cronTrigger);
            log.info(
                "Registered proactive job {} with cron '{}' timezone '{}' (tenant={})",
                jobId,
                cronExpression,
                tz,
                tenantId);
            return future;
          } catch (Exception e) {
            log.error("Failed to register cron for proactive job {}: {}", jobId, e.getMessage());
            return null;
          }
        })
        != null;
  }

  /**
   * cron 발화 1회의 본문. 잡 소유자의 테넌트 컨텍스트 안에서 실행을 위임한다.
   *
   * <p><b>왜 테넌트 컨텍스트가 필요한가</b>: 스케줄러 스레드에는 원 HTTP 요청이 없어 승계할 테넌트가
   * 없다. V99 로 {@code report_template} 에 RLS 가 걸렸으므로, 컨텍스트 없이 실행하면 양식 조회가
   * 예외도 로그도 없이 0행이 되어 사용자의 sections·style 없는 리포트가 생성된다. 트랜잭션만 붙여도
   * GUC 값이 비어 있으면 결과는 같다 — 컨텍스트와 트랜잭션 둘 다 있어야 한다.
   *
   * <p><b>왜 {@code TaskDecorator} 가 아닌가</b>: {@code ThreadPoolTaskScheduler} 의 데코레이터는
   * {@code schedule()} 호출 시점에 Runnable 을 감싸는데, 이 풀은 생성자에서 직접 만들고 데코레이터를
   * 붙이지 않는다. 대신 {@link #registerSchedule} 이 등록 시점 테넌트를 람다에 명시적으로 담는다 —
   * 선례 {@code TriggerSchedulerService} 와 같은 형태이며, 어느 경로로 등록되든(기동 재등록의 테넌트
   * 순회, 소유자의 HTTP 요청) 캡처되는 값이 곧 잡 행의 테넌트가 된다.
   *
   * <p><b>테넌트는 등록 시점에 캡처된다</b>(P2-e): 잡 행이 {@code tenant_id} 를 직접 들고 있고, 등록은
   * 항상 그 테넌트의 컨텍스트 안(기동 재등록의 테넌트 순회, 또는 소유자의 HTTP 요청)에서 일어나므로
   * {@link #registerSchedule} 이 그 값을 람다에 담아 둔다. 따라서 이 메서드는 <b>이미 올바른 테넌트
   * 컨텍스트 안</b>에서 호출된다.
   *
   * <p><b>왜 발화 시점에 소유자 멤버십으로 추론하지 않는가</b>(P2-e 에서 제거된 옛 방식): 소유자가 여러
   * 워크스페이스에 속하면 어느 테넌트의 잡인지 판별할 수 없어 발화를 통째로 건너뛰어야 했다. 이제 잡
   * 행이 소속 테넌트를 들고 있으므로 모호함 자체가 없다 — 옛 방식보다 엄격하게 강한 보장이다.
   *
   * <p><b>순서 주의</b>: {@code proactive_job} 은 V104 로 RLS 대상이 되므로, 컨텍스트 없이
   * {@code findById} 를 먼저 불러 테넌트를 알아내는 순서는 <b>불가능하다</b>(0행). 컨텍스트가 먼저,
   * 잡 조회가 나중이다.
   */
  public void runScheduledJob(Long jobId) {
    try {
      // system execution — userId를 job owner로 단일 조회
      proactiveJobRepository
          .findById(jobId)
          .ifPresent(job -> proactiveJobService.executeJob(jobId, job.userId()));
    } catch (Exception e) {
      log.error("Scheduled proactive job {} execution failed", jobId, e);
    }
  }

  public void unregisterSchedule(Long jobId) {
    scheduledTasks.computeIfPresent(
        jobId,
        (id, future) -> {
          future.cancel(false);
          log.info("Unregistered proactive job schedule {}", jobId);
          return null;
        });
    // 스케줄이 없어졌으므로 "다음 실행" 표시도 비운다 (#348).
    // 비활성화/삭제 후에도 과거 계산값이 남아 있으면 곧 실행될 것처럼 보인다.
    proactiveJobRepository.updateNextExecuteAt(jobId, null);
  }

  public void rescheduleJob(ProactiveJobResponse job) {
    unregisterSchedule(job.id());
    if (Boolean.TRUE.equals(job.enabled())
        && job.cronExpression() != null
        && !job.cronExpression().isBlank()) {
      registerSchedule(job.id(), job.cronExpression(), job.timezone());
    }
  }
}
