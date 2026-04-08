package com.smartfirehub.job.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.auth.dto.SignupRequest;
import com.smartfirehub.auth.service.AuthService;
import com.smartfirehub.job.dto.AsyncJobStatusResponse;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.user.dto.UserResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * AsyncJobService 통합 테스트. 핵심 메서드 7개(createJob, updateProgress, completeJob, failJob, subscribe,
 * getJobStatus, hasActiveJob/findActiveJobs)의 정상/예외 케이스를 검증한다.
 */
@Transactional
class AsyncJobServiceTest extends IntegrationTestBase {

  @Autowired private AsyncJobService asyncJobService;
  @Autowired private AuthService authService;

  private Long userId;

  /** 각 테스트 전 테스트 사용자를 생성한다. */
  @BeforeEach
  void setUp() {
    UserResponse user =
        authService.signup(
            new SignupRequest("jobTestUser", "jobtest@example.com", "Password123", "Job Tester"));
    userId = user.id();
  }

  // ──────────────────────────────────────────────
  // createJob
  // ──────────────────────────────────────────────

  /** createJob 성공: jobId가 반환되고 DB에서 PENDING 상태로 조회된다. */
  @Test
  void createJob_success_returnsJobId() {
    String jobId = asyncJobService.createJob("IMPORT", "dataset", "ds-1", userId, null);

    assertThat(jobId).isNotBlank();

    // getJobStatus로 DB 저장 검증
    AsyncJobStatusResponse status = asyncJobService.getJobStatus(jobId, userId);
    assertThat(status.jobId()).isEqualTo(jobId);
    assertThat(status.stage()).isEqualTo("PENDING");
    assertThat(status.progress()).isEqualTo(0);
    assertThat(status.userId()).isEqualTo(userId);
  }

  /** createJob: metadata가 null이어도 예외 없이 생성된다. */
  @Test
  void createJob_withNullMetadata_success() {
    String jobId = asyncJobService.createJob("PIPELINE", "pipeline", "p-1", userId, null);
    assertThat(jobId).isNotBlank();
  }

  /** createJob: metadata가 있을 경우 저장된다. */
  @Test
  void createJob_withMetadata_persisted() {
    Map<String, Object> meta = Map.of("fileName", "data.csv", "rows", 500);
    String jobId = asyncJobService.createJob("IMPORT", "dataset", "ds-2", userId, meta);

    AsyncJobStatusResponse status = asyncJobService.getJobStatus(jobId, userId);
    assertThat(status.metadata()).containsKey("fileName");
  }

  // ──────────────────────────────────────────────
  // updateProgress
  // ──────────────────────────────────────────────

  /** updateProgress 성공: DB 쓰로틀링 간격(5회)으로 인해 매 호출마다 DB에 저장되지 않을 수 있으나, 스테이지 변경 시에는 즉시 저장된다. */
  @Test
  void updateProgress_stageChange_persistedImmediately() {
    String jobId = asyncJobService.createJob("IMPORT", "dataset", "ds-3", userId, null);

    // 스테이지 변경 → 즉시 DB 저장
    asyncJobService.updateProgress(jobId, "PROCESSING", 30, "Processing...", null);

    AsyncJobStatusResponse status = asyncJobService.getJobStatus(jobId, userId);
    assertThat(status.stage()).isEqualTo("PROCESSING");
    assertThat(status.progress()).isEqualTo(30);
    assertThat(status.message()).isEqualTo("Processing...");
  }

  /** updateProgress: DB_UPDATE_INTERVAL(5) 번째 호출에서도 DB에 저장된다. */
  @Test
  void updateProgress_atInterval_persistedToDB() {
    String jobId = asyncJobService.createJob("IMPORT", "dataset", "ds-4", userId, null);

    // 첫 호출로 스테이지 변경 저장 후, 같은 스테이지로 4번 더 호출 (총 5번 = DB_UPDATE_INTERVAL)
    asyncJobService.updateProgress(jobId, "RUNNING", 10, "Step 1", null);
    asyncJobService.updateProgress(jobId, "RUNNING", 20, "Step 2", null);
    asyncJobService.updateProgress(jobId, "RUNNING", 30, "Step 3", null);
    asyncJobService.updateProgress(jobId, "RUNNING", 40, "Step 4", null);
    asyncJobService.updateProgress(jobId, "RUNNING", 50, "Step 5", null);

    AsyncJobStatusResponse status = asyncJobService.getJobStatus(jobId, userId);
    // 5번째 호출(count % 5 == 0)에서 DB 저장 → progress 50이어야 함
    assertThat(status.progress()).isEqualTo(50);
  }

  // ──────────────────────────────────────────────
  // completeJob
  // ──────────────────────────────────────────────

  /** completeJob 성공: DB에 COMPLETED / progress=100으로 저장된다. */
  @Test
  void completeJob_success_stageIsCompleted() {
    String jobId = asyncJobService.createJob("IMPORT", "dataset", "ds-5", userId, null);

    asyncJobService.completeJob(jobId, Map.of("result", "ok"));

    AsyncJobStatusResponse status = asyncJobService.getJobStatus(jobId, userId);
    assertThat(status.stage()).isEqualTo("COMPLETED");
    assertThat(status.progress()).isEqualTo(100);
    assertThat(status.message()).isEqualTo("Completed");
  }

  // ──────────────────────────────────────────────
  // failJob
  // ──────────────────────────────────────────────

  /** failJob 성공: DB에 FAILED 상태로 저장되고, 마지막 progress가 함께 persist된다. (SSE 이벤트와 REST 폴백 응답 일치 검증) */
  @Test
  void failJob_progressPersistedToDb() {
    String jobId = asyncJobService.createJob("IMPORT", "dataset", "ds-6", userId, null);
    // progress 60까지 진행 후 실패
    asyncJobService.updateProgress(jobId, "PROCESSING", 60, "Almost done", null);

    asyncJobService.failJob(jobId, "Unexpected error");

    AsyncJobStatusResponse status = asyncJobService.getJobStatus(jobId, userId);
    assertThat(status.stage()).isEqualTo("FAILED");
    // lastProgress(60)가 DB에 persist되어 REST 폴백 응답에도 반영되어야 한다
    assertThat(status.progress()).isEqualTo(60);
    assertThat(status.errorMessage()).isEqualTo("Unexpected error");
  }

  /** failJob: 진행 없이 실패 시 progress=0이 유지된다. */
  @Test
  void failJob_withoutPriorProgress_progressIsZero() {
    String jobId = asyncJobService.createJob("IMPORT", "dataset", "ds-7", userId, null);

    asyncJobService.failJob(jobId, "Immediate failure");

    AsyncJobStatusResponse status = asyncJobService.getJobStatus(jobId, userId);
    assertThat(status.stage()).isEqualTo("FAILED");
    assertThat(status.progress()).isEqualTo(0);
  }

  // ──────────────────────────────────────────────
  // subscribe
  // ──────────────────────────────────────────────

  /** subscribe 성공: SseEmitter가 반환된다. */
  @Test
  void subscribe_success_returnsEmitter() {
    String jobId = asyncJobService.createJob("IMPORT", "dataset", "ds-8", userId, null);

    SseEmitter emitter = asyncJobService.subscribe(jobId, userId);

    assertThat(emitter).isNotNull();
  }

  /** subscribe: 존재하지 않는 jobId → IllegalArgumentException. */
  @Test
  void subscribe_nonExistentJob_throwsIllegalArgument() {
    assertThatThrownBy(() -> asyncJobService.subscribe("non-existent-job-id", userId))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Job not found");
  }

  /** subscribe: 다른 사용자의 job → AccessDeniedException. */
  @Test
  void subscribe_otherUsersJob_throwsAccessDenied() {
    UserResponse otherUser =
        authService.signup(
            new SignupRequest("other", "other@example.com", "Password123", "Other User"));

    String jobId = asyncJobService.createJob("IMPORT", "dataset", "ds-9", otherUser.id(), null);

    assertThatThrownBy(() -> asyncJobService.subscribe(jobId, userId))
        .isInstanceOf(AccessDeniedException.class);
  }

  // ──────────────────────────────────────────────
  // getJobStatus
  // ──────────────────────────────────────────────

  /** getJobStatus 성공: 올바른 소유자가 조회하면 상태를 반환한다. */
  @Test
  void getJobStatus_success_returnsStatus() {
    String jobId = asyncJobService.createJob("PIPELINE", "pipeline", "p-2", userId, null);

    AsyncJobStatusResponse status = asyncJobService.getJobStatus(jobId, userId);

    assertThat(status).isNotNull();
    assertThat(status.jobId()).isEqualTo(jobId);
    assertThat(status.userId()).isEqualTo(userId);
  }

  /** getJobStatus: 존재하지 않는 jobId → IllegalArgumentException. */
  @Test
  void getJobStatus_nonExistentJob_throwsIllegalArgument() {
    assertThatThrownBy(() -> asyncJobService.getJobStatus("no-such-job", userId))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Job not found");
  }

  /** getJobStatus: 다른 사용자가 조회 → AccessDeniedException. */
  @Test
  void getJobStatus_otherUsersJob_throwsAccessDenied() {
    UserResponse otherUser =
        authService.signup(
            new SignupRequest("other2", "other2@example.com", "Password123", "Other2"));

    String jobId = asyncJobService.createJob("IMPORT", "dataset", "ds-10", otherUser.id(), null);

    assertThatThrownBy(() -> asyncJobService.getJobStatus(jobId, userId))
        .isInstanceOf(AccessDeniedException.class);
  }

  // ──────────────────────────────────────────────
  // hasActiveJob / findActiveJobs
  // ──────────────────────────────────────────────

  /** hasActiveJob: 활성 job이 있으면 true를 반환한다. */
  @Test
  void hasActiveJob_withActiveJob_returnsTrue() {
    asyncJobService.createJob("IMPORT", "dataset", "ds-11", userId, null);

    boolean result = asyncJobService.hasActiveJob("IMPORT", "dataset", "ds-11");

    assertThat(result).isTrue();
  }

  /** hasActiveJob: 활성 job이 없으면 false를 반환한다. */
  @Test
  void hasActiveJob_noActiveJob_returnsFalse() {
    boolean result = asyncJobService.hasActiveJob("IMPORT", "dataset", "no-such-resource");

    assertThat(result).isFalse();
  }

  /** hasActiveJob: COMPLETED 상태인 job은 활성으로 간주되지 않는다. */
  @Test
  void hasActiveJob_completedJob_returnsFalse() {
    String jobId = asyncJobService.createJob("IMPORT", "dataset", "ds-12", userId, null);
    asyncJobService.completeJob(jobId, null);

    boolean result = asyncJobService.hasActiveJob("IMPORT", "dataset", "ds-12");

    assertThat(result).isFalse();
  }

  /**
   * findActiveJobs: 활성 job 목록을 반환한다. unique constraint(job_type, resource, resource_id)가 있으므로 서로 다른
   * resourceId로 생성한다.
   */
  @Test
  void findActiveJobs_returnsActiveJobList() {
    asyncJobService.createJob("IMPORT", "dataset", "ds-13a", userId, null);
    asyncJobService.createJob("IMPORT", "dataset", "ds-13b", userId, null);

    List<AsyncJobStatusResponse> jobsA =
        asyncJobService.findActiveJobs("IMPORT", "dataset", "ds-13a");
    List<AsyncJobStatusResponse> jobsB =
        asyncJobService.findActiveJobs("IMPORT", "dataset", "ds-13b");

    assertThat(jobsA).hasSize(1);
    assertThat(jobsA.get(0).stage()).isEqualTo("PENDING");
    assertThat(jobsB).hasSize(1);
    assertThat(jobsB.get(0).stage()).isEqualTo("PENDING");
  }
}
