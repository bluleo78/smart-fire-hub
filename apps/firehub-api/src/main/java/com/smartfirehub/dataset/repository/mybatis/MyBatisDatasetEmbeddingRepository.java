package com.smartfirehub.dataset.repository.mybatis;

import com.smartfirehub.dataset.repository.DatasetEmbeddingRepository;
import com.smartfirehub.embedding.EmbeddingDimension;
import com.smartfirehub.embedding.EmbeddingSpace;
import com.smartfirehub.global.platformdb.mybatis.InChunks;
import com.smartfirehub.global.platformdb.mybatis.PlatformMyBatisRepository;
import com.smartfirehub.global.platformdb.mybatis.PlatformRetries;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.securitylevel.ai.EmbeddingAllowance;
import com.smartfirehub.securitylevel.repository.mybatis.MyBatisDatasetVisibility;
import com.smartfirehub.vectorstore.DatasetVectorIndex;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;

/**
 * DatasetEmbeddingRepository 의 MyBatis 구현(WD-707) — {@code JooqDatasetEmbeddingRepository} 와 같은 의미.
 *
 * <p>벡터는 벡터 저장소 포트({@link DatasetVectorIndex}, ADR 0002 결정 2)가 맡는다. 이 리포지토리는 jOOQ 판처럼 포트를 부른 뒤 같은
 * 트랜잭션에서 표시 열({@code embedded_dim}·{@code embedded_model})을 맞추고, 백로그·진행률은 표시 열로 판정한다(WD-718).
 * Tibero+platform 조합의 포트는 꺼짐 구현이라 벡터를 쓰거나 지우는 메서드는 예전처럼 {@link UnsupportedOperationException} 을 던진다
 * — 서비스가 {@code VectorStoreFeatures} 가드 뒤에서만 부른다. Tibero+qdrant 조합(WD-720)이 오면 같은 코드가 Qdrant 포트로
 * 그대로 돈다.
 *
 * <p>등급 허용 술어(countAll)는 {@code EmbeddingAiPolicyRepository.allowedDatasetSql}(jOOQ 렌더 문자열 —
 * MyBatis 판은 꺼진 기능)에 기대지 않고 공용 조각 {@code DatasetVisibility.aiPolicyAllowedDataset}(문서 청크 매퍼와 공유)이
 * 같은 모양으로 렌더한다 — Tibero 컨텍스트에 그 빈이 없고, jOOQ 렌더 문자열은 Tibero 유효성을 보장하지 않는다. 허용 값 규칙(ALL, 자체 호스팅이면
 * SELF_HOSTED_ONLY 도)은 {@link MyBatisDatasetVisibility#aiPolicyValues} 한 곳에 있고 계약 테스트의 ai_policy
 * 행렬이 두 구현을 고정한다. {@code MyBatisEmbeddingAiPolicyRepository}(WD-711)도 같은 조각·도우미를 쓴다(스펙 D4).
 */
// 배경 잡(JobRunr/@Async/@Scheduled)은 앰비언트 트랜잭션이 없다. RLS GUC 는 트랜잭션 시작
// 시점에만 주입되므로, 트랜잭션이 없으면 TenantContext 를 세워도 정책이 전 행을 차단한다.
// REQUIRED 라 서비스가 이미 연 트랜잭션에는 합류한다(기존 경로 동작 불변).
@PlatformMyBatisRepository
@Transactional
public class MyBatisDatasetEmbeddingRepository implements DatasetEmbeddingRepository {

  private final DatasetEmbeddingMapper mapper;

  // 벡터 저장소 포트(ADR 0002 결정 2) — Tibero+platform 이면 꺼짐 구현.
  private final DatasetVectorIndex vectorIndex;

  public MyBatisDatasetEmbeddingRepository(
      DatasetEmbeddingMapper mapper, DatasetVectorIndex vectorIndex) {
    this.mapper = mapper;
    this.vectorIndex = vectorIndex;
  }

  /**
   * source_text 만 동기 upsert. PG 는 ON CONFLICT, Tibero 는 MERGE 다.
   *
   * <p>Tibero 의 MERGE 는 같은 데이터셋이 동시에 처음 들어오면 한쪽이 PK 위반으로 실패할 수 있다(문장 단위 실패라 트랜잭션은 산다). 한 번 다시 시도하면
   * 이미 들어간 행을 만나 UPDATE 가 되어 PG 의 DO UPDATE 와 같은 결과다. 이 재시도는 Tibero MERGE 전용이며 PG 에서는 이 예외가 나지 않는다.
   */
  @Override
  public void upsertSourceText(long datasetId, String sourceText) {
    PlatformRetries.runOnceOnDuplicateKey(() -> mapper.upsertSourceText(datasetId, sourceText));
  }

  /** 카탈로그 벡터 1건 — {@link #upsertEmbeddings} 의 단건 편의. */
  @Override
  public void upsertEmbedding(EmbeddingSpace space, long datasetId, float[] embedding) {
    upsertEmbeddings(space, List.of(datasetId), List.of(embedding));
  }

  /** 포트로 벡터를 쓴 뒤 같은 트랜잭션에서 표시 열을 그 공간으로 맞춘다(jOOQ 판과 같음). */
  @Override
  public void upsertEmbeddings(
      EmbeddingSpace space, List<Long> datasetIds, List<float[]> embeddings) {
    if (datasetIds.size() != embeddings.size()) {
      throw new IllegalArgumentException(
          "데이터셋 수와 임베딩 수 불일치: " + datasetIds.size() + " vs " + embeddings.size());
    }
    if (datasetIds.isEmpty()) return;
    vectorIndex.upsertDense(space, datasetIds, embeddings);
    mapper.markEmbedded(space.dimension().size(), space.model(), InChunks.of(datasetIds));
  }

  /** 데이터셋 삭제 시 카탈로그 행 제거(FK CASCADE 와 별개로 명시 호출 경로 — 메타가 사라진 syncSourceText). */
  @Override
  public void delete(long datasetId) {
    mapper.delete(datasetId);
  }

  /** 이 데이터셋의 벡터를 지우고 표시 열을 비운다. 카탈로그 행·원문은 남는다. */
  @Override
  public void deleteVectors(long datasetId) {
    long tenantId = TenantContext.require("데이터셋 벡터 정리");
    vectorIndex.deleteDense(tenantId, List.of(datasetId));
    mapper.clearMarkers(tenantId, InChunks.of(List.of(datasetId)));
  }

  /** 여러 데이터셋의 벡터를 지우고 표시 열을 비운다. 지운 벡터 수를 돌려준다. */
  @Override
  public int deleteVectorsOf(List<Long> datasetIds) {
    if (datasetIds.isEmpty()) return 0;
    long tenantId = TenantContext.require("데이터셋 벡터 정리");
    int deleted = vectorIndex.deleteDense(tenantId, datasetIds);
    mapper.clearMarkers(tenantId, InChunks.of(datasetIds));
    return deleted;
  }

  /**
   * 등급이 임베딩 공급자를 허용하는 카탈로그 행 수(진행률 분모 — 서비스가 가드하지 않는 경로). hosting 해석은 {@code
   * JooqDatasetVisibility.aiPolicyAllows} 와 같다: ALL 은 언제나, SELF_HOSTED_ONLY 는 자체 호스팅일 때만 허용.
   */
  @Override
  public long countAll(EmbeddingAllowance allowance) {
    return mapper.countAllowed(MyBatisDatasetVisibility.aiPolicyValues(allowance.hosting()));
  }

  /** 표시 열이 이 공간을 가리키는 데이터셋 수(진행률 분자). */
  @Override
  public long countEmbedded(EmbeddingSpace space) {
    return mapper.countEmbedded(space.dimension().size(), space.model());
  }

  /** 판정식(표시 열이 현재 공간이 아님 + 등급 허용) 행 수 — jOOQ 판과 같은 식. */
  @Override
  public long countMissing(EmbeddingSpace space, EmbeddingAllowance allowance) {
    return mapper.countMissing(
        space.dimension().size(),
        space.model(),
        MyBatisDatasetVisibility.aiPolicyValues(allowance.hosting()));
  }

  /** 판정식을 만족하는 행을 dataset_id 순으로 {@code limit} 건(키셋). */
  @Override
  public List<SourceTextRow> findMissing(
      EmbeddingSpace space, long afterDatasetId, int limit, EmbeddingAllowance allowance) {
    return mapper.findMissing(
        space.dimension().size(),
        space.model(),
        afterDatasetId,
        limit,
        0,
        MyBatisDatasetVisibility.aiPolicyValues(allowance.hosting()));
  }

  /** 다른 차원의 벡터를 지우고, 그 차원을 가리키던 표시 열을 비운다. */
  @Override
  public int deleteOtherDimensions(EmbeddingDimension keep) {
    long tenantId = TenantContext.require("다른 차원 카탈로그 벡터 정리");
    int deleted = vectorIndex.deleteOtherDimensions(tenantId, keep);
    mapper.clearOtherDimensionMarkers(tenantId, keep.size());
    return deleted;
  }
}
