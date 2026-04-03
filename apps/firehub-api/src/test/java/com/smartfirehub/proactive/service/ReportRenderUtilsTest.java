package com.smartfirehub.proactive.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.smartfirehub.proactive.dto.ProactiveResult;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.ArrayList;
import java.util.HashMap;
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

  // --- substituteVariables tests ---

  @Test
  void substituteVariables_replacesAllPlaceholders() {
    var vars = Map.of("date", "2026-04-03", "jobName", "일간 요약");
    String result = reportRenderUtils.substituteVariables("리포트: {{jobName}} ({{date}})", vars);
    assertEquals("리포트: 일간 요약 (2026-04-03)", result);
  }

  @Test
  void substituteVariables_handlesNullContent() {
    String result = reportRenderUtils.substituteVariables(null, Map.of());
    assertEquals("", result);
  }

  @Test
  void substituteVariables_handlesEmptyContent() {
    String result = reportRenderUtils.substituteVariables("  ", Map.of());
    assertEquals("", result);
  }

  @Test
  void substituteVariables_noMatchingVars() {
    String result =
        reportRenderUtils.substituteVariables("No variables here", Map.of("date", "2026-04-03"));
    assertEquals("No variables here", result);
  }

  // --- buildVariables tests ---

  @Test
  void buildVariables_containsAllKeys() {
    Map<String, String> vars = reportRenderUtils.buildVariables("Job1", "Author1", "Template1");
    assertThat(vars).containsKeys("date", "jobName", "author", "templateName");
    assertThat(vars.get("jobName")).isEqualTo("Job1");
    assertThat(vars.get("author")).isEqualTo("Author1");
    assertThat(vars.get("templateName")).isEqualTo("Template1");
    assertThat(vars.get("date")).isNotBlank();
  }

  @Test
  void buildVariables_handlesNullValues() {
    Map<String, String> vars = reportRenderUtils.buildVariables(null, null, null);
    assertThat(vars.get("jobName")).isEmpty();
    assertThat(vars.get("author")).isEmpty();
    assertThat(vars.get("templateName")).isEmpty();
  }

  // --- buildTemplateSections with template structure tests ---

  @Test
  void buildTemplateSections_withStructure_handlesDivider() {
    List<Map<String, Object>> structure = new ArrayList<>();
    Map<String, Object> divider = new HashMap<>();
    divider.put("type", "divider");
    structure.add(divider);

    List<Map<String, Object>> result =
        reportRenderUtils.buildTemplateSections(List.of(), structure, Map.of());

    assertThat(result).hasSize(1);
    assertThat(result.get(0).get("type")).isEqualTo("divider");
    assertThat(result.get(0).get("depth")).isEqualTo(1);
  }

  @Test
  void buildTemplateSections_withStructure_handlesStaticSection() {
    List<Map<String, Object>> structure = new ArrayList<>();
    Map<String, Object> staticSection = new HashMap<>();
    staticSection.put("label", "Header");
    staticSection.put("static", true);
    staticSection.put("content", "Report for {{jobName}}");
    structure.add(staticSection);

    Map<String, String> vars = Map.of("jobName", "Daily Summary");
    List<Map<String, Object>> result =
        reportRenderUtils.buildTemplateSections(List.of(), structure, vars);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).get("label")).isEqualTo("Header");
    assertThat(result.get(0).get("static")).isEqualTo(true);
    assertThat((String) result.get(0).get("content")).contains("Report for Daily Summary");
    assertThat(result.get(0).get("depth")).isEqualTo(1);
  }

  @Test
  void buildTemplateSections_withStructure_handlesGroup() {
    List<Map<String, Object>> structure = new ArrayList<>();
    Map<String, Object> group = new HashMap<>();
    group.put("type", "group");
    group.put("label", "Analysis Group");

    List<Map<String, Object>> children = new ArrayList<>();
    Map<String, Object> child = new HashMap<>();
    child.put("key", "child1");
    child.put("label", "Child Section");
    children.add(child);
    group.put("children", children);

    structure.add(group);

    ProactiveResult.Section aiSection =
        new ProactiveResult.Section("child1", "Child Section", "**content**", "text", null);

    List<Map<String, Object>> result =
        reportRenderUtils.buildTemplateSections(List.of(aiSection), structure, Map.of());

    assertThat(result).hasSize(2);
    // group
    assertThat(result.get(0).get("type")).isEqualTo("group");
    assertThat(result.get(0).get("label")).isEqualTo("Analysis Group");
    assertThat(result.get(0).get("depth")).isEqualTo(1);
    // child at depth 2
    assertThat(result.get(1).get("label")).isEqualTo("Child Section");
    assertThat(result.get(1).get("depth")).isEqualTo(2);
    assertThat((String) result.get(1).get("content")).contains("<strong>content</strong>");
  }

  @Test
  void buildTemplateSections_withStructure_aiSectionMatchesByKey() {
    List<Map<String, Object>> structure = new ArrayList<>();
    Map<String, Object> section = new HashMap<>();
    section.put("key", "summary");
    section.put("label", "Summary");
    structure.add(section);

    ProactiveResult.Section aiSection =
        new ProactiveResult.Section("summary", "Summary", "AI generated text", "text", null);

    List<Map<String, Object>> result =
        reportRenderUtils.buildTemplateSections(List.of(aiSection), structure, Map.of());

    assertThat(result).hasSize(1);
    assertThat((String) result.get(0).get("content")).contains("AI generated text");
  }

  @Test
  void buildTemplateSections_withStructure_noMatchingAiSection() {
    List<Map<String, Object>> structure = new ArrayList<>();
    Map<String, Object> section = new HashMap<>();
    section.put("key", "missing");
    section.put("label", "Missing");
    structure.add(section);

    List<Map<String, Object>> result =
        reportRenderUtils.buildTemplateSections(List.of(), structure, Map.of());

    assertThat(result).hasSize(1);
    assertThat(result.get(0).get("content")).isEqualTo("");
  }
}
