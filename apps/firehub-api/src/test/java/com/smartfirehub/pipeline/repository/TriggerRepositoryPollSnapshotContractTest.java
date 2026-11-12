package com.smartfirehub.pipeline.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.user.repository.AccessFixtures.TriggerStored;
import com.smartfirehub.user.repository.TenantPairContractBase;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 데이터 변경 트리거 폴러 상태 정리(WD-399, WD-221) — 트리거를 다시 켤 때·감시 데이터셋을 지울 때 행 수 스냅숏과 함께 변경 카운터 스냅숏({@code
 * lastChangeSnapshot})도 지워지는지 본다. 남으면 다시 켠 트리거가 꺼진 동안의 변경으로 켜자마자 발화하거나, 지운 데이터셋의 기준선이 상태에 남는다.
 *
 * <p>인터페이스 계약 테스트 — 구현마다 하위 클래스로 실행한다(WD-485). WD-708 에서 jOOQ {@code DSLContext}·{@code JSONB} 직접
 * 사용을 DB 중립 픽스처로 바꿨다 — jOOQ 는 SQL 한 문장(jsonb_set·#-), MyBatis 는 잠근 뒤 자바에서 같은 의미로 고쳐 쓴다(스펙 D5). 아래
 * 시나리오가 두 방식이 같은 결과를 내는지 고정한다(원소 하나·마지막 원소·없는 원소·중복·문자열 원소·배열 아닌 목록·다른 유형·꺼진 트리거·다른 테넌트).
 */
public abstract class TriggerRepositoryPollSnapshotContractTest extends TenantPairContractBase {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** 폴러가 남기는 상태 그대로 — 감시 데이터셋 101·102 의 행 수·변경 카운터·테이블 실체 + 마지막 확인 시각. */
  private static final String POLLER_STATE =
      "{\"lastSnapshot\":{\"101\":3,\"102\":2},"
          + "\"lastChangeSnapshot\":{\"101\":40,\"102\":9},"
          + "\"lastRelationSnapshot\":{\"101\":7,\"102\":8},"
          + "\"lastCheckedAt\":\"2026-10-30T09:00:00\"}";

  /** 수정 시각 유지·갱신 판정용 과거 기준 시각(초 단위). */
  private static final LocalDateTime PAST = LocalDateTime.of(2020, 1, 2, 3, 4, 5);

  /** 계약 대상 리포지토리 — 구현별 하위 클래스가 제공한다. */
  protected abstract TriggerRepository repository();

  private long pipelineId;
  private long triggerId;

  @BeforeEach
  void createWatchingTrigger() {
    pipelineId = fx.createPipeline(tenantA, prefix + "-p", true);
    triggerId = watchTrigger(pipelineId, "{\"datasetIds\":[101,102]}", POLLER_STATE);
  }

  /** 데이터 변경 트리거 하나(켜짐, 상태·수정 시각 지정). */
  private long watchTrigger(long pipeline, String configJson, String stateJson) {
    long id =
        fx.createTrigger(pipeline, prefix + "-w" + System.nanoTime(), "DATASET_CHANGE", configJson);
    fx.setTriggerState(id, stateJson);
    fx.setTriggerUpdatedAt(id, PAST);
    return id;
  }

  /** 테넌트 A 에서 작업을 하고 저장된 trigger_state 를 리포지토리로 읽는다. */
  private Map<String, Object> runThenReadState(Runnable action) {
    runA(action);
    return inA(() -> repository().findById(triggerId).orElseThrow().triggerState());
  }

  private static Map<String, Object> parse(String json) {
    try {
      return JSON.readValue(json, new TypeReference<Map<String, Object>>() {});
    } catch (Exception e) {
      throw new IllegalStateException(json, e);
    }
  }

  private Map<String, Object> config(long id) {
    return parse(fx.triggerStored(id).configJson());
  }

  private Map<String, Object> state(long id) {
    return parse(fx.triggerStored(id).stateJson());
  }

  @Test
  @DisplayName("clearPollSnapshot — 행 수·변경 카운터·실체 스냅숏과 마지막 확인 시각을 지운다")
  void clearPollSnapshot_removesRowAndChangeSnapshots() {
    Map<String, Object> state = runThenReadState(() -> repository().clearPollSnapshot(triggerId));

    assertThat(state)
        .doesNotContainKeys(
            "lastSnapshot", "lastChangeSnapshot", "lastRelationSnapshot", "lastCheckedAt");
  }

  @Test
  @DisplayName("clearPollSnapshot — 다른 상태 키는 남기고 수정 시각·config 는 건드리지 않는다")
  void clearPollSnapshot_keepsOtherKeysAndUpdatedAt() {
    fx.setTriggerState(
        triggerId, "{\"lastSnapshot\":{\"101\":3},\"lastCheckedAt\":\"x\",\"nextFireTime\":\"n\"}");

    runA(() -> repository().clearPollSnapshot(triggerId));

    assertThat(state(triggerId)).isEqualTo(Map.of("nextFireTime", "n"));
    assertThat(fx.triggerStored(triggerId).updatedAt()).isEqualTo(PAST);
    assertThat(config(triggerId)).isEqualTo(Map.of("datasetIds", List.of(101, 102)));
  }

  @Test
  @DisplayName("clearPollSnapshot — 지울 키가 없거나 없는 id·다른 테넌트 트리거면 아무것도 바뀌지 않는다")
  void clearPollSnapshot_noopCases() {
    fx.setTriggerState(triggerId, "{\"nextFireTime\":\"n\"}");
    long pipelineB = fx.createPipeline(tenantB, prefix + "-pb", true);
    long triggerB = watchTrigger(pipelineB, "{\"datasetIds\":[101]}", POLLER_STATE);

    runA(() -> repository().clearPollSnapshot(triggerId));
    runA(() -> repository().clearPollSnapshot(-1L));
    runA(() -> repository().clearPollSnapshot(triggerB));

    assertThat(state(triggerId)).isEqualTo(Map.of("nextFireTime", "n"));
    assertThat(state(triggerB)).isEqualTo(parse(POLLER_STATE));
  }

  @Test
  @DisplayName("removeWatchedDataset — 세 스냅숏에서 그 데이터셋 항목만 지우고 마지막 확인 시각은 남긴다")
  void removeWatchedDataset_removesItsEntryFromBothSnapshots() {
    Map<String, Object> state = runThenReadState(() -> repository().removeWatchedDataset(101L));

    assertThat(state.get("lastSnapshot")).isEqualTo(Map.of("102", 2));
    assertThat(state.get("lastChangeSnapshot")).isEqualTo(Map.of("102", 9));
    assertThat(state.get("lastRelationSnapshot")).isEqualTo(Map.of("102", 8));
    assertThat(state.get("lastCheckedAt")).isEqualTo("2026-10-30T09:00:00");
  }

  @Test
  @DisplayName(
      "removeWatchedDataset — 원소 하나를 빼면 바뀐 트리거 수(여기선 둘)를 돌려주고 켜짐은 그대로, 다른 config 키는 남고 수정 시각은 새로 쓴다")
  void removeWatchedDataset_oneElement() {
    fx.setTriggerUpdatedAt(triggerId, PAST);
    long other = watchTrigger(pipelineId, "{\"datasetIds\":[101,102],\"mode\":\"ROWS\"}", "{}");

    int changed = inA(() -> repository().removeWatchedDataset(102L));

    assertThat(changed).isEqualTo(2);
    TriggerStored stored = fx.triggerStored(triggerId);
    assertThat(config(triggerId)).isEqualTo(Map.of("datasetIds", List.of(101)));
    assertThat(stored.enabled()).isTrue();
    assertThat(stored.updatedAt()).isAfter(PAST);
    assertThat(config(other)).isEqualTo(Map.of("datasetIds", List.of(101), "mode", "ROWS"));
    assertThat(state(other)).isEmpty();
  }

  @Test
  @DisplayName("removeWatchedDataset — 마지막 원소를 빼면 빈 배열로 남기고 트리거를 끈다")
  void removeWatchedDataset_lastElementDisables() {
    long single = watchTrigger(pipelineId, "{\"datasetIds\":[7]}", "{\"lastSnapshot\":{\"7\":1}}");

    int changed = inA(() -> repository().removeWatchedDataset(7L));

    assertThat(changed).isEqualTo(1);
    TriggerStored stored = fx.triggerStored(single);
    assertThat(config(single)).isEqualTo(Map.of("datasetIds", List.of()));
    assertThat(state(single)).isEqualTo(Map.of("lastSnapshot", Map.of()));
    assertThat(stored.enabled()).isFalse();
    // 같은 테넌트의 다른 트리거(7 을 감시하지 않음)는 그대로다.
    assertThat(fx.triggerStored(triggerId).enabled()).isTrue();
    assertThat(fx.triggerStored(triggerId).updatedAt()).isEqualTo(PAST);
  }

  @Test
  @DisplayName("removeWatchedDataset — 없는 원소면 0 을 돌려주고 config·상태·수정 시각이 그대로다")
  void removeWatchedDataset_absentElementIsNoop() {
    int changed = inA(() -> repository().removeWatchedDataset(999L));

    assertThat(changed).isZero();
    TriggerStored stored = fx.triggerStored(triggerId);
    assertThat(config(triggerId)).isEqualTo(Map.of("datasetIds", List.of(101, 102)));
    assertThat(state(triggerId)).isEqualTo(parse(POLLER_STATE));
    assertThat(stored.enabled()).isTrue();
    assertThat(stored.updatedAt()).isEqualTo(PAST);
  }

  @Test
  @DisplayName("removeWatchedDataset — 같은 id 가 두 번 들었으면 둘 다 빼고, 문자열 \"101\" 원소는 id 가 아니라 남긴다")
  void removeWatchedDataset_duplicatesAndStringElement() {
    long dup = watchTrigger(pipelineId, "{\"datasetIds\":[101,\"101\",101,5]}", "{}");

    runA(() -> repository().removeWatchedDataset(101L));

    assertThat(config(dup)).isEqualTo(Map.of("datasetIds", List.of("101", 5)));
    assertThat(fx.triggerStored(dup).enabled()).isTrue();
  }

  @Test
  @DisplayName(
      "removeWatchedDataset — 101.0·1.01e2 처럼 표기가 달라도 숫자로 같은 원소는 뺀다(jOOQ jsonb 숫자 같음) — 마지막 원소면 끈다")
  void removeWatchedDataset_numericallyEqualSpellings() {
    // Tibero 후보 조회가 JSON 텍스트 비교에 기대면 이 두 행을 놓친다(코드 리뷰 C2) — 픽스처가 표기를 그대로 저장한다.
    long decimal =
        watchTrigger(pipelineId, "{\"datasetIds\":[101.0]}", "{\"lastSnapshot\":{\"101\":1}}");
    long exponent = watchTrigger(pipelineId, "{\"datasetIds\":[1.01e2,5]}", "{}");

    int changed = inA(() -> repository().removeWatchedDataset(101L));

    // 기본 트리거([101,102]) + 위 두 행.
    assertThat(changed).isEqualTo(3);
    assertThat(config(decimal)).isEqualTo(Map.of("datasetIds", List.of()));
    assertThat(state(decimal)).isEqualTo(Map.of("lastSnapshot", Map.of()));
    assertThat(fx.triggerStored(decimal).enabled()).isFalse();
    assertThat(fx.triggerStored(decimal).updatedAt()).isNotEqualTo(PAST);
    assertThat(config(exponent)).isEqualTo(Map.of("datasetIds", List.of(5)));
    assertThat(fx.triggerStored(exponent).enabled()).isTrue();
  }

  @Test
  @DisplayName("removeWatchedDataset — 문자열 원소만 있으면 대상이 아니다(숫자 id 와 다르다)")
  void removeWatchedDataset_stringOnlyIsNotMatched() {
    long str = watchTrigger(pipelineId, "{\"datasetIds\":[\"101\"]}", "{}");

    runA(() -> repository().removeWatchedDataset(101L));

    assertThat(config(str)).isEqualTo(Map.of("datasetIds", List.of("101")));
    assertThat(fx.triggerStored(str).updatedAt()).isEqualTo(PAST);
  }

  @Test
  @DisplayName("removeWatchedDataset — datasetIds 가 배열이 아니거나 데이터 변경 유형이 아니면 건드리지 않는다")
  void removeWatchedDataset_nonArrayAndOtherTypeUntouched() {
    long scalar =
        watchTrigger(pipelineId, "{\"datasetIds\":101}", "{\"lastSnapshot\":{\"101\":1}}");
    long chain =
        fx.createTrigger(pipelineId, prefix + "-chain", "PIPELINE_CHAIN", "{\"datasetIds\":[101]}");
    fx.setTriggerUpdatedAt(chain, PAST);

    runA(() -> repository().removeWatchedDataset(101L));

    assertThat(config(scalar)).isEqualTo(Map.of("datasetIds", 101));
    assertThat(state(scalar)).isEqualTo(Map.of("lastSnapshot", Map.of("101", 1)));
    assertThat(fx.triggerStored(scalar).enabled()).isTrue();
    assertThat(config(chain)).isEqualTo(Map.of("datasetIds", List.of(101)));
    assertThat(fx.triggerStored(chain).updatedAt()).isEqualTo(PAST);
  }

  @Test
  @DisplayName("removeWatchedDataset — 꺼진 트리거는 원소가 남아도 꺼진 채이고 마지막 원소를 빼도 꺼진 채다")
  void removeWatchedDataset_disabledStaysDisabled() {
    long off = watchTrigger(pipelineId, "{\"datasetIds\":[101,102]}", "{}");
    fx.setTriggerEnabled(off, false);

    runA(() -> repository().removeWatchedDataset(101L));
    assertThat(config(off)).isEqualTo(Map.of("datasetIds", List.of(102)));
    assertThat(fx.triggerStored(off).enabled()).isFalse();

    runA(() -> repository().removeWatchedDataset(102L));
    assertThat(config(off)).isEqualTo(Map.of("datasetIds", List.of()));
    assertThat(fx.triggerStored(off).enabled()).isFalse();
  }

  @Test
  @DisplayName("removeWatchedDataset — 스냅숏 키가 없는 상태도 그대로 두고 감시 목록만 고친다")
  void removeWatchedDataset_stateWithoutSnapshots() {
    long bare = watchTrigger(pipelineId, "{\"datasetIds\":[101,3]}", "{\"nextFireTime\":\"n\"}");

    runA(() -> repository().removeWatchedDataset(101L));

    assertThat(state(bare)).isEqualTo(Map.of("nextFireTime", "n"));
    assertThat(config(bare)).isEqualTo(Map.of("datasetIds", List.of(3)));
  }

  @Test
  @DisplayName("removeWatchedDataset — 다른 테넌트의 같은 데이터셋 감시 트리거는 바뀌지 않는다")
  void removeWatchedDataset_otherTenantUntouched() {
    long pipelineB = fx.createPipeline(tenantB, prefix + "-pb", true);
    long triggerB = watchTrigger(pipelineB, "{\"datasetIds\":[101]}", POLLER_STATE);

    int changed = inA(() -> repository().removeWatchedDataset(101L));

    assertThat(changed).isEqualTo(1);
    TriggerStored stored = fx.triggerStored(triggerB);
    assertThat(config(triggerB)).isEqualTo(Map.of("datasetIds", List.of(101)));
    assertThat(state(triggerB)).isEqualTo(parse(POLLER_STATE));
    assertThat(stored.enabled()).isTrue();
    assertThat(stored.updatedAt()).isEqualTo(PAST);
  }
}
