package com.smartfirehub.job.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.smartfirehub.job.dto.AsyncJobStatusResponse;
import com.smartfirehub.job.exception.JobNotFoundException;
import com.smartfirehub.job.repository.AsyncJobRepository;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TestUsers;
import com.smartfirehub.user.dto.UserResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * AsyncJobService 통합 테스트. 핵심 메서드 7개(createJob, updateProgress, completeJob, failJob, subscribe,
 * getJobStatus, hasActiveJob/findActiveJobs)의 정상/예외 케이스를 검증한다.
 */
@Transactional
class AsyncJobServiceTest extends IntegrationTestBase {

  @Autowired private AsyncJobService asyncJobService;
  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private AsyncJobRepository asyncJobRepository;

  private Long userId;

  /** 각 테스트 전 테스트 사용자를 생성한다. */
  @BeforeEach
  void setUp() {
    UserResponse user =
        TestUsers.createMember(
            dsl,
            fixtureTransactionTemplate,
            passwordEncoder,
            "jobTestUser",
            "jobtest@example.com",
            "Password123",
            "Job Tester",
            DEFAULT_TEST_TENANT_ID);
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

  /** subscribe: 존재하지 않는 jobId → JobNotFoundException(404, 한국어 문구 — WD-203). */
  @Test
  void subscribe_nonExistentJob_throwsJobNotFound() {
    assertThatThrownBy(() -> asyncJobService.subscribe("non-existent-job-id", userId))
        .isInstanceOf(JobNotFoundException.class)
        .hasMessageContaining("작업을 찾을 수 없습니다");
  }

  /** subscribe: 다른 사용자의 job → AccessDeniedException. */
  @Test
  void subscribe_otherUsersJob_throwsAccessDenied() {
    UserResponse otherUser =
        TestUsers.createMember(
            dsl,
            fixtureTransactionTemplate,
            passwordEncoder,
            "other",
            "other@example.com",
            "Password123",
            "Other User",
            DEFAULT_TEST_TENANT_ID);

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

  /** getJobStatus: 존재하지 않는 jobId → JobNotFoundException(404, 한국어 문구 — WD-203). */
  @Test
  void getJobStatus_nonExistentJob_throwsJobNotFound() {
    assertThatThrownBy(() -> asyncJobService.getJobStatus("no-such-job", userId))
        .isInstanceOf(JobNotFoundException.class)
        .hasMessageContaining("작업을 찾을 수 없습니다");
  }

  /**
   * getJobStatus: API 연결 전체 갱신 잡도 시작한 사용자가 그대로 조회한다(WD-203). 서비스는 기능 권한이 아니라 소유자만 본다 — 엔드포인트에서
   * data:read 를 뺀 근거.
   */
  @Test
  void getJobStatus_apiConnectionRefreshJob_ownerCanRead() {
    String jobId =
        asyncJobService.createJob(
            "API_CONNECTION_REFRESH_ALL", "api_connection", "all", userId, null);
    asyncJobService.completeJob(jobId, Map.of("total", 2, "checked", 2));

    AsyncJobStatusResponse status = asyncJobService.getJobStatus(jobId, userId);

    assertThat(status.jobType()).isEqualTo("API_CONNECTION_REFRESH_ALL");
    assertThat(status.stage()).isEqualTo("COMPLETED");
  }

  /** getJobStatus: 다른 사용자가 조회 → AccessDeniedException. */
  @Test
  void getJobStatus_otherUsersJob_throwsAccessDenied() {
    UserResponse otherUser =
        TestUsers.createMember(
            dsl,
            fixtureTransactionTemplate,
            passwordEncoder,
            "other2",
            "other2@example.com",
            "Password123",
            "Other2",
            DEFAULT_TEST_TENANT_ID);

    String jobId = asyncJobService.createJob("IMPORT", "dataset", "ds-10", otherUser.id(), null);

    assertThatThrownBy(() -> asyncJobService.getJobStatus(jobId, userId))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("이 작업을 시작한 사용자만");
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

  // ──────────────────────────────────────────────
  // sendHeartbeats (SSE 하트비트)
  // ──────────────────────────────────────────────

  /** sendHeartbeats: 활성 emitter가 있으면 각 emitter에 comment("ping") 이벤트가 전송된다. */
  @Test
  void sendHeartbeats_withActiveEmitters_sendsPingComment() throws IOException {
    SseEmitter mockEmitter1 = mock(SseEmitter.class);
    SseEmitter mockEmitter2 = mock(SseEmitter.class);

    ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>> emitters =
        new ConcurrentHashMap<>();
    emitters.put("job-A", new CopyOnWriteArrayList<>(List.of(mockEmitter1)));
    emitters.put("job-B", new CopyOnWriteArrayList<>(List.of(mockEmitter2)));
    ReflectionTestUtils.setField(asyncJobService, "emitters", emitters);

    asyncJobService.sendHeartbeats();

    verify(mockEmitter1, times(1)).send(any(SseEmitter.SseEventBuilder.class));
    verify(mockEmitter2, times(1)).send(any(SseEmitter.SseEventBuilder.class));
  }

  /** sendHeartbeats: 활성 emitter가 없으면(emitters 맵이 비어 있으면) no-op이며 예외가 발생하지 않는다. */
  @Test
  void sendHeartbeats_noActiveEmitters_noOp() {
    ReflectionTestUtils.setField(
        asyncJobService,
        "emitters",
        new ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>>());

    asyncJobService.sendHeartbeats();
    // 예외 없이 반환되면 성공 (검증할 emitter가 없으므로 상호작용 없음)
  }

  /** sendHeartbeats: emitter의 send()가 실패(IOException)하면 removeEmitter로 정리되어 emitters 맵에서 제거된다. */
  @Test
  void sendHeartbeats_sendFails_removesDeadEmitter() throws IOException {
    SseEmitter mockEmitter = mock(SseEmitter.class);
    org.mockito.Mockito.doThrow(new IOException("broken pipe"))
        .when(mockEmitter)
        .send(any(SseEmitter.SseEventBuilder.class));

    ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>> emitters =
        new ConcurrentHashMap<>();
    CopyOnWriteArrayList<SseEmitter> list = new CopyOnWriteArrayList<>(List.of(mockEmitter));
    emitters.put("job-C", list);
    ReflectionTestUtils.setField(asyncJobService, "emitters", emitters);

    asyncJobService.sendHeartbeats();

    verify(mockEmitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
    assertThat(list).isEmpty();
  }

  // ──────────────────────────────────────────────
  // 진행 중 작업 부분 유니크 인덱스(idx_async_job_active_unique, V146 — WD-247)
  // ──────────────────────────────────────────────

  /** 내보내기용 두 번째 테스트 사용자를 만든다(다른 사용자 동시 내보내기 재현용). */
  private Long createSecondUser() {
    return TestUsers.createMember(
            dsl,
            fixtureTransactionTemplate,
            passwordEncoder,
            "jobTestUser2",
            "jobtest2@example.com",
            "Password123",
            "Job Tester 2",
            DEFAULT_TEST_TENANT_ID)
        .id();
  }

  /**
   * WD-247: 같은 데이터셋에 진행 중 내보내기가 있어도 다른 사용자의 내보내기·같은 사용자의 다른 형식 내보내기 작업을 만들 수 있다. V146 이전에는 부분 유니크
   * 인덱스가 DATA_EXPORT 에도 걸려 두 번째 createJob 이 DataIntegrityViolation(→ 409)으로 실패했다.
   */
  @Test
  void createJob_dataExport_sameDatasetWhileActive_allowsOtherUserAndFormat() {
    Long otherUserId = createSecondUser();

    String csvJob =
        asyncJobService.createJob(
            "DATA_EXPORT", "dataset", "ds-wd247", userId, Map.of("format", "CSV"));
    String otherUserJob =
        asyncJobService.createJob(
            "DATA_EXPORT", "dataset", "ds-wd247", otherUserId, Map.of("format", "CSV"));
    String excelJob =
        asyncJobService.createJob(
            "DATA_EXPORT", "dataset", "ds-wd247", userId, Map.of("format", "EXCEL"));

    // 세 작업 모두 진행 중(PENDING)으로 같은 데이터셋에 공존한다.
    assertThat(List.of(csvJob, otherUserJob, excelJob)).doesNotHaveDuplicates();
    assertThat(asyncJobService.findActiveJobs("DATA_EXPORT", "dataset", "ds-wd247")).hasSize(3);
  }

  /**
   * WD-247: 내보내기를 인덱스에서 뺀 뒤에도 사용자당 동시 내보내기 상한(3개)이 세는 진행 중 목록에 같은 데이터셋 내보내기가 모두 잡힌다. 상한
   * 판정(DataExportService.checkRateLimit)은 이 조회 결과 개수로 막으므로, 인덱스가 사라진 자리를 상한이 그대로 지킨다.
   */
  @Test
  void findActiveByUserAndJobType_countsSameDatasetExportsForUserLimit() {
    asyncJobService.createJob("DATA_EXPORT", "dataset", "ds-wd247-limit", userId, null);
    asyncJobService.createJob("DATA_EXPORT", "dataset", "ds-wd247-limit", userId, null);
    asyncJobService.createJob("DATA_EXPORT", "dataset", "ds-wd247-limit", userId, null);
    String done =
        asyncJobService.createJob("DATA_EXPORT", "dataset", "ds-wd247-limit", userId, null);
    asyncJobService.completeJob(done, null);

    // 완료된 작업은 빼고 진행 중 3개만 센다 → 상한(>= 3)에 걸린다.
    assertThat(asyncJobRepository.findActiveByUserAndJobType(userId, "DATA_EXPORT")).hasSize(3);
  }

  /**
   * WD-247: 가져오기(IMPORT)의 「데이터셋당 진행 중 1개」 제약은 V146 이후에도 유지된다 — 다른 사용자라도 같은 데이터셋에 두 번째 진행 중 임포트를 만들면
   * DB 가 거부한다(DataImportService 가 이를 한국어 409 로 바꾼다).
   *
   * <p>위반은 트랜잭션을 중단시키므로 마지막 문장으로 둔다(클래스 레벨 @Transactional 이 끝에 롤백한다).
   */
  @Test
  void createJob_import_sameDatasetWhileActive_stillRejected() {
    Long otherUserId = createSecondUser();
    asyncJobService.createJob("IMPORT", "dataset", "ds-wd247-import", userId, null);

    assertThatThrownBy(
            () ->
                asyncJobService.createJob(
                    "IMPORT", "dataset", "ds-wd247-import", otherUserId, null))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("idx_async_job_active_unique");
  }
}
