package com.smartfirehub.document.repository;

import com.smartfirehub.document.dto.Chunk;
import com.smartfirehub.embedding.EmbeddingDimension;
import com.smartfirehub.embedding.EmbeddingSpace;
import com.smartfirehub.securitylevel.ai.EmbeddingAllowance;
import java.util.Collection;
import java.util.List;

/**
 * document_chunk 본문 + 차원별 벡터 테이블(document_chunk_vec_N) 적재·검색(#713).
 *
 * <p>구현은 {@code JooqDocumentChunkRepository}(jOOQ/PostgreSQL) 이다 — WD-485. 다루는 테이블: {@code
 * document_chunk}, {@code document_chunk_vec_N}(차원별 벡터 테이블), {@code document_file}, {@code
 * dataset}.
 */
public interface DocumentChunkRepository {

  /** 재임베딩 대상 청크의 (id, content) 쌍. content 만 임베딩 모델에 재투입한다. */
  record ChunkContent(long chunkId, String content) {}

  /** 해당 문서의 기존 청크를 모두 삭제(잡 재시도 시 중복 방지). */
  void deleteByDocumentFileId(Long documentFileId);

  /**
   * 청크 본문은 부모 테이블에, 벡터는 {@code space} 의 차원 테이블에 넣는다(한 트랜잭션 — 클래스 레벨 @Transactional). 새 청크라 다른 차원 행이
   * 있을 수 없으므로 삭제 없이 INSERT 한다. 벡터 행은 {@code RETURNING id, chunk_index} 의 <b>chunk_index 로</b>
   * 매핑한다(RETURNING 순서는 VALUES 순서를 보장하지 않는다).
   */
  void insertBatch(
      Long documentFileId,
      Long datasetId,
      List<Chunk> chunks,
      List<float[]> embeddings,
      EmbeddingSpace space);

  /**
   * 벡터 없이 청크 본문만 넣는다 — 등급이 임베딩 공급자를 허용하지 않는 문서(S3 §4.3). 본문을 외부 공급자로 보내지 않으며, 이후 허용으로 바뀌면 재임베딩
   * 판정식(countMissing/findMissing)이 이 청크들을 집어 벡터를 채운다.
   */
  void insertChunksOnly(Long documentFileId, Long datasetId, List<Chunk> chunks);

  /**
   * 기존 청크의 벡터를 {@code space} 로 옮긴다: 다른 차원 테이블의 같은 청크 행 DELETE + 현재 차원 테이블 UPSERT (같은 차원 안의 모델 교체도
   * ON CONFLICT 로 처리). 불변식 "한 청크의 벡터는 차원 테이블 전체에서 최대 1행"을 이 트랜잭션이 지킨다. 부모가 사라진 id 는 INSERT…SELECT 가
   * 0행이라 조용히 건너뛴다(재임베딩 중 문서 삭제 경합).
   */
  void upsertEmbeddings(EmbeddingSpace space, List<Long> chunkIds, List<float[]> embeddings);

  /** 해당 데이터셋의 청크 (id, content) 를 id 오름차순으로 조회. 검수·청크 목록·데이터셋 단위 재임베딩이 쓴다. */
  List<ChunkContent> findChunkContentsByDataset(long datasetId);

  /**
   * 주어진 청크 id 들 중 해당 데이터셋에 속한 것의 개수(중복 id 는 한 번만 센다). 검수 항목 등록이 클라이언트가 보낸 근거 청크가 정말 그 데이터셋의 것인지 확인할
   * 때 쓴다 — 없는 청크와 다른 데이터셋(또는 다른 테넌트, RLS)의 청크를 구분하지 않는다.
   */
  long countChunksInDataset(long datasetId, Collection<Long> chunkIds);

  /**
   * 전체 청크 수. 재임베딩 진행률 계산의 분모 — 판정식과 같은 모집단이어야 하므로 {@code allowance}(EmbeddingAiGate #allowance() 가
   * 캡처한 값, 구현이 별칭 {@code c.dataset_id} 로 렌더)로 등급이 임베딩 공급자를 허용하지 않는 데이터셋의 청크를 뺀다(S3 §4.3).
   */
  long countAllChunks(EmbeddingAllowance allowance);

  /** {@code space} 로 임베딩된 청크 수(RLS 로 현재 테넌트). 진행률 분자. */
  long countEmbedded(EmbeddingSpace space);

  /**
   * 재임베딩 판정식: 현재 차원 테이블에 현재 모델 벡터가 없고 <b>등급이 임베딩 공급자를 허용하는</b> 데이터셋의 청크 수. 영향도·저장 시 투입·잡이 같은 식을 쓴다.
   * {@code allowance} 는 EmbeddingAiGate#allowance() 가 캡처한 값(별칭 {@code c.dataset_id} 렌더는 구현) — 정책 없는
   * 오버로드는 두지 않는다.
   */
  long countMissing(EmbeddingSpace space, EmbeddingAllowance allowance);

  /** 판정식(등급 허용 포함)을 만족하는 청크를 id 순으로 {@code limit} 건(키셋 {@code afterChunkId} 이후). */
  List<ChunkContent> findMissing(
      EmbeddingSpace space, long afterChunkId, int limit, EmbeddingAllowance allowance);

  /**
   * 현재 차원이 아닌 테이블의 이 테넌트 벡터를 지운다(재임베딩 완료 뒤 잔여 정리). RLS 만으로도 테넌트 범위지만 {@code WHERE tenant_id = ?} 를
   * 명시한다 — 소유자 커넥션에서 불려도 남의 행을 지우지 않게(조건 없는 DELETE 금지 규율).
   */
  int deleteOtherDimensions(EmbeddingDimension keep);

  /**
   * 여러 데이터셋의 청크 벡터를 모든 차원에서 지운다(청크 본문 content 는 남겨 키워드 검색 유지). 정책 위반 외부 벡터 정리(AiVectorPurgeService,
   * S3 §4.3)용 — 지운 행 수를 돌려준다. {@code tenant_id} 를 명시한다(조건 없는 DELETE 금지 규율).
   */
  int deleteVectorsOf(List<Long> datasetIds);
}
