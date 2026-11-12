package com.smartfirehub.pipeline.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.PlatformMapper;
import com.smartfirehub.pipeline.dto.PipelineDeleteImpactResponse.DownstreamChainTrigger;
import com.smartfirehub.pipeline.repository.TriggerRepository.ChainEdgeRow;
import com.smartfirehub.pipeline.repository.mybatis.TriggerRows.TriggerInsert;
import com.smartfirehub.pipeline.repository.mybatis.TriggerRows.TriggerRow;
import com.smartfirehub.pipeline.repository.mybatis.TriggerRows.VersionRow;
import com.smartfirehub.pipeline.repository.mybatis.TriggerRows.WatchRow;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * 파이프라인 트리거(pipeline_trigger) MyBatis 매퍼(WD-708). SQL 은 {@code mybatis/pipeline/TriggerMapper.xml}.
 * 문장 1:1 바인딩만 한다 — 테넌트는 정책(RLS)이 맡는다.
 */
@PlatformMapper
public interface TriggerMapper {

  List<TriggerRow> selectByPipelineId(@Param("pipelineId") Long pipelineId);

  TriggerRow selectById(@Param("id") Long id);

  List<TriggerRow> selectEnabledByType(@Param("triggerType") String triggerType);

  /** 켜진 PIPELINE_CHAIN 중 config.upstreamPipelineId(정수 변환)가 같은 것. */
  List<TriggerRow> selectEnabledChainByUpstream(@Param("upstreamId") Long upstreamId);

  /** 켜진 연쇄 간선(상류 id 는 원문 텍스트). */
  List<ChainEdgeRow> selectEnabledChainEdges();

  int insert(TriggerInsert row);

  VersionRow lockVersionById(@Param("id") Long id);

  /**
   * 부분 수정 — null 인 인자는 바꾸지 않는다. description 은 호출자가 공백→null 로 이미 바꿔 넘기고, 바꿀지 여부는 {@code
   * setDescription} 이 정한다.
   */
  int update(
      @Param("id") Long id,
      @Param("updatedAt") LocalDateTime updatedAt,
      @Param("updatedBy") Long updatedBy,
      @Param("name") String name,
      @Param("setDescription") boolean setDescription,
      @Param("description") String description,
      @Param("isEnabled") Boolean isEnabled,
      @Param("configJson") String configJson);

  int deleteById(@Param("id") Long id);

  int updateEnabled(
      @Param("id") Long id,
      @Param("enabled") boolean enabled,
      @Param("updatedAt") LocalDateTime updatedAt);

  /** trigger_state 만 바꾼다(수정 시각 불변). */
  int updateState(@Param("id") Long id, @Param("stateJson") String stateJson);

  List<DownstreamChainTrigger> selectEnabledDownstreamChain(@Param("upstreamId") Long upstreamId);

  int disableByUpstream(
      @Param("upstreamId") Long upstreamId, @Param("updatedAt") LocalDateTime updatedAt);

  /**
   * 감시 목록 정리 후보를 id 순으로 잠그고 읽는다(스펙 D5) — PG 는 datasetIds 에 이 id 가 든(@>) DATASET_CHANGE 트리거, Tibero
   * 는 DATASET_CHANGE 트리거 전부(JSON 텍스트 비교가 101.0 등을 놓쳐 구현이 숫자로 판정한다).
   */
  List<WatchRow> lockWatchingTriggers(@Param("datasetId") Long datasetId);

  /** 감시 목록 정리 결과를 한 행에 쓴다(config·trigger_state·is_enabled·updated_at). */
  int updateWatch(
      @Param("id") Long id,
      @Param("configJson") String configJson,
      @Param("stateJson") String stateJson,
      @Param("enabled") boolean enabled,
      @Param("updatedAt") LocalDateTime updatedAt);

  /** 한 트리거를 잠그고 상태를 읽는다(clearPollSnapshot 용). */
  WatchRow lockWatchById(@Param("id") Long id);

  List<Long> selectOutputDatasetIds(@Param("pipelineId") Long pipelineId);

  TriggerRow selectByTokenHash(@Param("tokenHash") String tokenHash);

  TriggerRow selectByWebhookId(@Param("webhookId") String webhookId);

  /** PENDING·RUNNING 실행이 있으면 1, 없으면 null. */
  Integer selectRunningExecutionMarker(@Param("pipelineId") Long pipelineId);

  /** 파이프라인 활성 플래그 — 행이 없으면 null. */
  Boolean selectPipelineActive(@Param("pipelineId") Long pipelineId);
}
