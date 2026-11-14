package com.smartfirehub.dataset.repository.mybatis;

import com.smartfirehub.dataset.dto.DatasetDetailResponse;
import com.smartfirehub.dataset.dto.DatasetReferencesResponse;
import com.smartfirehub.dataset.repository.DatasetReferenceRepository;
import com.smartfirehub.global.platformdb.mybatis.PlatformMyBatisRepository;
import com.smartfirehub.global.util.LikePatternUtils;
import java.util.List;

/**
 * DatasetReferenceRepository 의 MyBatis 구현(WD-707) — {@code JooqDatasetReferenceRepository} 와 같은 의미.
 *
 * <p>클래스 단위 {@code @Transactional} 을 두지 않는다 — jOOQ 판 그대로다(호출하는 서비스 메서드
 * getDatasetById·deleteDataset· getReferences 가 이미 트랜잭션이라 그 트랜잭션의 테넌트 컨텍스트를 쓴다). 대시보드 가시성은 공용 조각
 * {@code analytics/DashboardVisibility.xml}({@code JooqAnalyticsDashboardRepository.visibleTo} 와 같은
 * 규칙)로 읽고, 트리거 config 의 datasetIds 포함 판정만 벤더별 문장이다(매퍼 XML 주석 참조).
 */
@PlatformMyBatisRepository
public class MyBatisDatasetReferenceRepository implements DatasetReferenceRepository {

  private final DatasetReferenceMapper mapper;

  public MyBatisDatasetReferenceRepository(DatasetReferenceMapper mapper) {
    this.mapper = mapper;
  }

  /** 이 데이터셋을 출력으로 쓰는 파이프라인(상세 화면의 연결 파이프라인). */
  @Override
  public List<DatasetDetailResponse.LinkedPipelineInfo> findLinkedPipelines(Long id) {
    return mapper.selectLinkedPipelines(id);
  }

  /** 이 데이터셋을 출력으로 참조하는 파이프라인 스텝 수 — 삭제 전 FK 위반 사전 점검용(#126). */
  @Override
  public int countStepsByOutputDataset(Long id) {
    return mapper.countStepsByOutputDataset(id);
  }

  /** output_dataset_id 또는 pipeline_step_input.dataset_id 로 참조하는 파이프라인(중복 제거, id 순). */
  @Override
  public List<DatasetReferencesResponse.ReferenceItem> findPipelinesByFk(Long datasetId) {
    return mapper.selectPipelinesByFk(datasetId);
  }

  /** (WD-441) 이 데이터셋을 병합(MERGE) 적재 출력으로 쓰는 파이프라인(id 순). */
  @Override
  public List<DatasetReferencesResponse.ReferenceItem> findMergeOutputPipelines(Long datasetId) {
    return mapper.selectMergeOutputPipelines(datasetId);
  }

  /**
   * SQL 스텝 본문이 테이블 이름을 대소문자 무시로 포함하는 파이프라인 후보(WD-143). {@code %}·{@code _}·{@code \} 는 {@link
   * LikePatternUtils#containsPattern} 로 이스케이프해 글자 그대로 찾는다 — 확정은 서비스 파서가 한다.
   */
  @Override
  public List<PipelineSqlCandidate> findPipelineSqlCandidates(String tableName) {
    return mapper.selectPipelineSqlCandidates(LikePatternUtils.containsPattern(tableName));
  }

  /** 저장 쿼리 dataset_id 경로로 이 데이터셋을 쓰는 대시보드 — 조회자 가시성(본인 소유 또는 공유)을 함께 읽는다(WD-143). */
  @Override
  public List<DashboardHit> findDashboardsByFk(Long datasetId, Long userId) {
    return mapper.selectDashboardsByFk(datasetId, userId);
  }

  /**
   * 저장 쿼리 SQL 본문이 테이블 이름을 포함하는 대시보드 후보(WD-143). 거르기는 {@link #findPipelineSqlCandidates} 와 같고, 매퍼의
   * 평평한 행을 {@link DashboardSqlCandidate} 로 묶는다.
   */
  @Override
  public List<DashboardSqlCandidate> findDashboardSqlCandidates(String tableName, Long userId) {
    return mapper
        .selectDashboardSqlCandidates(LikePatternUtils.containsPattern(tableName), userId)
        .stream()
        .map(
            r ->
                new DashboardSqlCandidate(
                    new DashboardHit(r.id(), r.name(), r.visible()), r.sqlText()))
        // 매퍼는 (대시보드, 저장 쿼리) 쌍마다 한 행이라(sql_text 가 Tibero CLOB 이라 DISTINCT 불가, WD-740)
        // 같은 대시보드·같은 본문이 겹치는 것을 여기서 지운다. 순서는 처음 나온 순.
        .distinct()
        .toList();
  }

  /**
   * config.datasetIds 배열로 이 데이터셋을 감시하는 DATASET_CHANGE 트리거(#600, id 순). 삭제 시 놓치면 트리거가 고아로 남아 없는
   * 데이터셋을 계속 폴링한다.
   */
  @Override
  public List<DatasetReferencesResponse.TriggerReferenceItem> findDatasetChangeTriggers(
      Long datasetId) {
    return mapper.selectDatasetChangeTriggers(datasetId);
  }
}
