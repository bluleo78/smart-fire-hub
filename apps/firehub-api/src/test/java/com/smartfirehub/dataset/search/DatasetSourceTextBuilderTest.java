package com.smartfirehub.dataset.search;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import org.junit.jupiter.api.Test;

class DatasetSourceTextBuilderTest {

  @Test
  void build_합본에_모든_메타_필드가_포함된다() {
    var input = new DatasetSourceTextBuilder.Input(
        "화재 출동 기록",
        "2024년 소방 출동 통계",
        "fire_dispatch_2024",
        List.of("출동일시", "관할서"),
        List.of("화재", "소방"),
        "안전");

    String text = DatasetSourceTextBuilder.build(input);

    assertThat(text)
        .contains("화재 출동 기록")
        .contains("2024년 소방 출동 통계")
        .contains("fire_dispatch_2024")
        .contains("출동일시").contains("관할서")
        .contains("화재").contains("소방")
        .contains("안전");
  }

  @Test
  void build_null_필드는_건너뛰고_NPE를_내지_않는다() {
    var input = new DatasetSourceTextBuilder.Input(
        "이름만", null, null, List.of(), null, null);
    String text = DatasetSourceTextBuilder.build(input);
    assertThat(text).isEqualTo("이름만");
  }
}
