package com.smartfirehub.pipeline.dto;

import com.smartfirehub.pipeline.repository.PipelineExecutionRepository.ActiveExecutionState;
import java.util.List;

/**
 * 파이프라인 삭제 영향(WD-170) — 삭제 확인 창이 「자동 생성된 임시 데이터셋 N개도 함께 삭제됩니다」를 보여 주는 데 쓴다.
 *
 * @param tempDatasetCount 파이프라인을 지우면 함께 지워질 자동 생성 임시 데이터셋 개수. 다른 파이프라인 스텝이 출력으로 쓰고 있어 남는 것은 세지
 *     않는다(실제 삭제와 같은 판정). 호출자가 볼 수 없는 것도 세지 않는다(WD-310 — 숨긴 개수를 드러내지 않게).
 * @param hasHiddenTempDatasets 호출자가 볼 수 없는 임시 데이터셋이 함께 지워질 대상에 있는가(WD-310). true 면 삭제가 403 이라 확인 창이
 *     삭제를 막고 안내한다.
 * @param activeExecution 진행 중(PENDING/RUNNING) 실행 상태(WD-437) — NONE 이 아니면 삭제가 409 라 확인 창이 삭제를 막고 안내한다.
 *     CANCELLING 은 진행 중 실행이 모두 이미 취소를 요청한 상태다(「취소 진행 중」 안내).
 * @param downstreamChainTriggers 이 파이프라인을 선행으로 둔, 다른 파이프라인의 켜진 연쇄(PIPELINE_CHAIN) 트리거(WD-419). 삭제하면
 *     이 트리거들이 꺼지므로(WD-218) 확인 창이 「연쇄 트리거 N개가 꺼집니다」와 목록을 보여 준다. 없으면 빈 목록.
 */
public record PipelineDeleteImpactResponse(
    int tempDatasetCount,
    boolean hasHiddenTempDatasets,
    ActiveExecutionState activeExecution,
    List<DownstreamChainTrigger> downstreamChainTriggers) {

  /** 하류 연쇄 트리거가 없는 응답 — 기존 호출부(연쇄 트리거를 보지 않는 곳)가 그대로 쓰도록 남긴다. */
  public PipelineDeleteImpactResponse(
      int tempDatasetCount, boolean hasHiddenTempDatasets, ActiveExecutionState activeExecution) {
    this(tempDatasetCount, hasHiddenTempDatasets, activeExecution, List.of());
  }

  /**
   * 삭제하면 꺼질 하류 연쇄 트리거 한 건(WD-419) — 확인 창에 「파이프라인 「B」 — 트리거 「X」」로 보인다.
   *
   * @param triggerId 트리거 id
   * @param triggerName 트리거 이름
   * @param pipelineId 트리거가 붙은(하류) 파이프라인 id
   * @param pipelineName 하류 파이프라인 이름
   */
  public record DownstreamChainTrigger(
      long triggerId, String triggerName, long pipelineId, String pipelineName) {}
}
