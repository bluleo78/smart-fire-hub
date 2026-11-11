package com.smartfirehub.dataset.rowsearch;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.dataset.repository.SearchIndexStateRepository;
import com.smartfirehub.user.repository.TenantPairContractBase;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.TimeZone;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SearchIndexStateRepository 계약 테스트 — 구현마다 하위 클래스로 실행한다(WD-485, WD-707 에서 공개 메서드 17 개 전부로 넓힘).
 *
 * <p>픽스처는 {@code AccessContractBase} seam 이라 같은 시나리오가 PG(jOOQ·MyBatis)와 Tibero(MyBatis)에서 돈다. 리뷰
 * 포인트 1: 빈 config_hash 는 PG 가 {@code ''}, Tibero 가 NULL 로 저장하지만 읽기는 둘 다 {@code ""} 다(저장 형태는 {@link
 * #emptyStringIsNull()} 로 가른다). 리뷰 포인트 2: 임대 만료 경계(막 만료·아직 유효)를 벽시계 대기 없이 소유자 픽스처로 만들고, JVM 기본 시간대가
 * KST 여도 시각이 9시간 어긋나지 않는다.
 */
abstract class SearchIndexStateRepositoryContractTest extends TenantPairContractBase {

  /** 계약 대상 리포지토리 — 구현별 하위 클래스가 제공한다. */
  protected abstract SearchIndexStateRepository repository();

  private SearchIndexState stateA(long ds) {
    return inA(() -> repository().find(ds)).orElseThrow();
  }

  /** 테넌트 A 에 데이터셋 하나와 그 색인 상태 행을 만든다. */
  private long newIndexedDataset(String name) {
    long ds = fx.createDataset(tenantA, prefix + " " + name);
    runA(() -> repository().createIfAbsent(ds));
    return ds;
  }

  private static Instant millis(OffsetDateTime t) {
    return t.toInstant().truncatedTo(ChronoUnit.MILLIS);
  }

  /** 빈 해시의 저장 형태: PG 는 '', Tibero 는 NULL. */
  private void assertStoredEmptyHash(long ds) {
    if (emptyStringIsNull()) {
      assertThat(fx.searchIndexConfigHash(ds)).isNull();
    } else {
      assertThat(fx.searchIndexConfigHash(ds)).isEmpty();
    }
  }

  @Test
  @DisplayName("find — 행이 없으면 빈 값, createIfAbsent 직후에는 기본값(빈 해시·SYNCING·진행 0)")
  void find_missingAndDefaults() {
    long ds = fx.createDataset(tenantA, prefix + " ds");
    assertThat(inA(() -> repository().find(ds))).isEmpty();

    runA(() -> repository().createIfAbsent(ds));

    SearchIndexState s = stateA(ds);
    assertThat(s.datasetId()).isEqualTo(ds);
    assertThat(s.status()).isEqualTo("SYNCING");
    assertThat(s.configHash()).isEmpty(); // 두 DB 모두 "" 로 읽힌다(Tibero 는 NULL 저장)
    assertThat(s.embeddingModel()).isNull();
    assertThat(s.embeddingDim()).isNull();
    assertThat(s.sourceTableOid()).isNull();
    assertThat(s.syncCursor()).isNull();
    assertThat(s.passCursor()).isNull();
    assertThat(s.resumeAfterId()).isNull();
    assertThat(s.indexedRows()).isZero();
    assertThat(s.totalRows()).isZero();
    assertThat(s.consecutiveFailures()).isZero();
    assertThat(s.lastSyncedAt()).isNull();
    assertThat(s.lastError()).isNull();
    assertStoredEmptyHash(ds);
  }

  @Test
  @DisplayName("createIfAbsent 는 멱등이고 이미 있는 행의 상태를 되돌리지 않는다")
  void createIfAbsent_isIdempotent_andKeepsState() {
    long ds = newIndexedDataset("ds");
    inA(() -> repository().resetForFullPass(ds, "hash1", "m", 1024, 42L, null));

    runA(() -> repository().createIfAbsent(ds));

    SearchIndexState s = stateA(ds);
    assertThat(s.configHash()).isEqualTo("hash1");
    assertThat(s.embeddingModel()).isEqualTo("m");
  }

  @Test
  @DisplayName("delete — 행을 지우고 다른 데이터셋 행은 남긴다")
  void delete_removesOnlyTarget() {
    long ds1 = newIndexedDataset("ds1");
    long ds2 = newIndexedDataset("ds2");

    runA(() -> repository().delete(ds1));

    assertThat(inA(() -> repository().find(ds1))).isEmpty();
    assertThat(inA(() -> repository().find(ds2))).isPresent();
  }

  @Test
  @DisplayName("임대는 풀기 전까지 배타적이다 — 상태 행이 없으면 false")
  void lease_isExclusiveUntilReleased() {
    long ds = newIndexedDataset("ds");
    assertThat(inA(() -> repository().tryAcquireLease(ds, Duration.ofMinutes(10)))).isTrue();
    assertThat(inA(() -> repository().tryAcquireLease(ds, Duration.ofMinutes(10)))).isFalse();
    runA(() -> repository().releaseLease(ds));
    assertThat(inA(() -> repository().tryAcquireLease(ds, Duration.ofMinutes(10)))).isTrue();

    long noRow = fx.createDataset(tenantA, prefix + " norow");
    assertThat(inA(() -> repository().tryAcquireLease(noRow, Duration.ofMinutes(10)))).isFalse();
  }

  @Test
  @DisplayName("임대 경계 — DB 시계 기준 막 만료(−3초)는 다시 잡히고 아직 유효(+30초)는 안 잡힌다")
  void lease_expiryBoundary() {
    long ds = newIndexedDataset("ds");

    fx.setSearchLeaseFromDbNow(ds, -3);
    assertThat(inA(() -> repository().tryAcquireLease(ds, Duration.ofSeconds(600)))).isTrue();

    fx.setSearchLeaseFromDbNow(ds, 30);
    assertThat(inA(() -> repository().tryAcquireLease(ds, Duration.ofSeconds(600)))).isFalse();
    // 실패한 시도는 만료 시각을 건드리지 않는다.
    assertThat(fx.searchLeaseRemaining(ds))
        .isBetween(Duration.ofSeconds(20), Duration.ofSeconds(31));
  }

  @Test
  @DisplayName("잡은 임대 만료 시각은 DB 시계 + lease 다")
  void lease_untilIsDbNowPlusLease() {
    long ds = newIndexedDataset("ds");
    assertThat(fx.searchLeaseRemaining(ds)).isNull();

    assertThat(inA(() -> repository().tryAcquireLease(ds, Duration.ofSeconds(600)))).isTrue();

    assertThat(fx.searchLeaseRemaining(ds))
        .isBetween(Duration.ofSeconds(540), Duration.ofSeconds(601));
    runA(() -> repository().releaseLease(ds));
    assertThat(fx.searchLeaseRemaining(ds)).isNull();
  }

  /**
   * 코드리뷰 A-3: 전체 패스 시작은 비교 후 갱신이다 — 동기화가 읽은 뒤 정책 정리가 모델을 키워드 전용으로 바꿨으면 덮어쓰지 않는다(정리 무력화 방지). 읽은 값
   * 그대로면 쓴다(키워드 전용→실제 모델 재허용 전이도 이 경로).
   */
  @Test
  @DisplayName("resetForFullPass 는 embedding_model 비교 후 갱신이다(정리 개입 시 거부)")
  void resetForFullPass_isCompareAndSet_onEmbeddingModel() {
    long ds = newIndexedDataset("ds");
    String initial = stateA(ds).embeddingModel();
    assertThat(inA(() -> repository().resetForFullPass(ds, "hash", "real", 1024, 42L, initial)))
        .isTrue();
    // 동기화가 "real" 을 읽은 뒤 정리가 끼어든다.
    runA(() -> repository().markKeywordOnly(ds, RowSearchSyncService.KEYWORD_ONLY_MODEL));
    assertThat(inA(() -> repository().resetForFullPass(ds, "hash", "real", 1024, 42L, "real")))
        .isFalse();
    var kept = stateA(ds);
    assertThat(kept.embeddingModel()).isEqualTo(RowSearchSyncService.KEYWORD_ONLY_MODEL);
    assertThat(kept.configHash()).isEmpty();
    // 재허용 전이: 읽은 값(키워드 전용)이 그대로면 실제 모델로 쓴다.
    assertThat(
            inA(
                () ->
                    repository()
                        .resetForFullPass(
                            ds,
                            "hash",
                            "real",
                            1024,
                            42L,
                            RowSearchSyncService.KEYWORD_ONLY_MODEL)))
        .isTrue();
    assertThat(stateA(ds).embeddingModel()).isEqualTo("real");
  }

  @Test
  @DisplayName("resetForFullPass 는 설정·모델·차원·OID 를 쓰고 책갈피·진행 위치를 비운다 — OID 는 int 범위 밖도 그대로")
  void resetForFullPass_resetsPassAndStoresOid() {
    long ds = newIndexedDataset("ds");
    OffsetDateTime candidate = OffsetDateTime.now().minusMinutes(1);
    inA(() -> repository().resetForFullPass(ds, "h0", "m", 1024, 1L, null));
    runA(() -> repository().startPassIfNeeded(ds, candidate, 10L));
    runA(() -> repository().saveProgress(ds, 5L, 5L));
    runA(() -> repository().markCompleted(ds, 5, 10));
    runA(() -> repository().startPassIfNeeded(ds, candidate, 10L));
    runA(() -> repository().saveProgress(ds, 3L, 3L));

    long bigOid = 4_294_967_295L; // PG OID 최대(uint32) — int 로 잘리지 않아야 한다
    assertThat(inA(() -> repository().resetForFullPass(ds, "h1", "m2", 768, bigOid, "m"))).isTrue();

    SearchIndexState s = stateA(ds);
    assertThat(s.configHash()).isEqualTo("h1");
    assertThat(s.embeddingModel()).isEqualTo("m2");
    assertThat(s.embeddingDim()).isEqualTo(768);
    assertThat(s.sourceTableOid()).isEqualTo(bigOid);
    assertThat(s.syncCursor()).isNull();
    assertThat(s.passCursor()).isNull();
    assertThat(s.resumeAfterId()).isNull();
    assertThat(s.indexedRows()).isZero();
    assertThat(s.status()).isEqualTo("SYNCING");
  }

  @Test
  @DisplayName("markSourceOid — OID 만 바꾸고 진행 위치를 비운다(해시·모델은 그대로)")
  void markSourceOid_resetsProgressOnly() {
    long ds = newIndexedDataset("ds");
    inA(() -> repository().resetForFullPass(ds, "h", "m", 1024, 1L, null));
    runA(() -> repository().startPassIfNeeded(ds, OffsetDateTime.now(), 10L));
    runA(() -> repository().saveProgress(ds, 4L, 4L));

    runA(() -> repository().markSourceOid(ds, 99L));

    SearchIndexState s = stateA(ds);
    assertThat(s.sourceTableOid()).isEqualTo(99L);
    assertThat(s.passCursor()).isNull();
    assertThat(s.resumeAfterId()).isNull();
    assertThat(s.indexedRows()).isZero();
    assertThat(s.totalRows()).isEqualTo(10L); // 분모는 그대로
    assertThat(s.configHash()).isEqualTo("h");
    assertThat(s.embeddingModel()).isEqualTo("m");
    assertThat(s.status()).isEqualTo("SYNCING");
  }

  @Test
  @DisplayName("패스 진행·완주·실패 백오프 — 시작 후보는 한 번만, 완주 시 책갈피가 된다")
  void pass_progress_complete_and_failure_backoff() {
    long ds = newIndexedDataset("ds");
    inA(() -> repository().resetForFullPass(ds, "hash", "fake", 1024, 42L, null));
    OffsetDateTime candidate = OffsetDateTime.now().minusSeconds(5);
    runA(() -> repository().startPassIfNeeded(ds, candidate, 10L));
    // 이미 시작된 패스는 후보·분모를 덮지 않는다
    runA(() -> repository().startPassIfNeeded(ds, OffsetDateTime.now(), 99L));
    runA(() -> repository().saveProgress(ds, 2L, 2L));

    var mid = stateA(ds);
    assertThat(mid.resumeAfterId()).isEqualTo(2L);
    assertThat(mid.totalRows()).isEqualTo(10L);
    assertThat(mid.indexedRows()).isEqualTo(2L);
    assertThat(millis(mid.passCursor())).isEqualTo(millis(candidate));

    runA(() -> repository().markCompleted(ds, 3, 3));
    var done = stateA(ds);
    assertThat(done.status()).isEqualTo("IDLE");
    // 패스 시작 시 후보가 책갈피가 된다(세션 오프셋과 JVM 오프셋이 다를 수 있어 Instant 로 비교)
    assertThat(millis(done.syncCursor())).isEqualTo(millis(candidate));
    assertThat(done.resumeAfterId()).isNull();
    assertThat(done.passCursor()).isNull();
    assertThat(done.indexedRows()).isEqualTo(3L);
    assertThat(done.totalRows()).isEqualTo(3L);
    assertThat(done.lastSyncedAt()).isNotNull();

    runA(() -> repository().markFailed(ds, "boom", OffsetDateTime.now().plusMinutes(5)));
    var failed = stateA(ds);
    assertThat(failed.status()).isEqualTo("ERROR");
    assertThat(failed.lastError()).isEqualTo("boom");
    assertThat(failed.consecutiveFailures()).isEqualTo(1);
    assertThat(inA(() -> repository().findDueDatasetIds())).doesNotContain(ds); // 백오프 중
    runA(() -> repository().markFailed(ds, "boom2", OffsetDateTime.now().plusMinutes(5)));
    assertThat(stateA(ds).consecutiveFailures()).isEqualTo(2);

    // 완주는 오류·백오프를 해제한다.
    runA(() -> repository().markCompleted(ds, 3, 3));
    var recovered = stateA(ds);
    assertThat(recovered.consecutiveFailures()).isZero();
    assertThat(recovered.lastError()).isNull();
    assertThat(inA(() -> repository().findDueDatasetIds())).contains(ds);
  }

  @Test
  @DisplayName("saveProgress 는 증감을 더하되 0 아래로 내려가지 않는다(GREATEST)")
  void saveProgress_clampsAtZero() {
    long ds = newIndexedDataset("ds");
    runA(() -> repository().saveProgress(ds, 1L, 3L));
    runA(() -> repository().saveProgress(ds, 2L, -10L));

    SearchIndexState s = stateA(ds);
    assertThat(s.indexedRows()).isZero();
    assertThat(s.resumeAfterId()).isEqualTo(2L);
    assertThat(s.status()).isEqualTo("SYNCING");
  }

  @Test
  @DisplayName("markWaiting 은 실패 횟수를 올리지 않고 사유·재시도 시각만 남긴다")
  void markWaiting_doesNotCountFailure() {
    long ds = newIndexedDataset("ds");
    runA(() -> repository().markWaiting(ds, "임베딩 미설정", OffsetDateTime.now().plusMinutes(1)));

    SearchIndexState s = stateA(ds);
    assertThat(s.status()).isEqualTo("ERROR");
    assertThat(s.lastError()).isEqualTo("임베딩 미설정");
    assertThat(s.consecutiveFailures()).isZero();
    assertThat(inA(() -> repository().findDueDatasetIds())).doesNotContain(ds);

    runA(() -> repository().markWaiting(ds, "다시", OffsetDateTime.now().minusMinutes(1)));
    assertThat(inA(() -> repository().findDueDatasetIds())).contains(ds);
  }

  @Test
  @DisplayName("markSyncing — 백오프·오류를 풀고 SYNCING 으로(해시는 그대로)")
  void markSyncing_clearsBackoff() {
    long ds = newIndexedDataset("ds");
    inA(() -> repository().resetForFullPass(ds, "h", "m", 1024, 1L, null));
    runA(() -> repository().markFailed(ds, "boom", OffsetDateTime.now().plusHours(1)));

    runA(() -> repository().markSyncing(ds));

    SearchIndexState s = stateA(ds);
    assertThat(s.status()).isEqualTo("SYNCING");
    assertThat(s.consecutiveFailures()).isZero();
    assertThat(s.lastError()).isNull();
    assertThat(s.configHash()).isEqualTo("h");
    assertThat(inA(() -> repository().findDueDatasetIds())).contains(ds);
  }

  @Test
  @DisplayName("forceFull — 해시를 비우고(빈 해시) 백오프를 풀어 바로 due 가 된다")
  void forceFull_emptiesHashAndClearsBackoff() {
    long ds = newIndexedDataset("ds");
    inA(() -> repository().resetForFullPass(ds, "h", "m", 1024, 1L, null));
    runA(() -> repository().markFailed(ds, "boom", OffsetDateTime.now().plusHours(1)));

    runA(() -> repository().forceFull(ds));

    SearchIndexState s = stateA(ds);
    assertThat(s.configHash()).isEmpty();
    assertThat(s.status()).isEqualTo("SYNCING");
    assertThat(s.consecutiveFailures()).isZero();
    assertThat(s.lastError()).isNull();
    assertThat(s.embeddingModel()).isEqualTo("m"); // 모델은 그대로
    assertThat(inA(() -> repository().findDueDatasetIds())).contains(ds);
    assertStoredEmptyHash(ds);
  }

  @Test
  @DisplayName("markKeywordOnly — 모델을 키워드 전용 표식으로, 해시를 비우고 백오프를 푼다")
  void markKeywordOnly_marksAndEmptiesHash() {
    long ds = newIndexedDataset("ds");
    inA(() -> repository().resetForFullPass(ds, "h", "m", 1024, 1L, null));
    runA(() -> repository().markFailed(ds, "boom", OffsetDateTime.now().plusHours(1)));

    runA(() -> repository().markKeywordOnly(ds, RowSearchSyncService.KEYWORD_ONLY_MODEL));

    SearchIndexState s = stateA(ds);
    assertThat(s.embeddingModel()).isEqualTo(RowSearchSyncService.KEYWORD_ONLY_MODEL);
    assertThat(s.configHash()).isEmpty();
    assertThat(s.status()).isEqualTo("SYNCING");
    assertThat(s.consecutiveFailures()).isZero();
    assertThat(inA(() -> repository().findDueDatasetIds())).contains(ds);
    assertStoredEmptyHash(ds);
  }

  @Test
  @DisplayName("빈 해시 왕복 — forceFull 뒤 다음 패스는 실제 해시와 달라 전체 재색인으로 판정되고, 새 해시로 다시 쓸 수 있다")
  void emptyHash_roundTrip_triggersFullPass() {
    long ds = newIndexedDataset("ds");
    inA(() -> repository().resetForFullPass(ds, "real-hash", "m", 1024, 1L, null));
    runA(() -> repository().forceFull(ds));

    // 서비스 판정: config.configHash().equals(state.configHash()) 가 false 여야 전체 재색인이다.
    assertThat("real-hash".equals(stateA(ds).configHash())).isFalse();
    assertThat(inA(() -> repository().resetForFullPass(ds, "real-hash", "m", 1024, 1L, "m")))
        .isTrue();
    assertThat(stateA(ds).configHash()).isEqualTo("real-hash");
  }

  @Test
  @DisplayName("findDueDatasetIds — 백오프 없음·지난 재시도는 due, 미래 재시도는 제외, dataset_id 순")
  void findDue_respectsBackoffAndOrder() {
    long ds1 = newIndexedDataset("ds1");
    long ds2 = newIndexedDataset("ds2");
    long ds3 = newIndexedDataset("ds3");
    runA(() -> repository().markFailed(ds2, "x", OffsetDateTime.now().plusHours(1)));
    runA(() -> repository().markFailed(ds3, "x", OffsetDateTime.now().minusMinutes(1)));

    var due = inA(() -> repository().findDueDatasetIds());
    assertThat(due).contains(ds1, ds3).doesNotContain(ds2);
    assertThat(due).isSorted();
  }

  @Test
  @DisplayName("countStale — 모델·차원이 다르거나 없으면 센다, 키워드 전용은 세지 않는다")
  void countStale_countsDifferentSpaceExceptKeywordOnly() {
    long same = newIndexedDataset("same");
    long otherDim = newIndexedDataset("dim");
    long otherModel = newIndexedDataset("model");
    newIndexedDataset("none"); // 모델·차원 NULL
    long keyword = newIndexedDataset("kw");
    inA(() -> repository().resetForFullPass(same, "h", "m", 1024, 1L, null));
    inA(() -> repository().resetForFullPass(otherDim, "h", "m", 512, 1L, null));
    inA(() -> repository().resetForFullPass(otherModel, "h", "x", 1024, 1L, null));
    runA(() -> repository().markKeywordOnly(keyword, RowSearchSyncService.KEYWORD_ONLY_MODEL));

    assertThat(inA(() -> repository().countStale("m", 1024))).isEqualTo(3L);
    assertThat(fx.inTenant(tenantB, () -> repository().countStale("m", 1024))).isZero();
  }

  /**
   * 리뷰 포인트 1: 빈 문자열 모델을 쓰고 같은 값으로 비교하면 두 DB 가 같은 행을 고른다(PG 는 '' = '', Tibero 는 NULL·NULL 을 NULL 안전
   * 비교로 같다고 본다). 저장된 NULL 과 '' 를 비교하는 교차 경우만 DB 마다 다르다 — Tibero 는 둘을 구분할 수 없다. 서비스는 모델 id(공급자
   * modelId 또는 키워드 전용 표식)만 넘겨 '' 가 오지 않는다.
   */
  @Test
  @DisplayName("빈 문자열 모델 — 쓴 값 그대로 비교하면 두 DB 같은 결과, NULL 과의 교차 비교만 DB 별로 갈린다")
  void emptyStringModel_writeThenCompare() {
    long ds = newIndexedDataset("ds");
    inA(() -> repository().resetForFullPass(ds, "h", "", 1024, 1L, null));
    assertThat(stateA(ds).embeddingModel()).isEqualTo(emptyStringIsNull() ? null : "");

    // 쓴 값('')으로 비교 후 갱신 — 두 DB 모두 통과.
    assertThat(inA(() -> repository().resetForFullPass(ds, "h", "", 1024, 1L, ""))).isTrue();
    // 같은 공간('')이면 오래되지 않았다 — 두 DB 같음.
    assertThat(inA(() -> repository().countStale("", 1024))).isZero();
    // 다른 모델이면 오래됐다 — 두 DB 같음.
    assertThat(inA(() -> repository().countStale("m", 1024))).isEqualTo(1L);

    // 교차: 저장 '' 에 기대값 NULL — PG 는 다르다(거부), Tibero 는 같다(통과).
    assertThat(inA(() -> repository().resetForFullPass(ds, "h", "", 1024, 1L, null)))
        .isEqualTo(emptyStringIsNull());
  }

  @Test
  @DisplayName("빈 오류 메시지 — PG 는 \"\", Tibero 는 null 로 돌아온다")
  void emptyErrorMessage() {
    long ds = newIndexedDataset("ds");
    runA(() -> repository().markFailed(ds, "", OffsetDateTime.now().plusMinutes(1)));
    assertThat(stateA(ds).lastError()).isEqualTo(emptyStringIsNull() ? null : "");
  }

  @Test
  @DisplayName("긴 한글 오류 메시지(4000 바이트 초과)도 잘리지 않고 왕복한다")
  void longKoreanErrorMessage() {
    long ds = newIndexedDataset("ds");
    String longError = "임베딩 공급자 응답 오류 ".repeat(300) + "끝"; // 약 3600자 · 9000+ 바이트
    runA(() -> repository().markFailed(ds, longError, OffsetDateTime.now().plusMinutes(1)));
    assertThat(stateA(ds).lastError()).isEqualTo(longError);
  }

  @Test
  @DisplayName("65532 바이트를 넘는 한글 오류·대기 사유도 쓰기가 실패하지 않고 백오프가 기록된다 — 남는 값은 원문의 접두어(MyBatis 는 잘라 넣는다)")
  void overColumnLimitKoreanErrorMessage_isRecordedAsPrefix() {
    long ds = newIndexedDataset("ds");
    // 한글 한 글자는 UTF-8 3바이트 — 25000자면 75000 바이트로 Tibero VARCHAR2(65532) 한도를 넘는다.
    String hugeError = "가".repeat(25_000);

    runA(() -> repository().markFailed(ds, hugeError, OffsetDateTime.now().plusMinutes(5)));
    var failed = stateA(ds);
    assertThat(failed.status()).isEqualTo("ERROR");
    assertThat(failed.consecutiveFailures()).isEqualTo(1);
    assertThat(failed.lastError()).isNotEmpty();
    assertThat(hugeError).startsWith(failed.lastError());
    assertThat(inA(() -> repository().findDueDatasetIds())).doesNotContain(ds); // 백오프 기록됨

    String hugeReason = "대".repeat(25_000);
    runA(() -> repository().markWaiting(ds, hugeReason, OffsetDateTime.now().plusMinutes(5)));
    var waiting = stateA(ds);
    assertThat(waiting.lastError()).isNotEmpty();
    assertThat(hugeReason).startsWith(waiting.lastError());
    assertThat(inA(() -> repository().findDueDatasetIds())).doesNotContain(ds);
  }

  @Test
  @DisplayName("다른 테넌트 차단 — B 컨텍스트는 A 행을 읽지도·잡지도·바꾸지도·지우지도 못한다")
  void otherTenantIsBlocked() {
    long ds = newIndexedDataset("ds");
    inA(() -> repository().resetForFullPass(ds, "h", "m", 1024, 1L, null));

    assertThat(fx.inTenant(tenantB, () -> repository().find(ds))).isEmpty();
    assertThat(fx.inTenant(tenantB, () -> repository().tryAcquireLease(ds, Duration.ofMinutes(1))))
        .isFalse();
    assertThat(
            fx.inTenant(tenantB, () -> repository().resetForFullPass(ds, "hb", "mb", 1, 2L, "m")))
        .isFalse();
    assertThat(fx.inTenant(tenantB, () -> repository().findDueDatasetIds())).doesNotContain(ds);
    fx.inTenant(
        tenantB,
        () -> {
          repository().forceFull(ds);
          repository().markFailed(ds, "b", OffsetDateTime.now().plusHours(1));
          repository().delete(ds);
          return null;
        });

    Optional<SearchIndexState> kept = inA(() -> repository().find(ds));
    assertThat(kept).isPresent();
    assertThat(kept.get().configHash()).isEqualTo("h");
    assertThat(kept.get().status()).isEqualTo("SYNCING");
    assertThat(fx.searchLeaseRemaining(ds)).isNull();
  }

  /**
   * 리뷰 포인트 2: JVM 기본 시간대가 KST 여도 시각이 9시간 어긋나지 않는다. 자바에서 바인딩한 재시도 시각(markFailed·markWaiting)이 DB 시계와
   * 비교되는 findDueDatasetIds 가 양쪽 방향의 어긋남을 각각 잡고, 패스 후보 왕복·DB 시계로 쓴 완료 시각 읽기도 확인한다.
   */
  @Test
  @DisplayName("JVM 기본 시간대가 Asia/Seoul 이어도 임대·백오프·책갈피 시각이 어긋나지 않는다")
  void timesAreCorrect_whenJvmZoneIsSeoul() {
    TimeZone original = TimeZone.getDefault();
    TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
    try {
      long ds = newIndexedDataset("kst");
      assertThat(inA(() -> repository().tryAcquireLease(ds, Duration.ofSeconds(600)))).isTrue();
      assertThat(inA(() -> repository().tryAcquireLease(ds, Duration.ofSeconds(600)))).isFalse();
      assertThat(fx.searchLeaseRemaining(ds))
          .isBetween(Duration.ofSeconds(540), Duration.ofSeconds(601));

      // 5분 뒤 재시도 — 9시간 이르게 저장되면 바로 due 가 된다.
      runA(() -> repository().markFailed(ds, "boom", OffsetDateTime.now().plusMinutes(5)));
      assertThat(inA(() -> repository().findDueDatasetIds())).doesNotContain(ds);
      // 1분 전 재시도 — 9시간 늦게 저장되면 due 가 안 된다.
      runA(() -> repository().markWaiting(ds, "wait", OffsetDateTime.now().minusMinutes(1)));
      assertThat(inA(() -> repository().findDueDatasetIds())).contains(ds);

      OffsetDateTime candidate = OffsetDateTime.now().minusSeconds(30);
      runA(() -> repository().startPassIfNeeded(ds, candidate, 3L));
      assertThat(millis(stateA(ds).passCursor())).isEqualTo(millis(candidate));
      runA(() -> repository().markCompleted(ds, 3, 3));
      SearchIndexState done = stateA(ds);
      assertThat(millis(done.syncCursor())).isEqualTo(millis(candidate));
      // DB 시계로 쓴 완료 시각 — 읽기 경로가 9시간 밀리지 않는다(서버 시계 차이를 감안해 10분 허용).
      assertThat(Duration.between(done.lastSyncedAt().toInstant(), Instant.now()).abs())
          .isLessThan(Duration.ofMinutes(10));
    } finally {
      TimeZone.setDefault(original);
    }
  }
}
