package com.smartfirehub.global.platformdb.mybatis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** PlatformSharing.flip(공유 켜기 공용 흐름, WD-710 C3) 단위 테스트 — 문장은 가짜 함수로 대신한다. */
class PlatformSharingTest {

  /** 테스트용 잠금 대상 행. */
  private record Row(Long id, String name) implements PlatformSharing.Target {}

  @Test
  @DisplayName("빈 id 목록이면 문장을 하나도 돌지 않고 빈 맵")
  void emptyIds_runsNothing() {
    AtomicBoolean called = new AtomicBoolean();
    Map<Long, String> out =
        PlatformSharing.flip(
            List.of(),
            chunks -> {
              called.set(true);
              return List.of();
            },
            chunks -> {
              called.set(true);
              return 0;
            },
            "차트");
    assertThat(out).isEmpty();
    assertThat(called).isFalse();
  }

  @Test
  @DisplayName("잠근 대상이 없으면 UPDATE 를 돌지 않고 빈 맵")
  void noTargets_skipsUpdate() {
    AtomicBoolean marked = new AtomicBoolean();
    Map<Long, String> out =
        PlatformSharing.flip(
            List.of(1L, 2L),
            chunks -> List.of(),
            chunks -> {
              marked.set(true);
              return 0;
            },
            "차트");
    assertThat(out).isEmpty();
    assertThat(marked).isFalse();
  }

  @Test
  @DisplayName("잠근 대상만 UPDATE 에 넘기고 id→이름을 id 오름차순으로 돌려준다")
  void returnsLockedTargetsAscending() {
    List<List<Long>> markedChunks = new ArrayList<>();
    Map<Long, String> out =
        PlatformSharing.flip(
            List.of(9L, 3L, 5L, 3L),
            chunks -> List.of(new Row(9L, "z"), new Row(3L, "c")),
            chunks -> {
              markedChunks.addAll(chunks);
              return 2;
            },
            "저장 쿼리");
    assertThat(out).containsExactly(Map.entry(3L, "c"), Map.entry(9L, "z"));
    assertThat(markedChunks).containsExactly(List.of(3L, 9L));
  }

  @Test
  @DisplayName("잠근 수와 바꾼 수가 다르면 라벨과 두 수를 담은 IllegalStateException")
  void countMismatch_throws() {
    assertThatThrownBy(
            () ->
                PlatformSharing.flip(
                    List.of(1L, 2L),
                    chunks -> List.of(new Row(1L, "a"), new Row(2L, "b")),
                    chunks -> 1,
                    "차트"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageStartingWith("차트 공유 갱신 행 수가 잠근 대상 수와 다르다")
        .hasMessageContaining("locked=2")
        .hasMessageContaining("updated=1");
  }
}
