package com.smartfirehub.proactive.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.proactive.dto.ProactiveResult;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ReportRenderUtilsTest extends IntegrationTestBase {

  @Autowired private ReportRenderUtils reportRenderUtils;

  @Test
  void markdownToHtml_converts_basic_markdown() {
    String html = reportRenderUtils.markdownToHtml("**bold**");
    assertThat(html).contains("<strong>bold</strong>");
  }

  @Test
  void markdownToHtml_converts_gfm_table() {
    String table = "| A | B |\n|---|---|\n| 1 | 2 |";
    String html = reportRenderUtils.markdownToHtml(table);
    assertThat(html).contains("<table>");
  }

  @Test
  void markdownToHtml_returns_empty_for_null() {
    assertThat(reportRenderUtils.markdownToHtml(null)).isEmpty();
    assertThat(reportRenderUtils.markdownToHtml("")).isEmpty();
    assertThat(reportRenderUtils.markdownToHtml("   ")).isEmpty();
  }

  @Test
  void buildTemplateSections_extracts_content_and_cards() {
    List<Map<String, Object>> cards = List.of(Map.of("label", "Total", "value", 42));
    ProactiveResult.Section section =
        new ProactiveResult.Section(
            "s1", "My Section", "**hello**", "cards", Map.of("cards", cards));

    List<Map<String, Object>> result = reportRenderUtils.buildTemplateSections(List.of(section));

    assertThat(result).hasSize(1);
    Map<String, Object> s = result.get(0);
    assertThat(s.get("label")).isEqualTo("My Section");
    assertThat((String) s.get("content")).contains("<strong>hello</strong>");
    assertThat(s.get("cards")).isEqualTo(cards);
  }

  @Test
  void buildTemplateSections_handles_null_content() {
    ProactiveResult.Section section =
        new ProactiveResult.Section("s1", "Label", null, "text", null);

    List<Map<String, Object>> result = reportRenderUtils.buildTemplateSections(List.of(section));

    assertThat(result).hasSize(1);
    assertThat(result.get(0).get("content")).isEqualTo("");
  }
}
