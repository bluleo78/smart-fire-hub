package com.smartfirehub.securitylevel.repository.mybatis;

import com.smartfirehub.global.platformdb.PlatformDbFeature;
import com.smartfirehub.global.platformdb.mybatis.PlatformFeatureOff;
import com.smartfirehub.global.platformdb.mybatis.PlatformMyBatisRepository;
import com.smartfirehub.securitylevel.access.ProviderHosting;
import com.smartfirehub.securitylevel.repository.EmbeddingAiPolicyRepository;
import java.util.List;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * EmbeddingAiPolicyRepository 의 MyBatis 구현(WD-711) — {@code JooqEmbeddingAiPolicyRepository} 와 같은
 * 의미.
 *
 * <p>허용 규칙은 MyBatis 쪽 단 한 벌을 쓴다(스펙 D4): 허용 값은 {@link MyBatisDatasetVisibility#aiPolicyValues}, SQL
 * 은 공용 조각 {@code DatasetVisibility.aiPolicyAllowedDataset} — 데이터셋 임베딩 카탈로그·문서 청크 진행률 분모와 같다. jOOQ
 * 규칙과의 일치는 계약 테스트의 ai_policy × 호스팅 행렬이 고정한다(두 벌 유지, ADR 0001 결정 1).
 *
 * <p>트랜잭션(내가 정함): jOOQ 판은 클래스 단위 트랜잭션이 없다(조회는 EmbeddingAiGate 의 readOnly 트랜잭션 안에서만 불린다). MyBatis 판은
 * 클래스 단위 readOnly 를 둔다 — 트랜잭션 없이 불리면 테넌트 컨텍스트가 없어 정책이 0행(=허용 없음·불허 없음)으로 조용히 열리므로, 게이트 밖 호출에도 안전하게
 * 닫는다. REQUIRED 라 게이트 트랜잭션에는 합류해 동작은 같다. 렌더 전용 {@code allowedDatasetSql} 은 SUPPORTS.
 */
@PlatformMyBatisRepository
@Transactional(readOnly = true)
public class MyBatisEmbeddingAiPolicyRepository implements EmbeddingAiPolicyRepository {

  private final EmbeddingAiPolicyMapper mapper;

  public MyBatisEmbeddingAiPolicyRepository(EmbeddingAiPolicyMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public boolean datasetAllowed(long datasetId, long tenantId, ProviderHosting hosting) {
    return mapper.countAllowed(
            datasetId, tenantId, MyBatisDatasetVisibility.aiPolicyValues(hosting))
        > 0;
  }

  @Override
  public boolean datasetDisallowed(long datasetId, long tenantId, ProviderHosting hosting) {
    return mapper.countDisallowed(
            datasetId, tenantId, MyBatisDatasetVisibility.aiPolicyValues(hosting))
        > 0;
  }

  @Override
  public List<Long> disallowedDatasetIds(long tenantId, ProviderHosting hosting) {
    return mapper.selectDisallowedIds(tenantId, MyBatisDatasetVisibility.aiPolicyValues(hosting));
  }

  /**
   * 문자열 술어 렌더 — 소비자가 jOOQ 벡터 경로(JooqDatasetEmbedding·JooqDocumentChunk)뿐이고 Tibero 에서 꺼져 있어 지원하지
   * 않는다(스펙 D3, ADR 꺼진 기능 규칙).
   */
  @Override
  @Transactional(propagation = Propagation.SUPPORTS)
  public String allowedDatasetSql(String datasetIdExpr, ProviderHosting hosting) {
    throw PlatformFeatureOff.unsupported(PlatformDbFeature.VECTOR_SEARCH);
  }
}
