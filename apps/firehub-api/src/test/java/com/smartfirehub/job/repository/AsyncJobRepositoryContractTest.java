package com.smartfirehub.job.repository;

import static java.time.temporal.ChronoUnit.MINUTES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.smartfirehub.global.platformdb.mybatis.LongTextAssertions;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.job.dto.AsyncJobStatusResponse;
import com.smartfirehub.job.repository.mybatis.AsyncJobRow;
import com.smartfirehub.user.repository.AccessContractBase;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;

/**
 * AsyncJobRepository 계약 테스트 — 구현마다 하위 클래스로 실행한다(WD-706).
 *
 * <p>async_job 은 표준 테넌트 정책 대상이다. 구현의 클래스 레벨 {@code @Transactional} 이 지키는 의미를 고정한다: 앰비언트 트랜잭션 없이
 * {@code TenantContext} 만 세워도 쓰기·읽기가 그 테넌트로 돈다(배경 잡 경로). 컨텍스트가 없으면 읽기는 빈 값(소유자 조회 포함 — 리뷰 포인트 1),
 * 쓰기는 거절된다(PG RLS WITH CHECK, Tibero NOT NULL·정책). 목록 메서드는 정렬도 페이지도 없으므로(jOOQ 와 같음) 집합으로 비교한다.
 */
public abstract class AsyncJobRepositoryContractTest extends AccessContractBase {

  protected abstract AsyncJobRepository repository();

  /**
   * 리포지토리의 트랜잭션 프록시를 거치지 않고 매퍼로 id 를 직접 조회한다(트랜잭션 없음) — MyBatis 구현만 제공한다. jOOQ 구현에는 같은 원시 진입점이 없어
   * {@link #hasRawMapper()} 가 false 이고 시험을 건너뛴다.
   */
  protected AsyncJobRow rawSelectByIdWithoutTransaction(String id) {
    throw new UnsupportedOperationException();
  }

  /** {@link #rawSelectByIdWithoutTransaction} 를 제공하는가. */
  protected boolean hasRawMapper() {
    return false;
  }

  private long tenantA;
  private long tenantB;
  private long userA;
  private long userB;

  @BeforeEach
  void setUp() {
    tenantA = fx.createTenant();
    tenantB = fx.createTenant();
    userA = fx.createUser(prefix + "-a", null);
    userB = fx.createUser(prefix + "-b", null);
  }

  private <T> T inA(Supplier<T> call) {
    return fx.inTenant(tenantA, call);
  }

  private void runA(Runnable call) {
    inA(
        () -> {
          call.run();
          return null;
        });
  }

  private static String newId() {
    return UUID.randomUUID().toString();
  }

  /** A 에 작업 하나(DATA_IMPORT, 자원은 접두 기반 고유 값). */
  private String insertA(String jobType, String resource, String resourceId, long user) {
    String id = newId();
    runA(() -> repository().insert(id, jobType, resource, resourceId, user, Map.of()));
    return id;
  }

  private static List<String> ids(List<AsyncJobStatusResponse> jobs) {
    return jobs.stream().map(AsyncJobStatusResponse::jobId).toList();
  }

  @Test
  @DisplayName("insert 는 PENDING·진행률 0 으로 넣고 findById 가 모든 값을 돌려준다 — 시각은 DB 기본값(JVM 시각과 같은 벽시계)")
  void insert_thenFindById_roundTrip() {
    String id = newId();
    Map<String, Object> meta = Map.of("fileName", "한글.csv", "rows", 12, "nested", Map.of("k", "v"));
    runA(() -> repository().insert(id, "DATA_IMPORT", "dataset", prefix, userA, meta));

    AsyncJobStatusResponse r = inA(() -> repository().findById(id)).orElseThrow();
    assertThat(r.jobId()).isEqualTo(id);
    assertThat(r.jobType()).isEqualTo("DATA_IMPORT");
    assertThat(r.stage()).isEqualTo("PENDING");
    assertThat(r.progress()).isZero();
    assertThat(r.message()).isNull();
    assertThat(r.errorMessage()).isNull();
    assertThat(r.userId()).isEqualTo(userA);
    assertThat(r.metadata()).isEqualTo(meta);
    assertThat(r.createdAt()).isCloseTo(LocalDateTime.now(), within(3, MINUTES));
    assertThat(r.updatedAt()).isCloseTo(LocalDateTime.now(), within(3, MINUTES));
    assertThat(fx.countAsyncJob(tenantA, id)).isEqualTo(1);
  }

  @Test
  @DisplayName("metadata 가 null·빈 맵이면 \"{}\" 로 저장돼 빈 맵으로 읽힌다")
  void insert_nullOrEmptyMetadata_readsEmptyMap() {
    String nullMeta = newId();
    String emptyMeta = newId();
    runA(() -> repository().insert(nullMeta, "DATA_EXPORT", "dataset", prefix, userA, null));
    runA(() -> repository().insert(emptyMeta, "DATA_EXPORT", "dataset", prefix, userA, Map.of()));

    assertThat(inA(() -> repository().findById(nullMeta)).orElseThrow().metadata()).isEmpty();
    assertThat(inA(() -> repository().findById(emptyMeta)).orElseThrow().metadata()).isEmpty();
  }

  @Test
  @DisplayName("트랜잭션 없이 컨텍스트만 세워도 insert·조회가 그 테넌트로 돈다(배경 잡 경로 — 클래스 레벨 트랜잭션)")
  void withContextWithoutTransaction_worksInThatTenant() {
    String id = newId();
    TenantContext.runScopedGet(
        tenantA,
        () -> {
          repository().insert(id, "DATA_IMPORT", "dataset", prefix, userA, Map.of("a", 1));
          return null;
        });

    assertThat(fx.countAsyncJob(tenantA, id)).isEqualTo(1);
    assertThat(TenantContext.runScopedGet(tenantA, () -> repository().findById(id))).isPresent();
    assertThat(TenantContext.runScopedGet(tenantA, () -> repository().findOwnerById(id)))
        .contains(userA);
    TenantContext.runScopedGet(
        tenantA,
        () -> {
          repository().updateStageAndProgress(id, "RUNNING", 10, "m", Map.of());
          return null;
        });
    assertThat(inA(() -> repository().findById(id)).orElseThrow().stage()).isEqualTo("RUNNING");
  }

  /**
   * 클래스 레벨 {@code @Transactional} 이 왜 필요한지의 다른 반쪽 — 트랜잭션 없이 문장을 실행하면 컨텍스트를 세워도 테넌트(PG GUC·Tibero
   * 컨텍스트)가 심기지 않아 정책이 전 행을 막는다. 자바독의 「트랜잭션이 없으면 TenantContext 를 세워도 정책이 전 행을 차단한다」를 두 DB 에서 고정한다.
   */
  @Test
  @DisplayName("트랜잭션 없이 매퍼를 직접 부르면 컨텍스트를 세워도 정책이 행을 막는다(MyBatis 만)")
  void rawMapperWithoutTransaction_policyBlocksEvenWithContext() {
    Assumptions.assumeTrue(hasRawMapper(), "원시 매퍼 진입점이 있는 구현만");
    String id = insertA("DATA_IMPORT", "dataset", prefix, userA);

    assertThat(TenantContext.runScopedGet(tenantA, () -> rawSelectByIdWithoutTransaction(id)))
        .isNull();
    // 같은 컨텍스트로 리포지토리(트랜잭션 프록시)를 거치면 보인다 — 위 null 이 행 부재 탓이 아님을 보인다.
    assertThat(TenantContext.runScopedGet(tenantA, () -> repository().findById(id))).isPresent();
  }

  @Test
  @DisplayName("테넌트 컨텍스트가 없으면 읽기(소유자 조회 포함)는 빈 값, insert 는 거절된다")
  void withoutTenant_readsEmpty_insertRejected() {
    String id = insertA("DATA_IMPORT", "dataset", "77", userA);

    assertThat(fx.noTenant(() -> repository().findById(id))).isEmpty();
    assertThat(fx.noTenant(() -> repository().findOwnerById(id))).isEmpty();
    assertThat(fx.noTenant(() -> repository().findDatasetResourceId(id))).isEmpty();
    assertThat(fx.noTenant(() -> repository().findActiveByResource("DATA_IMPORT", "dataset", "77")))
        .isEmpty();
    String rejected = newId();
    assertThatThrownBy(
            () ->
                fx.noTenant(
                    () -> {
                      repository().insert(rejected, "DATA_IMPORT", "dataset", "78", userA, null);
                      return null;
                    }))
        // 거절 경로는 DB 마다 다르다(실측) — PG 는 tenant_id DEFAULT 가 NULL 이라 RLS WITH CHECK 가 먼저
        // 막고(42501 → BadSqlGrammarException), Tibero 는 NOT NULL 이
        // 막는다(DataIntegrityViolationException).
        // 계약은 「행이 생기지 않고 데이터 접근 예외」다.
        .isInstanceOf(DataAccessException.class);
    // 거절된 쓰기는 어느 테넌트에도 행을 남기지 않는다(정책을 우회한 소유자 기준).
    assertThat(fx.countAsyncJobAnyTenant(rejected)).isZero();
  }

  @Test
  @DisplayName("다른 테넌트의 작업은 조회·소유자·목록 어디에도 보이지 않고 갱신·삭제도 닿지 않는다")
  void otherTenant_isolated() {
    String id = insertA("DATA_IMPORT", "dataset", prefix, userA);

    assertThat(fx.inTenant(tenantB, () -> repository().findById(id))).isEmpty();
    assertThat(fx.inTenant(tenantB, () -> repository().findOwnerById(id))).isEmpty();
    assertThat(
            fx.inTenant(
                tenantB, () -> repository().findActiveByResource("DATA_IMPORT", "dataset", prefix)))
        .isEmpty();
    assertThat(
            fx.inTenant(
                tenantB, () -> repository().findActiveByUserAndJobType(userA, "DATA_IMPORT")))
        .isEmpty();
    fx.inTenant(
        tenantB,
        () -> {
          repository().updateStageAndError(id, "FAILED", 5, "x");
          return null;
        });
    assertThat(inA(() -> repository().findById(id)).orElseThrow().stage()).isEqualTo("PENDING");
  }

  @Test
  @DisplayName("findOwnerById 는 시작한 사용자 id, 없는 작업은 빈 값")
  void findOwnerById() {
    String id = insertA("DATA_IMPORT", "dataset", prefix, userB);
    assertThat(inA(() -> repository().findOwnerById(id))).contains(userB);
    assertThat(inA(() -> repository().findOwnerById(newId()))).isEmpty();
  }

  @Test
  @DisplayName("findDatasetResourceId 는 dataset 작업의 숫자 id 만 — 다른 자원·숫자 아님·없는 작업은 빈 값")
  void findDatasetResourceId() {
    String numeric = insertA("DATA_EXPORT", "dataset", "12345", userA);
    String text = insertA("DATA_EXPORT", "dataset", "abc", userA);
    String otherResource = insertA("API_CONNECTION_REFRESH_ALL", "api_connection", "9", userA);

    assertThat(inA(() -> repository().findDatasetResourceId(numeric))).contains(12345L);
    assertThat(inA(() -> repository().findDatasetResourceId(text))).isEmpty();
    assertThat(inA(() -> repository().findDatasetResourceId(otherResource))).isEmpty();
    assertThat(inA(() -> repository().findDatasetResourceId(newId()))).isEmpty();
  }

  @Test
  @DisplayName("updateStageAndProgress 는 단계·진행률·메시지·메타데이터를 바꾸고 updated_at 을 지금으로 옮긴다")
  void updateStageAndProgress() {
    String id = insertA("DATA_IMPORT", "dataset", prefix, userA);
    LocalDateTime old = LocalDateTime.of(2020, 1, 1, 0, 0);
    fx.setAsyncJobState(tenantA, id, "PENDING", old, old);

    runA(() -> repository().updateStageAndProgress(id, "RUNNING", 42, "적재 중", Map.of("done", 42)));

    AsyncJobStatusResponse r = inA(() -> repository().findById(id)).orElseThrow();
    assertThat(r.stage()).isEqualTo("RUNNING");
    assertThat(r.progress()).isEqualTo(42);
    assertThat(r.message()).isEqualTo("적재 중");
    assertThat(r.metadata()).isEqualTo(Map.of("done", 42));
    assertThat(r.updatedAt()).isCloseTo(LocalDateTime.now(), within(3, MINUTES));
    assertThat(r.createdAt()).isEqualTo(old);

    // 빈 맵은 "{}" 로 덮어쓴다.
    runA(() -> repository().updateStageAndProgress(id, "RUNNING", 50, null, null));
    AsyncJobStatusResponse r2 = inA(() -> repository().findById(id)).orElseThrow();
    assertThat(r2.metadata()).isEmpty();
    assertThat(r2.message()).isNull();
  }

  /** 실패 사유는 예외 메시지(외부 응답 포함)라 길이를 통제할 수 없다 — Tibero 바이트 한도를 넘어도 실패 기록은 남아야 한다(WD-728). */
  @Test
  @DisplayName("error_message 에 한도+1 바이트 한글 값을 넣어도 저장되고 한도 안으로 잘린다")
  void updateStageAndError_overLimit_isStoredAsPrefix() {
    String id = insertA("DATA_IMPORT", "dataset", prefix, userA);
    String error = LongTextAssertions.overVarchar2Limit();

    runA(() -> repository().updateStageAndError(id, "FAILED", 9, error));

    AsyncJobStatusResponse r = inA(() -> repository().findById(id)).orElseThrow();
    assertThat(r.stage()).isEqualTo("FAILED");
    LongTextAssertions.assertStoredPrefix(r.errorMessage(), error);
  }

  @Test
  @DisplayName("updateStageAndError 는 단계·진행률·오류를 바꾸고 메시지·메타데이터는 그대로 둔다")
  void updateStageAndError() {
    String id = insertA("DATA_IMPORT", "dataset", prefix, userA);
    runA(() -> repository().updateStageAndProgress(id, "RUNNING", 30, "m", Map.of("k", "v")));
    LocalDateTime old = LocalDateTime.of(2020, 1, 1, 0, 0);
    fx.setAsyncJobState(tenantA, id, "RUNNING", old, old);

    runA(() -> repository().updateStageAndError(id, "FAILED", 31, "파일 오류"));

    AsyncJobStatusResponse r = inA(() -> repository().findById(id)).orElseThrow();
    assertThat(r.stage()).isEqualTo("FAILED");
    assertThat(r.progress()).isEqualTo(31);
    assertThat(r.errorMessage()).isEqualTo("파일 오류");
    assertThat(r.message()).isEqualTo("m");
    assertThat(r.metadata()).isEqualTo(Map.of("k", "v"));
    assertThat(r.updatedAt()).isCloseTo(LocalDateTime.now(), within(3, MINUTES));
  }

  @Test
  @DisplayName("진행 중 목록 — 같은 자원·사용자 유형의 끝나지 않은 작업만(COMPLETED·FAILED 제외)")
  void findActive_excludesFinished() {
    String exportA = insertA("DATA_EXPORT", "dataset", prefix, userA);
    String exportB = insertA("DATA_EXPORT", "dataset", prefix, userA);
    String done = insertA("DATA_EXPORT", "dataset", prefix, userA);
    String failed = insertA("DATA_EXPORT", "dataset", prefix, userA);
    runA(() -> repository().updateStageAndProgress(done, "COMPLETED", 100, null, null));
    runA(() -> repository().updateStageAndError(failed, "FAILED", 3, "x"));
    String otherResource = insertA("DATA_EXPORT", "dataset", prefix + "x", userA);
    String otherUser = insertA("DATA_EXPORT", "dataset", prefix, userB);
    String otherType = insertA("DATA_IMPORT", "dataset", prefix, userA);

    assertThat(ids(inA(() -> repository().findActiveByResource("DATA_EXPORT", "dataset", prefix))))
        .containsExactlyInAnyOrder(exportA, exportB, otherUser);
    assertThat(ids(inA(() -> repository().findActiveByUserAndJobType(userA, "DATA_EXPORT"))))
        .containsExactlyInAnyOrder(exportA, exportB, otherResource);
    assertThat(ids(inA(() -> repository().findActiveByUserAndJobType(userA, "DATA_IMPORT"))))
        .containsExactly(otherType);
  }

  @Test
  @DisplayName("findStaleJobs 는 updated_at 이 기준보다 엄격히 이른 끝나지 않은 작업만(경계와 같은 시각은 제외)")
  void findStaleJobs_strictBoundary() {
    LocalDateTime before = LocalDateTime.of(2024, 6, 1, 9, 0, 0);
    String stale = insertA("DATA_EXPORT", "dataset", prefix, userA);
    String atBoundary = insertA("DATA_EXPORT", "dataset", prefix, userA);
    String fresh = insertA("DATA_EXPORT", "dataset", prefix, userA);
    String finishedOld = insertA("DATA_EXPORT", "dataset", prefix, userA);
    fx.setAsyncJobState(tenantA, stale, "RUNNING", before.minusDays(1), before.minusSeconds(1));
    fx.setAsyncJobState(tenantA, atBoundary, "RUNNING", before.minusDays(1), before);
    fx.setAsyncJobState(tenantA, fresh, "PENDING", before, before.plusSeconds(1));
    fx.setAsyncJobState(
        tenantA, finishedOld, "COMPLETED", before.minusDays(1), before.minusDays(1));

    List<AsyncJobStatusResponse> found = inA(() -> repository().findStaleJobs(before));
    assertThat(ids(found)).containsExactly(stale);
    assertThat(found.get(0).updatedAt()).isEqualTo(before.minusSeconds(1));
    assertThat(fx.inTenant(tenantB, () -> repository().findStaleJobs(before))).isEmpty();
  }

  @Test
  @DisplayName("deleteOlderThan 은 created_at 이 기준보다 엄격히 이른 끝난 작업만 지우고 건수를 돌려준다")
  void deleteOlderThan_strictBoundaryFinishedOnly() {
    LocalDateTime before = LocalDateTime.of(2024, 6, 1, 9, 0, 0);
    String oldDone = insertA("DATA_EXPORT", "dataset", prefix, userA);
    String oldFailed = insertA("DATA_EXPORT", "dataset", prefix, userA);
    String atBoundary = insertA("DATA_EXPORT", "dataset", prefix, userA);
    String oldRunning = insertA("DATA_EXPORT", "dataset", prefix, userA);
    String newDone = insertA("DATA_EXPORT", "dataset", prefix, userA);
    fx.setAsyncJobState(tenantA, oldDone, "COMPLETED", before.minusSeconds(1), before);
    fx.setAsyncJobState(tenantA, oldFailed, "FAILED", before.minusDays(3), before);
    fx.setAsyncJobState(tenantA, atBoundary, "COMPLETED", before, before);
    fx.setAsyncJobState(tenantA, oldRunning, "RUNNING", before.minusDays(3), before);
    fx.setAsyncJobState(tenantA, newDone, "COMPLETED", before.plusDays(1), before);

    // 다른 테넌트에서는 아무것도 지우지 못한다.
    assertThat(fx.inTenant(tenantB, () -> repository().deleteOlderThan(before))).isZero();
    assertThat(inA(() -> repository().deleteOlderThan(before))).isEqualTo(2);
    assertThat(fx.countAsyncJob(tenantA, oldDone)).isZero();
    assertThat(fx.countAsyncJob(tenantA, oldFailed)).isZero();
    assertThat(fx.countAsyncJob(tenantA, atBoundary)).isEqualTo(1);
    assertThat(fx.countAsyncJob(tenantA, oldRunning)).isEqualTo(1);
    assertThat(fx.countAsyncJob(tenantA, newDone)).isEqualTo(1);
  }

  // ── 유일 제약(리뷰 포인트 3) ────────────────────────────────────────────────

  @Test
  @DisplayName("같은 id 를 다시 넣으면 DuplicateKeyException")
  void insert_duplicateId_isDuplicateKey() {
    String id = insertA("DATA_EXPORT", "dataset", prefix, userA);
    assertThatThrownBy(
            () ->
                runA(
                    () ->
                        repository()
                            .insert(id, "DATA_EXPORT", "dataset", prefix + "2", userA, null)))
        .isInstanceOf(DuplicateKeyException.class);
  }

  @Test
  @DisplayName("같은 자원에 진행 중 임포트가 둘이면 DuplicateKeyException — 내보내기는 여럿 가능, 끝난 뒤에는 다시 가능")
  void activeUnique_importOnlyAndOnlyWhileActive() {
    String first = insertA("DATA_IMPORT", "dataset", prefix, userA);
    assertThatThrownBy(() -> insertA("DATA_IMPORT", "dataset", prefix, userB))
        .isInstanceOf(DuplicateKeyException.class);

    // 내보내기는 진행 중 유일 조건에서 빠진다(V146).
    insertA("DATA_EXPORT", "dataset", prefix, userA);
    insertA("DATA_EXPORT", "dataset", prefix, userA);

    // 다른 테넌트의 같은 자원은 별개다.
    String otherTenant = newId();
    fx.inTenant(
        tenantB,
        () -> {
          repository().insert(otherTenant, "DATA_IMPORT", "dataset", prefix, userA, null);
          return null;
        });

    runA(() -> repository().updateStageAndProgress(first, "COMPLETED", 100, null, null));
    String second = insertA("DATA_IMPORT", "dataset", prefix, userA);
    assertThat(fx.countAsyncJob(tenantA, second)).isEqualTo(1);
  }

  // ── 빈 문자열(리뷰 포인트 2, controller Ruling) ─────────────────────────────

  @Test
  @DisplayName("빈 메시지·오류는 PG 에서 그대로, Tibero 에서 null 로 돌아온다")
  void emptyMessage_perDb() {
    String id = insertA("DATA_IMPORT", "dataset", prefix, userA);
    runA(() -> repository().updateStageAndProgress(id, "RUNNING", 1, "", null));
    runA(() -> repository().updateStageAndError(id, "RUNNING", 1, ""));

    AsyncJobStatusResponse r = inA(() -> repository().findById(id)).orElseThrow();
    if (emptyStringIsNull()) {
      assertThat(r.message()).isNull();
      assertThat(r.errorMessage()).isNull();
    } else {
      assertThat(r.message()).isEmpty();
      assertThat(r.errorMessage()).isEmpty();
    }
  }
}
