package com.smartfirehub.dataset.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.PlatformMapper;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * dataset_search_index MyBatis 매퍼(WD-707). SQL 은 {@code
 * mybatis/dataset/SearchIndexStateMapper.xml}.
 *
 * <p>시각 파라미터는 {@link Instant} 로 받는다 — Tibero 는 전역 {@code UtcInstantTypeHandler} 가 UTC 벽시계로, PG 는 기본
 * 핸들러가 timestamptz 순간으로 바인딩해 JVM 기본 시간대와 무관하다.
 */
@PlatformMapper
public interface SearchIndexStateMapper {

  /** 한 행(없으면 null). 시각은 Instant 로 읽는다. */
  Row selectByDatasetId(@Param("datasetId") long datasetId);

  /** 행이 없을 때만 넣는다(PG ON CONFLICT DO NOTHING, Tibero MERGE WHEN NOT MATCHED). */
  int insertIfAbsent(@Param("datasetId") long datasetId);

  /** 색인 상태 행을 지운다. */
  int delete(@Param("datasetId") long datasetId);

  /** config_hash 를 비우고 백오프를 풀어 다음 주기에 전체 재색인되게 한다. */
  int forceFull(@Param("datasetId") long datasetId);

  /** 키워드 전용 모델로 표시하고 config_hash 를 비운다. */
  int markKeywordOnly(
      @Param("datasetId") long datasetId, @Param("keywordOnlyModel") String keywordOnlyModel);

  /** 지금 처리할 차례인 데이터셋 id(백오프가 끝났거나 없는 행, dataset_id 순). */
  List<Long> selectDueDatasetIds();

  /** 현재 임베딩 모델·차원과 다른 공간으로 색인된 행 수(키워드 전용은 뺀다). */
  long countStale(
      @Param("model") String model,
      @Param("dim") int dim,
      @Param("keywordOnlyModel") String keywordOnlyModel);

  /** 임대가 비었거나 만료됐을 때만 DB 시각 + seconds 까지 임대를 잡고 잡은 행 수(0 또는 1)를 돌려준다. */
  int tryAcquireLease(@Param("datasetId") long datasetId, @Param("seconds") long seconds);

  /** 임대를 푼다. */
  int releaseLease(@Param("datasetId") long datasetId);

  /** 기대 모델과 같을 때만(비교 후 교체) 새 설정으로 패스를 처음부터 다시 시작하게 한다. */
  int resetForFullPass(
      @Param("datasetId") long datasetId,
      @Param("configHash") String configHash,
      @Param("model") String model,
      @Param("dim") int dim,
      @Param("sourceOid") long sourceOid,
      @Param("expectedModel") String expectedModel);

  /** 원본 테이블 식별자를 바꾸고 진행 위치만 처음으로 돌린다. */
  int markSourceOid(@Param("datasetId") long datasetId, @Param("sourceOid") long sourceOid);

  /** 패스 시작 때만 책갈피 후보와 원본 전체 행 수를 기록한다. */
  int startPassIfNeeded(
      @Param("datasetId") long datasetId,
      @Param("candidate") Instant candidate,
      @Param("totalRows") long totalRows);

  /** SYNCING 으로 두고 백오프·오류를 해제한다. */
  int markSyncing(@Param("datasetId") long datasetId);

  /** 재개 위치와 진행 행 수(증감, 0 미만 금지)를 기록한다. */
  int saveProgress(
      @Param("datasetId") long datasetId,
      @Param("resumeAfterId") long resumeAfterId,
      @Param("indexedDelta") long indexedDelta);

  /** 패스 완주 — 책갈피를 확정하고 오류·백오프를 해제한다. */
  int markCompleted(
      @Param("datasetId") long datasetId,
      @Param("indexedRows") long indexedRows,
      @Param("totalRows") long totalRows);

  /** 실패 — 오류를 남기고 실패 횟수를 올려 nextAttemptAt 까지 백오프한다. */
  int markFailed(
      @Param("datasetId") long datasetId,
      @Param("error") String error,
      @Param("nextAttemptAt") Instant nextAttemptAt);

  /** 대기 — 사유만 남기고 nextAttemptAt 에 다시 due 가 되게 한다(실패 횟수는 그대로). */
  int markWaiting(
      @Param("datasetId") long datasetId,
      @Param("reason") String reason,
      @Param("nextAttemptAt") Instant nextAttemptAt);

  /** dataset_search_index 한 행의 원시 값 — 시각은 Instant, config_hash 는 Tibero 에서 빈 해시면 null 이다. */
  record Row(
      long datasetId,
      String status,
      String configHash,
      String embeddingModel,
      Integer embeddingDim,
      Long sourceTableOid,
      Instant syncCursor,
      Instant passCursor,
      Long resumeAfterId,
      long indexedRows,
      long totalRows,
      int consecutiveFailures,
      Instant lastSyncedAt,
      String lastError) {}
}
