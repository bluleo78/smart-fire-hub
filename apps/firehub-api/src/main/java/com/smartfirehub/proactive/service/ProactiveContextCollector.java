package com.smartfirehub.proactive.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.dashboard.service.DashboardService;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.proactive.dto.AnomalyEvent;
import com.smartfirehub.proactive.dto.ProactiveJobExecutionResponse;
import com.smartfirehub.proactive.repository.ProactiveJobExecutionRepository;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class ProactiveContextCollector {

  private static final int MAX_ATTENTION_ITEMS = 50;
  private static final int MAX_CONTEXT_LENGTH = 50_000;

  private final DashboardService dashboardService;
  private final ObjectMapper objectMapper;
  private final ProactiveJobExecutionRepository executionRepository;

  public String collectContext(Map<String, Object> config, Long jobId) {
    try {
      Map<String, Object> context = new HashMap<>();

      // 4개 독립 호출을 병렬 실행. 테넌트는 호출 스레드에서 붙잡아 각 작업 안에서 다시 세운다
      // (이유는 scopedAsync 의 Javadoc 참고). require 는 반드시 호출 스레드에서 — 풀 스레드에서
      // 부르면 컨텍스트가 비어 있어 그 자리에서 던진다.
      long tenantId = TenantContext.require("proactive 컨텍스트 수집");
      var statsFuture = scopedAsync(tenantId, dashboardService::getStats);
      var healthFuture = scopedAsync(tenantId, dashboardService::getSystemHealth);
      var attentionFuture = scopedAsync(tenantId, dashboardService::getAttentionItems);
      var activityFuture =
          scopedAsync(tenantId, () -> dashboardService.getActivityFeed(null, null, 0, 20));
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

      // 5. Previous executions (last 3 COMPLETED)
      if (jobId != null) {
        try {
          List<ProactiveJobExecutionResponse> recentExecutions =
              executionRepository.findByJobId(jobId, 10, 0).stream()
                  .filter(e -> "COMPLETED".equals(e.status()) && e.result() != null)
                  .limit(3)
                  .toList();
          if (!recentExecutions.isEmpty()) {
            List<Map<String, Object>> prevExecs =
                recentExecutions.stream().map(this::summarizeExecution).toList();
            context.put("previousExecutions", prevExecs);
          }
        } catch (Exception e) {
          log.warn("Failed to collect previous executions for job {}", jobId, e);
        }
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

  /**
   * 대시보드 조회 하나를 <b>테넌트를 다시 세운 채</b> 비동기로 실행한다.
   *
   * <p>왜 이 감싸기가 필요한가: {@code supplyAsync} 는 공용 {@code ForkJoinPool} 에서 돌고, 그 풀에는
   * {@code TenantContextTaskDecorator} 가 붙은 {@code @Async} 풀과 달리 테넌트 컨텍스트 승계 장치가
   * 없다. 세우지 않으면 RLS GUC 가 비어 대시보드 조회가 <b>예외도 로그도 없이</b> 0행이 되고,
   * {@code DataSchema} 를 거치는 조회는 예외를 던진다. 네 곳이 같은 감싸기를 복붙하고 있었으므로
   * 한 곳으로 모아 한 군데만 빠뜨리는 사고를 구조적으로 막는다.
   *
   * <p>executor 는 일부러 넘기지 않는다 — 기존 동작({@code supplyAsync} 의 기본 풀)을 그대로 유지한다.
   */
  private <T> CompletableFuture<T> scopedAsync(long tenantId, Supplier<T> call) {
    return CompletableFuture.supplyAsync(() -> TenantContext.runScopedGet(tenantId, call));
  }

  /**
   * Add anomaly context to the collected data. Called when a job is triggered by anomaly detection.
   */
  public void addAnomalyContext(Map<String, Object> context, AnomalyEvent event) {
    if (event == null) return;

    Map<String, Object> anomalyInfo = new LinkedHashMap<>();
    anomalyInfo.put("metricName", event.metricName());
    anomalyInfo.put("metricId", event.metricId());
    anomalyInfo.put("currentValue", event.currentValue());
    anomalyInfo.put("expectedRange", Map.of("mean", event.mean(), "stddev", event.stddev()));
    anomalyInfo.put("deviation", String.format("%.2fσ", event.deviation()));
    anomalyInfo.put("sensitivity", event.sensitivity());
    anomalyInfo.put("recentHistory", event.recentHistory());
    anomalyInfo.put("triggerReason", "이 리포트는 메트릭 이상 감지에 의해 자동 생성되었습니다.");

    context.put("anomaly", anomalyInfo);
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

  private Map<String, Object> summarizeExecution(ProactiveJobExecutionResponse exec) {
    Map<String, Object> entry = new HashMap<>();
    entry.put("completedAt", exec.completedAt() != null ? exec.completedAt().toString() : "");
    Object sectionsObj = exec.result().get("sections");
    if (sectionsObj instanceof List<?> sectionsList) {
      List<Map<String, String>> summarySections =
          sectionsList.stream()
              .filter(s -> s instanceof Map)
              .map(
                  s -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> sec = (Map<String, Object>) s;
                    String content = sec.get("content") instanceof String c ? c : "";
                    if (content.length() > 2000) {
                      content = content.substring(0, 2000) + "...[truncated]";
                    }
                    return Map.of(
                        "key", String.valueOf(sec.getOrDefault("key", "")),
                        "label", String.valueOf(sec.getOrDefault("label", "")),
                        "content", content);
                  })
              .toList();
      entry.put("sections", summarySections);
    }
    return entry;
  }
}
