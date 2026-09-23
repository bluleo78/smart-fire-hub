package com.smartfirehub.global.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** RankFusion: 순위 리스트 여러 개를 RRF(k=60)로 합친다. 점수·동점 정렬·출처 추적을 검증한다. */
class RankFusionTest {

  record Hit(long id) {}

  @Test
  void fuse_rewardsItemsInBothRankings_andTracksSources() {
    List<Hit> semantic = List.of(new Hit(5), new Hit(12), new Hit(3));
    List<Hit> keyword = List.of(new Hit(12), new Hit(7), new Hit(5));

    var fused = RankFusion.fuse(List.of(semantic, keyword), Hit::id, 10);

    // 12: 1/62 + 1/61, 5: 1/61 + 1/63 → 12 가 최상위
    assertThat(fused).extracting(f -> f.hit().id()).startsWith(12L, 5L);
    assertThat(fused.get(0).score()).isEqualTo(1.0 / 62 + 1.0 / 61);
    assertThat(fused.get(0).sources()).isEqualTo(Set.of(0, 1));
    var three = fused.stream().filter(f -> f.hit().id() == 3).findFirst().orElseThrow();
    assertThat(three.sources()).isEqualTo(Set.of(0));
  }

  @Test
  void fuse_breaksTiesByKeyAscending_andRespectsLimit() {
    List<Hit> a = List.of(new Hit(9));
    List<Hit> b = List.of(new Hit(4));

    var fused = RankFusion.fuse(List.of(a, b), Hit::id, 1);

    assertThat(fused).hasSize(1);
    assertThat(fused.get(0).hit().id()).isEqualTo(4L); // 동점 → 키 오름차순
  }
}
