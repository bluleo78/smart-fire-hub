package com.smartfirehub.proactive.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.proactive.dto.ProactiveResult;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

@Service
@Slf4j
public class ReportRenderUtils {

  private static final Duration CHART_TIMEOUT = Duration.ofSeconds(30);
  private static final List<String> CHART_COLORS =
      List.of("#228be6", "#40c057", "#fab005", "#fa5252", "#868e96");

  private final WebClient agentWebClient;
  private final ObjectMapper objectMapper;
  private final Parser markdownParser;
  private final HtmlRenderer htmlRenderer;
  private final String internalToken;

  public ReportRenderUtils(
      @Value("${agent.url}") String agentUrl,
      @Value("${agent.internal-token}") String internalToken,
      ObjectMapper objectMapper) {
    this.internalToken = internalToken;
    this.objectMapper = objectMapper;
    var extensions = List.of(TablesExtension.create());
    this.markdownParser = Parser.builder().extensions(extensions).build();
    this.htmlRenderer = HtmlRenderer.builder().extensions(extensions).build();
    HttpClient httpClient = HttpClient.create().responseTimeout(CHART_TIMEOUT);
    this.agentWebClient =
        WebClient.builder()
            .baseUrl(agentUrl)
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .codecs(c -> c.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
            .build();
  }

  public String markdownToHtml(String markdown) {
    if (markdown == null || markdown.isBlank()) return "";
    Node document = markdownParser.parse(markdown);
    return htmlRenderer.render(document);
  }

  public String substituteVariables(String content, Map<String, String> variables) {
    if (content == null || content.isBlank()) return "";
    String result = content;
    for (Map.Entry<String, String> entry : variables.entrySet()) {
      result = result.replace("{{" + entry.getKey() + "}}", entry.getValue());
    }
    return result;
  }

  public Map<String, String> buildVariables(String jobName, String author, String templateName) {
    Map<String, String> vars = new HashMap<>();
    vars.put("date", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
    vars.put("jobName", jobName != null ? jobName : "");
    vars.put("author", author != null ? author : "");
    vars.put("templateName", templateName != null ? templateName : "");
    return vars;
  }

  public List<Map<String, Object>> buildTemplateSections(List<ProactiveResult.Section> sections) {
    List<Map<String, Object>> templateSections = new ArrayList<>();
    for (ProactiveResult.Section section : sections) {
      Map<String, Object> map = new HashMap<>();
      map.put("label", section.label() != null ? section.label() : "");
      map.put("content", section.content() != null ? markdownToHtml(section.content()) : "");

      if (section.data() instanceof Map<?, ?> dataMap) {
        Object cards = dataMap.get("cards");
        if (cards instanceof List<?> cardList) {
          map.put("cards", cardList);
        }
      }

      templateSections.add(map);
    }
    return templateSections;
  }

  public List<Map<String, Object>> buildTemplateSections(
      List<ProactiveResult.Section> aiSections,
      List<Map<String, Object>> templateStructure,
      Map<String, String> variables) {
    List<Map<String, Object>> result = new ArrayList<>();
    processTemplateSections(templateStructure, aiSections, variables, result, 1);
    return result;
  }

  @SuppressWarnings("unchecked")
  private void processTemplateSections(
      List<Map<String, Object>> templateSections,
      List<ProactiveResult.Section> aiSections,
      Map<String, String> variables,
      List<Map<String, Object>> result,
      int depth) {
    if (templateSections == null) return;
    for (Map<String, Object> tmplSection : templateSections) {
      String type = (String) tmplSection.get("type");
      String label = (String) tmplSection.get("label");
      String key = (String) tmplSection.get("key");
      Boolean isStatic = (Boolean) tmplSection.get("static");

      if ("divider".equals(type)) {
        Map<String, Object> map = new HashMap<>();
        map.put("type", "divider");
        map.put("label", "");
        map.put("content", "");
        map.put("depth", depth);
        result.add(map);
        continue;
      }

      if (Boolean.TRUE.equals(isStatic)) {
        String content = (String) tmplSection.get("content");
        String rendered = substituteVariables(content, variables);
        Map<String, Object> map = new HashMap<>();
        map.put("label", label);
        map.put("content", markdownToHtml(rendered));
        map.put("static", true);
        map.put("depth", depth);
        result.add(map);
        continue;
      }

      if ("group".equals(type)) {
        Map<String, Object> map = new HashMap<>();
        map.put("label", label);
        map.put("type", "group");
        map.put("content", "");
        map.put("depth", depth);
        result.add(map);

        List<Map<String, Object>> children =
            (List<Map<String, Object>>) tmplSection.get("children");
        if (children != null) {
          processTemplateSections(children, aiSections, variables, result, depth + 1);
        }
        continue;
      }

      // AI-generated section: match by key
      ProactiveResult.Section aiSection =
          aiSections.stream()
              .filter(s -> key != null && key.equals(s.key()))
              .findFirst()
              .orElse(null);

      Map<String, Object> map = new HashMap<>();
      map.put("label", label != null ? label : "");
      map.put("depth", depth);

      if (aiSection != null) {
        map.put("content", aiSection.content() != null ? markdownToHtml(aiSection.content()) : "");
        if (aiSection.data() instanceof Map<?, ?> dataMap) {
          Object cards = dataMap.get("cards");
          if (cards instanceof List<?> cardList) {
            map.put("cards", cardList);
          }
        }
      } else {
        map.put("content", "");
      }

      result.add(map);
    }
  }

  public List<ChartImage> renderChartImages(List<Map<String, Object>> templateSections) {
    List<ChartImage> chartImages = new ArrayList<>();
    List<Map<String, Object>> chartRequests = new ArrayList<>();

    for (Map<String, Object> section : templateSections) {
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> cards = (List<Map<String, Object>>) section.get("cards");
      if (cards == null || cards.isEmpty()) {
        continue;
      }

      List<Object> labels = cards.stream().map(c -> c.get("label")).toList();
      List<Object> values = cards.stream().map(c -> c.get("value")).toList();

      Map<String, Object> chartRequest =
          Map.of(
              "type",
              "bar",
              "title",
              String.valueOf(section.get("label")),
              "data",
              Map.of(
                  "labels",
                  labels,
                  "datasets",
                  List.of(
                      Map.of(
                          "label", String.valueOf(section.get("label")),
                          "data", values,
                          "backgroundColor", CHART_COLORS))),
              "width",
              500,
              "height",
              300);

      chartRequests.add(chartRequest);
    }

    if (chartRequests.isEmpty()) {
      return chartImages;
    }

    try {
      Map<String, Object> requestBody = Map.of("charts", chartRequests);
      String responseBody =
          agentWebClient
              .post()
              .uri("/agent/chart-render")
              .contentType(MediaType.APPLICATION_JSON)
              .header("Authorization", "Internal " + internalToken)
              .bodyValue(requestBody)
              .retrieve()
              .bodyToMono(String.class)
              .timeout(CHART_TIMEOUT)
              .block();

      if (responseBody == null) {
        log.warn("ReportRenderUtils: chart-render returned null response");
        return chartImages;
      }

      Map<String, Object> responseMap =
          objectMapper.readValue(responseBody, new TypeReference<>() {});
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> images = (List<Map<String, Object>>) responseMap.get("images");
      if (images == null) {
        return chartImages;
      }

      int chartIndex = 0;
      for (Map<String, Object> section : templateSections) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cards = (List<Map<String, Object>>) section.get("cards");
        if (cards == null || cards.isEmpty()) {
          continue;
        }
        if (chartIndex < images.size()) {
          Map<String, Object> image = images.get(chartIndex);
          String cid = "chart-" + chartIndex;
          String base64 = (String) image.get("base64");
          if (base64 != null && !base64.isBlank()) {
            chartImages.add(new ChartImage(cid, base64));
            section.put("chartCid", cid);
          }
          chartIndex++;
        }
      }
    } catch (Exception e) {
      log.warn(
          "ReportRenderUtils: chart rendering failed, proceeding without charts: {}",
          e.getMessage());
    }

    return chartImages;
  }

  public void convertChartCidsToDataUris(
      List<Map<String, Object>> templateSections, List<ChartImage> chartImages) {
    Map<String, String> cidToDataUri = new HashMap<>();
    for (ChartImage chart : chartImages) {
      String dataUri = "data:image/png;base64," + chart.base64();
      cidToDataUri.put(chart.cid(), dataUri);
    }
    for (Map<String, Object> section : templateSections) {
      Object cid = section.get("chartCid");
      if (cid instanceof String cidStr) {
        String dataUri = cidToDataUri.get(cidStr);
        if (dataUri != null) {
          section.put("chartDataUri", dataUri);
        }
      }
    }
  }

  public record ChartImage(String cid, String base64) {}
}
