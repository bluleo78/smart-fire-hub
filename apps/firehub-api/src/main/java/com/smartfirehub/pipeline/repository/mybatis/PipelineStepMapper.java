package com.smartfirehub.pipeline.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.PlatformMapper;
import com.smartfirehub.pipeline.repository.mybatis.PipelineStepRows.CursorRow;
import com.smartfirehub.pipeline.repository.mybatis.PipelineStepRows.DependencyRow;
import com.smartfirehub.pipeline.repository.mybatis.PipelineStepRows.IdPair;
import com.smartfirehub.pipeline.repository.mybatis.PipelineStepRows.InputRow;
import com.smartfirehub.pipeline.repository.mybatis.PipelineStepRows.StepInsert;
import com.smartfirehub.pipeline.repository.mybatis.PipelineStepRows.StepRow;
import com.smartfirehub.securitylevel.repository.mybatis.MyBatisDatasetVisibility.VisibilityParams;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * 파이프라인 스텝(pipeline_step)·입력·의존 MyBatis 매퍼(WD-708). SQL 은 {@code
 * mybatis/pipeline/PipelineStepMapper.xml}. 문장 1:1 바인딩만 한다 — 테넌트는 정책(RLS)이 맡고, id 목록은 호출자가 {@code
 * InChunks} 로 나눠 넘긴다(Tibero IN 상한).
 */
@PlatformMapper
public interface PipelineStepMapper {

  /** 파이프라인의 스텝(출력 데이터셋 이름은 가시성 조각으로 가린다) — step_order 순. */
  List<StepRow> selectSteps(
      @Param("pipelineId") Long pipelineId, @Param("vis") VisibilityParams vis);

  List<InputRow> selectInputs(@Param("chunks") List<List<Long>> chunks);

  List<DependencyRow> selectDependencies(@Param("chunks") List<List<Long>> chunks);

  /** 스텝 한 건을 넣고 생성 키를 홀더에 돌려준다. */
  int insert(StepInsert row);

  int update(
      @Param("pipelineId") Long pipelineId,
      @Param("stepId") Long stepId,
      @Param("row") StepInsert row);

  int renameTemporarily(
      @Param("pipelineId") Long pipelineId,
      @Param("stepId") Long stepId,
      @Param("newName") String newName);

  int insertInput(@Param("stepId") Long stepId, @Param("datasetId") Long datasetId);

  int insertDependency(
      @Param("stepId") Long stepId, @Param("dependsOnStepId") Long dependsOnStepId);

  List<Long> selectStepIdsByPipeline(@Param("pipelineId") Long pipelineId);

  int deleteSteps(@Param("pipelineId") Long pipelineId, @Param("chunks") List<List<Long>> chunks);

  int deleteDependencies(@Param("chunks") List<List<Long>> chunks);

  int deleteInputs(@Param("chunks") List<List<Long>> chunks);

  /** (스텝 id, 그 스텝의 러너 TEMP 데이터셋 id) 쌍 — 출처 스텝 기준. */
  List<IdPair> selectTempDatasetsByStepIds(@Param("chunks") List<List<Long>> chunks);

  /** (데이터셋 id, 소유 스텝 id) 쌍 — TEMP 이고 출처 스텝이 있는 데이터셋만. */
  List<IdPair> selectTempOwnersByDatasetIds(@Param("chunks") List<List<Long>> chunks);

  List<Long> selectAutoTempDatasetIds(@Param("chunks") List<List<Long>> chunks);

  /** 후보 데이터셋 중 (제외 스텝 밖에서) 출력으로 쓰이는 id. excludedChunks 가 빈 목록이면 제외 없음. */
  List<Long> selectUsedOutputDatasetIds(
      @Param("datasetChunks") List<List<Long>> datasetChunks,
      @Param("excludedChunks") List<List<Long>> excludedChunks);

  CursorRow selectCursor(@Param("stepId") Long stepId);

  /** 책갈피 전진 — 해석된 출력이 written 과 같을 때만(같지 않으면 0 행). */
  int advanceCursor(
      @Param("stepId") Long stepId,
      @Param("candidate") Instant candidate,
      @Param("wasFullRebuild") boolean wasFullRebuild,
      @Param("writtenOutputDatasetId") Long writtenOutputDatasetId);

  int setFullRebuildPending(@Param("stepId") Long stepId, @Param("pending") boolean pending);

  int setCursor(
      @Param("stepId") Long stepId,
      @Param("lastRunAt") Instant lastRunAt,
      @Param("pending") boolean pending);

  Long selectStepIdByPipelineAndName(
      @Param("pipelineId") Long pipelineId, @Param("name") String name);
}
