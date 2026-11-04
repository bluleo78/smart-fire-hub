package com.smartfirehub.dataset.repository;

import com.smartfirehub.embedding.EmbeddingDimension;
import com.smartfirehub.embedding.EmbeddingSpace;
import com.smartfirehub.securitylevel.ai.EmbeddingAllowance;
import java.util.List;

/**
 * dataset_embedding upsert/update/delete.
 *
 * <p>가시성 설계상 source_text(동기, 외부호출 없음)와 embedding(비동기) 경로를 분리한다. 신규 행은 벡터 없이 시작해도 키워드(트라이그램) 검색에는 즉시
 * 노출된다.
 *
 * <p>벡터는 차원별 테이블(dataset_embedding_vec_N)에 둔다(#713).
 *
 * <p>구현은 {@code JooqDatasetEmbeddingRepository}(jOOQ/PostgreSQL) 이다 — WD-485.
 */
public interface DatasetEmbeddingRepository {

  /** source_text 만 동기 upsert(외부 호출 없음). 벡터는 차원 테이블에 따로 있다 — 새 행은 벡터 없이 시작해 키워드 검색에는 즉시 노출된다. */
  void upsertSourceText(long datasetId, String sourceText);

  /** 재임베딩 대상 데이터셋의 (id, source_text). source_text 를 그대로 임베딩한다. */
  record SourceTextRow(long datasetId, String sourceText) {}

  /**
   * 카탈로그 벡터 1건을 {@code space} 로 쓴다 — {@link #upsertEmbeddings} 의 단건 편의(적재 경로
   * DatasetEmbeddingService 가 쓴다).
   */
  void upsertEmbedding(EmbeddingSpace space, long datasetId, float[] embedding);

  /**
   * 카탈로그 벡터 여러 건을 {@code space} 로 쓴다: 다른 차원 행 DELETE(차원마다 {@code dataset_id = ANY(?)} 1회) + 현재 차원
   * UPSERT(JDBC 배치 1회) — 한 트랜잭션. 재임베딩 잡이 배치(64건)마다 한 번 부른다(행마다 부르면 배치당 트랜잭션이 64회 열린다 —
   * DocumentChunkRepository.upsertEmbeddings 와 같은 형태). 부모 dataset_embedding 행이 없으면(삭제 경합)
   * INSERT…SELECT 가 0행이라 그 건만 조용히 건너뛴다 — 옛 UPDATE 0행과 같은 의미.
   */
  void upsertEmbeddings(EmbeddingSpace space, List<Long> datasetIds, List<float[]> embeddings);

  /** 데이터셋 삭제 시 인덱스 행 제거(FK CASCADE 와 별개로 명시 호출 경로 제공). */
  void delete(long datasetId);

  /**
   * 이 데이터셋의 모든 차원 벡터를 지운다(카탈로그 행·source_text 는 남긴다 — 키워드 검색 유지). 등급이 임베딩 공급자를 허용하지 않게 된 데이터셋의 남은 벡터
   * 정리용(S3 §4.3). deleteVectorsOf 와 같이 RLS 와 별개로 {@code tenant_id} 를 명시한다 — 소유자 커넥션에서 불려도 남의 테넌트 행을
   * 지우지 않게. 테넌트 문맥이 없으면 예외(문맥 없이 돌면 조용히 0행이 된다).
   */
  void deleteVectors(long datasetId);

  /**
   * 여러 데이터셋의 모든 차원 벡터를 한 번에 지운다(카탈로그 행·source_text 는 남긴다). 정책 위반 외부 벡터 정리(AiVectorPurgeService, S3
   * §4.3)용 — 지운 행 수를 돌려준다. RLS 와 별개로 {@code tenant_id} 를 명시한다(소유자 커넥션에서 불려도 남의 행을 지우지 않게).
   */
  int deleteVectorsOf(List<Long> datasetIds);

  /**
   * 카탈로그 행 수(dataset_embedding). 진행률 분모 — 판정식과 같은 모집단이어야 100% 에 닿는다. {@code allowance} 는
   * EmbeddingAiGate#allowance() 가 캡처한 값이고 구현이 별칭 {@code de.dataset_id} 로 술어를 렌더한다 — 등급이 임베딩 공급자를
   * 허용하지 않는 데이터셋은 모집단에서 빠진다(S3 §4.3).
   */
  long countAll(EmbeddingAllowance allowance);

  /** {@code space} 로 임베딩된 데이터셋 수. */
  long countEmbedded(EmbeddingSpace space);

  /**
   * 재임베딩 판정식(현재 차원 테이블에 현재 모델 벡터가 없고 <b>등급이 임베딩 공급자를 허용하는</b> 카탈로그 행 수). {@code allowance} 는
   * EmbeddingAiGate#allowance() 가 캡처한 값(별칭 {@code de.dataset_id} 렌더는 구현) — 정책 없는 오버로드는 두지 않는다(우회
   * 경로가 된다).
   */
  long countMissing(EmbeddingSpace space, EmbeddingAllowance allowance);

  /** 판정식(등급 허용 포함)을 만족하는 행을 dataset_id 순으로 {@code limit} 건(키셋). */
  List<SourceTextRow> findMissing(
      EmbeddingSpace space, long afterDatasetId, int limit, EmbeddingAllowance allowance);

  /** 현재 차원이 아닌 테이블의 이 테넌트 카탈로그 벡터 삭제(WHERE tenant_id 명시 — 문서 청크 쪽과 같은 규율). */
  int deleteOtherDimensions(EmbeddingDimension keep);
}
