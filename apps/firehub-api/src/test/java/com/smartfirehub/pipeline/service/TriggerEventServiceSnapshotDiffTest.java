package com.smartfirehub.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 데이터 변경 트리거 폴러의 스냅숏 비교 규칙 단위 테스트(WD-221). Spring 컨텍스트 없이 순수 함수 {@link
 * TriggerEventService#diffSnapshots} 만 본다 — 행 수 통계(n_live_tup)는 비동기로 갱신돼 통합 테스트로는 「실제 변경」을 안정적으로 만들
 * 수 없어서다.
 */
class TriggerEventServiceSnapshotDiffTest {

  /** 생성 직후 첫 폴링: 직전 기준선이 없으면 빈 데이터셋(0행)이어도 발화하지 않고 기준선만 잡는다. 예전엔 직전값 -1 과 비교해 바로 발화했다. */
  @Test
  void firstPoll_withoutBaseline_onlyRecordsBaseline() {
    var diff =
        TriggerEventService.diffSnapshots(
            List.of(1L, 2L), Set.of(1L, 2L), Map.of(1L, 0L, 2L, 5L), Map.of());

    assertThat(diff.changedIds()).isEmpty();
    assertThat(diff.nextSnapshot()).containsExactlyInAnyOrderEntriesOf(Map.of(1L, 0L, 2L, 5L));
    assertThat(diff.missingIds()).isEmpty();
  }

  /** 감시 대상 추가: 새로 들어온 데이터셋은 기준선만 잡고, 기존 대상의 실제 변경만 발화 대상이 된다. */
  @Test
  void addedTarget_isBaselinedNotFired_whileExistingChangeStillFires() {
    var unchanged =
        TriggerEventService.diffSnapshots(
            List.of(1L, 3L), Set.of(1L, 3L), Map.of(1L, 10L, 3L, 7L), Map.of(1L, 10L));
    assertThat(unchanged.changedIds()).isEmpty();
    assertThat(unchanged.nextSnapshot()).containsEntry(3L, 7L);

    var changed =
        TriggerEventService.diffSnapshots(
            List.of(1L, 3L), Set.of(1L, 3L), Map.of(1L, 11L, 3L, 7L), Map.of(1L, 10L));
    assertThat(changed.changedIds()).containsExactly(1L);
  }

  /** 실제 변경: 직전 기준선과 행 수가 다르면 발화한다(대조군 — 위 「발화 안 함」 단언이 공허하지 않음을 보인다). */
  @Test
  void realChange_fires() {
    var diff =
        TriggerEventService.diffSnapshots(
            List.of(1L, 2L), Set.of(1L, 2L), Map.of(1L, 3L, 2L, 5L), Map.of(1L, 2L, 2L, 5L));

    assertThat(diff.changedIds()).containsExactly(1L);
    assertThat(diff.nextSnapshot()).containsEntry(1L, 3L);
  }

  /**
   * 감시 대상 하나 삭제: 삭제된 데이터셋은 변경으로 보지 않고 기준선에서도 지워, 몇 번을 폴링해도 다시 발화하지 않는다. 예전엔 행 수 0 ≠ 직전값으로 매 주기
   * 발화했다.
   */
  @Test
  void deletedTarget_neverFires_acrossRepeatedPolls() {
    Map<Long, Long> last = Map.of(1L, 4L, 2L, 9L);
    for (int poll = 0; poll < 3; poll++) {
      var diff =
          TriggerEventService.diffSnapshots(List.of(1L, 2L), Set.of(1L), Map.of(1L, 4L), last);
      assertThat(diff.changedIds()).isEmpty();
      assertThat(diff.missingIds()).containsExactly(2L);
      assertThat(diff.nextSnapshot()).containsOnlyKeys(1L);
      last = diff.nextSnapshot();
    }
  }

  /** 전부 삭제: 발화 없음, 기준선 비움, 모두 정리 대상(폴러가 감시 목록에서 빼고 트리거를 끈다). */
  @Test
  void allTargetsDeleted_noFire_allMissing() {
    var diff =
        TriggerEventService.diffSnapshots(
            List.of(1L, 2L), Set.of(), Map.of(), Map.of(1L, 4L, 2L, 9L));

    assertThat(diff.changedIds()).isEmpty();
    assertThat(diff.nextSnapshot()).isEmpty();
    assertThat(diff.missingIds()).containsExactly(1L, 2L);
  }

  /** 행 수 조회 실패는 삭제가 아니다: 정리 대상이 아니고, 직전 기준선을 그대로 둬 다음 폴링의 거짓 변경을 막는다. */
  @Test
  void estimateFailure_isNotDeletion_andKeepsBaseline() {
    var diff = TriggerEventService.diffSnapshots(List.of(1L), Set.of(1L), Map.of(), Map.of(1L, 4L));

    assertThat(diff.changedIds()).isEmpty();
    assertThat(diff.missingIds()).isEmpty();
    assertThat(diff.nextSnapshot()).containsEntry(1L, 4L);
  }
}
