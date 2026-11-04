package com.smartfirehub.pipeline.repository;

import com.smartfirehub.pipeline.dto.TriggerEventResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 트리거 발화 이력(trigger_event) 저장·조회 리포지토리.
 *
 * <p>구현은 {@code JooqTriggerEventRepository}(jOOQ/PostgreSQL) 이다 — WD-485.
 */
public interface TriggerEventRepository {

  /**
   * 어떤 실행이 PIPELINE_CHAIN 연쇄의 몇 번째 단계였는지(chainDepth)를 그 실행을 만든 FIRED 이벤트에서 읽는다(WD-106).
   *
   * <p>연쇄 발화는 {@code fireTrigger} 가 params 를 FIRED 이벤트 detail 에 그대로 남기므로, 거기에 실어 둔 깊이를 다음 단계가 이어
   * 받는다 — 실행 레코드에 컬럼을 추가하지 않고(마이그레이션 없이) 깊이를 전달하는 방법이다.
   *
   * <p><b>PIPELINE_CHAIN 트리거의 FIRED 이벤트만 믿는다.</b> API·WEBHOOK 트리거는 외부 호출자의 요청 본문이 params 로 그대로
   * detail 에 남으므로, 거기의 chainDepth(예: 음수)를 믿으면 외부에서 깊이 상한을 무력화할 수 있다. 음수도 0 으로 다룬다.
   *
   * <p>{@code pipeline_id} 조건은 인덱스(idx_trigger_event_pipeline)를 타기 위한 것이다 — FIRED 이벤트의 pipeline_id
   * 는 그 실행의 파이프라인과 같다.
   *
   * @return 깊이. 연쇄로 시작된 실행이 아니면(수동·스케줄·API 등) 빈 값
   */
  Optional<Integer> findChainDepthOfExecution(Long pipelineId, Long executionId);

  /** 한 트리거의 가장 최근 이벤트 요약 — 종류와 detail.reason(없으면 null)(WD-169). */
  record LatestEvent(String eventType, String reason) {}

  /**
   * 한 트리거의 가장 최근 이벤트를 읽는다(WD-169). 트리거를 만든 사람이 정지·권한 회수돼 건너뛸 때 「처음 한 번만」 알리기 위한 판단 근거다 — 직전 이벤트가 같은
   * 사유의 건너뜀이면 이미 알렸다고 본다. 별도 컬럼 없이(마이그레이션 없이) 기존 이벤트 이력으로 판단한다.
   *
   * <p>순서는 created_at 내림차순(인덱스 idx_trigger_event_trigger_time 을 탄다)에 id 로 동률을 가른다 — 같은 시각에 겹친 이벤트도
   * 나중에 넣은 것이 최근이다.
   */
  Optional<LatestEvent> findLatestByTrigger(Long triggerId);

  void create(
      Long triggerId,
      Long pipelineId,
      Long executionId,
      String eventType,
      Map<String, Object> detail);

  List<TriggerEventResponse> findByPipelineId(Long pipelineId, int limit);

  List<TriggerEventResponse> findByTriggerId(Long triggerId, int limit);

  int deleteOlderThan(int days);
}
