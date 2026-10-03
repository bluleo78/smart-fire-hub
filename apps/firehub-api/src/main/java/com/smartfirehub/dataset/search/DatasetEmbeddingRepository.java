package com.smartfirehub.dataset.search;

import com.smartfirehub.embedding.EmbeddingDimension;
import com.smartfirehub.embedding.EmbeddingSpace;
import com.smartfirehub.embedding.VectorTables;
import com.smartfirehub.global.tenant.TenantContext;
import java.util.List;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * dataset_embedding upsert/update/delete.
 *
 * <p>가시성 설계상 source_text(동기, 외부호출 없음)와 embedding(비동기) 경로를 분리한다. 신규 행은 벡터 없이 시작해도 키워드(트라이그램) 검색에는 즉시
 * 노출된다.
 *
 * <p>벡터는 차원별 테이블(dataset_embedding_vec_N)에 둔다(#713).
 */
@Repository
// 배경 잡(JobRunr/@Async/@Scheduled)은 앰비언트 트랜잭션이 없다. RLS GUC 는 트랜잭션 시작
// 시점에만 주입되므로, 트랜잭션이 없으면 TenantContext 를 세워도 정책이 전 행을 차단한다.
// REQUIRED 라 서비스가 이미 연 트랜잭션에는 합류한다(기존 경로 동작 불변).
@Transactional
public class DatasetEmbeddingRepository {

  private final DSLContext dsl;

  public DatasetEmbeddingRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  /** source_text 만 동기 upsert(외부 호출 없음). 벡터는 차원 테이블에 따로 있다 — 새 행은 벡터 없이 시작해 키워드 검색에는 즉시 노출된다. */
  public void upsertSourceText(long datasetId, String sourceText) {
    String sql =
        "INSERT INTO dataset_embedding(dataset_id, source_text, updated_at) "
            + "VALUES (?, ?, NOW()) "
            + "ON CONFLICT (dataset_id) DO UPDATE SET source_text = EXCLUDED.source_text, updated_at = NOW()";
    dsl.execute(sql, datasetId, sourceText);
  }

  /** 재임베딩 대상 데이터셋의 (id, source_text). source_text 를 그대로 임베딩한다. */
  public record SourceTextRow(long datasetId, String sourceText) {}

  /**
   * 카탈로그 벡터 1건을 {@code space} 로 쓴다 — {@link #upsertEmbeddings} 의 단건 편의(적재 경로
   * DatasetEmbeddingService 가 쓴다).
   */
  public void upsertEmbedding(EmbeddingSpace space, long datasetId, float[] embedding) {
    upsertEmbeddings(space, List.of(datasetId), List.of(embedding));
  }

  /**
   * 카탈로그 벡터 여러 건을 {@code space} 로 쓴다: 다른 차원 행 DELETE(차원마다 {@code dataset_id = ANY(?)} 1회) + 현재 차원
   * UPSERT(JDBC 배치 1회) — 한 트랜잭션. 재임베딩 잡이 배치(64건)마다 한 번 부른다(행마다 부르면 배치당 트랜잭션이 64회 열린다 —
   * DocumentChunkRepository.upsertEmbeddings 와 같은 형태). 부모 dataset_embedding 행이 없으면(삭제 경합)
   * INSERT…SELECT 가 0행이라 그 건만 조용히 건너뛴다 — 옛 UPDATE 0행과 같은 의미.
   */
  public void upsertEmbeddings(
      EmbeddingSpace space, List<Long> datasetIds, List<float[]> embeddings) {
    if (datasetIds.size() != embeddings.size()) {
      throw new IllegalArgumentException(
          "데이터셋 수와 임베딩 수 불일치: " + datasetIds.size() + " vs " + embeddings.size());
    }
    if (datasetIds.isEmpty()) return;
    Long[] ids = datasetIds.toArray(Long[]::new);
    for (EmbeddingDimension d : EmbeddingDimension.values()) {
      if (d == space.dimension()) continue;
      dsl.execute("DELETE FROM " + d.datasetTable() + " WHERE dataset_id = ANY(?)", (Object) ids);
    }
    org.jooq.BatchBindStep batch =
        dsl.batch(
            "INSERT INTO "
                + space.dimension().datasetTable()
                + " (dataset_id, embedding, embedding_model, updated_at)"
                + " SELECT de.dataset_id, ?::vector, ?, now() FROM dataset_embedding de WHERE de.dataset_id = ?"
                + " ON CONFLICT (dataset_id) DO UPDATE SET embedding = EXCLUDED.embedding,"
                + " embedding_model = EXCLUDED.embedding_model, updated_at = now()");
    for (int i = 0; i < datasetIds.size(); i++) {
      batch =
          batch.bind(
              VectorLiterals.toVectorLiteral(embeddings.get(i)), space.model(), datasetIds.get(i));
    }
    batch.execute();
  }

  /** 데이터셋 삭제 시 인덱스 행 제거(FK CASCADE 와 별개로 명시 호출 경로 제공). */
  public void delete(long datasetId) {
    dsl.execute("DELETE FROM dataset_embedding WHERE dataset_id = ?", datasetId);
  }

  /** 카탈로그 행 수(dataset_embedding). 진행률 분모 — 판정식과 같은 모집단이어야 100% 에 닿는다. */
  public long countAll() {
    return dsl.fetchOne("SELECT count(*) FROM dataset_embedding").get(0, Long.class);
  }

  /** {@code space} 로 임베딩된 데이터셋 수. */
  public long countEmbedded(EmbeddingSpace space) {
    return dsl.fetchOne(
            "SELECT count(*) FROM "
                + space.dimension().datasetTable()
                + " WHERE embedding_model = ?",
            space.model())
        .get(0, Long.class);
  }

  /** 재임베딩 판정식(현재 차원 테이블에 현재 모델 벡터가 없는 카탈로그 행 수). */
  public long countMissing(EmbeddingSpace space) {
    return dsl.fetchOne(
            "SELECT count(*) FROM dataset_embedding de WHERE " + missingPredicate(space),
            space.model())
        .get(0, Long.class);
  }

  /** 판정식을 만족하는 행을 dataset_id 순으로 {@code limit} 건(키셋). */
  public List<SourceTextRow> findMissing(EmbeddingSpace space, long afterDatasetId, int limit) {
    return dsl.fetch(
            "SELECT de.dataset_id, de.source_text FROM dataset_embedding de WHERE de.dataset_id > ? AND "
                + missingPredicate(space)
                + " ORDER BY de.dataset_id LIMIT ?",
            afterDatasetId,
            space.model(),
            limit)
        .map(
            r ->
                new SourceTextRow(
                    r.get("dataset_id", Long.class), r.get("source_text", String.class)));
  }

  /** 현재 차원이 아닌 테이블의 이 테넌트 카탈로그 벡터 삭제(WHERE tenant_id 명시 — 문서 청크 쪽과 같은 규율). */
  public int deleteOtherDimensions(EmbeddingDimension keep) {
    return VectorTables.deleteOtherDimensions(
        dsl, keep, EmbeddingDimension::datasetTable, TenantContext.require("다른 차원 카탈로그 벡터 정리"));
  }

  private static String missingPredicate(EmbeddingSpace space) {
    return "NOT EXISTS (SELECT 1 FROM "
        + space.dimension().datasetTable()
        + " v WHERE v.dataset_id = de.dataset_id AND v.embedding_model = ?)";
  }
}
