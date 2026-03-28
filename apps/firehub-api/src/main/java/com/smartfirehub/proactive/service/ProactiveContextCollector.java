package com.smartfirehub.proactive.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.dashboard.service.DashboardService;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ProactiveContextCollector {

  private static final Logger log = LoggerFactory.getLogger(ProactiveContextCollector.class);
  private static final int MAX_ATTENTION_ITEMS = 50;
  private static final int MAX_CONTEXT_LENGTH = 50_000;

  private final DashboardService dashboardService;
  private final ObjectMapper objectMapper;

  public ProactiveContextCollector(DashboardService dashboardService, ObjectMapper objectMapper) {
    this.dashboardService = dashboardService;
    this.objectMapper = objectMapper;
  }

  public String collectContext(Map<String, Object> config) {
    try {
      Map<String, Object> context = new HashMap<>();

      // 4개 독립 호출을 병렬 실행
      var statsFuture = CompletableFuture.supplyAsync(() -> dashboardService.getStats());
      var healthFuture = CompletableFuture.supplyAsync(() -> dashboardService.getSystemHealth());
      var attentionFuture =
          CompletableFuture.supplyAsync(() -> dashboardService.getAttentionItems());
      var activityFuture =
          CompletableFuture.supplyAsync(() -> dashboardService.getActivityFeed(null, null, 0, 20));
      CompletableFuture.allOf(statsFuture, healthFuture, attentionFuture, activityFuture).join();

      // 1. Dashboard stats
      try {
        context.put("stats", statsFuture.get());
      } catch (Exception e) {
        log.warn("Failed to collect stats", e);
        context.put("stats", Map.of("error", e.getMessage()));
      }

      // 2. System health
      try {
        context.put("systemHealth", healthFuture.get());
      } catch (Exception e) {
        log.warn("Failed to collect systemHealth", e);
        context.put("systemHealth", Map.of("error", e.getMessage()));
      }

      // 3. Attention items (최대 50건, severity 순)
      try {
        var attentionItems = attentionFuture.get();
        List<?> filtered =
            attentionItems.stream()
                .sorted(
                    Comparator.comparingInt(
                        item -> {
                          String severity =
                              item instanceof com.smartfirehub.dashboard.dto.AttentionItemResponse a
                                  ? a.severity()
                                  : "INFO";
                          return switch (severity) {
                            case "CRITICAL" -> 0;
                            case "WARNING" -> 1;
                            default -> 2;
                          };
                        }))
                .limit(MAX_ATTENTION_ITEMS)
                .toList();
        context.put("attentionItems", filtered);
      } catch (Exception e) {
        log.warn("Failed to collect attentionItems", e);
        context.put("attentionItems", List.of());
      }

      // 4. Activity feed (최근 20건)
      try {
        context.put("activityFeed", activityFuture.get());
      } catch (Exception e) {
        log.warn("Failed to collect activityFeed", e);
        context.put("activityFeed", Map.of("error", e.getMessage()));
      }

      // config.targets 기반 필터링 (scope: ALL/SELECTED)
      applyTargetFilter(context, config);

      // JSON 직렬화 후 크기 제한
      String json = objectMapper.writeValueAsString(context);
      if (json.length() > MAX_CONTEXT_LENGTH) {
        json = json.substring(0, MAX_CONTEXT_LENGTH) + "...[truncated]";
      }
      return json;

    } catch (Exception e) {
      log.error("Failed to collect proactive context", e);
      return "{}";
    }
  }

  private void applyTargetFilter(Map<String, Object> context, Map<String, Object> config) {
    if (config == null) return;
    Object scopeObj = config.get("scope");
    if (!"SELECTED".equals(scopeObj)) return;

    Object targetsObj = config.get("targets");
    if (!(targetsObj instanceof List<?> targets)) return;

    // scope=SELECTED이면 targets에 명시된 키만 남김
    context.keySet().removeIf(key -> !targets.contains(key));
  }
}
