package com.smartfirehub.proactive.service;

import com.smartfirehub.audit.service.AuditLogService;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.proactive.dto.ProactiveJobAdminSummaryResponse;
import com.smartfirehub.proactive.dto.ProactiveJobExecutionResponse;
import com.smartfirehub.proactive.exception.ProactiveJobNotFoundException;
import com.smartfirehub.proactive.repository.ProactiveJobExecutionRepository;
import com.smartfirehub.proactive.repository.ProactiveJobRepository;
import com.smartfirehub.proactive.repository.ProactiveJobRepository.WorkspaceJobRow;
import com.smartfirehub.user.dto.UserResponse;
import com.smartfirehub.user.repository.UserRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 워크스페이스 스마트 작업 관리(proactive:admin) — WD-192.
 *
 * <p>스마트 작업은 작성자 한정이라, 작성자가 정지·제거되거나 쓰기 권한을 잃으면(작업은 「활성」인 채 실행 때마다 건너뛴다 — WD-166) 아무도 그 작업을 정리할 수
 * 없었다. 이 서비스는 관리자가 워크스페이스 전체 작업을 <b>최소 정보로</b> 보고 <b>끄거나 삭제</b>만 하게 한다.
 *
 * <ul>
 *   <li>열람(상세)·편집·켜기·지금 실행은 여기 없다 — 작성자의 데이터 권한으로 만든 내용(프롬프트·지표 SQL·리포트)이 관리자에게 새지 않게, 그리고 자격 없는
 *       사람이 남의 이름으로 자동 실행을 늘리지 않게 한다.
 *   <li>자격을 잃었다고 자동으로 끄지 않는다(WD-166 결정 유지) — 끌지는 사람이 정한다.
 *   <li>워크스페이스 경계: 모든 조회·변경에 현재 테넌트 tenant_id 를 명시한다(RLS 와 이중). 다른 워크스페이스 작업은 404.
 *   <li>끄기·삭제는 감사 로그를 남긴다(남의 작업을 바꾸는 관리 행위라 누가 했는지 추적해야 한다).
 * </ul>
 *
 * <p>{@link ProactiveJobService} 와 분리한 이유: 그 서비스는 테스트가 생성자를 직접 부르고(AnomalyTriggeredExecutionTest),
 * 관리 경로는 감사 기록 의존이 따로 있어 한 클래스에 섞으면 작성자 경로 변경과 관리자 경로 변경이 서로를 흔든다.
 */
@Service
public class ProactiveJobAdminService {

  /** 감사 로그 action — 관리자가 남의 작업을 끔. */
  public static final String ACTION_ADMIN_DISABLE = "PROACTIVE_JOB_ADMIN_DISABLE";

  /** 감사 로그 action — 관리자가 남의 작업을 삭제. */
  public static final String ACTION_ADMIN_DELETE = "PROACTIVE_JOB_ADMIN_DELETE";

  /** 감사 로그 resource. */
  static final String AUDIT_RESOURCE = "proactive_job";

  private final ProactiveJobRepository proactiveJobRepository;
  private final ProactiveJobExecutionRepository executionRepository;
  private final ProactiveJobSchedulerService schedulerService;
  private final ProactiveOwnerEligibility ownerEligibility;
  private final AuditLogService auditLogService;
  private final UserRepository userRepository;

  public ProactiveJobAdminService(
      ProactiveJobRepository proactiveJobRepository,
      ProactiveJobExecutionRepository executionRepository,
      // 스케줄러는 ProactiveJobService 와 순환 의존이 있어 같은 방식으로 지연 주입한다
      @Lazy ProactiveJobSchedulerService schedulerService,
      ProactiveOwnerEligibility ownerEligibility,
      AuditLogService auditLogService,
      UserRepository userRepository) {
    this.proactiveJobRepository = proactiveJobRepository;
    this.executionRepository = executionRepository;
    this.schedulerService = schedulerService;
    this.ownerEligibility = ownerEligibility;
    this.auditLogService = auditLogService;
    this.userRepository = userRepository;
  }

  /** 워크스페이스 전체 작업 목록(최소 정보). 질의 수는 작업 수와 무관하다 — 작업+작성자 이름 1회, 자격 판정 배치(멤버십 1회·권한 1회), 최근 실행 1회. */
  @Transactional(readOnly = true)
  public List<ProactiveJobAdminSummaryResponse> getWorkspaceJobs() {
    long tenantId = TenantContext.require("워크스페이스 스마트 작업 목록");
    List<WorkspaceJobRow> rows = proactiveJobRepository.findWorkspaceRows(tenantId);
    if (rows.isEmpty()) {
      return List.of();
    }
    Map<Long, Optional<String>> eligibility =
        ownerEligibility.findIneligibilityCodes(
            rows.stream().map(WorkspaceJobRow::ownerId).toList());
    // 최근 실행은 상태만 쓴다 — 오류 문구·전달 채널은 작업 내용에 가까워 싣지 않는다
    Map<Long, ProactiveJobExecutionResponse> latest =
        executionRepository.findLatestByJobIds(rows.stream().map(WorkspaceJobRow::id).toList());
    return rows.stream()
        .map(
            row -> {
              Optional<String> code = eligibility.getOrDefault(row.ownerId(), Optional.empty());
              ProactiveJobExecutionResponse last = latest.get(row.id());
              return new ProactiveJobAdminSummaryResponse(
                  row.id(),
                  row.name(),
                  row.ownerName(),
                  row.enabled(),
                  code.isEmpty(),
                  code.orElse(null),
                  row.nextExecuteAt(),
                  row.timezone(),
                  last != null ? last.status() : null);
            })
        .toList();
  }

  /**
   * 관리자 끄기 — 작성자와 무관하게 이 워크스페이스 작업을 끈다. 이미 꺼져 있어도 성공(멱등)이며 감사는 남긴다. 예약 실행도 함께 해제한다(행만 바꾸고 예약을 두면 서버
   * 재시작 전까지 계속 발화한다).
   */
  @Transactional
  public void disableJob(Long id, Long adminUserId) {
    long tenantId = TenantContext.require("스마트 작업 관리자 끄기");
    WorkspaceJobRow job = requireWorkspaceJob(id, tenantId);
    proactiveJobRepository.disableInTenant(id, tenantId);
    schedulerService.unregisterSchedule(id);
    audit(adminUserId, ACTION_ADMIN_DISABLE, job, "스마트 작업 관리자 끄기: " + job.name());
  }

  /** 관리자 삭제 — 예약 실행을 먼저 해제하고 행을 지운다(WD-165 와 같은 순서). */
  @Transactional
  public void deleteJob(Long id, Long adminUserId) {
    long tenantId = TenantContext.require("스마트 작업 관리자 삭제");
    WorkspaceJobRow job = requireWorkspaceJob(id, tenantId);
    schedulerService.unregisterSchedule(id);
    proactiveJobRepository.deleteInTenant(id, tenantId);
    audit(adminUserId, ACTION_ADMIN_DELETE, job, "스마트 작업 관리자 삭제: " + job.name());
  }

  /** 이 워크스페이스 작업이 아니면(없음·다른 워크스페이스) 작성자 경로와 같은 404. */
  private WorkspaceJobRow requireWorkspaceJob(Long id, long tenantId) {
    return proactiveJobRepository
        .findWorkspaceRow(id, tenantId)
        .orElseThrow(() -> new ProactiveJobNotFoundException("Proactive Job을 찾을 수 없습니다: " + id));
  }

  /**
   * 감사 로그(resource = proactive_job, resourceId = 작업 id). 행위자 username 은 감사 테이블 NOT NULL 이라 조회해서 넣는다
   * — RoleService.audit 와 같은 방식. 메타데이터에는 누구의 어떤 작업이었는지만 남기고 작업 내용은 싣지 않는다.
   */
  private void audit(Long adminUserId, String action, WorkspaceJobRow job, String description) {
    String adminName =
        userRepository.findById(adminUserId).map(UserResponse::username).orElse("unknown");
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("jobName", job.name());
    metadata.put("ownerUserId", job.ownerId());
    metadata.put("ownerName", job.ownerName());
    metadata.put("wasEnabled", job.enabled());
    auditLogService.log(
        adminUserId,
        adminName,
        action,
        AUDIT_RESOURCE,
        String.valueOf(job.id()),
        description,
        null,
        null,
        "SUCCESS",
        null,
        metadata);
  }
}
