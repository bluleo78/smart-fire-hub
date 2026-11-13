package com.smartfirehub.document.repository.mybatis;

import com.smartfirehub.document.dto.Chunk;
import com.smartfirehub.document.repository.DocumentChunkRepository;
import com.smartfirehub.embedding.EmbeddingDimension;
import com.smartfirehub.embedding.EmbeddingSpace;
import com.smartfirehub.global.platformdb.PlatformDbFeature;
import com.smartfirehub.global.platformdb.mybatis.InChunks;
import com.smartfirehub.global.platformdb.mybatis.PlatformFeatureOff;
import com.smartfirehub.global.platformdb.mybatis.PlatformMyBatisRepository;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.securitylevel.ai.EmbeddingAllowance;
import com.smartfirehub.securitylevel.repository.mybatis.MyBatisDatasetVisibility;
import com.smartfirehub.vectorstore.ChunkVectorIndex;
import java.util.Collection;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;

/**
 * DocumentChunkRepository 의 MyBatis 구현(WD-712) — {@code JooqDocumentChunkRepository} 와 같은 의미.
 *
 * <p>벡터·트라이그램은 벡터 저장소 포트({@link ChunkVectorIndex}, ADR 0002 결정 2)가 맡는다. 벡터를 옮기거나 지우는 메서드는 jOOQ 판처럼
 * 포트를 부른 뒤 같은 트랜잭션에서 표시 열을 맞추고, 백로그·진행률은 표시 열로 판정한다(WD-718). Tibero+platform 조합의 포트는 꺼짐 구현이라 그
 * 메서드들은 예전처럼 {@link UnsupportedOperationException} 을 던진다. {@code insertBatch} 는 예외를 바로 던진다 — 청크를 먼저
 * 넣고 벡터를 나중에 쓰는 적재 순서는 Qdrant 조합에서 정한다(ADR 0002 결정 6, WD-720).
 *
 * <p>등급 허용 술어(countAllChunks)는 {@code EmbeddingAiPolicyRepository.allowedDatasetSql}(jOOQ 렌더 문자열 —
 * MyBatis 판은 꺼진 기능)에 기대지 않고 매퍼 XML 이 같은 모양으로 직접 렌더한다(Task 8 의 {@code
 * MyBatisDatasetEmbeddingRepository.countAll} 과 같은 판단) — Tibero 컨텍스트에 그 빈이 없고, jOOQ 렌더 문자열은 Tibero
 * 유효성을 보장하지 않는다. 허용 값은 {@link MyBatisDatasetVisibility#aiPolicyValues} 한 곳(스펙 D4)이고 계약 테스트의
 * ai_policy 행렬이 두 구현을 고정한다.
 */
// 배경 잡(JobRunr/@Async/@Scheduled)은 앰비언트 트랜잭션이 없다. RLS GUC 는 트랜잭션 시작
// 시점에만 주입되므로, 트랜잭션이 없으면 TenantContext 를 세워도 정책이 전 행을 차단한다.
// REQUIRED 라 서비스가 이미 연 트랜잭션에는 합류한다(기존 경로 동작 불변).
@PlatformMyBatisRepository
@Transactional
public class MyBatisDocumentChunkRepository implements DocumentChunkRepository {

  private final DocumentChunkMapper mapper;

  // 벡터 저장소 포트(ADR 0002 결정 2) — Tibero+platform 이면 꺼짐 구현.
  private final ChunkVectorIndex vectorIndex;

  /** 매퍼와 청크 벡터 포트를 주입받는다(플랫폼 MyBatis 빈 — 매퍼 SQL 은 mybatis/ 아래 XML). */
  public MyBatisDocumentChunkRepository(DocumentChunkMapper mapper, ChunkVectorIndex vectorIndex) {
    this.mapper = mapper;
    this.vectorIndex = vectorIndex;
  }

  /** 해당 문서의 기존 청크를 모두 삭제(잡 재시도 시 중복 방지). */
  @Override
  public void deleteByDocumentFileId(Long documentFileId) {
    mapper.deleteByDocumentFileId(documentFileId);
  }

  /** 벡터 검색이 꺼진 플랫폼 DB 라 지원하지 않는다 — 서비스가 VectorStoreFeatures 로 건너뛴다(insertChunksOnly 로 간다). */
  @Override
  public void insertBatch(
      Long documentFileId,
      Long datasetId,
      List<Chunk> chunks,
      List<float[]> embeddings,
      EmbeddingSpace space) {
    throw PlatformFeatureOff.unsupported(PlatformDbFeature.VECTOR_SEARCH);
  }

  /**
   * 벡터 없이 청크 본문만 넣는다 — 등급이 임베딩 공급자를 허용하지 않거나 플랫폼 DB 가 벡터를 지원하지 않는 문서. 청크마다 단건 INSERT 를 돈다(같은 트랜잭션).
   * jOOQ 판의 200건 다건 VALUES 를 옮기지 않은 이유: Tibero 에는 다건 VALUES 가 없고, INSERT ALL 은 IDENTITY 를 한 문장에 한
   * 번만 매겨 둘째 행부터 PK 중복으로 실패한다(실측 JDBC-4008). 스프링 트랜잭션 안에서는 MyBatis BATCH 실행기로 바꿀 수 없어 단건 반복이 된다. 빈
   * 목록이면 문장을 돌리지 않는다.
   */
  @Override
  public void insertChunksOnly(Long documentFileId, Long datasetId, List<Chunk> chunks) {
    for (Chunk chunk : chunks) {
      mapper.insertChunk(documentFileId, datasetId, chunk);
    }
  }

  /** 포트로 청크 벡터를 옮긴 뒤 같은 트랜잭션에서 표시 열을 그 공간으로 맞춘다(jOOQ 판과 같음). */
  @Override
  public void upsertEmbeddings(
      EmbeddingSpace space, List<Long> chunkIds, List<float[]> embeddings) {
    if (chunkIds.size() != embeddings.size()) {
      throw new IllegalArgumentException(
          "청크 수와 임베딩 수 불일치: " + chunkIds.size() + " vs " + embeddings.size());
    }
    if (chunkIds.isEmpty()) return;
    vectorIndex.upsertDense(space, chunkIds, embeddings);
    mapper.markEmbedded(space.dimension().size(), space.model(), InChunks.of(chunkIds));
  }

  /**
   * 해당 데이터셋의 청크 (id, content) 를 id 오름차순으로 조회. 검수·청크 목록이 쓴다(가드 없음). jOOQ 판처럼 가시성 술어는 없고 테넌트는 정책이
   * 좁힌다.
   */
  @Override
  public List<ChunkContent> findChunkContentsByDataset(long datasetId) {
    return mapper.selectChunkContentsByDataset(datasetId);
  }

  /**
   * 주어진 청크 id 들 중 해당 데이터셋에 속한 것의 개수(중복 id 는 한 번만 센다). id 목록은 {@link InChunks} 로 중복을 걷고 500 개씩 나눠 한
   * 문장의 OR 로 잇는다 — Tibero IN 목록 1000 개 상한을 피하고, 행 하나는 술어가 여럿 맞아도 한 번만 세진다. 빈 목록은 조회 없이 0.
   */
  @Override
  public long countChunksInDataset(long datasetId, Collection<Long> chunkIds) {
    if (chunkIds.isEmpty()) return 0;
    return mapper.countChunksInDataset(datasetId, InChunks.of(chunkIds));
  }

  /**
   * 등급이 임베딩 공급자를 허용하는 데이터셋의 청크 수(진행률 분모 — 서비스가 가드하지 않는 경로). hosting 해석은 {@code
   * JooqDatasetVisibility.aiPolicyAllows} 와 같다: ALL 은 언제나, SELF_HOSTED_ONLY 는 자체 호스팅일 때만 허용.
   */
  @Override
  public long countAllChunks(EmbeddingAllowance allowance) {
    return mapper.countAllowed(MyBatisDatasetVisibility.aiPolicyValues(allowance.hosting()));
  }

  /** 표시 열이 이 공간을 가리키는 청크 수(진행률 분자). */
  @Override
  public long countEmbedded(EmbeddingSpace space) {
    return mapper.countEmbedded(space.dimension().size(), space.model());
  }

  /** 판정식(표시 열이 현재 공간이 아님 + 등급 허용) 청크 수 — jOOQ 판과 같은 식. */
  @Override
  public long countMissing(EmbeddingSpace space, EmbeddingAllowance allowance) {
    return mapper.countMissing(
        space.dimension().size(),
        space.model(),
        MyBatisDatasetVisibility.aiPolicyValues(allowance.hosting()));
  }

  /** 판정식을 만족하는 청크를 id 순으로 {@code limit} 건(키셋). */
  @Override
  public List<ChunkContent> findMissing(
      EmbeddingSpace space, long afterChunkId, int limit, EmbeddingAllowance allowance) {
    return mapper.findMissing(
        space.dimension().size(),
        space.model(),
        afterChunkId,
        limit,
        0,
        MyBatisDatasetVisibility.aiPolicyValues(allowance.hosting()));
  }

  /** 다른 차원의 청크 벡터를 지우고, 그 차원을 가리키던 표시 열을 비운다. */
  @Override
  public int deleteOtherDimensions(EmbeddingDimension keep) {
    long tenantId = TenantContext.require("다른 차원 벡터 정리");
    int deleted = vectorIndex.deleteOtherDimensions(tenantId, keep);
    mapper.clearOtherDimensionMarkers(tenantId, keep.size());
    return deleted;
  }

  /** 여러 데이터셋의 청크 벡터를 지우고 표시 열을 비운다(청크 본문은 남는다). */
  @Override
  public int deleteVectorsOf(List<Long> datasetIds) {
    if (datasetIds.isEmpty()) return 0;
    long tenantId = TenantContext.require("청크 벡터 정리");
    int deleted = vectorIndex.deleteDense(tenantId, datasetIds);
    mapper.clearMarkersOfDatasets(tenantId, InChunks.of(datasetIds));
    return deleted;
  }
}
