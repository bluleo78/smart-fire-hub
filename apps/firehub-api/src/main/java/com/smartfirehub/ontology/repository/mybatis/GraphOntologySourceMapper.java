package com.smartfirehub.ontology.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.PlatformMapper;
import com.smartfirehub.securitylevel.repository.mybatis.MyBatisDatasetVisibility.VisibilityParams;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * 지식그래프 출처(graph_ontology_source) MyBatis 매퍼(WD-711). SQL 은 {@code
 * mybatis/ontology/GraphOntologySourceMapper.xml}. 테넌트는 정책(RLS)이 거르고 tenant_id 는 열 DEFAULT 가 채운다.
 */
@PlatformMapper
public interface GraphOntologySourceMapper {

  /**
   * (온톨로지, 데이터셋) 출처를 멱등으로 남긴다 — PG 는 ON CONFLICT DO NOTHING, Tibero 는 MERGE(NOT MATCHED 만). 바뀐 행 수.
   */
  int record(@Param("ontologyId") long ontologyId, @Param("datasetId") long datasetId);

  /** 이 온톨로지의 출처 데이터셋 id(삭제된 데이터셋 포함). */
  List<Long> selectDatasetIds(@Param("ontologyId") long ontologyId);

  /**
   * 읽기를 막는 출처의 수 — 보이지 않는 기존 데이터셋 출처, 그리고 {@code deletedSourceReadable} 이 false 면 삭제된 데이터셋 출처까지.
   *
   * @param vis 조회자 가시성 조각 파라미터(dataset 테이블 이름 관례)
   */
  long countUnreadable(
      @Param("ontologyId") long ontologyId,
      @Param("vis") VisibilityParams vis,
      @Param("deletedSourceReadable") boolean deletedSourceReadable);

  /**
   * 출처 기록 또는 적재 이력이 있는 데이터셋 id(오름차순). tenant_id 를 명시한다(인터페이스가 테넌트를 인자로 받는다 — 스펙 D1).
   *
   * @param chunks {@code InChunks.of(datasetIds)} — Tibero IN 목록 1000 개 상한 대응
   */
  List<Long> selectDatasetsWithGraphHistory(
      @Param("tenantId") long tenantId, @Param("chunks") List<List<Long>> chunks);
}
