package com.smartfirehub.dataset.rowsearch;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 검색 텍스트 조립·해시 규칙. 표시명 변경도 config_hash 를 바꿔 재색인을 유발해야 한다. */
class SearchConfigTest {

  private final SearchConfig cfg =
      new SearchConfig(
          List.of(new SearchConfig.Field("title", "제목"), new SearchConfig.Field("content", null)));

  @Test
  void buildSourceText_labelsWithDisplayName_skipsBlank() {
    Map<String, Object> v = new HashMap<>();
    v.put("title", "배관 누수");
    v.put("content", "  ");
    assertThat(cfg.buildSourceText(v)).isEqualTo("제목: 배관 누수");
    v.put("content", "천장 물");
    assertThat(cfg.buildSourceText(v)).isEqualTo("제목: 배관 누수\ncontent: 천장 물");
  }

  @Test
  void buildSourceText_truncatesTo8000() {
    assertThat(cfg.buildSourceText(Map.of("title", "가".repeat(9000)))).hasSize(8000);
  }

  @Test
  void configHash_changesWithDisplayNameAndOrder() {
    String base = cfg.configHash();
    var renamed =
        new SearchConfig(List.of(new SearchConfig.Field("title", "제목2"), new SearchConfig.Field("content", null)));
    var reordered =
        new SearchConfig(List.of(new SearchConfig.Field("content", null), new SearchConfig.Field("title", "제목")));
    assertThat(renamed.configHash()).isNotEqualTo(base);
    assertThat(reordered.configHash()).isNotEqualTo(base);
    assertThat(base).hasSize(64);
  }
}
