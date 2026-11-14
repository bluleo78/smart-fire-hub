package com.smartfirehub.dataset.repository.jooq;

import com.smartfirehub.dataset.repository.DatasetEmbeddingRepository;
import com.smartfirehub.embedding.EmbeddingDimension;
import com.smartfirehub.embedding.EmbeddingSpace;
import com.smartfirehub.embedding.SourceHash;
import com.smartfirehub.embedding.repository.EmbeddingMarkers;
import com.smartfirehub.global.platformdb.ConditionalOnPostgresPlatformDb;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.securitylevel.ai.EmbeddingAllowance;
import com.smartfirehub.securitylevel.repository.EmbeddingAiPolicyRepository;
import com.smartfirehub.vectorstore.DatasetVectorIndex;
import java.util.List;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * DatasetEmbeddingRepository 의 jOOQ(PostgreSQL) 구현 — WD-485.
 *
 * <p>dataset_embedding upsert/update/delete.
 *
 * <p>가시성 설계상 source_text(동기, 외부호출 없음)와 embedding(비동기) 경로를 분리한다. 신규 행은 벡터 없이 시작해도 키워드(트라이그램) 검색에는 즉시
 * 노출된다.
 *
 * <p>벡터는 차원별 테이블(dataset_embedding_vec_N)에 둔다(#713).
 *
 * <p>벡터 SQL 은 벡터 저장소 포트({@link DatasetVectorIndex}, ADR 0002 결정 2)로 나갔다. 이 리포지토리는 기반 테이블(원문·표시
 * 열·백로그 판정)만 다룬다. 벡터를 쓰거나 지우는 메서드는 포트를 부른 뒤 같은 트랜잭션(클래스 레벨 {@code @Transactional})에서 표시 열을 맞춘다.
 * pgvector 구현은 그 트랜잭션에 합류하므로 벡터 행과 표시 열이 함께 커밋되거나 함께 롤백된다.
 */
@Repository
@ConditionalOnPostgresPlatformDb
// 배경 잡(JobRunr/@Async/@Scheduled)은 앰비언트 트랜잭션이 없다. RLS GUC 는 트랜잭션 시작
// 시점에만 주입되므로, 트랜잭션이 없으면 TenantContext 를 세워도 정책이 전 행을 차단한다.
// REQUIRED 라 서비스가 이미 연 트랜잭션에는 합류한다(기존 경로 동작 불변).
@Transactional
public class JooqDatasetEmbeddingRepository implements DatasetEmbeddingRepository {

  private final DSLContext dsl;

  // 임베딩 등급 허용 술어 렌더(WD-485 T10) — 별칭 de.dataset_id 는 이 구현이 소유한다(예전 호출자가 넘기던 값 그대로).
  private final EmbeddingAiPolicyRepository aiPolicyRepository;

  // 벡터 저장소 포트 — 벡터 SQL 은 여기로 나갔다(ADR 0002 결정 2).
  private final DatasetVectorIndex vectorIndex;

  public JooqDatasetEmbeddingRepository(
      DSLContext dsl,
      EmbeddingAiPolicyRepository aiPolicyRepository,
      DatasetVectorIndex vectorIndex) {
    this.dsl = dsl;
    this.aiPolicyRepository = aiPolicyRepository;
    this.vectorIndex = vectorIndex;
  }

  /**
   * source_text 와 그 해시를 동기 upsert(외부 호출 없음). 벡터는 차원 테이블에 따로 있다 — 새 행은 벡터 없이 시작해 키워드 검색에는 즉시 노출된다.
   * 해시가 바뀌면 판정식이 이 행을 백로그로 본다(WD-720).
   */
  @Override
  public void upsertSourceText(long datasetId, String sourceText) {
    String sql =
        "INSERT INTO dataset_embedding(dataset_id, source_text, source_hash, updated_at) "
            + "VALUES (?, ?, ?, NOW()) "
            + "ON CONFLICT (dataset_id) DO UPDATE SET source_text = EXCLUDED.source_text,"
            + " source_hash = EXCLUDED.source_hash, updated_at = NOW()";
    dsl.execute(sql, datasetId, sourceText, SourceHash.of(sourceText));
  }

  /**
   * 카탈로그 벡터 1건을 {@code space} 로 쓴다 — {@link #upsertEmbeddings} 의 단건 편의(적재 경로
   * DatasetEmbeddingService 가 쓴다).
   */
  @Override
  public void upsertEmbedding(
      EmbeddingSpace space, long datasetId, float[] embedding, String sourceHash) {
    upsertEmbeddings(space, List.of(datasetId), List.of(embedding), List.of(sourceHash));
  }

  /**
   * 카탈로그 벡터 여러 건을 {@code space} 로 쓴다: 다른 차원 행 DELETE(차원마다 {@code dataset_id = ANY(?)} 1회) + 현재 차원
   * UPSERT(JDBC 배치 1회) — 한 트랜잭션. 재임베딩 잡이 배치(64건)마다 한 번 부른다(행마다 부르면 배치당 트랜잭션이 64회 열린다 —
   * DocumentChunkRepository.upsertEmbeddings 와 같은 형태). 부모 dataset_embedding 행이 없으면(삭제 경합)
   * INSERT…SELECT 가 0행이라 그 건만 조용히 건너뛴다 — 옛 UPDATE 0행과 같은 의미.
   */
  @Override
  public void upsertEmbeddings(
      EmbeddingSpace space,
      List<Long> datasetIds,
      List<float[]> embeddings,
      List<String> sourceHashes) {
    EmbeddingMarkers.requireSameSize(datasetIds, embeddings, sourceHashes);
    if (datasetIds.isEmpty()) return;
    vectorIndex.upsertDense(space, datasetIds, embeddings);
    // 표시 열을 같은 트랜잭션에서 새 공간으로 맞춘다 — 다른 차원 행은 포트가 지웠으므로 표시가 정확히 이 공간을 가리킨다.
    // 부모가 없는 id(삭제 경합)는 포트의 INSERT…SELECT 처럼 여기서도 0행이다. 원문이 그새 바뀐 행도 0행이다(WD-720).
    markEmbedded(space, datasetIds, sourceHashes);
  }

  /** 데이터셋 삭제 시 인덱스 행 제거(FK CASCADE 와 별개로 명시 호출 경로 제공). */
  @Override
  public void delete(long datasetId) {
    dsl.execute("DELETE FROM dataset_embedding WHERE dataset_id = ?", datasetId);
  }

  /**
   * 이 데이터셋의 모든 차원 벡터를 지운다(카탈로그 행·source_text 는 남긴다 — 키워드 검색 유지). 등급이 임베딩 공급자를 허용하지 않게 된 데이터셋의 남은 벡터
   * 정리용(S3 §4.3). deleteVectorsOf 와 같이 RLS 와 별개로 {@code tenant_id} 를 명시한다 — 소유자 커넥션에서 불려도 남의 테넌트 행을
   * 지우지 않게. 테넌트 문맥이 없으면 예외(문맥 없이 돌면 조용히 0행이 된다).
   */
  @Override
  public void deleteVectors(long datasetId) {
    long tenantId = TenantContext.require("데이터셋 벡터 정리");
    vectorIndex.deleteDense(tenantId, List.of(datasetId));
    clearMarkers(tenantId, new Long[] {datasetId});
  }

  /**
   * 여러 데이터셋의 모든 차원 벡터를 한 번에 지운다(카탈로그 행·source_text 는 남긴다). 정책 위반 외부 벡터 정리(AiVectorPurgeService, S3
   * §4.3)용 — 지운 행 수를 돌려준다. RLS 와 별개로 {@code tenant_id} 를 명시한다(소유자 커넥션에서 불려도 남의 행을 지우지 않게).
   */
  @Override
  public int deleteVectorsOf(List<Long> datasetIds) {
    if (datasetIds.isEmpty()) return 0;
    long tenantId = TenantContext.require("데이터셋 벡터 정리");
    int deleted = vectorIndex.deleteDense(tenantId, datasetIds);
    clearMarkers(tenantId, datasetIds.toArray(Long[]::new));
    return deleted;
  }

  /**
   * 카탈로그 행 수(dataset_embedding). 진행률 분모 — 판정식과 같은 모집단이어야 100% 에 닿는다. {@code allowance} 는
   * EmbeddingAiGate#allowance() 가 캡처한 값이고 구현이 별칭 {@code de.dataset_id} 로 술어를 렌더한다 — 등급이 임베딩 공급자를
   * 허용하지 않는 데이터셋은 모집단에서 빠진다(S3 §4.3).
   */
  @Override
  public long countAll(EmbeddingAllowance allowance) {
    return dsl.fetchOne("SELECT count(*) FROM dataset_embedding de WHERE " + allowedSql(allowance))
        .get(0, Long.class);
  }

  /**
   * {@code space} 로 임베딩된 데이터셋 수 — 표시 열로 센다(RLS 로 현재 테넌트). 원문이 바뀌어 낡은 벡터는 세지 않는다(판정식의 반대, WD-720).
   */
  @Override
  public long countEmbedded(EmbeddingSpace space) {
    return dsl.fetchOne(
            "SELECT count(*) FROM dataset_embedding de WHERE de.embedded_dim = ? AND de.embedded_model = ?"
                + " AND NOT "
                + STALE_PREDICATE,
            space.dimension().size(),
            space.model())
        .get(0, Long.class);
  }

  /**
   * 재임베딩 판정식(표시 열이 현재 공간이 아니고 <b>등급이 임베딩 공급자를 허용하는</b> 카탈로그 행 수). 표시 열은 벡터 행의 거울이라 예전 "현재 차원 테이블에
   * 현재 모델 벡터가 없음" anti-join 과 같은 결과다(WD-717). {@code allowance} 는 EmbeddingAiGate#allowance() 가 캡처한
   * 값(별칭 {@code de.dataset_id} 렌더는 구현) — 정책 없는 오버로드는 두지 않는다(우회 경로가 된다).
   */
  @Override
  public long countMissing(EmbeddingSpace space, EmbeddingAllowance allowance) {
    return dsl.fetchOne(
            "SELECT count(*) FROM dataset_embedding de WHERE "
                + MISSING_PREDICATE
                + " AND "
                + allowedSql(allowance),
            space.dimension().size(),
            space.model())
        .get(0, Long.class);
  }

  /** 판정식(등급 허용 포함)을 만족하는 행을 dataset_id 순으로 {@code limit} 건(키셋). */
  @Override
  public List<SourceTextRow> findMissing(
      EmbeddingSpace space, long afterDatasetId, int limit, EmbeddingAllowance allowance) {
    return dsl.fetch(
            "SELECT de.dataset_id, de.source_text FROM dataset_embedding de WHERE de.dataset_id > ? AND "
                + MISSING_PREDICATE
                + " AND "
                + allowedSql(allowance)
                + " ORDER BY de.dataset_id LIMIT ?",
            afterDatasetId,
            space.dimension().size(),
            space.model(),
            limit)
        .map(
            r ->
                new SourceTextRow(
                    r.get("dataset_id", Long.class), r.get("source_text", String.class)));
  }

  /** 현재 차원이 아닌 테이블의 이 테넌트 카탈로그 벡터 삭제(WHERE tenant_id 명시 — 문서 청크 쪽과 같은 규율). */
  @Override
  public int deleteOtherDimensions(EmbeddingDimension keep) {
    long tenantId = TenantContext.require("다른 차원 카탈로그 벡터 정리");
    int deleted = vectorIndex.deleteOtherDimensions(tenantId, keep);
    // 표시 열도 같은 트랜잭션에서 비운다 — 다른 차원을 가리키던 거울은 방금 지운 벡터를 가리키기 때문이다.
    dsl.execute(
        "UPDATE dataset_embedding SET embedded_dim = NULL, embedded_model = NULL, embedded_hash = NULL"
            + " WHERE tenant_id = ? AND embedded_dim <> ?",
        tenantId,
        keep.size());
    return deleted;
  }

  /**
   * 낡음 — 지금 원문 해시가 있고, 벡터를 만든 원문의 해시가 그와 다르거나 없다(WD-720). 지금 원문 해시가 없는 기존 행(V161 이전)은 낡았다고 보지
   * 않는다(예전처럼 공간만으로 판정). 바인드 없음.
   */
  private static final String STALE_PREDICATE =
      "(de.source_hash IS NOT NULL AND (de.embedded_hash IS NULL OR de.embedded_hash <> de.source_hash))";

  /**
   * 판정식 — 표시 열이 현재 공간(차원·모델)을 가리키지 않거나 원문이 바뀌어 낡은 행. 바인드 순서: 차원, 모델. NULL 을 명시적으로 다뤄 {@code IS
   * DISTINCT FROM} 이 없는 DB(Tibero)에도 같은 식을 옮길 수 있게 한다.
   */
  private static final String MISSING_PREDICATE =
      "(de.embedded_dim IS NULL OR de.embedded_dim <> ? OR de.embedded_model IS NULL"
          + " OR de.embedded_model <> ? OR "
          + STALE_PREDICATE
          + ")";

  /**
   * 표시 열 채우기 — 포트로 벡터를 쓴 메서드가 같은 트랜잭션에서 부른다. 표시 열은 플랫폼 DB 에 남는다(WD-718).
   *
   * <p>행마다 "벡터를 만든 원문 해시 = 지금 원문 해시" 일 때만 채운다(조건부 UPDATE, JDBC 배치 1회). 지금 해시가 없는 기존 행은 채우면서 넘긴 해시로
   * 원문 해시도 채운다 — 벡터를 만든 원문이 곧 저장된 원문이라는 V160 백필과 같은 전제다.
   */
  private void markEmbedded(EmbeddingSpace space, List<Long> datasetIds, List<String> hashes) {
    org.jooq.BatchBindStep batch =
        dsl.batch(
            "UPDATE dataset_embedding SET embedded_dim = ?, embedded_model = ?, embedded_hash = ?,"
                + " source_hash = COALESCE(source_hash, ?)"
                + " WHERE dataset_id = ? AND (source_hash IS NULL OR source_hash = ?)");
    for (int i = 0; i < datasetIds.size(); i++) {
      String hash = hashes.get(i);
      batch =
          batch.bind(space.dimension().size(), space.model(), hash, hash, datasetIds.get(i), hash);
    }
    batch.execute();
  }

  /** 표시 열 비우기 — 벡터를 지운 메서드가 같은 트랜잭션에서 부른다. 벡터 삭제처럼 {@code tenant_id} 를 명시한다. */
  private void clearMarkers(long tenantId, Long[] datasetIds) {
    dsl.execute(
        "UPDATE dataset_embedding SET embedded_dim = NULL, embedded_model = NULL, embedded_hash = NULL"
            + " WHERE tenant_id = ? AND dataset_id = ANY(?)",
        tenantId,
        datasetIds);
  }

  /**
   * 등급 허용 술어 — 예전 호출자가 넘기던 {@code aiGate.allowedDatasetSql("de.dataset_id")} 와 바이트 동일한 렌더다. 별칭은 이
   * 클래스 SQL 의 {@code dataset_embedding de} 에 맞춘 코드 상수다. 호스팅은 호출자가 캡처한 값({@link
   * EmbeddingAllowance})을 그대로 쓴다.
   */
  private String allowedSql(EmbeddingAllowance allowance) {
    return aiPolicyRepository.allowedDatasetSql("de.dataset_id", allowance.hosting());
  }

  @Override
  public java.util.Optional<String> findSourceText(long datasetId) {
    return dsl.fetchOptional(
            "SELECT source_text FROM dataset_embedding WHERE dataset_id = ?", datasetId)
        .map(r -> r.get(0, String.class));
  }

  /** 키워드 표시 채우기 — 행마다 조건부 UPDATE(JDBC 배치 1회). 원문 해시 판정은 {@link #markEmbedded} 와 같은 규칙이다. */
  @Override
  public void markKeywordIndexed(String rule, List<Long> datasetIds, List<String> sourceHashes) {
    if (datasetIds.size() != sourceHashes.size()) {
      throw new IllegalArgumentException(
          "id·원문 해시 수 불일치: " + datasetIds.size() + " vs " + sourceHashes.size());
    }
    if (datasetIds.isEmpty()) return;
    org.jooq.BatchBindStep batch =
        dsl.batch(
            "UPDATE dataset_embedding SET keyword_marker = ?, source_hash = COALESCE(source_hash, ?)"
                + " WHERE dataset_id = ? AND (source_hash IS NULL OR source_hash = ?)");
    for (int i = 0; i < datasetIds.size(); i++) {
      String hash = sourceHashes.get(i);
      batch = batch.bind(rule + ":" + hash, hash, datasetIds.get(i), hash);
    }
    batch.execute();
  }

  /**
   * 키워드 백로그 — 표시가 없거나, 원문 해시가 없거나(기존 행), 표시가 「규칙:지금 해시」 가 아닌 행. 규칙 표지는 바인드로 넘겨 SQL 에서 해시와 잇는다(해시
   * 계산은 SQL 에서 하지 않는다).
   */
  @Override
  public List<SourceTextRow> findKeywordMissing(String rule, long afterDatasetId, int limit) {
    return dsl.fetch(
            "SELECT de.dataset_id, de.source_text FROM dataset_embedding de WHERE de.dataset_id > ?"
                + " AND (de.keyword_marker IS NULL OR de.source_hash IS NULL"
                + " OR de.keyword_marker <> (CAST(? AS text) || de.source_hash))"
                + " ORDER BY de.dataset_id LIMIT ?",
            afterDatasetId,
            rule + ":",
            limit)
        .map(
            r ->
                new SourceTextRow(
                    r.get("dataset_id", Long.class), r.get("source_text", String.class)));
  }
}
