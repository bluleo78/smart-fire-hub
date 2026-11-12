package com.smartfirehub.pipeline.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.pipeline.dto.TriggerEventResponse;
import com.smartfirehub.pipeline.repository.TriggerEventRepository.LatestEvent;
import com.smartfirehub.user.repository.TenantPairContractBase;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * TriggerEventRepository 계약 테스트 — 구현마다 하위 클래스로 실행한다(WD-708).
 *
 * <p>trigger_event 는 테넌트 정책(RLS) 아래 있어 호출은 늘 테넌트 컨텍스트({@code inA}/{@code inB})로 감싼다. detail 은 JSON
 * 열(PG jsonb / Tibero JSON)이고 {@code ->>} 추출은 Tibero 에서 JSON_VALUE 다. 정렬 시각은 픽스처로 정해 넣어 벽시계에 기대지
 * 않는다.
 */
public abstract class TriggerEventRepositoryContractTest extends TenantPairContractBase {

  protected abstract TriggerEventRepository repository();

  private long pipelineA;
  private long chainTrigger;
  private long apiTrigger;
  private LocalDateTime base;

  @BeforeEach
  void createPipelineAndTriggers() {
    pipelineA = fx.createPipeline(tenantA, prefix + "-p", true);
    chainTrigger = fx.createTrigger(pipelineA, prefix + "-chain", "PIPELINE_CHAIN", "{}");
    apiTrigger = fx.createTrigger(pipelineA, prefix + "-api", "API", "{}");
    // 같은 초 단위 정렬이 드라이버 정밀도에 흔들리지 않게 초 단위로 맞춘 기준 시각.
    base = fx.dbWallClockNow().withNano(0).minusHours(1);
  }

  private long event(long triggerId, Long exec, String type, String detail, int minutesAfter) {
    return fx.insertTriggerEvent(triggerId, exec, type, detail, base.plusMinutes(minutesAfter));
  }

  @Test
  @DisplayName("create — detail JSON(중첩·배열·숫자)이 왕복하고 트리거 이름이 조인되며 시각은 DB 시계로 쓰인다")
  void create_roundTripsDetailAndJoinsTriggerName() {
    long exec = fx.createExecution(pipelineA, "COMPLETED");
    Map<String, Object> detail =
        Map.of("chainDepth", 2, "note", "한글 사유", "nested", Map.of("a", "b"), "list", List.of(1, 2));

    runA(() -> repository().create(chainTrigger, pipelineA, exec, "FIRED", detail));

    List<TriggerEventResponse> events = inA(() -> repository().findByPipelineId(pipelineA, 10));
    assertThat(events).hasSize(1);
    TriggerEventResponse e = events.get(0);
    assertThat(e.id()).isNotNull();
    assertThat(e.triggerId()).isEqualTo(chainTrigger);
    assertThat(e.triggerName()).isEqualTo(prefix + "-chain");
    assertThat(e.eventType()).isEqualTo("FIRED");
    assertThat(e.executionId()).isEqualTo(exec);
    assertThat(e.detail()).isEqualTo(detail);
    assertThat(Duration.between(e.createdAt(), fx.dbWallClockNow()).abs())
        .isLessThan(Duration.ofMinutes(2));
    assertThat(fx.triggerEventCreatedAts(chainTrigger)).hasSize(1);
  }

  @Test
  @DisplayName("create — detail 이 null·빈 맵이고 executionId 가 null 이면 detail 은 빈 맵, 실행 id 는 null 이다")
  void create_nullAndEmptyDetail() {
    runA(() -> repository().create(chainTrigger, pipelineA, null, "SKIPPED", null));
    runA(() -> repository().create(chainTrigger, pipelineA, null, "SKIPPED", Map.of()));

    List<TriggerEventResponse> events = inA(() -> repository().findByTriggerId(chainTrigger, 10));
    assertThat(events).hasSize(2);
    assertThat(events).allSatisfy(e -> assertThat(e.detail()).isEmpty());
    assertThat(events).allSatisfy(e -> assertThat(e.executionId()).isNull());
  }

  @Test
  @DisplayName("findByPipelineId — 최신순이고 상한을 지키며, 같은 시각이면 나중 id 가 먼저다")
  void findByPipelineId_orderLimitAndTieBreak() {
    long oldest = event(chainTrigger, null, "FIRED", null, 0);
    long middleA = event(apiTrigger, null, "FIRED", null, 5);
    long middleB = event(chainTrigger, null, "SKIPPED", null, 5);
    long newest = event(apiTrigger, null, "FIRED", null, 10);

    List<TriggerEventResponse> all = inA(() -> repository().findByPipelineId(pipelineA, 10));
    assertThat(all)
        .extracting(TriggerEventResponse::id)
        .containsExactly(newest, middleB, middleA, oldest);

    List<TriggerEventResponse> top2 = inA(() -> repository().findByPipelineId(pipelineA, 2));
    assertThat(top2).extracting(TriggerEventResponse::id).containsExactly(newest, middleB);
    assertThat(top2.get(0).createdAt()).isEqualTo(base.plusMinutes(10));
  }

  @Test
  @DisplayName("findByTriggerId — 그 트리거 이벤트만 최신순으로, 다른 트리거·다른 테넌트 이벤트는 섞이지 않는다")
  void findByTriggerId_onlyThatTrigger() {
    long c1 = event(chainTrigger, null, "FIRED", "{\"k\":1}", 0);
    long c2 = event(chainTrigger, null, "FIRED", "{\"k\":2}", 1);
    event(apiTrigger, null, "FIRED", null, 2);

    List<TriggerEventResponse> events = inA(() -> repository().findByTriggerId(chainTrigger, 10));
    assertThat(events).extracting(TriggerEventResponse::id).containsExactly(c2, c1);
    assertThat(events.get(0).detail()).isEqualTo(Map.of("k", 2));

    // 다른 테넌트 컨텍스트에서는 보이지 않는다.
    assertThat(inB(() -> repository().findByTriggerId(chainTrigger, 10))).isEmpty();
    assertThat(inB(() -> repository().findByPipelineId(pipelineA, 10))).isEmpty();
  }

  @Test
  @DisplayName("findChainDepthOfExecution — PIPELINE_CHAIN 트리거의 FIRED 이벤트 chainDepth 만 믿고 음수는 0 이다")
  void findChainDepth_onlyChainFiredEvents() {
    long execChain = fx.createExecution(pipelineA, "COMPLETED");
    long execNegative = fx.createExecution(pipelineA, "COMPLETED");
    long execApi = fx.createExecution(pipelineA, "COMPLETED");
    long execSkipped = fx.createExecution(pipelineA, "COMPLETED");
    long execNoDepth = fx.createExecution(pipelineA, "COMPLETED");
    long execGarbage = fx.createExecution(pipelineA, "COMPLETED");
    long execNone = fx.createExecution(pipelineA, "COMPLETED");

    event(chainTrigger, execChain, "FIRED", "{\"chainDepth\":3}", 0);
    event(chainTrigger, execNegative, "FIRED", "{\"chainDepth\":-4}", 1);
    // 외부 호출자가 params 로 싣을 수 있는 API 트리거 이벤트의 깊이는 믿지 않는다.
    event(apiTrigger, execApi, "FIRED", "{\"chainDepth\":9}", 2);
    event(chainTrigger, execSkipped, "SKIPPED", "{\"chainDepth\":5}", 3);
    event(chainTrigger, execNoDepth, "FIRED", "{\"other\":1}", 4);
    event(chainTrigger, execGarbage, "FIRED", "{\"chainDepth\":\"abc\"}", 5);

    assertThat(inA(() -> repository().findChainDepthOfExecution(pipelineA, execChain))).contains(3);
    assertThat(inA(() -> repository().findChainDepthOfExecution(pipelineA, execNegative)))
        .contains(0);
    assertThat(inA(() -> repository().findChainDepthOfExecution(pipelineA, execApi))).isEmpty();
    assertThat(inA(() -> repository().findChainDepthOfExecution(pipelineA, execSkipped))).isEmpty();
    assertThat(inA(() -> repository().findChainDepthOfExecution(pipelineA, execNoDepth))).isEmpty();
    assertThat(inA(() -> repository().findChainDepthOfExecution(pipelineA, execGarbage))).isEmpty();
    assertThat(inA(() -> repository().findChainDepthOfExecution(pipelineA, execNone))).isEmpty();
    // 다른 파이프라인 id 로는 찾지 못하고, 다른 테넌트에서도 보이지 않는다.
    assertThat(inA(() -> repository().findChainDepthOfExecution(pipelineA + 1_000_000, execChain)))
        .isEmpty();
    assertThat(inB(() -> repository().findChainDepthOfExecution(pipelineA, execChain))).isEmpty();
  }

  @Test
  @DisplayName("findLatestByTrigger — MISSED 는 건너뛰고 최신 이벤트의 종류와 reason 을 돌려준다")
  void findLatestByTrigger_skipsMissedAndReadsReason() {
    event(chainTrigger, null, "SKIPPED", "{\"reason\":\"OLD\"}", 0);
    event(chainTrigger, null, "SKIPPED", "{\"reason\":\"CREATOR_SUSPENDED\"}", 5);
    event(chainTrigger, null, "MISSED", "{\"reason\":\"IGNORED\"}", 10);

    LatestEvent latest = inA(() -> repository().findLatestByTrigger(chainTrigger)).orElseThrow();

    assertThat(latest.eventType()).isEqualTo("SKIPPED");
    assertThat(latest.reason()).isEqualTo("CREATOR_SUSPENDED");
  }

  @Test
  @DisplayName("findLatestByTrigger — reason 이 없거나 detail 이 없으면 reason 은 null 이고, 이벤트가 없으면 빈 값이다")
  void findLatestByTrigger_nullReasonAndEmpty() {
    assertThat(inA(() -> repository().findLatestByTrigger(chainTrigger))).isEmpty();

    event(chainTrigger, null, "FIRED", "{\"x\":1}", 0);
    LatestEvent noReason = inA(() -> repository().findLatestByTrigger(chainTrigger)).orElseThrow();
    assertThat(noReason.eventType()).isEqualTo("FIRED");
    assertThat(noReason.reason()).isNull();

    event(chainTrigger, null, "FIRED", null, 1);
    LatestEvent noDetail = inA(() -> repository().findLatestByTrigger(chainTrigger)).orElseThrow();
    assertThat(noDetail.reason()).isNull();
  }

  @Test
  @DisplayName("findLatestByTrigger — 같은 시각이면 나중에 넣은 이벤트가 최근이다")
  void findLatestByTrigger_tieBreakById() {
    event(chainTrigger, null, "FIRED", null, 3);
    event(chainTrigger, null, "SKIPPED", "{\"reason\":\"LATER\"}", 3);

    LatestEvent latest = inA(() -> repository().findLatestByTrigger(chainTrigger)).orElseThrow();

    assertThat(latest.eventType()).isEqualTo("SKIPPED");
    assertThat(latest.reason()).isEqualTo("LATER");
  }

  @Test
  @DisplayName("findLatestByTrigger — 다른 테넌트 컨텍스트에서는 이벤트가 보이지 않는다")
  void findLatestByTrigger_otherTenantInvisible() {
    event(chainTrigger, null, "FIRED", null, 0);

    assertThat(inB(() -> repository().findLatestByTrigger(chainTrigger))).isEmpty();
  }

  @Test
  @DisplayName("deleteOlderThan — 기준보다 오래된 이벤트만 지우고 지운 수를 돌려주며, 다른 테넌트 이벤트는 건드리지 않는다")
  void deleteOlderThan_deletesOnlyOldInOwnTenant() {
    LocalDateTime now = fx.dbWallClockNow();
    fx.insertTriggerEvent(chainTrigger, null, "FIRED", null, now.minusDays(90));
    fx.insertTriggerEvent(chainTrigger, null, "FIRED", null, now.minusDays(40));
    fx.insertTriggerEvent(chainTrigger, null, "FIRED", null, now.minusDays(2));
    fx.insertTriggerEvent(apiTrigger, null, "FIRED", null, now.plusDays(1));

    long pipelineB = fx.createPipeline(tenantB, prefix + "-pb", true);
    long triggerB = fx.createTrigger(pipelineB, prefix + "-tb", "API", "{}");
    fx.insertTriggerEvent(triggerB, null, "FIRED", null, now.minusDays(90));

    int deleted = inA(() -> repository().deleteOlderThan(30));

    assertThat(deleted).isEqualTo(2);
    assertThat(fx.countTriggerEvents(pipelineA)).isEqualTo(2);
    assertThat(fx.countTriggerEvents(pipelineB)).isEqualTo(1);
    assertThat(inA(() -> repository().deleteOlderThan(30))).isZero();
  }
}
