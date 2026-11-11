package com.smartfirehub.dataset.repository.mybatis;

import com.smartfirehub.dataset.repository.DatasetEmbeddingRepository;
import com.smartfirehub.embedding.EmbeddingDimension;
import com.smartfirehub.embedding.EmbeddingSpace;
import com.smartfirehub.global.platformdb.PlatformDbFeature;
import com.smartfirehub.global.platformdb.mybatis.PlatformFeatureOff;
import com.smartfirehub.global.platformdb.mybatis.PlatformMyBatisRepository;
import com.smartfirehub.securitylevel.access.LevelPolicy;
import com.smartfirehub.securitylevel.access.ProviderHosting;
import com.smartfirehub.securitylevel.ai.EmbeddingAllowance;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

/**
 * DatasetEmbeddingRepository 의 MyBatis 구현(WD-707) — {@code JooqDatasetEmbeddingRepository} 와 같은 의미.
 *
 * <p>살아 있는 경로(기반 테이블 dataset_embedding 만 쓰는 {@code upsertSourceText}·{@code delete}·{@code
 * countAll})만 구현한다. 벡터 테이블 메서드는 서비스가 {@code PlatformDbFeatures}(VECTOR_SEARCH) 가드 뒤에서만 부르고 Tibero
 * 기준선에 테이블도 없어 {@link UnsupportedOperationException} 을 던진다(스펙 D1). PG 프로필의 이 빈도 같다 — PG 운영은 jOOQ
 * 구현이다.
 *
 * <p>등급 허용 술어(countAll)는 묶음 ⑥ 의 {@code EmbeddingAiPolicyRepository.allowedDatasetSql}(jOOQ 렌더 문자열)에
 * 기대지 않고 매퍼 XML 이 같은 모양으로 직접 렌더한다 — Tibero 컨텍스트에 그 빈이 없고, jOOQ 렌더 문자열은 Tibero 유효성을 보장하지 않는다.
 * 규칙(ALL, 자체 호스팅이면 SELF_HOSTED_ONLY 도)은 {@code JooqDatasetVisibility.aiPolicyAllows} 와 같으며 계약 테스트의
 * ai_policy 행렬이 두 구현을 고정한다. 묶음 ⑥ 이전 시 한 곳으로 모을 후보다.
 */
// 배경 잡(JobRunr/@Async/@Scheduled)은 앰비언트 트랜잭션이 없다. RLS GUC 는 트랜잭션 시작
// 시점에만 주입되므로, 트랜잭션이 없으면 TenantContext 를 세워도 정책이 전 행을 차단한다.
// REQUIRED 라 서비스가 이미 연 트랜잭션에는 합류한다(기존 경로 동작 불변).
@PlatformMyBatisRepository
@Transactional
public class MyBatisDatasetEmbeddingRepository implements DatasetEmbeddingRepository {

  private final DatasetEmbeddingMapper mapper;

  public MyBatisDatasetEmbeddingRepository(DatasetEmbeddingMapper mapper) {
    this.mapper = mapper;
  }

  /**
   * source_text 만 동기 upsert. PG 는 ON CONFLICT, Tibero 는 MERGE 다.
   *
   * <p>Tibero 의 MERGE 는 같은 데이터셋이 동시에 처음 들어오면 한쪽이 PK 위반으로 실패할 수 있다(문장 단위 실패라 트랜잭션은 산다). 한 번 다시 시도하면
   * 이미 들어간 행을 만나 UPDATE 가 되어 PG 의 DO UPDATE 와 같은 결과다. 이 재시도는 Tibero MERGE 전용이며 PG 에서는 이 예외가 나지 않는다.
   */
  @Override
  public void upsertSourceText(long datasetId, String sourceText) {
    try {
      mapper.upsertSourceText(datasetId, sourceText);
    } catch (DuplicateKeyException e) {
      mapper.upsertSourceText(datasetId, sourceText);
    }
  }

  /** 벡터 검색이 꺼진 플랫폼 DB 라 지원하지 않는다 — 서비스가 PlatformDbFeatures 로 건너뛴다. */
  @Override
  public void upsertEmbedding(EmbeddingSpace space, long datasetId, float[] embedding) {
    throw PlatformFeatureOff.unsupported(PlatformDbFeature.VECTOR_SEARCH);
  }

  /** 벡터 검색이 꺼진 플랫폼 DB 라 지원하지 않는다 — 서비스가 PlatformDbFeatures 로 건너뛴다. */
  @Override
  public void upsertEmbeddings(
      EmbeddingSpace space, List<Long> datasetIds, List<float[]> embeddings) {
    throw PlatformFeatureOff.unsupported(PlatformDbFeature.VECTOR_SEARCH);
  }

  /** 데이터셋 삭제 시 카탈로그 행 제거(FK CASCADE 와 별개로 명시 호출 경로 — 메타가 사라진 syncSourceText). */
  @Override
  public void delete(long datasetId) {
    mapper.delete(datasetId);
  }

  /** 벡터 검색이 꺼진 플랫폼 DB 라 지원하지 않는다 — 서비스가 PlatformDbFeatures 로 건너뛴다. */
  @Override
  public void deleteVectors(long datasetId) {
    throw PlatformFeatureOff.unsupported(PlatformDbFeature.VECTOR_SEARCH);
  }

  /** 벡터 검색이 꺼진 플랫폼 DB 라 지원하지 않는다 — 서비스가 PlatformDbFeatures 로 건너뛴다. */
  @Override
  public int deleteVectorsOf(List<Long> datasetIds) {
    throw PlatformFeatureOff.unsupported(PlatformDbFeature.VECTOR_SEARCH);
  }

  /**
   * 등급이 임베딩 공급자를 허용하는 카탈로그 행 수(진행률 분모 — 서비스가 가드하지 않는 경로). hosting 해석은 {@code
   * JooqDatasetVisibility.aiPolicyAllows} 와 같다: ALL 은 언제나, SELF_HOSTED_ONLY 는 자체 호스팅일 때만 허용.
   */
  @Override
  public long countAll(EmbeddingAllowance allowance) {
    String selfHosted =
        allowance.hosting() == ProviderHosting.SELF_HOSTED
            ? LevelPolicy.AiPolicy.SELF_HOSTED_ONLY.name()
            : null;
    return mapper.countAllowed(LevelPolicy.AiPolicy.ALL.name(), selfHosted);
  }

  /** 벡터 검색이 꺼진 플랫폼 DB 라 지원하지 않는다 — 서비스가 PlatformDbFeatures 로 건너뛴다. */
  @Override
  public long countEmbedded(EmbeddingSpace space) {
    throw PlatformFeatureOff.unsupported(PlatformDbFeature.VECTOR_SEARCH);
  }

  /** 벡터 검색이 꺼진 플랫폼 DB 라 지원하지 않는다 — 서비스가 PlatformDbFeatures 로 건너뛴다. */
  @Override
  public long countMissing(EmbeddingSpace space, EmbeddingAllowance allowance) {
    throw PlatformFeatureOff.unsupported(PlatformDbFeature.VECTOR_SEARCH);
  }

  /** 벡터 검색이 꺼진 플랫폼 DB 라 지원하지 않는다 — 서비스가 PlatformDbFeatures 로 건너뛴다. */
  @Override
  public List<SourceTextRow> findMissing(
      EmbeddingSpace space, long afterDatasetId, int limit, EmbeddingAllowance allowance) {
    throw PlatformFeatureOff.unsupported(PlatformDbFeature.VECTOR_SEARCH);
  }

  /** 벡터 검색이 꺼진 플랫폼 DB 라 지원하지 않는다 — 서비스가 PlatformDbFeatures 로 건너뛴다. */
  @Override
  public int deleteOtherDimensions(EmbeddingDimension keep) {
    throw PlatformFeatureOff.unsupported(PlatformDbFeature.VECTOR_SEARCH);
  }
}
