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
  // 이전 리포트 본문을 컨텍스트에 실으면 그 리포트의 등급도 새 리포트로 넘어간다(WD-198)
  private final ProactiveReportLevelRecorder levelRecorder;

  /**
   * 리포트 컨텍스트 수집.
   *
   * @param ownerUserId 작업 소유자 — 홈 대시보드 데이터(데이터셋 이름·개수·활동)를 이 사용자 자격으로 거른다(보안 등급). 비동기 러너에는 요청 사용자가
   *     없어 명시해야 한다(없으면 "아무것도 못 봄"으로 데이터셋 항목이 전부 빠진다). 소유자 미상(null)이면 아무것도 못 보는 자격(fail-closed)
   */
  public String collectContext(Map<String, Object> config, Long jobId, Long ownerUserId) {
    return collectContext(config, jobId, ownerUserId, null);
  }

  /**
   * 리포트 컨텍스트 수집 — 이상 탐지로 시작된 실행이면 그 이상(지표·현재값·평균·편차·최근 값)을 함께 싣는다(WD-171).
   *
   * <p>예전에는 이상 이벤트가 실행기로 전달되지 않아 이상 탐지 리포트가 평소 예약 리포트와 같은 내용이었다 — 받는 사람이 왜 리포트가 왔는지 알 수 없었다. 이상 정보는
   * 대상 필터(scope=SELECTED) 뒤에 넣어 항상 실리고, 길이 제한에 잘리지 않게 직렬화 맨 앞에 둔다.
   *
   * @param anomaly 이상 탐지 이벤트. 예약·수동 실행이면 null
   */
  public String collectContext(
      Map<String, Object> config, Long jobId, Long ownerUserId, AnomalyEvent anomaly) {
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
      AiCall aiCall = aiHostingResolver.shareCall();
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
      // 실린 이전 리포트들의 등급(WD-198) — 대상 필터 뒤에도 남았을 때만 알린다(아래).
      List<Long> previousLevelIds = List.of();
      if (jobId != null) {
        try {
          List<ProactiveJobExecutionResponse> recentExecutions =
              executionRepository.findByJobId(jobId, 10, 0).stream()
                  .filter(e -> "COMPLETED".equals(e.status()) && e.result() != null)
                  .limit(3)
                  .toList();
          if (!recentExecutions.isEmpty()) {
            previousLevelIds =
                recentExecutions.stream()
                    .filter(e -> e.securityLevel() != null)
                    .map(e -> e.securityLevel().id())
                    .toList();
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

      // 이전 리포트 본문(sections)이 새 리포트의 입력이 된다 — 필터 뒤에도 실렸으면 그 리포트들의 등급을 이번 실행 등급에 넣는다(WD-198). 실행기
      // 범위(ProactiveReportLevelRecorder#runInExecution) 밖에서 불리면 아무 일도 하지 않는다.
      if (context.containsKey("previousExecutions")) {
        previousLevelIds.forEach(levelRecorder::noteLevelId);
      }
      // 대시보드 항목(주의 항목·활동 피드)에는 데이터셋 이름·임포트 결과가 실린다 — 필터 뒤에도 실린 항목이 가리키는 데이터셋의 등급을 넣는다(WD-198).
      // 대시보드 조회는 공용 풀 스레드라 실행기 범위가 닿지 않으므로, 결과를 받은 이 스레드(범위 안)에서 알린다. 통계(stats·systemHealth)는
      // 개수뿐이라 넣지 않는다.
      levelRecorder.noteDatasetIds(dashboardDatasetIds(context));

      // 이상 탐지 실행이면 이상 정보를 맨 앞에 싣는다(WD-171) — 필터 뒤라 항상 남고, 앞에 있어 길이 제한에 잘리지 않는다
      Map<String, Object> payload = context;
      if (anomaly != null) {
        payload = new LinkedHashMap<>();
        addAnomalyContext(payload, anomaly);
        payload.putAll(context);
      }

      // JSON 직렬화 후 크기 제한
      String json = objectMapper.writeValueAsString(payload);
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

  /** 컨텍스트에 실린 주의 항목·활동 피드 중 데이터셋을 가리키는 항목의 데이터셋 id(WD-198 리포트 등급용). */
  private static List<Long> dashboardDatasetIds(Map<String, Object> context) {
    List<Long> ids = new java.util.ArrayList<>();
    if (context.get("attentionItems") instanceof List<?> items) {
      for (Object o : items) {
        if (o instanceof com.smartfirehub.dashboard.dto.AttentionItemResponse a
            && "DATASET".equals(a.entityType())
            && a.entityId() != null) {
          ids.add(a.entityId());
        }
      }
    }
    if (context.get("activityFeed")
            instanceof com.smartfirehub.dashboard.dto.ActivityFeedResponse feed
        && feed.items() != null) {
      for (var item : feed.items()) {
        if ("DATASET".equals(item.entityType()) && item.entityId() != null) {
          ids.add(item.entityId());
        }
      }
    }
    return ids;
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
