package com.smartfirehub.proactive.service;

import com.smartfirehub.global.exception.CodedApiException;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.notification.dto.NotificationEvent;
import com.smartfirehub.notification.service.SseEmitterRegistry;
import com.smartfirehub.proactive.dto.AnomalyEvent;
import com.smartfirehub.proactive.dto.CreateProactiveJobRequest;
import com.smartfirehub.proactive.dto.ProactiveJobExecutionResponse;
import com.smartfirehub.proactive.dto.ProactiveJobExecutionSummaryResponse;
import com.smartfirehub.proactive.dto.ProactiveJobResponse;
import com.smartfirehub.proactive.dto.RecipientResponse;
import com.smartfirehub.proactive.dto.ReportListItemResponse;
import com.smartfirehub.proactive.dto.UpdateProactiveJobRequest;
import com.smartfirehub.proactive.exception.ProactiveJobAlreadyRunningException;
import com.smartfirehub.proactive.exception.ProactiveJobException;
import com.smartfirehub.proactive.exception.ProactiveJobNotFoundException;
import com.smartfirehub.proactive.repository.AnomalyEventRepository;
import com.smartfirehub.proactive.repository.ProactiveJobExecutionRepository;
import com.smartfirehub.proactive.repository.ProactiveJobRepository;
import com.smartfirehub.proactive.repository.ReportTemplateRepository;
import com.smartfirehub.proactive.util.ProactiveCron;
import com.smartfirehub.proactive.util.ProactiveTime;
import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.ClearanceResolver;
import com.smartfirehub.securitylevel.access.DatasetAccessPolicy;
import com.smartfirehub.securitylevel.access.LevelPolicy;
import com.smartfirehub.securitylevel.ai.PolicyBlockedException;
import com.smartfirehub.securitylevel.dto.SecurityLevelSummary;
import com.smartfirehub.tenant.repository.TenantRepository;
import com.smartfirehub.user.repository.UserRepository;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.support.CronExpression;
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
  private final MetricSqlAccessChecker metricSqlAccessChecker;
  // 작업에 연결할 리포트 양식이 작성자가 쓸 수 있는 것(기본·본인)인지 확인한다(WD-163)
  private final ReportTemplateRepository reportTemplateRepository;
  // 이상 탐지 알림을 보내기 전에 작업 주인이 아직 자격이 있는지 본다(WD-166)
  private final ProactiveOwnerEligibility ownerEligibility;
  // 이상 탐지 처리 전에 워크스페이스(테넌트)가 정지됐는지 본다(WD-174)
  private final TenantRepository tenantRepository;
  // 리포트 열람 시 작성자의 현재 보안 등급을 계산한다(WD-198)
  private final ClearanceResolver clearanceResolver;
  // 스마트 작업 실행 중 AI 가 이전 리포트 본문을 읽으면 그 등급도 새 리포트 등급에 넣는다(WD-198)
  private final ProactiveReportLevelRecorder levelRecorder;

  /** 리포트 등급이 작성자의 현재 등급보다 높아 열 수 없을 때의 코드(WD-198). 웹이 이 코드로 안내 화면을 고른다. */
  public static final String REPORT_LEVEL_BLOCKED_CODE = "REPORT_SECURITY_LEVEL_BLOCKED";

  /** 리포트 등급 차단 문구(WD-198) — 사람이 정한 문구 그대로. */
  public static final String REPORT_LEVEL_BLOCKED_MESSAGE = "현재 보안 등급으로 열 수 없는 리포트입니다";

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
      ProactiveJobAsyncRunner asyncRunner,
      MetricSqlAccessChecker metricSqlAccessChecker,
      ReportTemplateRepository reportTemplateRepository,
      ProactiveOwnerEligibility ownerEligibility,
      TenantRepository tenantRepository,
      ClearanceResolver clearanceResolver,
      ProactiveReportLevelRecorder levelRecorder) {
    this.proactiveJobRepository = proactiveJobRepository;
    this.executionRepository = executionRepository;
    this.schedulerService = schedulerService;
    this.userRepository = userRepository;
    this.anomalyEventRepository = anomalyEventRepository;
    this.sseEmitterRegistry = sseEmitterRegistry;
    this.asyncRunner = asyncRunner;
    this.metricSqlAccessChecker = metricSqlAccessChecker;
    this.reportTemplateRepository = reportTemplateRepository;
    this.ownerEligibility = ownerEligibility;
    this.tenantRepository = tenantRepository;
    this.clearanceResolver = clearanceResolver;
    this.levelRecorder = levelRecorder;
    // asyncRunner가 runningJobs 맵을 공유하여 슬롯 해제가 동일한 맵에 반영되도록 한다
    asyncRunner.setRunningJobs(this.runningJobs);
  }

  /**
   * cronExpression / timezone 값을 Spring 스케줄러가 실제로 사용하는 파서로 사전 검증한다.
   *
   * <p>둘 다 nullable / blank 허용 — 값이 비어 있으면 스케줄에 등록되지 않을 뿐 저장은 정상 처리된다. 값이 들어 있으면 다음 조건을 만족해야 한다.
   *
   * <ul>
   *   <li>{@code cronExpression}: {@link CronExpression#parse} 통과 (6필드: 초 분 시 일 월 요일)
   *   <li>{@code timezone}: {@link ZoneId#of} 통과 (IANA zone)
   * </ul>
   *
   * 실패 시 {@link ProactiveJobException} 을 던지며, {@code GlobalExceptionHandler} 가 400 으로 매핑한다. 기존 동작은
   * 잘못된 값을 그대로 저장한 뒤 스케줄러 catch-all 에서 조용히 실패시키는 것이었는데, 이로 인해 사용자에게는 정상 생성처럼 보이지만 실제로는 한 번도 실행되지 않는
   * "좀비 잡" 이 발생했다 (#221).
   *
   * @param cronExpression 검증할 cron 식 (nullable)
   * @param timezone 검증할 IANA timezone 문자열 (nullable)
   * @throws ProactiveJobException 형식이 유효하지 않을 때
   */
  static void validateCronAndTimezone(String cronExpression, String timezone) {
    if (cronExpression != null && !cronExpression.isBlank()) {
      // 등록 경로(ProactiveJobSchedulerService)와 같은 정규화를 거쳐 검증한다 (#354).
      // 원시 문자열로 검증하면 5필드를 거부하는데, 정작 작업 편집 폼의 CRON_PRESETS 는
      // 5필드('0 9 * * *')를 보내므로 UI 프리셋으로 만든 작업이 400 으로 막혔다.
      // 실패 사유는 Spring 의 영어·6필드 기준 문구 대신 화면 안내(5칸)와 같은 한국어로 알린다 (WD-138).
      String reason = ProactiveCron.describeInvalid(cronExpression);
      if (reason != null) {
        throw new ProactiveJobException(
            "유효하지 않은 cron 표현식입니다: \"" + cronExpression.trim() + "\" — " + reason);
      }
      // 파이프라인 트리거와 같은 최소 실행 간격(1분) — 매초 리포트 생성이 끝없이 이어지지 않게 한다(WD-366).
      String tooFrequent = ProactiveCron.describeTooFrequent(cronExpression);
      if (tooFrequent != null) {
        throw new ProactiveJobException(tooFrequent + ": \"" + cronExpression.trim() + "\"");
      }
    }
    if (timezone != null && !timezone.isBlank()) {
      try {
        ZoneId.of(timezone);
      } catch (DateTimeException e) {
        throw new ProactiveJobException(
            "유효하지 않은 timezone: \"" + timezone + "\" — IANA zone ID 가 아닙니다");
      }
    }
  }

  @Transactional(readOnly = true)
  public List<ProactiveJobResponse> getJobs(Long userId) {
    return withOwnerEligibility(
        withLastExecutions(proactiveJobRepository.findByUserId(userId)), userId);
  }

  /**
   * 작업 주인의 실행 자격 판정을 붙인다(WD-192).
   *
   * <p>여기 오는 작업은 모두 요청자 본인 것이라 주인이 한 명뿐이다 — 판정을 한 번만 하고 모든 행에 같은 값을 싣는다(작업 수만큼 조회하지 않는다). 판정은 실행기가
   * 실행 직전에 보는 것과 같은 {@link ProactiveOwnerEligibility} 다.
   */
  private List<ProactiveJobResponse> withOwnerEligibility(
      List<ProactiveJobResponse> jobs, Long ownerId) {
    if (jobs.isEmpty()) {
      return jobs;
    }
    var code = ownerEligibility.findIneligibilityCode(ownerId);
    return jobs.stream().map(job -> job.withOwnerEligibility(code)).toList();
  }

  /**
   * 요청자가 이 작업의 주인인지 확인한다 — 남의 작업이거나 없는 작업이면 조회와 같은 404 (WD-164, 수정·삭제는 WD-165).
   *
   * <p>「지금 실행」은 실행 슬롯을 잡고 비동기 실행(실행 행 생성 → 소유자 확인)으로 넘긴 뒤 202 를 돌려준다. 소유자 확인이 비동기 쪽에만 있으면 남의 작업
   * 번호로도 202 가 나가고, 그 작업 주인의 실행 이력에 「Job을 찾을 수 없습니다」 실패가 쌓이며 그동안 주인의 실행이 409 로 막힐 수 있었다. 그래서 슬롯·실행
   * 행보다 먼저 동기로 확인한다.
   */
  @Transactional(readOnly = true)
  public void requireOwnJob(Long id, Long userId) {
    if (proactiveJobRepository.findById(id, userId).isEmpty()) {
      throw new ProactiveJobNotFoundException("Proactive Job을 찾을 수 없습니다: " + id);
    }
  }

  @Transactional(readOnly = true)
  public ProactiveJobResponse getJob(Long id, Long userId) {
    // 존재하지 않는 Job 조회 시 404 반환 (#41)
    ProactiveJobResponse job =
        proactiveJobRepository
            .findById(id, userId)
            .orElseThrow(
                () -> new ProactiveJobNotFoundException("Proactive Job을 찾을 수 없습니다: " + id));
    return withOwnerEligibility(withLastExecutions(List.of(job)), userId).get(0);
  }

  /**
   * 잡마다 가장 최근 실행(상태·오류 문구)을 붙인다 (WD-134).
   *
   * <p>화면은 이 값으로 목록의 실패 표시와 상세 「마지막 실행 상태」를 그린다. 잡 행과 실행 이력은 다른 테이블이라 잡 조회 뒤 한 번에 모아 읽는다 (잡 수만큼 따로
   * 조회하지 않는다).
   */
  private List<ProactiveJobResponse> withLastExecutions(List<ProactiveJobResponse> jobs) {
    Map<Long, ProactiveJobExecutionResponse> latest =
        executionRepository.findLatestByJobIds(
            jobs.stream().map(ProactiveJobResponse::id).toList());
    return jobs.stream().map(job -> job.withLastExecution(latest.get(job.id()))).toList();
  }

  /**
   * 작업에 연결하려는 리포트 양식이 작성자가 쓸 수 있는 양식(기본·본인)인지 확인한다(WD-163). 남의 양식 id 는 없는 양식과 같이 거절해 존재 여부도 드러내지
   * 않는다. templateId 가 없으면(기본 형식) 검사하지 않는다.
   */
  private void requireUsableTemplate(Long templateId, Long userId) {
    if (templateId != null
        && reportTemplateRepository.findVisibleTo(templateId, userId).isEmpty()) {
      throw new ProactiveJobException("리포트 양식을 찾을 수 없습니다: " + templateId);
    }
  }

  @Transactional
  public ProactiveJobResponse createJob(CreateProactiveJobRequest request, Long userId) {
    // cronExpression / timezone 형식을 사전 검증한다. 잘못된 값을 그대로 저장하면 스케줄러가 silent fail 하여
    // 사용자에게는 정상 생성처럼 보이지만 실제 실행이 한 번도 일어나지 않는 좀비 상태가 된다 (#221).
    validateCronAndTimezone(request.cronExpression(), request.timezone());
    // 보안 등급(S2): 메트릭 SQL 이 작성자가 볼 수 없는 데이터셋을 참조하면 거부(스펙 §4.2 6행). update 도 같은 검사 — 우회로 차단.
    metricSqlAccessChecker.requireMetricQueriesAllowed(request.config(), userId);
    requireUsableTemplate(request.templateId(), userId);

    Long id =
        proactiveJobRepository.create(
            userId,
            request.name(),
            request.prompt(),
            request.templateId(),
            request.cronExpression(),
            request.timezone(),
            request.enabled(),
            request.triggerType(),
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
    return withOwnerEligibility(List.of(job), userId).get(0);
  }

  @Transactional
  public void updateJob(Long id, UpdateProactiveJobRequest request, Long userId) {
    // 남의 작업·없는 작업은 404 — 저장소의 id+user_id 조건 UPDATE 는 0행이어도 조용히 지나가 204(무동작)가 나갔다 (WD-165)
    requireOwnJob(id, userId);
    // 업데이트 요청에 포함된 cronExpression / timezone 도 동일하게 사전 검증한다 (#221).
    validateCronAndTimezone(request.cronExpression(), request.timezone());
    // 보안 등급(S2): 메트릭 SQL 이 작성자가 볼 수 없는 데이터셋을 참조하면 거부(스펙 §4.2 6행). update 도 같은 검사 — 우회로 차단.
    metricSqlAccessChecker.requireMetricQueriesAllowed(request.config(), userId);
    requireUsableTemplate(request.templateId(), userId);

    proactiveJobRepository.update(
        id,
        userId,
        request.name(),
        request.prompt(),
        request.templateId(),
        Boolean.TRUE.equals(request.clearTemplate()),
        request.cronExpression(),
        request.timezone(),
        request.enabled(),
        request.triggerType(),
        request.config());
    ProactiveJobResponse updated = proactiveJobRepository.findById(id, userId).orElse(null);
    if (updated != null) {
      schedulerService.rescheduleJob(updated);
    }
  }

  @Transactional
  public void deleteJob(Long id, Long userId) {
    // 소유자 확인을 스케줄 해제보다 먼저 한다 (WD-165) — 예전엔 남의 삭제 요청이 행은 못 지우면서(204) 주인 작업의 예약 실행만 해제해, 작업은
    // 「켜짐」으로 남은 채 서버 재시작 전까지 돌지 않았다.
    requireOwnJob(id, userId);
    schedulerService.unregisterSchedule(id);
    proactiveJobRepository.delete(id, userId);
  }

  /**
   * 내 작업 끄기(WD-192) — 컨트롤러가 proactive:read 만으로 허용하는 유일한 상태 변경이다.
   *
   * <p>작성자가 스마트 작업 쓰기 권한을 잃으면 작업은 「활성」인 채 실행 때마다 건너뛰는데(WD-166), 끄기·삭제까지 쓰기 권한을 요구하면 본인조차 정리할 수 없었다.
   * 그래서 끄기는 읽기 권한으로 열되 <b>끄기만</b> 한다 — 켜기는 자동 실행을 늘리므로 여전히 쓰기 권한이 필요한 PUT 으로만 한다. 남의 작업·없는 작업은
   * 404(WD-165 와 같다).
   */
  @Transactional
  public void disableOwnJob(Long id, Long userId) {
    requireOwnJob(id, userId);
    toggleJob(id, userId, false);
  }

  @Transactional
  public void toggleJob(Long id, Long userId, boolean enabled) {
    // 켜기만 바뀌므로(cron·시간대 그대로) 한 번 읽은 잡으로 검증·재등록한다.
    ProactiveJobResponse job = proactiveJobRepository.findById(id, userId).orElse(null);
    if (enabled && job != null) {
      // 켜기 전에 저장된 cron 을 저장 때와 같은 규칙으로 확인한다(WD-366) — 매초처럼 너무 잦은 옛 잡을 다시 켜지 못하게 400 으로 알린다.
      validateCronAndTimezone(job.cronExpression(), job.timezone());
    }
    proactiveJobRepository.update(
        id, userId, null, null, null, false, null, null, enabled, null, null);
    if (job != null) {
      if (enabled && job.cronExpression() != null && !job.cronExpression().isBlank()) {
        schedulerService.registerSchedule(job.id(), job.cronExpression(), job.timezone());
      } else {
        schedulerService.unregisterSchedule(id);
      }
    }
  }

  @Transactional(readOnly = true)
  public List<ProactiveJobExecutionSummaryResponse> getExecutions(
      Long jobId, Long userId, int limit, int offset) {
    // job 소유권 확인 — 실행 이력 탭이 실행 중 5초마다 부르므로 최근 실행까지 붙이는 getJob 대신 잡 행만 확인한다
    proactiveJobRepository
        .findById(jobId, userId)
        .orElseThrow(() -> new ProactiveJobNotFoundException("Proactive Job을 찾을 수 없습니다: " + jobId));
    // 목록은 경량 뷰(result 미포함)를 반환한다 — 리포트 본문은 get_execution(단건)으로 조회 (#604)
    return executionRepository.findSummariesByJobId(jobId, limit, offset);
  }

  @Transactional(readOnly = true)
  public ProactiveJobExecutionResponse getExecution(Long executionId) {
    return executionRepository
        .findById(executionId)
        .orElseThrow(() -> new ProactiveJobException("Execution을 찾을 수 없습니다: " + executionId));
  }

  /**
   * 리포트 전문(실행 상세·HTML·PDF) 열람 판정(WD-198). 리포트 등급이 있고 작성자의 <b>현재</b> 최대 등급 rank 가 그보다 낮으면 403 + 「현재
   * 보안 등급으로 열 수 없는 리포트입니다」.
   *
   * <p>등급이 null(이 기능 이전의 리포트·등급 데이터를 읽지 않은 리포트)이면 지금처럼 열린다(사람이 고른 설계 D). 비교 기준은 데이터셋 열람과 같은
   * security_level.rank 이며 등급의 rank 는 조회 시점 값을 쓴다(순서 변경 반영). 역할이 하나도 없는 작성자(NO_RANK)는 등급 있는 리포트를 모두
   * 못 연다(fail-closed). 리포트 전문은 작성자만 열 수 있으므로(WD-162) 판정 주체는 작성자다.
   *
   * @return 판정에 쓴 작성자 자격(등급이 null 이면 null) — PDF 판정이 재사용한다
   */
  public Clearance requireReportViewable(
      ProactiveJobExecutionResponse execution, Long ownerUserId) {
    SecurityLevelSummary level = execution.securityLevel();
    if (level == null) {
      return null;
    }
    Clearance c = clearanceResolver.resolve(ownerUserId);
    if (c.rank() < level.rank()) {
      throw new CodedApiException(
          HttpStatus.FORBIDDEN, REPORT_LEVEL_BLOCKED_CODE, REPORT_LEVEL_BLOCKED_MESSAGE);
    }
    // 열람 허용 = 리포트 본문이 호출자에게 간다. 스마트 작업 실행 중의 AI 호출(get_execution 등)이면 이 리포트 등급을 지금 실행의 리포트
    // 등급에 넣는다 — 이전 리포트 내용이 새 리포트로 옮겨 가는 경로(WD-198). 실행에 묶이지 않은 요청이면 아무 일도 없다.
    levelRecorder.noteLevelId(level.id());
    return c;
  }

  /**
   * 리포트 PDF 내려받기 판정(WD-198) — 열람 판정 + 리포트 등급의 내보내기 정책(export_policy). 데이터셋 내보내기({@code
   * DatasetAccessGuard#requireExport})와 같은 규칙·문구·응답 형태(403 POLICY_BLOCKED, errors 에
   * action·levelName·policyKey)다: PERMISSION 정책은 제한 데이터 내보내기 권한(data:export_restricted) 보유자만, DENY
   * 는 누구도 못 받는다. 등급이 null 이면 판정할 데이터가 없어 지금처럼 내려받는다.
   */
  public void requireReportExportable(ProactiveJobExecutionResponse execution, Long ownerUserId) {
    Clearance c = requireReportViewable(execution, ownerUserId);
    SecurityLevelSummary level = execution.securityLevel();
    if (level == null) {
      return;
    }
    LevelPolicy.ExportPolicy policy = LevelPolicy.ExportPolicy.valueOf(level.exportPolicy());
    if (DatasetAccessPolicy.exportPolicyAllows(policy, c.permissions())) {
      return;
    }
    String message =
        policy == LevelPolicy.ExportPolicy.PERMISSION
            ? "'" + level.name() + "' 등급 데이터를 내보내려면 제한 데이터 내보내기 권한이 필요합니다."
            : "'" + level.name() + "' 등급 데이터는 내보낼 수 없습니다.";
    throw new CodedApiException(
        HttpStatus.FORBIDDEN,
        PolicyBlockedException.CODE,
        message,
        Map.of("action", "EXPORT", "levelName", level.name(), "policyKey", "export_policy"));
  }

  /**
   * 사용자가 소유한 모든 스마트 작업의 리포트를 잡 횡단으로 조회한다.
   *
   * <p>잡 단위 조회와 달리 getJob() 소유권 검증을 거치지 않으므로, 스코핑은 리포지토리 쿼리의 user_id 조건이 전담한다.
   */
  @Transactional(readOnly = true)
  public List<ReportListItemResponse> getReports(Long userId, int limit, int offset) {
    List<ReportListItemResponse> reports =
        executionRepository.findReportsByUserId(userId, limit, offset);
    // 목록의 한 줄 요약도 리포트 본문의 일부다(WD-198) — 작성자의 현재 등급으로 열 수 없는 리포트는 요약을 비운다. 제목·등급 배지는 남겨 「등급 때문에
    // 열 수 없는 리포트가 있다」는 것은 알 수 있게 한다. 등급 있는 리포트가 없으면 자격 계산도 하지 않는다.
    if (reports.stream().noneMatch(r -> r.securityLevel() != null)) {
      return reports;
    }
    int rank = clearanceResolver.resolve(userId).rank();
    return reports.stream()
        .map(
            r ->
                r.securityLevel() != null && rank < r.securityLevel().rank()
                    ? new ReportListItemResponse(
                        r.executionId(),
                        r.jobId(),
                        r.jobName(),
                        r.title(),
                        null,
                        r.completedAt(),
                        r.securityLevel())
                    : r)
        .toList();
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

  /**
   * 알림 수신자 후보 검색. <b>현재 테넌트 멤버만</b> 나온다 — {@code "user"} 는 전역 테이블이라 RLS 가 걸러 주지 못하므로, 좁히지 않으면 이 피커가
   * 다른 테넌트 사용자의 이름·이메일을 그대로 노출한다.
   */
  @Transactional(readOnly = true)
  /**
   * 알림 수신자 검색/조회.
   *
   * <p>{@code userIds}가 주어지면 검색어를 무시하고 해당 ID들의 사용자 정보를 그대로 반환한다 (#555) — 저장된 채널 수신자를 편집 화면 재오픈 시
   * 이름/이메일로 복원하는 용도. 검색 없이 벌크 조회이므로 20건 제한을 두지 않는다.
   */
  public List<RecipientResponse> searchRecipients(String search, List<Long> userIds) {
    long tenantId = TenantContext.require("알림 수신자 검색");
    if (userIds != null && !userIds.isEmpty()) {
      return userRepository.findByIds(tenantId, userIds).stream()
          .map(u -> new RecipientResponse(u.id(), u.name(), u.email()))
          .toList();
    }
    return userRepository.findAllPaginated(tenantId, search, 0, 20).stream()
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
    // 워크스페이스 정지 관문(WD-174) — 이상 이벤트 저장·SSE 알림·실행보다 먼저 본다. 지표 폴러(MetricPollerService)는 ACTIVE 테넌트만
    // 돌아
    // 정지 워크스페이스에서는 이 이벤트가 새로 생기지 않지만, 폴링 도중 정지되는 경합에서도 AI 호출·전달이 시작되지 않게 같은 규칙을 둔다.
    // 실행 행·알림 없이 log.info 한 줄로 끝낸다 — 정지는 운영자가 의도한 상태다.
    long tenantId = TenantContext.require("이상 탐지 실행 워크스페이스 상태 확인");
    if (!tenantRepository.isActive(tenantId)) {
      log.info(
          "Anomaly for job {} ignored: tenant {} is not active (metric={})",
          event.jobId(),
          tenantId,
          event.metricName());
      return;
    }
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
    // 저장 실패가 job 실행을 막지 않도록 예외를 포획한다.
    // ERROR 로 남기는 이유(P2-e): anomaly_event 는 V103/V104 로 tenant_id + RLS 대상이 됐다.
    // 이 리스너는 @Async 스레드라 테넌트 컨텍스트·트랜잭션 배선이 어긋나면 NOT NULL 위반으로
    // 전부 실패하는데, WARN 한 줄에 묻히면 이상탐지 이력이 통째로 비어 가는 것을 아무도 모른다.
    // 스택트레이스도 함께 남긴다 — 메시지만으로는 RLS 위반과 다른 원인을 구분할 수 없다.
    try {
      anomalyEventRepository.save(event);
    } catch (Exception e) {
      log.error("Failed to save anomaly event for job {}: {}", event.jobId(), e.getMessage(), e);
    }

    // 해당 사용자에게 SSE를 통해 실시간 이상 탐지 알림을 전송한다
    // 연결이 없거나 전송 실패해도 job 실행에는 영향을 주지 않는다
    // 정지·제거·권한 회수된 주인에게는 보내지 않는다(WD-166) — 실행 자체는 아래 실행기가 건너뛰며 이력에 사유를 남긴다
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
              ProactiveTime.nowUtc());
      if (ownerEligibility.findIneligibilityReason(event.userId()).isEmpty()) {
        sseEmitterRegistry.broadcast(event.userId(), notification);
      } else {
        log.info(
            "Anomaly notification skipped for job {} (owner no longer eligible)", event.jobId());
      }
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
      // 감지된 이상을 함께 넘겨 리포트가 무엇 때문에 왔는지 설명하게 한다(WD-171)
      asyncRunner.executeJob(event.jobId(), event.userId(), event);
      recordCooldown(event.jobId());
    } catch (Exception e) {
      log.warn("Anomaly-triggered execution failed for job {}: {}", event.jobId(), e.getMessage());
    }
  }

  private boolean isInCooldown(Long jobId) {
    LocalDateTime lastExec = lastAnomalyExecution.get(jobId);
    if (lastExec == null) return false;

    int cooldownMinutes = getCooldownMinutes(jobId);
    return ProactiveTime.nowUtc().isBefore(lastExec.plusMinutes(cooldownMinutes));
  }

  private void recordCooldown(Long jobId) {
    lastAnomalyExecution.put(jobId, ProactiveTime.nowUtc());
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
