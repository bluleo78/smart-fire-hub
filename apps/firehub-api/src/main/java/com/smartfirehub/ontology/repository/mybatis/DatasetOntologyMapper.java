package com.smartfirehub.ontology.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.PlatformMapper;
import org.apache.ibatis.annotations.Param;

/**
 * 데이터셋↔온톨로지 연결(dataset_ontology) MyBatis 매퍼(WD-711). SQL 은 {@code
 * mybatis/ontology/DatasetOntologyMapper.xml}. 테넌트는 정책(RLS)이 거르고 tenant_id 는 열 DEFAULT 가 채운다.
 */
@PlatformMapper
public interface DatasetOntologyMapper {

  /**
   * 연결 업서트 — PG 는 ON CONFLICT (tenant_id, dataset_id) DO UPDATE, Tibero 는 MERGE ON dataset_id. 바뀐 행
   * 수.
   *
   * @param userId 바인딩한 사용자(null 허용)
   */
  int bind(
      @Param("datasetId") long datasetId,
      @Param("ontologyId") long ontologyId,
      @Param("userId") Long userId);

  /** 데이터셋에 연결된 온톨로지 id — 없으면 null. */
  Long selectOntologyIdByDataset(@Param("datasetId") long datasetId);
}
