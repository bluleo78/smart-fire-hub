package com.smartfirehub.proactive.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.dashboard.service.DashboardService;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.proactive.dto.AnomalyEvent;
import com.smartfirehub.proactive.dto.ProactiveJobExecutionResponse;
import com.smartfirehub.proactive.repository.ProactiveJobExecutionRepository;
import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.ClearanceResolver;
import com.smartfirehub.securitylevel.ai.AiCall;
import com.smartfirehub.securitylevel.ai.AiCallContext;
import com.smartfirehub.securitylevel.ai.AiHostingResolver;
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
  private final ClearanceResolver clearanceResolver;
  // 컨텍스트가 채팅 공급자로 가고 리포트로 발송되므로 대시보드 조회를 AI+SHARE 범위로 감싼다(스펙 §4.3 Proactive 행).
  private final AiCallContext aiCallContext;
  private final AiHostingResolver aiHostingResolver;

  /**
   * 리포트 컨텍스트 수집.
   *
   * @param ownerUserId 작업 소유자 — 홈 대시보드 데이터(데이터셋 이름·개수·활동)를 이 사용자 자격으로 거른다(보안 등급). 비동기 러너에는 요청 사용자가
   *     없어 명시해야 한다(없으면 "아무것도 못 봄"으로 데이터셋 항목이 전부 빠진다). 소유자 미상(null)이면 아무것도 못 보는 자격(fail-closed)
   */
  public String collectContext(Map<String, Object> config, Long jobId, Long ownerUserId) {
    try {
      Map<String, Object> context = new HashMap<>();

      // 4개 독립 호출을 병렬 실행. 테넌트는 호출 스레드에서 붙잡아 각 작업 안에서 다시 세운다
      // (이유는 scopedAsync 의 Javadoc 참고). require 는 반드시 호출 스레드에서 — 풀 스레드에서
      // 부르면 컨텍스트가 비어 있어 그 자리에서 던진다.
      long tenantId = TenantContext.require("proactive 컨텍스트 수집");
      // 소유자 자격은 호출 스레드(테넌트 컨텍스트 있음)에서 한 번 계산해 네 작업이 공유한다.
      Clearance viewer =
          ownerUserId != null
              ? clearanceResolver.resolve(ownerUserId)
              : Clearance.none(-1L, tenantId);
      // 컨텍스트는 채팅 공급자로 가고 리포트로 발송된다(스펙 §4.3 Proactive 행) — 소유자 VIEW 에 더해 AI(공유 호스팅 규칙
      // forShare: 채팅·임베딩 모두 자체 호스팅이어야 자체 호스팅) + SHARE 를 통과한 데이터셋만 싣는다. 호스팅은 호출 스레드(테넌트
      // 컨텍스트 있음)에서 한 번 계산해 네 작업이 공유한다.
      AiCall aiCall = new AiCall(aiHostingResolver.forShare(), true);
      var statsFuture = scopedAsync(tenantId, aiCall, () -> dashboardService.getStats(viewer));
      var healthFuture =
          scopedAsync(tenantId, aiCall, () -> dashboardService.getSystemHealth(viewer));
      var attentionFuture =
          scopedAsync(tenantId, aiCall, () -> dashboardService.getAttentionItems(viewer));
      var activityFuture =
          scopedAsync(
              tenantId, aiCall, () -> dashboardService.getActivityFeed(null, null, 0, 20, viewer));
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
   * <p>왜 이 감싸기가 필요한가: {@code supplyAsync} 는 공용 {@code ForkJoinPool} 에서 돌고, 그 풀에는 {@code
   * TenantContextTaskDecorator} 가 붙은 {@code @Async} 풀과 달리 테넌트 컨텍스트 승계 장치가 없다. 세우지 않으면 RLS GUC 가 비어
   * 대시보드 조회가 <b>예외도 로그도 없이</b> 0행이 되고, {@code DataSchema} 를 거치는 조회는 예외를 던진다. 네 곳이 같은 감싸기를 복붙하고
   * 있었으므로 한 곳으로 모아 한 군데만 빠뜨리는 사고를 구조적으로 막는다.
   *
   * <p>AI 범위({@link AiCallContext#callWith})도 같은 이유로 <b>작업 안에서</b> 다시 세운다 — ThreadLocal 이라 호출 스레드에서
   * 세우면 풀 스레드의 대시보드 조회가 AI·SHARE 술어 없이 소유자 VIEW 만으로 걸러져 공유 금지(기밀) 데이터셋 이름이 리포트에 실린다.
   *
   * <p>executor 는 일부러 넘기지 않는다 — 기존 동작({@code supplyAsync} 의 기본 풀)을 그대로 유지한다.
   */
  private <T> CompletableFuture<T> scopedAsync(long tenantId, AiCall aiCall, Supplier<T> call) {
    return CompletableFuture.supplyAsync(
        () -> TenantContext.runScopedGet(tenantId, () -> aiCallContext.callWith(aiCall, call)));
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
