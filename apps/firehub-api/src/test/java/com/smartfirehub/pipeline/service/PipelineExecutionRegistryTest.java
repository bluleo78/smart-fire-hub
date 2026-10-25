package com.smartfirehub.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** 실행 등록부(WD-230) 단위 테스트 — 등록·해제·스냅샷 복사본. */
class PipelineExecutionRegistryTest {

  private final PipelineExecutionRegistry registry = new PipelineExecutionRegistry();

  @Test
  void registerAndUnregister() {
    registry.register(1L, 10L);
    registry.register(2L, 20L);
    assertThat(registry.snapshot()).containsExactlyInAnyOrderEntriesOf(Map.of(1L, 10L, 2L, 20L));

    registry.unregister(1L);
    assertThat(registry.snapshot()).containsExactlyEntriesOf(Map.of(2L, 20L));
  }

  @Test
  void unregisterUnknownExecution_isNoop() {
    registry.unregister(99L);
    assertThat(registry.snapshot()).isEmpty();
  }

  @Test
  void snapshotIsACopy() {
    registry.register(1L, 10L);
    Map<Long, Long> snap = registry.snapshot();

    registry.register(2L, 20L);
    registry.unregister(1L);

    // 이미 뜬 스냅샷은 이후 변경에 영향받지 않고, 스냅샷을 고쳐도 등록부는 그대로다.
    assertThat(snap).containsExactlyEntriesOf(Map.of(1L, 10L));
    snap.clear();
    assertThat(registry.snapshot()).containsExactlyEntriesOf(Map.of(2L, 20L));
  }
}
