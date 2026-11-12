package com.smartfirehub.pipeline.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.PlatformMapper;
import com.smartfirehub.pipeline.repository.TriggerEventRepository.LatestEvent;
import com.smartfirehub.pipeline.repository.mybatis.TriggerEventRows.EventRow;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * 트리거 발화 이력(trigger_event) MyBatis 매퍼(WD-708). SQL 은 {@code
 * mybatis/pipeline/TriggerEventMapper.xml}. 문장 1:1 바인딩만 한다 — 테넌트는 정책(RLS)이 맡는다.
 */
@PlatformMapper
public interface TriggerEventMapper {

  /**
   * PIPELINE_CHAIN 트리거의 FIRED 이벤트 detail.chainDepth 원문 — 해당 이벤트가 없거나 값이 없으면 null. 숫자 해석은 구현이 한다.
   */
  String selectChainDepthRaw(
      @Param("pipelineId") Long pipelineId, @Param("executionId") Long executionId);

  /** 한 트리거의 가장 최근 이벤트(MISSED 제외) — 없으면 null. */
  LatestEvent selectLatest(@Param("triggerId") Long triggerId);

  /** 이벤트 한 건 삽입. detailJson 이 null 이면 detail 은 NULL. */
  int insert(
      @Param("triggerId") Long triggerId,
      @Param("pipelineId") Long pipelineId,
      @Param("executionId") Long executionId,
      @Param("eventType") String eventType,
      @Param("detailJson") String detailJson);

  /** 파이프라인의 이벤트(트리거 이름 조인) — 최신순, 상한 limit. */
  List<EventRow> selectByPipeline(
      @Param("pipelineId") Long pipelineId, @Param("limit") int limit, @Param("offset") int offset);

  /** 트리거의 이벤트(트리거 이름 조인) — 최신순, 상한 limit. */
  List<EventRow> selectByTrigger(
      @Param("triggerId") Long triggerId, @Param("limit") int limit, @Param("offset") int offset);

  /** 기준 시각보다 오래된 이벤트를 지우고 지운 행 수를 돌려준다. */
  int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff);
}
