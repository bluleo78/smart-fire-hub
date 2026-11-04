package com.smartfirehub.dataset.repository;

import com.smartfirehub.dataset.rowsearch.SearchIndexState;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * dataset_search_index 저장소(RLS — 트랜잭션 안에서만 테넌트 GUC 가 선다).
 *
 * <p>동시 실행 방지는 advisory lock 대신 임대(sync_lease_until) 컬럼으로 한다 — sync 는 여러 트랜잭션에 걸치고 세션 락은 풀 커넥션에서 새기
 * 때문이다.
 *
 * <p>구현은 {@code JooqSearchIndexStateRepository}(jOOQ/PostgreSQL) 이다 — WD-485.
 */
public interface SearchIndexStateRepository {

  /** 데이터셋의 색인 상태 행. 없으면(검색 꺼짐) 빈 값. */
  Optional<SearchIndexState> find(long datasetId);

  /** 검색 켜기: 행이 없으면 만든다. config_hash='' 이라 첫 스윕이 전체 재색인한다. */
  void createIfAbsent(long datasetId);

  /** 색인 상태 행을 지운다(검색 끄기). 색인 테이블은 FK 가 없으므로 호출자가 따로 DROP 한다. */
  void delete(long datasetId);

  /** 수동 재색인: 해시를 비워 다음 스윕이 전체 재색인하게 하고 백오프를 해제한다. */
  void forceFull(long datasetId);

  /**
   * 정책 정리(S3 §4.3): 상태 모델을 즉시 키워드 전용 표식으로 바꾼다 — 다음 스윕 전에도 검색이 키워드 경로를 타(질의 임베딩 안 함) 외부 공급자로 나가지
   * 않는다. 해시를 비우고 백오프를 풀어 다음 스윕이 바로 전체 재색인하게 한다({@link #forceFull} 과 같은 방식).
   */
  void markKeywordOnly(long datasetId, String keywordOnlyModel);

  /** 이번 스윕 대상: 백오프 대기 중이 아닌 것. */
  List<Long> findDueDatasetIds();

  /**
   * 이 공간과 다른(또는 아직 없는) 모델·차원으로 만들어진 행 검색 색인 수. 저장 전 비용 안내용(스윕이 스스로 재색인한다). 키워드 전용 색인(S3 — 등급이 임베딩
   * 공급자를 허용하지 않음)은 임베딩 공간이 바뀌어도 재임베딩하지 않으므로 세지 않는다.
   */
  long countStale(String model, int dim);

  /**
   * 동기화 임대를 잡는다. 성공하면 true.
   *
   * <p>"임대가 비었거나 만료됐으면 now()+lease 로 설정"을 조건부 UPDATE 한 문장으로 한다 — PostgreSQL 은 같은 행의 UPDATE 를 행 잠금으로
   * 직렬화하고, 뒤따른 쪽은 커밋된 새 값으로 WHERE 를 다시 평가하므로 두 인스턴스가 동시에 시도해도 정확히 하나만 1행을 갱신한다(SELECT 후 UPDATE 의 경합
   * 창이 없다). 상태 행이 없으면 0행이라 false.
   */
  boolean tryAcquireLease(long datasetId, Duration lease);

  /** 임대를 푼다(동기화 성공·실패와 무관하게 finally 에서). 다음 스윕이 바로 다시 잡을 수 있다. */
  void releaseLease(long datasetId);

  /**
   * 전체 패스 시작 준비: 설정·모델·차원·원본 OID 를 기록하고 책갈피·진행 위치를 비운다.
   *
   * <p><b>비교 후 갱신</b>: 상태 행의 embedding_model 이 아직 {@code expectedModel}(동기화가 임대를 잡고 읽은 값)일 때만 쓴다.
   * 정책 정리 (AiVectorPurgeService)는 임대 없이 {@link #markKeywordOnly} 로 모델을 키워드 전용 표식으로 바꾸는데, 진행 중인 동기화가
   * 그 뒤에 무조건 덮어쓰면 표식이 실제 모델로 되돌아가 정리가 무력화된다. 키워드 전용→실제 모델(허용으로 바뀜) 전이도 이 메서드를 거치므로 "키워드 전용이면 거부" 가
   * 아니라 "읽은 뒤 바뀌었으면 거부" 로 둔다.
   *
   * @return 갱신했으면 true, 그 사이 모델이 바뀌어(정리 개입) 쓰지 않았으면 false
   */
  boolean resetForFullPass(
      long datasetId,
      String configHash,
      String model,
      int dim,
      long sourceOid,
      String expectedModel);

  /**
   * swap 재구축: OID 만 새 값으로(재구축 중 검색이 STALE 이 아니라 새 색인을 읽도록 즉시 갱신). 새 색인 테이블은 비어 있으므로 진행률
   * 분자(indexed_rows)도 0 에서 다시 센다.
   */
  void markSourceOid(long datasetId, long sourceOid);

  /**
   * 패스 시작 시에만 책갈피 후보와 원본 전체 행 수(진행률 분모)를 기록한다. 여러 주기에 걸친 패스 도중 캡처한 후보로 덮으면, 앞서 지나간 id 구간에서 그 사이 바뀐
   * 행을 다음 패스가 놓친다.
   *
   * <p>jOOQ plain SQL 은 OffsetDateTime 을 varchar 로 바인딩하므로 {@code ?::timestamptz} 로 명시 캐스팅한다.
   */
  void startPassIfNeeded(long datasetId, OffsetDateTime candidate, long totalRows);

  /**
   * 설정 변경 직후: 다음 스윕 전까지 SYNCING 으로 두고 백오프·오류를 해제한다(재색인 판단은 config_hash 가 한다).
   *
   * <p>이전 실패의 백오프(next_attempt_at)를 남기면 사용자가 방금 확인한 "다시 색인"이 백오프만큼 밀리고, 옛 설정의 오류 메시지가 새 설정의 상태처럼
   * 보인다 — 수동 재색인({@link #forceFull})과 같은 방식으로 지운다.
   */
  void markSyncing(long datasetId);

  /**
   * 배치 하나를 저장한 뒤 이어 처리할 위치와 진행률을 기록한다.
   *
   * <p>indexed_rows 는 count(*) 대신 배치가 보고한 증감(새로 넣은 행 − 지운 행)을 더한다 — 배치마다 색인 전체를 세면 큰 테이블에서 O(n) 이
   * 반복된다. 원본에서 삭제된 행은 완주 시 {@link #markCompleted} 가 실제 개수로 바로잡는다.
   */
  void saveProgress(long datasetId, long resumeAfterId, long indexedDelta);

  /** 패스 완주: 패스 시작 후보를 책갈피로 확정하고 오류·백오프를 해제한다. */
  void markCompleted(long datasetId, long indexedRows, long totalRows);

  /** 실패: 커서는 그대로 두고(다음 주기 재처리) 백오프를 건다. */
  void markFailed(long datasetId, String error, OffsetDateTime nextAttemptAt);

  /**
   * 대기: 사유만 남기고 잠시 뒤 다시 due 가 되게 한다. {@link #markFailed} 와 달리 실패 횟수를 올리지 않는다 — 임베딩 미설정(#713)은 장애가
   * 아니라 설정 대기라, 지수 백오프(최대 30분)에 빠지면 설정 저장 뒤에도 한참 degraded 로 남는다.
   */
  void markWaiting(long datasetId, String reason, OffsetDateTime nextAttemptAt);
}
