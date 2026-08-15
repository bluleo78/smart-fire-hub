package com.smartfirehub.proactive.service;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.proactive.dto.ProactiveJobResponse;
import com.smartfirehub.proactive.repository.ProactiveJobRepository;
import com.smartfirehub.proactive.util.ProactiveCron;
import com.smartfirehub.tenant.dto.MembershipResponse;
import com.smartfirehub.tenant.repository.MembershipRepository;
import jakarta.annotation.PostConstruct;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
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
  private final MembershipRepository membershipRepository;
  private final ConcurrentHashMap<Long, ScheduledFuture<?>> scheduledTasks =
      new ConcurrentHashMap<>();

  public ProactiveJobSchedulerService(
      ProactiveJobRepository proactiveJobRepository,
      MembershipRepository membershipRepository,
      @Lazy ProactiveJobService proactiveJobService) {
    this.proactiveJobRepository = proactiveJobRepository;
    this.membershipRepository = membershipRepository;
    this.proactiveJobService = proactiveJobService;

    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(4);
    scheduler.setThreadNamePrefix("proactive-");
    scheduler.initialize();
    this.taskScheduler = scheduler;
  }

  @PostConstruct
  public void reloadAllSchedules() {
    List<ProactiveJobResponse> jobs = proactiveJobRepository.findAllEnabled();
    log.info("Reloading {} enabled proactive jobs", jobs.size());

    for (ProactiveJobResponse job : jobs) {
      try {
        if (job.cronExpression() != null && !job.cronExpression().isBlank()) {
          registerSchedule(job.id(), job.cronExpression(), job.timezone());
        }
      } catch (Exception e) {
        log.error("Failed to register proactive job schedule {}: {}", job.id(), e.getMessage());
      }
    }
  }

  public void registerSchedule(Long jobId, String cronExpression, String timezone) {
    boolean registered = doRegister(jobId, cronExpression, timezone);

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
  private boolean doRegister(Long jobId, String cronExpression, String timezone) {
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
                taskScheduler.schedule(() -> runScheduledJob(jobId), cronTrigger);
            log.info(
                "Registered proactive job {} with cron '{}' timezone '{}'",
                jobId,
                cronExpression,
                tz);
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
   * {@code schedule()} 호출 시점에 Runnable 을 감싼다. 그런데 이 프로젝트의 {@code schedule()} 호출은
   * (1) {@code @PostConstruct reloadAllSchedules}(컨텍스트가 아예 없음 → null 을 캡처)와 (2) 잡
   * 생성·수정 요청(그 <b>등록자</b>의 테넌트를 이후 모든 발화에 고정)뿐이라, 어느 쪽도 "발화 시점의
   * 잡 소유자 테넌트"가 되지 못한다. 그래서 발화마다 다시 해석한다.
   *
   * <p><b>테넌트가 모호하면 실행 자체를 건너뛴다</b>: 임의의 기본 테넌트로 떨어뜨리면 그 사용자가
   * 속하지도 않은 워크스페이스에서 잡이 돌고, 정작 소유자의 {@code report_template} 은 RLS 로 0행이
   * 되어 sections·style 없는 리포트가 조용히 만들어진다. 눈에 띄게 실패(ERROR 로그)하는 편이 조용히
   * 열화되는 것보다 낫다. 자세한 판단 기준은 {@link #resolveTenantId} 참고.
   *
   * <p><b>순서가 강제된 이유</b>: 테넌트를 알려면 소유자 id 가 필요하고, 소유자 id 는 잡 행에만 있다.
   * 그래서 {@code proactive_job} 조회가 스코프 <b>바깥</b>에 온다. {@code proactive_job} 은 아직 RLS
   * 대상이 아니라 가능한 순서다 — P2-d 에서 {@code proactive_job.tenant_id} 가 생기면 잡 행에서 직접
   * 테넌트를 읽어 이 닭·달걀이 사라진다.
   */
  public void runScheduledJob(Long jobId) {
    try {
      // system execution — userId를 job owner로 단일 조회
      proactiveJobRepository
          .findById(jobId)
          .ifPresent(
              job ->
                  resolveTenantId(job.userId(), jobId)
                      .ifPresent(
                          tenantId ->
                              TenantContext.runScoped(
                                  tenantId,
                                  () -> proactiveJobService.executeJob(jobId, job.userId()))));
    } catch (Exception e) {
      log.error("Scheduled proactive job {} execution failed", jobId, e);
    }
  }

  /**
   * 잡 소유자의 멤버십에서 실행 테넌트를 해석한다.
   *
   * <p>멤버십이 정확히 하나면 그 테넌트다. 0개(멤버십 없음)이거나 2개 이상(어느 워크스페이스의 잡인지
   * 판별 불가)이면 <b>빈 값을 돌려 실행을 건너뛰게</b> 하고 ERROR 로 남긴다.
   *
   * <p>왜 기본 테넌트 폴백을 쓰지 않는가: 테넌트 2·3 에만 속한 소유자의 잡이 기본 테넌트(1)로 돌면
   * {@code report_template} 조회가 RLS 로 0행이 되어, 예외도 로그도 없이 소유자의 sections·style 이
   * 빠진 리포트가 생성된다 — 이 밴드가 고친 결함과 정확히 같은 형태다. 게다가 소유자가 속하지도 않은
   * 테넌트 컨텍스트에서 잡 전체가 실행된다. 조용한 열화보다 눈에 띄는 실패가 낫다.
   *
   * <p>P2-d 에서 {@code proactive_job.tenant_id} 가 추가되면 이 추론 자체가 필요 없어진다(잡 행이
   * 소속 테넌트를 직접 들고 있게 된다). 그때 이 메서드를 제거할 것.
   *
   * <p>{@code membership}/{@code tenant} 는 전역(RLS 미적용) 테이블이라 컨텍스트·트랜잭션 없이도
   * 조회된다({@link MembershipRepository} 클래스 주석 참고).
   */
  private Optional<Long> resolveTenantId(Long userId, Long jobId) {
    List<MembershipResponse> memberships = membershipRepository.findActiveByUser(userId);
    Optional<Long> tenantId = MembershipResponse.soleActiveTenant(memberships);
    if (tenantId.isEmpty()) {
      log.error(
          "Proactive job {} 소유자 {} 의 ACTIVE 멤버십이 {}개라 실행 테넌트를 확정할 수 없어"
              + " 이번 발화를 건너뛴다. P2-d 의 proactive_job.tenant_id 가 이를 정확하게 만든다.",
          jobId,
          userId,
          memberships.size());
    }
    return tenantId;
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
