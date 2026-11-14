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

  /**
   * source_text 만 동기 upsert(외부 호출 없음). 벡터는 차원 테이블에 따로 있다 — 새 행은 벡터 없이 시작해 키워드 검색에는 즉시 노출된다.
   *
   * <p>같은 문장에서 원문 해시({@code source_hash}, {@link com.smartfirehub.embedding.SourceHash})도
   * 쓴다(WD-720). 원문이 바뀌면 벡터를 만든 원문의 해시({@code embedded_hash})와 달라져 백로그로 잡힌다. 표시를 비우지 않고 해시를 비교하므로,
   * 내용이 같은 편집(태그 순서만 바뀐 경우 등)은 재임베딩을 부르지 않는다.
   */
  void upsertSourceText(long datasetId, String sourceText);

  /** 재임베딩 대상 데이터셋의 (id, source_text). source_text 를 그대로 임베딩한다. */
  record SourceTextRow(long datasetId, String sourceText) {}

  /**
   * 카탈로그 벡터 1건을 {@code space} 로 쓴다 — {@link #upsertEmbeddings} 의 단건 편의(적재 경로
   * DatasetEmbeddingService 가 쓴다).
   *
   * @param sourceHash 이 벡터를 만든 원문의 해시({@link com.smartfirehub.embedding.SourceHash#of})
   */
  void upsertEmbedding(EmbeddingSpace space, long datasetId, float[] embedding, String sourceHash);

  /**
   * 카탈로그 벡터 여러 건을 {@code space} 로 쓴다: 다른 차원 행 DELETE(차원마다 {@code dataset_id = ANY(?)} 1회) + 현재 차원
   * UPSERT(JDBC 배치 1회) — 한 트랜잭션. 재임베딩 잡이 배치(64건)마다 한 번 부른다(행마다 부르면 배치당 트랜잭션이 64회 열린다 —
   * DocumentChunkRepository.upsertEmbeddings 와 같은 형태). 부모 dataset_embedding 행이 없으면(삭제 경합)
   * INSERT…SELECT 가 0행이라 그 건만 조용히 건너뛴다 — 옛 UPDATE 0행과 같은 의미.
   *
   * <p>표시(공간·{@code embedded_hash})는 <b>벡터를 만든 원문의 해시가 지금 원문 해시와 같을 때만</b> 채운다(WD-720, ADR 0002 결정
   * 4 ③). 옛 원문으로 돌던 늦은 재임베딩이 끝나도 표시가 차지 않아 그 행은 백로그에 남고 다음 재임베딩이 새 원문으로 다시 만든다. 지금 원문 해시가 아직 없는 기존
   * 행(V161 이전, 해시 NULL)은 예전처럼 채우고, 이때 넘긴 해시로 {@code source_hash} 를 함께 채운다.
   *
   * @param sourceHashes 각 벡터를 만든 원문의 해시. {@code datasetIds} 와 순서가 같다
   * @throws IllegalArgumentException id·벡터·해시 수가 다를 때
   */
  void upsertEmbeddings(
      EmbeddingSpace space,
      List<Long> datasetIds,
      List<float[]> embeddings,
      List<String> sourceHashes);

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

  /** {@code space} 로 임베딩된 데이터셋 수. 원문이 바뀌어 벡터가 낡은 행은 세지 않는다(WD-720). */
  long countEmbedded(EmbeddingSpace space);

  /**
   * 재임베딩 판정식(현재 공간 벡터가 없거나 <b>원문이 바뀌어 낡았고</b>, <b>등급이 임베딩 공급자를 허용하는</b> 카탈로그 행 수). {@code
   * allowance} 는 EmbeddingAiGate#allowance() 가 캡처한 값(별칭 {@code de.dataset_id} 렌더는 구현) — 정책 없는 오버로드는
   * 두지 않는다(우회 경로가 된다).
   */
  long countMissing(EmbeddingSpace space, EmbeddingAllowance allowance);

  /** 판정식(등급 허용 포함)을 만족하는 행을 dataset_id 순으로 {@code limit} 건(키셋). */
  List<SourceTextRow> findMissing(
      EmbeddingSpace space, long afterDatasetId, int limit, EmbeddingAllowance allowance);

  /** 현재 차원이 아닌 테이블의 이 테넌트 카탈로그 벡터 삭제(WHERE tenant_id 명시 — 문서 청크 쪽과 같은 규율). */
  int deleteOtherDimensions(EmbeddingDimension keep);

  /** 이 데이터셋의 지금 원문. 행이 없으면(삭제됨) empty — 커밋 뒤 키워드 쓰기가 읽는다(WD-720). */
  java.util.Optional<String> findSourceText(long datasetId);

  /**
   * 키워드 표시({@code keyword_marker} = {@code rule + ":" + 원문 해시})를 채운다(WD-720, ADR 0002 결정 4 키워드 표시).
   * 키워드를 만든 원문의 해시가 지금 원문 해시와 같을 때만 채운다 — 그새 원문이 바뀌었으면 표시가 비어 백로그가 새 원문으로 다시 쓴다. 지금 해시가 없는 기존 행은 넘긴
   * 해시로 함께 채운다.
   *
   * @param rule 키워드 생성 규칙 표지(포트의 {@code keywordRule()}) — 규칙이 바뀌면 표시가 모두 어긋나 다시 쓴다
   * @param sourceHashes 각 키워드를 만든 원문의 해시. {@code datasetIds} 와 순서가 같다
   */
  void markKeywordIndexed(String rule, List<Long> datasetIds, List<String> sourceHashes);

  /**
   * 키워드 표시가 비었거나 지금 원문·규칙과 어긋난 행을 dataset_id 순으로 {@code limit} 건(키셋). 키워드 백로그 스윕이 쓴다. 등급과 무관하다 —
   * 키워드는 외부 공급자로 나가지 않는다(별도 저장소는 같은 신뢰 경계 안, ADR 0002 결정 5).
   */
  List<SourceTextRow> findKeywordMissing(String rule, long afterDatasetId, int limit);
}
