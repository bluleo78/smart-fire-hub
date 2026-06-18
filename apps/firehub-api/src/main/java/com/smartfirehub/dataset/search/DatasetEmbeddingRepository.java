package com.smartfirehub.dataset.search;

import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/**
 * dataset_embedding upsert/update/delete.
 *
 * <p>가시성 설계상 source_text(동기, 외부호출 없음)와 embedding(비동기) 경로를 분리한다. 신규 행은 embedding=null 로
 * 시작해도 키워드(트라이그램) 검색에는 즉시 노출된다.
 */
@Repository
public class DatasetEmbeddingRepository {

  private final DSLContext dsl;

  public DatasetEmbeddingRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  /** source_text 만 동기 upsert(외부 호출 없음). embedding 은 건드리지 않음(신규행 embedding=null 로 시작). */
  public void upsertSourceText(long datasetId, String sourceText) {
    String sql =
        "INSERT INTO dataset_embedding(dataset_id, source_text, updated_at) "
            + "VALUES (?, ?, NOW()) "
            + "ON CONFLICT (dataset_id) DO UPDATE SET source_text = EXCLUDED.source_text, updated_at = NOW()";
    dsl.execute(sql, datasetId, sourceText);
  }

  /** embedding 만 비동기 갱신. 벡터는 A4 와 동일하게 텍스트 리터럴 + {@code ?::vector} 캐스팅으로 바인딩한다. */
  public void updateEmbedding(long datasetId, float[] embedding, String model) {
    String sql =
        "UPDATE dataset_embedding SET embedding = ?::vector, embedding_model = ?, updated_at = NOW() "
            + "WHERE dataset_id = ?";
    dsl.execute(sql, VectorLiterals.toVectorLiteral(embedding), model, datasetId);
  }

  /** 데이터셋 삭제 시 인덱스 행 제거(FK CASCADE 와 별개로 명시 호출 경로 제공). */
  public void delete(long datasetId) {
    dsl.execute("DELETE FROM dataset_embedding WHERE dataset_id = ?", datasetId);
  }

  /** 전체 데이터셋 수. 임베딩 진행률 계산의 분모. */
  public long countAllDatasets() {
    return dsl.fetchOne("SELECT COUNT(*) FROM dataset").get(0, Long.class);
  }

  /** 특정 모델로 임베딩이 채워진 데이터셋 수. 임베딩 진행/완료 판단에 사용. */
  public long countEmbeddedByModel(String model) {
    return dsl.fetchOne(
            "SELECT COUNT(*) FROM dataset_embedding WHERE embedding IS NOT NULL AND embedding_model = ?",
            model)
        .get(0, Long.class);
  }
}
