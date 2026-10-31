package com.smartfirehub.analytics.service;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 차트 종류와 config 값 검사 (WD-413).
 *
 * <p>예전에는 MAP 의 spatialColumn 만 보고 나머지는 그대로 저장해, {@code yAxis} 가 문자열이거나 {@code xAxis} 가 숫자인 config
 * 가 201 로 들어갔다. 그런 차트는 편집 화면이 깨지거나(yAxis.includes) 조용히 빈 차트가 됐다. 잘못된 종류는 DB 제약에 걸려 409
 * 「입력값이 제약 조건을 만족하지 않습니다」로만 안내됐다.
 *
 * <p>검사는 <b>값이 있을 때 그 타입</b>만 본다. 종류별 키를 "필수"로 강제하지 않는 이유: TABLE 처럼 축 없이도 정상인 종류가 있고, 화면이
 * 빠진 축을 다시 고르도록 안내하므로 축 없는 저장(작성 중 저장·AI 도구)을 막을 필요가 없다. MAP 의 spatialColumn 만은 지도를 그릴
 * 수 없어 예전처럼 필수다. 위반은 {@link IllegalArgumentException} 으로 던져 전역 핸들러가 400 + 이 문구로 바꾼다.
 */
final class ChartConfigValidator {

  /** DB 제약 chart_chart_type_check(V48)와 같은 목록 — 순서는 안내 문구에 그대로 쓴다. */
  static final List<String> CHART_TYPES =
      List.of(
          "BAR", "LINE", "PIE", "AREA", "SCATTER", "DONUT", "TABLE", "MAP", "HISTOGRAM", "BOXPLOT",
          "HEATMAP", "TREEMAP", "FUNNEL", "RADAR", "WATERFALL", "GAUGE", "CANDLESTICK");

  /** 컬럼 이름·축 제목처럼 문자열이어야 하는 키. */
  private static final Set<String> STRING_KEYS =
      Set.of(
          "xAxis",
          "groupBy",
          "xAxisLabel",
          "yAxisLabel",
          "spatialColumn",
          "colorByColumn",
          "weightColumn",
          "valueColumn",
          "open",
          "high",
          "low",
          "close");

  /** 문자열 배열이어야 하는 키. */
  private static final Set<String> STRING_LIST_KEYS = Set.of("yAxis", "colors");

  /** 켜고 끄는 옵션. */
  private static final Set<String> BOOLEAN_KEYS = Set.of("showLegend", "showGrid", "stacked");

  /** 숫자여야 하는 키(게이지 범위·목표, 히스토그램 구간 수). */
  private static final Set<String> NUMBER_KEYS = Set.of("min", "max", "target", "bins");

  /** 히스토그램 구간 수 상한 — 웹 정규화(MAX_HISTOGRAM_BINS)와 같은 값 */
  static final int MAX_BINS = 1000;

  private ChartConfigValidator() {}

  /** 차트 종류가 허용 목록에 있는지 본다 — 없으면 허용 목록과 함께 400. */
  static void validateType(String chartType) {
    if (!CHART_TYPES.contains(chartType)) {
      throw new IllegalArgumentException(
          "지원하지 않는 차트 종류입니다: " + chartType + " (가능한 종류: " + String.join(", ", CHART_TYPES) + ")");
    }
  }

  /**
   * config 값의 타입을 본다. config 가 null 이면 볼 것이 없다(요청 DTO 가 필요한 곳은 따로 막는다).
   *
   * @param chartType 이 config 를 쓸 차트 종류(MAP 필수 키·게이지 범위 검사에 쓴다)
   */
  static void validateConfig(String chartType, Map<String, Object> config) {
    if (config == null) {
      return;
    }
    for (Map.Entry<String, Object> e : config.entrySet()) {
      String key = e.getKey();
      Object value = e.getValue();
      if (value == null) {
        continue;
      }
      if (STRING_KEYS.contains(key) && !(value instanceof String)) {
        throw invalid(key, "컬럼 이름(문자열)이어야 합니다.");
      }
      if (STRING_LIST_KEYS.contains(key)
          && !(value instanceof List<?> list && list.stream().allMatch(String.class::isInstance))) {
        throw invalid(key, "문자열 목록(배열)이어야 합니다.");
      }
      if (BOOLEAN_KEYS.contains(key) && !(value instanceof Boolean)) {
        throw invalid(key, "true 또는 false 여야 합니다.");
      }
      if (NUMBER_KEYS.contains(key) && !(value instanceof Number)) {
        throw invalid(key, "숫자여야 합니다.");
      }
    }
    Object mapDisplayMode = config.get("mapDisplayMode");
    if (mapDisplayMode != null && !Set.of("points", "heatmap").contains(mapDisplayMode)) {
      throw invalid("mapDisplayMode", "points 또는 heatmap 이어야 합니다.");
    }
    // 히스토그램이 구간 배열을 bins 길이로 만든다 — 0·음수·소수·지나치게 큰 값은 화면을 깨뜨린다
    if (config.get("bins") instanceof Number bins) {
      double value = bins.doubleValue();
      if (value != Math.rint(value) || value < 1 || value > MAX_BINS) {
        throw invalid("bins", "1 이상 " + MAX_BINS + " 이하의 정수여야 합니다.");
      }
    }
    if (config.get("min") instanceof Number min
        && config.get("max") instanceof Number max
        && min.doubleValue() >= max.doubleValue()) {
      throw new IllegalArgumentException("차트 설정 min 은 max 보다 작아야 합니다.");
    }
    if ("MAP".equals(chartType)) {
      Object spatialColumn = config.get("spatialColumn");
      if (spatialColumn == null || spatialColumn.toString().isBlank()) {
        throw new IllegalArgumentException("MAP 차트는 config에 spatialColumn이 필요합니다");
      }
    }
  }

  private static IllegalArgumentException invalid(String key, String rule) {
    return new IllegalArgumentException("차트 설정 " + key + " 은(는) " + rule);
  }
}
