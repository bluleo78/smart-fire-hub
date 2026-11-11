package com.smartfirehub.dataset.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.PlatformMapper;
import org.apache.ibatis.annotations.Param;

/**
 * dataset_embedding MyBatis 매퍼(WD-707). SQL 은 {@code mybatis/dataset/DatasetEmbeddingMapper.xml}.
 *
 * <p>기반 테이블(dataset_embedding)만 다룬다 — 벡터 테이블 문장은 두지 않는다(꺼진 기능 경로, 스펙 D1).
 */
@PlatformMapper
public interface DatasetEmbeddingMapper {

  /** source_text 를 넣거나 바꾸고 updated_at 을 DB 시계로 갱신한다. PG 는 ON CONFLICT, Tibero 는 MERGE. */
  int upsertSourceText(@Param("datasetId") long datasetId, @Param("sourceText") String sourceText);

  /** 카탈로그 행 하나를 지운다(없으면 0). */
  int delete(@Param("datasetId") long datasetId);

  /**
   * 등급이 임베딩 공급자를 허용하는 카탈로그 행 수.
   *
   * @param aiPolicyAll ai_policy 의 "모두 허용" 값
   * @param aiPolicySelfHosted 자체 호스팅이면 함께 허용할 값, 외부면 null
   */
  long countAllowed(
      @Param("aiPolicyAll") String aiPolicyAll,
      @Param("aiPolicySelfHosted") String aiPolicySelfHosted);
}
