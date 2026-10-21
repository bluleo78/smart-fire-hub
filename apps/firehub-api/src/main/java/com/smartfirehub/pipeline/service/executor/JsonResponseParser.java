package com.smartfirehub.pipeline.service.executor;

import com.jayway.jsonpath.InvalidJsonException;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class JsonResponseParser {

  /**
   * Parses a JSON response string, extracts an array at the given JSONPath, and maps each element
   * to a row according to the provided field mappings.
   *
   * @param jsonResponse raw JSON string
   * @param dataPath JSONPath expression to the data array (e.g. "$.data.items")
   * @param fieldMappings column mapping rules
   * @return list of mapped rows ready for insertBatch
   * @throws ApiCallException if the dataPath is not found
   */
  public List<Map<String, Object>> parseAndMap(
      String jsonResponse,
      String dataPath,
      List<ApiCallConfig.FieldMapping> fieldMappings,
      Map<String, String> columnTypeMap) {

    // JSON 이 아닌 응답(HTML 오류 페이지·평문·빈 본문)을 먼저 걸러 낸다(WD-150). jayway 의 기본 파서(json-smart)는 관대해서
    // HTML 을 문자열 하나로 읽어 버리고, 그러면 「데이터 경로를 찾을 수 없습니다」나 0행 성공처럼 원인과 다른 결과가 나온다.
    // Python 실행기는 resp.json() 실패로 같은 상황을 「JSON 형식이 아닙니다」로 알린다 — 같은 문구를 쓴다.
    if (!looksLikeJson(jsonResponse)) {
      throw new ApiCallException(ApiCallErrorMessages.notJson());
    }

    Object extracted;
    try {
      extracted = JsonPath.read(jsonResponse, dataPath);
    } catch (PathNotFoundException e) {
      // 실행 상세·「테스트 호출」에 그대로 보이는 문구라 한국어로 둔다(WD-150).
      throw new ApiCallException("응답에서 데이터 경로를 찾을 수 없습니다: " + dataPath, e);
    } catch (InvalidJsonException e) {
      // HTML 오류 페이지 등 JSON 이 아닌 응답 — jayway 의 영어 원문 대신 공용 안내를 쓴다(WD-150, Python 실행기와 같은 문구).
      throw new ApiCallException(ApiCallErrorMessages.notJson(), e);
    }

    List<?> rawList;
    if (extracted instanceof List<?> list) {
      rawList = list;
    } else {
      rawList = List.of(extracted);
    }

    List<Map<String, Object>> result = new ArrayList<>(rawList.size());
    int skipped = 0;

    for (Object item : rawList) {
      try {
        Map<String, Object> row = mapItem(item, fieldMappings, columnTypeMap);
        result.add(row);
      } catch (Exception e) {
        skipped++;
        log.warn("Skipping row due to conversion error: {}", e.getMessage());
      }
    }

    if (skipped > 0) {
      log.warn(
          "Skipped {} rows due to type conversion errors (total extracted: {})",
          skipped,
          rawList.size());
    }

    return result;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> mapItem(
      Object item,
      List<ApiCallConfig.FieldMapping> fieldMappings,
      Map<String, String> columnTypeMap) {
    Map<String, Object> sourceMap =
        item instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    Map<String, Object> row = new LinkedHashMap<>();

    for (ApiCallConfig.FieldMapping mapping : fieldMappings) {
      Object rawValue = sourceMap.get(mapping.sourceField());
      // Use actual column type from dataset metadata; fall back to mapping.dataType() for backward
      // compat
      String resolvedType =
          columnTypeMap != null
              ? columnTypeMap.getOrDefault(mapping.targetColumn(), mapping.dataType())
              : mapping.dataType();
      Object converted = convertValue(rawValue, resolvedType, mapping);
      row.put(mapping.targetColumn(), converted);
    }

    return row;
  }

  private Object convertValue(
      Object rawValue, String dataType, ApiCallConfig.FieldMapping mapping) {
    if (rawValue == null) {
      return null;
    }

    if (dataType == null) {
      // No type info available — preserve Jackson's native types (Integer, Long, Double, Boolean,
      // String)
      // so JDBC can correctly bind them to PostgreSQL column types.
      return rawValue;
    }

    return switch (dataType.toUpperCase()) {
      case "TEXT", "VARCHAR" -> rawValue.toString();
      case "INTEGER" -> toLong(rawValue, mapping);
      case "DECIMAL" -> toBigDecimal(rawValue, mapping);
      case "BOOLEAN" -> toBoolean(rawValue);
      case "DATE" -> toLocalDate(rawValue, mapping);
      case "TIMESTAMP" -> toLocalDateTime(rawValue, mapping);
      case "GEOMETRY" -> rawValue instanceof String ? rawValue : rawValue.toString();
      default -> rawValue;
    };
  }

  private Long toLong(Object value, ApiCallConfig.FieldMapping mapping) {
    if (value instanceof Number n) {
      return n.longValue();
    }
    String s = value.toString();
    if ("comma_separated".equalsIgnoreCase(mapping.numberFormat())) {
      s = s.replace(",", "");
    }
    return Long.parseLong(s.strip());
  }

  private BigDecimal toBigDecimal(Object value, ApiCallConfig.FieldMapping mapping) {
    if (value instanceof Number n) {
      return new BigDecimal(n.toString());
    }
    String s = value.toString();
    if ("comma_separated".equalsIgnoreCase(mapping.numberFormat())) {
      s = s.replace(",", "");
    }
    return new BigDecimal(s.strip());
  }

  private Boolean toBoolean(Object value) {
    if (value instanceof Boolean b) {
      return b;
    }
    String s = value.toString().toLowerCase();
    return "true".equals(s) || "1".equals(s) || "yes".equals(s);
  }

  private LocalDate toLocalDate(Object value, ApiCallConfig.FieldMapping mapping) {
    String s = value.toString();
    String fmt = mapping.dateFormat();
    if (fmt != null && !fmt.isBlank()) {
      return LocalDate.parse(s, DateTimeFormatter.ofPattern(fmt));
    }
    return LocalDate.parse(s);
  }

  private LocalDateTime toLocalDateTime(Object value, ApiCallConfig.FieldMapping mapping) {
    String s = value.toString();
    String fmt = mapping.dateFormat();

    // Determine timezone: field-level override takes priority, then parent
    String tzStr = mapping.sourceTimezone();
    ZoneId zone = (tzStr != null && !tzStr.isBlank()) ? ZoneId.of(tzStr) : ZoneId.of("UTC");

    if (fmt != null && !fmt.isBlank()) {
      DateTimeFormatter formatter = DateTimeFormatter.ofPattern(fmt);
      try {
        // Try parsing as ZonedDateTime first (if format includes zone)
        ZonedDateTime zdt = ZonedDateTime.parse(s, formatter);
        return zdt.withZoneSameInstant(ZoneId.of("UTC")).toLocalDateTime();
      } catch (Exception ignored) {
        // Fall through to LocalDateTime parse
      }
      LocalDateTime ldt = LocalDateTime.parse(s, formatter);
      return ZonedDateTime.of(ldt, zone).withZoneSameInstant(ZoneId.of("UTC")).toLocalDateTime();
    }

    // No format specified — try ISO formats
    try {
      ZonedDateTime zdt = ZonedDateTime.parse(s);
      return zdt.withZoneSameInstant(ZoneId.of("UTC")).toLocalDateTime();
    } catch (Exception ignored) {
      // Fall through
    }
    LocalDateTime ldt = LocalDateTime.parse(s);
    return ZonedDateTime.of(ldt, zone).withZoneSameInstant(ZoneId.of("UTC")).toLocalDateTime();
  }

  /**
   * JSON 값의 첫 글자로 시작하는지 본다 — 객체·배열·문자열·숫자·true/false/null. 엄격한 검증이 아니라 HTML({@code <})·평문 같은 흔한
   * 비JSON 응답을 가려내는 용도다(전체를 한 번 더 파싱하지 않으려고).
   */
  static boolean looksLikeJson(String body) {
    if (body == null) return false;
    for (int i = 0; i < body.length(); i++) {
      char c = body.charAt(i);
      if (Character.isWhitespace(c) || c == '\uFEFF') continue;
      return c == '{'
          || c == '['
          || c == '"'
          || c == '-'
          || (c >= '0' && c <= '9')
          || c == 't'
          || c == 'f'
          || c == 'n';
    }
    return false;
  }

  /**
   * Reads a single value from JSON at the given path.
   *
   * @throws ApiCallException if the path is not found
   */
  public <T> T readPath(String json, String path, Class<T> type) {
    try {
      return JsonPath.read(json, path);
    } catch (PathNotFoundException e) {
      throw new ApiCallException("응답에서 JSON 경로를 찾을 수 없습니다: " + path, e);
    }
  }
}
