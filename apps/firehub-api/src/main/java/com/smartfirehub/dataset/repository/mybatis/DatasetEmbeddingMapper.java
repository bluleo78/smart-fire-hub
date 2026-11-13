package com.smartfirehub.dataset.repository.mybatis;

import com.smartfirehub.dataset.repository.DatasetEmbeddingRepository;
import com.smartfirehub.global.platformdb.mybatis.PlatformMapper;
import com.smartfirehub.securitylevel.repository.mybatis.MyBatisDatasetVisibility;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * dataset_embedding MyBatis 매퍼(WD-707). SQL 은 {@code mybatis/dataset/DatasetEmbeddingMapper.xml}.
 *
 * <p>기반 테이블(dataset_embedding)만 다룬다 — 벡터는 벡터 저장소 포트가 맡는다(ADR 0002). 표시 열과 판정식(WD-717·718)은 여기 있다.
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
   * @param aiPolicy {@code MyBatisDatasetVisibility.aiPolicyValues} 가 호스팅 위치로 정한 허용 값
   */
  long countAllowed(@Param("aiPolicy") MyBatisDatasetVisibility.AiPolicyValues aiPolicy);

  /** 표시 열을 공간(차원·모델)으로 채운다. */
  int markEmbedded(
      @Param("dim") int dim,
      @Param("model") String model,
      @Param("chunks") List<List<Long>> chunks);

  /** 이 테넌트 데이터셋들의 표시 열을 비운다. */
  int clearMarkers(@Param("tenantId") long tenantId, @Param("chunks") List<List<Long>> chunks);

  /** 이 테넌트에서 {@code keepDim} 이 아닌 차원을 가리키는 표시 열을 비운다. */
  int clearOtherDimensionMarkers(@Param("tenantId") long tenantId, @Param("keepDim") int keepDim);

  /** 표시 열이 이 공간을 가리키는 행 수. */
  long countEmbedded(@Param("dim") int dim, @Param("model") String model);

  /** 판정식(등급 허용 포함)을 만족하는 행 수. */
  long countMissing(
      @Param("dim") int dim,
      @Param("model") String model,
      @Param("aiPolicy") MyBatisDatasetVisibility.AiPolicyValues aiPolicy);

  /** 판정식(등급 허용 포함)을 만족하는 행을 dataset_id 순으로 {@code limit} 건(키셋). offset 은 0 이다. */
  List<DatasetEmbeddingRepository.SourceTextRow> findMissing(
      @Param("dim") int dim,
      @Param("model") String model,
      @Param("afterDatasetId") long afterDatasetId,
      @Param("limit") int limit,
      @Param("offset") int offset,
      @Param("aiPolicy") MyBatisDatasetVisibility.AiPolicyValues aiPolicy);
}
