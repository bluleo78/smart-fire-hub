package com.smartfirehub.global.platformdb.mybatis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

/** IN 조각 헬퍼 단위 시험 — 빈 입력·경계(500/501)·중복 제거 순서. */
class InChunksTest {

  private static List<Long> range(int n) {
    return new ArrayList<>(LongStream.range(0, n).boxed().toList());
  }

  @Test
  void empty_returnsNoChunks() {
    assertThat(InChunks.of(List.<Long>of())).isEmpty();
  }

  @Test
  void exactly500_isOneChunk() {
    List<List<Long>> chunks = InChunks.of(range(500));
    assertThat(chunks).hasSize(1);
    assertThat(chunks.get(0)).hasSize(500);
  }

  @Test
  void size501_splitsIntoTwo() {
    List<List<Long>> chunks = InChunks.of(range(501));
    assertThat(chunks).hasSize(2);
    assertThat(chunks.get(0)).hasSize(500);
    assertThat(chunks.get(1)).containsExactly(500L);
  }

  @Test
  void duplicates_areRemovedKeepingFirstSeenOrder() {
    assertThat(InChunks.of(List.of(3L, 1L, 3L, 2L, 1L))).containsExactly(List.of(3L, 1L, 2L));
  }

  @Test
  void duplicatesCountedAfterDedupe_notBefore() {
    List<Long> in = range(500);
    in.addAll(range(500));
    assertThat(InChunks.of(in)).hasSize(1);
  }
}
