package com.smartfirehub.dataset.repository.mybatis;

import com.smartfirehub.dataset.dto.DatasetDetailResponse;
import com.smartfirehub.dataset.dto.DatasetReferencesResponse;
import com.smartfirehub.dataset.repository.DatasetReferenceRepository;
import com.smartfirehub.global.platformdb.mybatis.PlatformMapper;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * 데이터셋 참조(파이프라인·대시보드·트리거) 조회 MyBatis 매퍼(WD-707). SQL 은 {@code
 * mybatis/dataset/DatasetReferenceMapper.xml}, 대시보드 가시성은 공용 조각 {@code
 * mybatis/analytics/DashboardVisibility.xml}.
 */
@PlatformMapper
public interface DatasetReferenceMapper {

  /**
   * SQL 본문 대조 후보 대시보드의 평평한 행 — 리포지토리가 {@link DatasetReferenceRepository.DashboardSqlCandidate} 로
   * 묶는다.
   */
  record DashboardSqlRow(Long id, String name, boolean visible, String sqlText) {}

  /** 이 데이터셋을 출력으로 쓰는 파이프라인(중복 없이, 활성 여부 포함). */
  List<DatasetDetailResponse.LinkedPipelineInfo> selectLinkedPipelines(@Param("id") Long id);

  /** 이 데이터셋을 출력으로 쓰는 스텝 수(같은 파이프라인의 스텝도 각각 센다). */
  int countStepsByOutputDataset(@Param("id") Long id);

  /** 출력·입력 어느 쪽으로든 이 데이터셋을 잇는 파이프라인(한 번씩, id 순). */
  List<DatasetReferencesResponse.ReferenceItem> selectPipelinesByFk(
      @Param("datasetId") Long datasetId);

  /** 이 데이터셋을 MERGE 적재 출력으로 쓰는 파이프라인(id 순). */
  List<DatasetReferencesResponse.ReferenceItem> selectMergeOutputPipelines(
      @Param("datasetId") Long datasetId);

  /** pattern 은 {@code LikePatternUtils.containsPattern} 로 이스케이프한 부분 일치 패턴. */
  List<DatasetReferenceRepository.PipelineSqlCandidate> selectPipelineSqlCandidates(
      @Param("pattern") String pattern);

  /** 저장 쿼리 dataset_id 로 이 데이터셋을 쓰는 대시보드와 조회자 가시성. */
  List<DatasetReferenceRepository.DashboardHit> selectDashboardsByFk(
      @Param("datasetId") Long datasetId, @Param("userId") Long userId);

  /** pattern 은 {@code LikePatternUtils.containsPattern} 로 이스케이프한 부분 일치 패턴. */
  List<DashboardSqlRow> selectDashboardSqlCandidates(
      @Param("pattern") String pattern, @Param("userId") Long userId);

  /** 감시 데이터셋 목록에 이 데이터셋이 든 DATASET_CHANGE 트리거. */
  List<DatasetReferencesResponse.TriggerReferenceItem> selectDatasetChangeTriggers(
      @Param("datasetId") Long datasetId);
}
