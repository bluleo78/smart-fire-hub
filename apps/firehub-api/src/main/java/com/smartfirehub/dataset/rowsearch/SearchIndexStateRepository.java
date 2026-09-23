package com.smartfirehub.dataset.rowsearch;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * dataset_search_index 저장소(RLS — 트랜잭션 안에서만 테넌트 GUC 가 선다).
 *
 * <p>동시 실행 방지는 advisory lock 대신 임대(sync_lease_until) 컬럼으로 한다 — sync 는 여러 트랜잭션에 걸치고
 * 세션 락은 풀 커넥션에서 새기 때문이다.
 */
@Repository
@Transactional
@RequiredArgsConstructor
public class SearchIndexStateRepository {

  /** 백오프·오류 해제(재시도 즉시 허용) — 수동 재색인과 설정 변경 직후가 함께 쓴다. */
  private static final String CLEAR_BACKOFF =
      " consecutive_failures = 0, next_attempt_at = NULL, last_error = NULL,";

  /** 패스 진행 위치 초기화(책갈피·재개 위치·진행률 분자) — 전체 재색인과 swap 재구축이 함께 쓴다. */
  private static final String RESET_PASS =
      " sync_cursor = NULL, pass_cursor = NULL, resume_after_id = NULL, indexed_rows = 0,";

  private final DSLContext dsl;

  /** 데이터셋의 색인 상태 행. 없으면(검색 꺼짐) 빈 값. */
  public Optional<SearchIndexState> find(long datasetId) {
    return Optional.ofNullable(
            dsl.fetchOne("SELECT * FROM dataset_search_index WHERE dataset_id = ?", datasetId))
        .map(SearchIndexStateRepository::toState);
  }

  /** 검색 켜기: 행이 없으면 만든다. config_hash='' 이라 첫 스윕이 전체 재색인한다. */
  public void createIfAbsent(long datasetId) {
    dsl.execute(
        "INSERT INTO dataset_search_index(dataset_id) VALUES (?) ON CONFLICT (dataset_id) DO NOTHING",
        datasetId);
  }

  /** 색인 상태 행을 지운다(검색 끄기). 색인 테이블은 FK 가 없으므로 호출자가 따로 DROP 한다. */
  public void delete(long datasetId) {
    dsl.execute("DELETE FROM dataset_search_index WHERE dataset_id = ?", datasetId);
  }

  /** 수동 재색인: 해시를 비워 다음 스윕이 전체 재색인하게 하고 백오프를 해제한다. */
  public void forceFull(long datasetId) {
    dsl.execute(
        "UPDATE dataset_search_index SET config_hash = '', status = 'SYNCING',"
            + CLEAR_BACKOFF
            + " updated_at = now() WHERE dataset_id = ?",
        datasetId);
  }

  /** 이번 스윕 대상: 백오프 대기 중이 아닌 것. */
  public List<Long> findDueDatasetIds() {
    return dsl.fetch(
            "SELECT dataset_id FROM dataset_search_index"
                + " WHERE next_attempt_at IS NULL OR next_attempt_at <= now() ORDER BY dataset_id")
        .map(r -> r.get(0, Long.class));
  }

  /**
   * 동기화 임대를 잡는다. 성공하면 true.
   *
   * <p>"임대가 비었거나 만료됐으면 now()+lease 로 설정"을 조건부 UPDATE 한 문장으로 한다 — PostgreSQL 은 같은 행의
   * UPDATE 를 행 잠금으로 직렬화하고, 뒤따른 쪽은 커밋된 새 값으로 WHERE 를 다시 평가하므로 두 인스턴스가 동시에
   * 시도해도 정확히 하나만 1행을 갱신한다(SELECT 후 UPDATE 의 경합 창이 없다). 상태 행이 없으면 0행이라 false.
   */
  public boolean tryAcquireLease(long datasetId, Duration lease) {
    return dsl.execute(
            "UPDATE dataset_search_index SET sync_lease_until = now() + (? * interval '1 second')"
                + " WHERE dataset_id = ? AND (sync_lease_until IS NULL OR sync_lease_until < now())",
            lease.toSeconds(),
            datasetId)
        == 1;
  }

  /** 임대를 푼다(동기화 성공·실패와 무관하게 finally 에서). 다음 스윕이 바로 다시 잡을 수 있다. */
  public void releaseLease(long datasetId) {
    dsl.execute("UPDATE dataset_search_index SET sync_lease_until = NULL WHERE dataset_id = ?", datasetId);
  }

  /** 전체 패스 시작 준비: 설정·모델·차원·원본 OID 를 기록하고 책갈피·진행 위치를 비운다. */
  public void resetForFullPass(long datasetId, String configHash, String model, int dim, long sourceOid) {
    dsl.execute(
        "UPDATE dataset_search_index SET config_hash = ?, embedding_model = ?, embedding_dim = ?,"
            + " source_table_oid = ?,"
            + RESET_PASS
            + " status = 'SYNCING', updated_at = now() WHERE dataset_id = ?",
        configHash, model, dim, sourceOid, datasetId);
  }

  /**
   * swap 재구축: OID 만 새 값으로(재구축 중 검색이 STALE 이 아니라 새 색인을 읽도록 즉시 갱신). 새 색인 테이블은 비어
   * 있으므로 진행률 분자(indexed_rows)도 0 에서 다시 센다.
   */
  public void markSourceOid(long datasetId, long sourceOid) {
    dsl.execute(
        "UPDATE dataset_search_index SET source_table_oid = ?,"
            + RESET_PASS
            + " status = 'SYNCING', updated_at = now() WHERE dataset_id = ?",
        sourceOid, datasetId);
  }

  /**
   * 패스 시작 시에만 책갈피 후보와 원본 전체 행 수(진행률 분모)를 기록한다. 여러 주기에 걸친 패스 도중 캡처한 후보로
   * 덮으면, 앞서 지나간 id 구간에서 그 사이 바뀐 행을 다음 패스가 놓친다.
   *
   * <p>jOOQ plain SQL 은 OffsetDateTime 을 varchar 로 바인딩하므로 {@code ?::timestamptz} 로 명시 캐스팅한다.
   */
  public void startPassIfNeeded(long datasetId, OffsetDateTime candidate, long totalRows) {
    dsl.execute(
        "UPDATE dataset_search_index SET pass_cursor = ?::timestamptz, total_rows = ?"
            + " WHERE dataset_id = ? AND pass_cursor IS NULL",
        candidate, totalRows, datasetId);
  }

  /**
   * 설정 변경 직후: 다음 스윕 전까지 SYNCING 으로 두고 백오프·오류를 해제한다(재색인 판단은 config_hash 가 한다).
   *
   * <p>이전 실패의 백오프(next_attempt_at)를 남기면 사용자가 방금 확인한 "다시 색인"이 백오프만큼 밀리고, 옛 설정의
   * 오류 메시지가 새 설정의 상태처럼 보인다 — 수동 재색인({@link #forceFull})과 같은 방식으로 지운다.
   */
  public void markSyncing(long datasetId) {
    dsl.execute(
        "UPDATE dataset_search_index SET status = 'SYNCING',"
            + CLEAR_BACKOFF
            + " updated_at = now() WHERE dataset_id = ?",
        datasetId);
  }

  /**
   * 배치 하나를 저장한 뒤 이어 처리할 위치와 진행률을 기록한다.
   *
   * <p>indexed_rows 는 count(*) 대신 배치가 보고한 증감(새로 넣은 행 − 지운 행)을 더한다 — 배치마다 색인 전체를 세면
   * 큰 테이블에서 O(n) 이 반복된다. 원본에서 삭제된 행은 완주 시 {@link #markCompleted} 가 실제 개수로 바로잡는다.
   */
  public void saveProgress(long datasetId, long resumeAfterId, long indexedDelta) {
    dsl.execute(
        "UPDATE dataset_search_index SET resume_after_id = ?, indexed_rows = GREATEST(indexed_rows + ?, 0),"
            + " status = 'SYNCING', updated_at = now() WHERE dataset_id = ?",
        resumeAfterId, indexedDelta, datasetId);
  }

  /** 패스 완주: 패스 시작 후보를 책갈피로 확정하고 오류·백오프를 해제한다. */
  public void markCompleted(long datasetId, long indexedRows, long totalRows) {
    dsl.execute(
        "UPDATE dataset_search_index SET sync_cursor = pass_cursor, pass_cursor = NULL, resume_after_id = NULL,"
            + " indexed_rows = ?, total_rows = ?, status = 'IDLE', consecutive_failures = 0,"
            + " next_attempt_at = NULL, last_error = NULL, last_synced_at = now(), updated_at = now()"
            + " WHERE dataset_id = ?",
        indexedRows, totalRows, datasetId);
  }

  /** 실패: 커서는 그대로 두고(다음 주기 재처리) 백오프를 건다. */
  public void markFailed(long datasetId, String error, OffsetDateTime nextAttemptAt) {
    dsl.execute(
        "UPDATE dataset_search_index SET status = 'ERROR', last_error = ?,"
            + " consecutive_failures = consecutive_failures + 1, next_attempt_at = ?::timestamptz, updated_at = now()"
            + " WHERE dataset_id = ?",
        error, nextAttemptAt, datasetId);
  }

  private static SearchIndexState toState(Record r) {
    return new SearchIndexState(
        r.get("dataset_id", Long.class),
        r.get("status", String.class),
        r.get("config_hash", String.class),
        r.get("embedding_model", String.class),
        r.get("embedding_dim", Integer.class),
        r.get("source_table_oid", Long.class),
        r.get("sync_cursor", OffsetDateTime.class),
        r.get("pass_cursor", OffsetDateTime.class),
        r.get("resume_after_id", Long.class),
        r.get("indexed_rows", Long.class),
        r.get("total_rows", Long.class),
        r.get("consecutive_failures", Integer.class),
        r.get("last_synced_at", OffsetDateTime.class),
        r.get("last_error", String.class));
  }
}
