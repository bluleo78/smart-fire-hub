package com.smartfirehub.securitylevel.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.PlatformMapper;
import com.smartfirehub.securitylevel.repository.mybatis.MyBatisDatasetVisibility.AiPolicyValues;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * 임베딩·AI 허용 등급 정책 MyBatis 매퍼(WD-711). SQL 은 {@code
 * mybatis/securitylevel/EmbeddingAiPolicyMapper.xml}. 허용 판정은 공용 조각 {@code
 * DatasetVisibility.aiPolicyAllowedDataset} 하나, 허용 값은 {@link
 * MyBatisDatasetVisibility#aiPolicyValues} 가 만든다(스펙 D4). jOOQ 처럼 dataset.tenant_id 를 인자로 명시한다.
 */
@PlatformMapper
public interface EmbeddingAiPolicyMapper {

  /** 이 테넌트의 그 데이터셋이 존재하고 등급이 허용하면 1, 아니면 0. */
  long countAllowed(
      @Param("datasetId") long datasetId,
      @Param("tenantId") long tenantId,
      @Param("aiPolicy") AiPolicyValues aiPolicy);

  /** 이 테넌트의 그 데이터셋이 존재하고 등급이 허용하지 않으면 1, 아니면 0. */
  long countDisallowed(
      @Param("datasetId") long datasetId,
      @Param("tenantId") long tenantId,
      @Param("aiPolicy") AiPolicyValues aiPolicy);

  /** 이 테넌트에서 등급이 허용하지 않는 데이터셋 id(오름차순). */
  List<Long> selectDisallowedIds(
      @Param("tenantId") long tenantId, @Param("aiPolicy") AiPolicyValues aiPolicy);
}
