package com.smartfirehub.mapping.repository;

import com.smartfirehub.securitylevel.access.DatasetVisibility;
import java.util.List;
import java.util.Optional;

/**
 * 데이터셋-온톨로지 매핑(dataset_mapping 테이블) 저장·조회 리포지토리. 라이브 매핑 조회는 dataset 과 조인한다.
 *
 * <p>구현은 {@code JooqMappingRepository}(jOOQ/PostgreSQL) 이다 — WD-485.
 */
public interface MappingRepository {

  /** 한 온톨로지에 묶인 매핑 한 건(이름 변경 연쇄 갱신용) — spec 은 raw JSON. */
  record OntologyMappingRow(long datasetId, String specJson) {}

  /**
   * 데이터셋이 살아 있는 매핑 한 건(사용처 조회용). datasetName 은 조회자가 볼 수 없는 데이터셋이면 null 이다 — 보안 등급상 이름을 보여 줄 수 없는
   * 데이터셋도 "사용 중"이라는 사실은 숨기지 않는다(삭제는 똑같이 막혀야 하므로).
   */
  record LiveMappingRow(long datasetId, String datasetName, String specJson) {}

  void upsert(long datasetId, long ontologyId, String specJson, String status, Long userId);

  Optional<StoredMapping> findByDataset(long datasetId);

  Optional<StoredMapping> findByDatasetForUpdate(long datasetId);

  boolean deleteByDatasetExceptOntology(long datasetId, long keepOntologyId);

  void updateStatus(long datasetId, String status, Long userId);

  List<OntologyMappingRow> findByOntologyForUpdate(long ontologyId);

  List<LiveMappingRow> findLiveByOntology(long ontologyId, DatasetVisibility nameVisibility);

  void updateSpec(long datasetId, String specJson);
}
