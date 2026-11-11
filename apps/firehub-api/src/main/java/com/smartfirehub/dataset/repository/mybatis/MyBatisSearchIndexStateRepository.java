package com.smartfirehub.dataset.repository.mybatis;

import com.smartfirehub.dataset.repository.SearchIndexStateRepository;
import com.smartfirehub.dataset.rowsearch.RowSearchSyncService;
import com.smartfirehub.dataset.rowsearch.SearchIndexState;
import com.smartfirehub.global.platformdb.mybatis.PlatformMyBatisRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

/**
 * SearchIndexStateRepository 의 MyBatis 구현(WD-707) — {@code JooqSearchIndexStateRepository} 와 같은 의미.
 *
 * <p>dataset_search_index 저장소(RLS — 트랜잭션 안에서만 테넌트 컨텍스트가 선다). 클래스 레벨 {@code @Transactional} 은 jOOQ
 * 구현 그대로다 — 스윕 스케줄러는 앰비언트 트랜잭션이 없으므로 호출마다 테넌트 트랜잭션이 열려야 정책이 행을 보여 준다.
 *
 * <p>빈 config_hash: PG 는 {@code ''}, Tibero 는 NULL 로 저장된다(Tibero 는 {@code ''} 를 NULL 로 다룬다). 「빈 해시
 * = 강제 전체 재색인」 의미가 두 DB 같도록 읽을 때 NULL 을 {@code ""} 로 맞춘다 — PG 열은 NOT NULL 이라 이 변환은 Tibero 에서만 일어난다.
 * 저장값에 센티널을 쓰지 않는다.
 *
 * <p>시각은 {@link Instant} 로 바인딩·조회하고 UTC {@link OffsetDateTime} 으로 바꾼다 — JVM 기본 시간대(KST 등)와 무관하다.
 */
@PlatformMyBatisRepository
@Transactional
public class MyBatisSearchIndexStateRepository implements SearchIndexStateRepository {

  /** last_error 에 쓰는 메시지 최대 글자 수 — UTF-8 로 최대 48000 바이트라 Tibero VARCHAR2(65532) 바이트 한도를 넘지 않는다. */
  static final int MAX_ERROR_CHARS = 16_000;

  private final SearchIndexStateMapper mapper;

  public MyBatisSearchIndexStateRepository(SearchIndexStateMapper mapper) {
    this.mapper = mapper;
  }

  /** 데이터셋의 색인 상태 행. 없으면(검색 꺼짐) 빈 값. */
  @Override
  public Optional<SearchIndexState> find(long datasetId) {
    return Optional.ofNullable(mapper.selectByDatasetId(datasetId))
        .map(MyBatisSearchIndexStateRepository::toState);
  }

  /**
   * 검색 켜기: 행이 없으면 만든다(빈 해시라 첫 스윕이 전체 재색인한다).
   *
   * <p>PG 는 ON CONFLICT DO NOTHING 이라 예외가 나지 않는다. Tibero 는 MERGE(WHEN NOT MATCHED) 인데, 같은 데이터셋을 동시에
   * 처음 켜거나 RLS 로 안 보이는 행과 PK 가 겹치면 중복 키 오류가 난다(문장 단위 실패라 트랜잭션은 산다). 중복 키는 「행이 이미 있다」는 뜻이므로 PG 의 DO
   * NOTHING 과 같게 무시한다 — 다시 시도해도 안 보이는 행이면 또 실패하므로 재시도하지 않는다. 이 분기는 Tibero 전용이다.
   */
  @Override
  public void createIfAbsent(long datasetId) {
    try {
      mapper.insertIfAbsent(datasetId);
    } catch (DuplicateKeyException alreadyExists) {
      // 이미 있음 — PG ON CONFLICT DO NOTHING 과 같은 결과.
    }
  }

  /** 색인 상태 행을 지운다(검색 끄기). 색인 테이블은 FK 가 없으므로 호출자가 따로 DROP 한다. */
  @Override
  public void delete(long datasetId) {
    mapper.delete(datasetId);
  }

  /** 수동 재색인: 해시를 비워 다음 스윕이 전체 재색인하게 하고 백오프를 해제한다. */
  @Override
  public void forceFull(long datasetId) {
    mapper.forceFull(datasetId);
  }

  /** 정책 정리(S3 §4.3): 모델을 키워드 전용 표식으로, 해시를 비우고 백오프를 푼다({@link #forceFull} 과 같은 방식). */
  @Override
  public void markKeywordOnly(long datasetId, String keywordOnlyModel) {
    mapper.markKeywordOnly(datasetId, keywordOnlyModel);
  }

  /** 이번 스윕 대상: 백오프 대기 중이 아닌 것(DB 시계 기준). */
  @Override
  public List<Long> findDueDatasetIds() {
    return mapper.selectDueDatasetIds();
  }

  /** 이 공간과 다른(또는 아직 없는) 모델·차원으로 만들어진 행 검색 색인 수 — 키워드 전용 색인은 세지 않는다. */
  @Override
  public long countStale(String model, int dim) {
    return mapper.countStale(model, dim, RowSearchSyncService.KEYWORD_ONLY_MODEL);
  }

  /** 동기화 임대를 잡는다(조건부 UPDATE 한 문장, DB 시계 기준). 상태 행이 없으면 0행이라 false. */
  @Override
  public boolean tryAcquireLease(long datasetId, Duration lease) {
    return mapper.tryAcquireLease(datasetId, lease.toSeconds()) == 1;
  }

  /** 임대를 푼다(동기화 성공·실패와 무관하게 finally 에서). */
  @Override
  public void releaseLease(long datasetId) {
    mapper.releaseLease(datasetId);
  }

  /** 전체 패스 시작 준비 — embedding_model 비교 후 갱신(인터페이스 javadoc). */
  @Override
  public boolean resetForFullPass(
      long datasetId,
      String configHash,
      String model,
      int dim,
      long sourceOid,
      String expectedModel) {
    return mapper.resetForFullPass(datasetId, configHash, model, dim, sourceOid, expectedModel)
        == 1;
  }

  /** swap 재구축: OID 만 새 값으로, 진행률 분자는 0 에서 다시 센다. */
  @Override
  public void markSourceOid(long datasetId, long sourceOid) {
    mapper.markSourceOid(datasetId, sourceOid);
  }

  /** 패스 시작 시에만 책갈피 후보와 원본 전체 행 수를 기록한다(jOOQ 의 ?::timestamptz 대신 Instant 바인딩). */
  @Override
  public void startPassIfNeeded(long datasetId, OffsetDateTime candidate, long totalRows) {
    mapper.startPassIfNeeded(datasetId, toInstant(candidate), totalRows);
  }

  /** 설정 변경 직후: SYNCING 으로 두고 백오프·오류를 해제한다. */
  @Override
  public void markSyncing(long datasetId) {
    mapper.markSyncing(datasetId);
  }

  /** 배치 하나 저장 뒤 재개 위치와 진행률(증감, 0 미만 금지)을 기록한다. */
  @Override
  public void saveProgress(long datasetId, long resumeAfterId, long indexedDelta) {
    mapper.saveProgress(datasetId, resumeAfterId, indexedDelta);
  }

  /** 패스 완주: 패스 시작 후보를 책갈피로 확정하고 오류·백오프를 해제한다. */
  @Override
  public void markCompleted(long datasetId, long indexedRows, long totalRows) {
    mapper.markCompleted(datasetId, indexedRows, totalRows);
  }

  /** 실패: 커서는 그대로 두고 백오프를 건다. 오류 메시지는 {@link #truncateError} 로 열 한도 안에 자른다. */
  @Override
  public void markFailed(long datasetId, String error, OffsetDateTime nextAttemptAt) {
    mapper.markFailed(datasetId, truncateError(error), toInstant(nextAttemptAt));
  }

  /** 대기: 사유만 남기고 잠시 뒤 다시 due 가 되게 한다(실패 횟수는 그대로). 사유도 열 한도 안에 자른다. */
  @Override
  public void markWaiting(long datasetId, String reason, OffsetDateTime nextAttemptAt) {
    mapper.markWaiting(datasetId, truncateError(reason), toInstant(nextAttemptAt));
  }

  /**
   * last_error 에 쓸 메시지를 {@link #MAX_ERROR_CHARS} 글자 이하로 자른다.
   *
   * <p>왜: Tibero 의 last_error 는 VARCHAR2(65532) — 바이트 한도다. 긴(또는 한글처럼 여러 바이트인) 오류 메시지를 그대로 넣으면
   * UPDATE 가 실패해 백오프가 기록되지 않고, 같은 데이터셋을 곧바로 다시 집는다. UTF-8 한 글자(char)는 최대 3바이트(보충 문자는 char 두 개에
   * 4바이트)라 16000 글자면 48000 바이트를 넘지 않는다. MyBatis 구현은 PG 에서도 같은 길이로 잘라 두 DB 의 MyBatis 결과를 맞춘다(jOOQ 판은
   * PG TEXT 라 자르지 않는다 — 계약은 「원문의 접두어」만 요구한다). 보충 문자의 짝(서로게이트)을 반으로 가르지 않게 끝이 상위 서로게이트면 한 글자 덜 자른다.
   */
  static String truncateError(String message) {
    if (message == null || message.length() <= MAX_ERROR_CHARS) {
      return message;
    }
    int end = MAX_ERROR_CHARS;
    if (Character.isHighSurrogate(message.charAt(end - 1))) {
      end--;
    }
    return message.substring(0, end);
  }

  private static Instant toInstant(OffsetDateTime t) {
    return t == null ? null : t.toInstant();
  }

  private static OffsetDateTime toUtc(Instant t) {
    return t == null ? null : OffsetDateTime.ofInstant(t, ZoneOffset.UTC);
  }

  /** 원시 행 → 도메인 상태. 빈 해시는 두 DB 모두 "" (Tibero NULL → ""). */
  private static SearchIndexState toState(SearchIndexStateMapper.Row r) {
    return new SearchIndexState(
        r.datasetId(),
        r.status(),
        r.configHash() == null ? "" : r.configHash(),
        r.embeddingModel(),
        r.embeddingDim(),
        r.sourceTableOid(),
        toUtc(r.syncCursor()),
        toUtc(r.passCursor()),
        r.resumeAfterId(),
        r.indexedRows(),
        r.totalRows(),
        r.consecutiveFailures(),
        toUtc(r.lastSyncedAt()),
        r.lastError());
  }
}
