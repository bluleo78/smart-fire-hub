package com.smartfirehub.mapping.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.PlatformMapper;
import com.smartfirehub.mapping.repository.MappingRepository.LiveMappingRow;
import com.smartfirehub.mapping.repository.MappingRepository.OntologyMappingRow;
import com.smartfirehub.mapping.repository.StoredMapping;
import com.smartfirehub.securitylevel.repository.mybatis.MyBatisDatasetVisibility.VisibilityParams;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * 데이터셋 매핑(dataset_mapping) MyBatis 매퍼(WD-708). SQL 은 {@code mybatis/mapping/MappingMapper.xml}. 문장
 * 1:1 바인딩만 한다 — 테넌트는 정책(RLS)이 맡는다.
 */
@PlatformMapper
public interface MappingMapper {

  /** 데이터셋 한 건의 매핑을 넣거나 덮는다(PG ON CONFLICT, Tibero MERGE ON dataset_id). */
  int upsert(
      @Param("datasetId") long datasetId,
      @Param("ontologyId") long ontologyId,
      @Param("specJson") String specJson,
      @Param("status") String status,
      @Param("userId") Long userId);

  /** 데이터셋 매핑 — 없으면 null. */
  StoredMapping selectByDataset(@Param("datasetId") long datasetId);

  /** 데이터셋 매핑을 행 잠금(FOR UPDATE)과 함께 읽는다 — 없으면 null. */
  StoredMapping selectByDatasetForUpdate(@Param("datasetId") long datasetId);

  /** keep 이 아닌 온톨로지의 매핑을 지우고 지운 행 수를 돌려준다. */
  int deleteByDatasetExceptOntology(
      @Param("datasetId") long datasetId, @Param("keepOntologyId") long keepOntologyId);

  /** 상태만 바꾼다(수정자·수정 시각 함께). */
  int updateStatus(
      @Param("datasetId") long datasetId,
      @Param("status") String status,
      @Param("userId") Long userId);

  /** 온톨로지에 묶인 매핑 전부를 데이터셋 id 순으로 행 잠금과 함께 읽는다. */
  List<OntologyMappingRow> selectByOntologyForUpdate(@Param("ontologyId") long ontologyId);

  /** 온톨로지에 묶이고 데이터셋이 남은 매핑 — 이름은 가시성을 만족할 때만 싣는다. */
  List<LiveMappingRow> selectLiveByOntology(
      @Param("ontologyId") long ontologyId, @Param("vis") VisibilityParams vis);

  /** spec 만 교체한다(상태·수정자는 그대로, 수정 시각만 갱신). */
  int updateSpec(@Param("datasetId") long datasetId, @Param("specJson") String specJson);
}
