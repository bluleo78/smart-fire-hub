package com.smartfirehub.pipeline.service.executor;

import com.jayway.jsonpath.InvalidJsonException;
import com.jayway.jsonpath.InvalidPathException;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import com.smartfirehub.pipeline.service.ValueConversion;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
   * <p>값을 컬럼 타입으로 바꿀 수 없으면 행·필드·값·타입을 짚는 {@link ApiCallException} 으로 실패한다(WD-431, 규칙은 {@link
   * ValueConversion}). DB 에 쓰기 전에 페이지 전체를 변환하므로 실패한 페이지는 한 행도 적재되지 않는다.
   *
   * @param jsonResponse raw JSON string
   * @param dataPath JSONPath expression to the data array (e.g. "$.data.items")
   * @param fieldMappings column mapping rules
   * @param rowOffset 앞 페이지까지 받은 응답 항목 수 — 실패 문구의 행 번호가 페이지를 넘어 이어지게 한다(단건 호출은 0)
   * @return list of mapped rows ready for insertBatch
   * @throws ApiCallException if the dataPath is not found, 매핑 설정이 잘못됐거나 값을 변환할 수 없을 때
   */
  public List<Map<String, Object>> parseAndMap(
      String jsonResponse,
      String dataPath,
      List<ApiCallConfig.FieldMapping> fieldMappings,
      Map<String, String> columnTypeMap,
      int rowOffset) {

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
      // 실행 상세·「테스트 호출」에 그대로 보이는 문구라 한국어로 둔다(WD-150). Python 실행기도 같은 규칙·같은 문구로 실패한다(WD-212) —
      // 정의된 경로(필드·인덱스만)가 없거나, 와일드카드·필터 앞까지의 경로가 없을 때다. 와일드카드 뒤가 비면 예외 없이 빈 목록(0행)이다.
      throw new ApiCallException(ApiCallErrorMessages.dataPathNotFound(dataPath), e);
    } catch (InvalidJsonException e) {
      // HTML 오류 페이지 등 JSON 이 아닌 응답 — jayway 의 영어 원문 대신 공용 안내를 쓴다(WD-150, Python 실행기와 같은 문구).
      throw new ApiCallException(ApiCallErrorMessages.notJson(), e);
    } catch (InvalidPathException e) {
      // JSONPath 문법 오류(WD-212). PathNotFoundException 이 이 클래스의 하위라 위 catch 뒤에 둔다
      // (순서를 바꾸면 「찾을 수 없음」이 문법 오류로 보인다). 예전에는 jayway 영어 원문이 스텝 오류로 그대로 저장됐다.
      // Python 실행기와 같은 문구다.
      throw new ApiCallException(ApiCallErrorMessages.dataPathInvalid(dataPath), e);
    }

    List<?> rawList;
    if (extracted == null) {
      // 경로 값이 JSON null 이면 0행으로 본다(WD-212) — 빈 배열과 같은 「데이터 없음」이다. 예전에는 List.of(null) 이 NPE 를 던져
      // 메시지 없는 실패가 저장됐다. Python 실행기도 null 을 0행으로 처리한다.
      rawList = List.of();
    } else if (extracted instanceof List<?> list) {
      rawList = list;
    } else {
      rawList = List.of(extracted);
    }

    // 매핑별 타입·날짜 형식·시간대는 행마다가 아니라 한 번만 풀어 둔다 — 잘못된 설정은 여기서 설정 오류로 알린다(WD-431).
    // 항목이 없으면 값 변환이 없으므로 예전처럼 설정을 검사하지 않는다.
    List<ResolvedMapping> resolved =
        rawList.isEmpty() ? List.of() : resolve(fieldMappings, columnTypeMap);
    List<Map<String, Object>> result = new ArrayList<>(rawList.size());
    for (int i = 0; i < rawList.size(); i++) {
      // 행 번호는 응답 항목 순번(1부터, 앞 페이지 다음부터)이다(WD-431).
      result.add(mapItem(rawList.get(i), resolved, rowOffset + i + 1));
    }
    return result;
  }

  /** 필드 매핑 하나를 행 루프 전에 푼 결과 — 대문자 타입(없으면 null), 날짜 형식(없으면 null), 원본 시간대. */
  private record ResolvedMapping(
      ApiCallConfig.FieldMapping mapping,
      String upperType,
      DateTimeFormatter formatter,
      ZoneId zone) {}

  /** 데이터셋 컬럼 타입을 우선하고(없으면 매핑의 dataType), DATE·TIMESTAMP 의 형식·시간대를 검증해 둔다. */
  private static List<ResolvedMapping> resolve(
      List<ApiCallConfig.FieldMapping> fieldMappings, Map<String, String> columnTypeMap) {
    List<ResolvedMapping> out = new ArrayList<>(fieldMappings.size());
    for (ApiCallConfig.FieldMapping mapping : fieldMappings) {
      String type =
          columnTypeMap != null
              ? columnTypeMap.getOrDefault(mapping.targetColumn(), mapping.dataType())
              : mapping.dataType();
      String upper = type == null ? null : type.toUpperCase(Locale.ROOT);
      boolean temporal = "DATE".equals(upper) || "TIMESTAMP".equals(upper);
      DateTimeFormatter formatter = temporal ? formatter(mapping) : null;
      ZoneId zone = "TIMESTAMP".equals(upper) ? sourceZone(mapping) : UTC;
      out.add(new ResolvedMapping(mapping, upper, formatter, zone));
    }
    return out;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> mapItem(Object item, List<ResolvedMapping> resolved, int rowNumber) {
    Map<String, Object> sourceMap =
        item instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    Map<String, Object> row = new LinkedHashMap<>();

    for (ResolvedMapping rm : resolved) {
      ApiCallConfig.FieldMapping mapping = rm.mapping();
      Object rawValue = sourceMap.get(mapping.sourceField());
      try {
        row.put(mapping.targetColumn(), convertValue(rawValue, rm));
      } catch (ValueConversion.Failed e) {
        // 원문(영어) 예외는 로그에만 남기고, 사용자에게는 행·필드·값·타입을 짚는 한국어 문구를 보인다.
        log.debug("API 응답 값 변환 실패: row={} field={}", rowNumber, mapping.sourceField(), e);
        throw new ApiCallException(
            ApiCallErrorMessages.valueConversion(
                rowNumber,
                mapping.sourceField(),
                mapping.targetColumn(),
                ValueConversion.pyStr(rawValue),
                rm.upperType()),
            e);
      }
    }

    return row;
  }

  /** 값 하나를 컬럼 타입으로 바꾼다. 파서 예외는 {@link ValueConversion.Failed} 로 바꿔 던진다(호출부는 그것만 잡는다). */
  private Object convertValue(Object rawValue, ResolvedMapping rm) {
    String upper = rm.upperType();
    if (rawValue == null || upper == null) {
      // 타입 정보가 없으면 Jackson 의 원래 타입(Integer·Long·Double·Boolean·String)을 그대로 둬 JDBC 가 바인딩하게 한다.
      return rawValue;
    }
    if (ValueConversion.isBlankNull(rawValue, upper)) {
      return null;
    }
    ApiCallConfig.FieldMapping mapping = rm.mapping();
    try {
      return switch (upper) {
        case "TEXT", "VARCHAR" -> rawValue.toString();
        case "INTEGER" -> toLong(rawValue, mapping);
        case "DECIMAL" -> toBigDecimal(rawValue, mapping);
        case "BOOLEAN" -> ValueConversion.toBooleanStrict(rawValue);
        case "DATE" -> toLocalDate(rawValue, rm);
        case "TIMESTAMP" -> toLocalDateTime(rawValue, rm);
        case "GEOMETRY" -> rawValue instanceof String ? rawValue : rawValue.toString();
        default -> rawValue;
      };
    } catch (NumberFormatException | ArithmeticException | DateTimeException e) {
      // NumberFormatException(BigDecimal), DateTimeParseException(날짜) 등 — 값 변환 실패로 통일한다.
      throw new ValueConversion.Failed(e);
    }
  }

  /**
   * 64비트 정수로 바꾼다 — 규칙은 {@link ValueConversion#toLongStrict}(WD-431). comma_separated 면 쉼표를 먼저 뺀다.
   */
  private Long toLong(Object value, ApiCallConfig.FieldMapping mapping) {
    if (value instanceof String s && "comma_separated".equalsIgnoreCase(mapping.numberFormat())) {
      value = s.replace(",", "");
    }
    return ValueConversion.toLongStrict(value);
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

  /** 필드 매핑의 날짜 형식(없으면 null). 패턴 자체가 잘못됐으면 값 변환 실패가 아니라 설정 오류로 알린다(WD-431). */
  private static DateTimeFormatter formatter(ApiCallConfig.FieldMapping mapping) {
    String fmt = mapping.dateFormat();
    if (fmt == null || fmt.isBlank()) {
      return null;
    }
    try {
      return DateTimeFormatter.ofPattern(fmt);
    } catch (IllegalArgumentException e) {
      throw new ApiCallException(
          ApiCallErrorMessages.invalidDateFormat(mapping.sourceField(), fmt), e);
    }
  }

  /** 필드 매핑의 원본 시간대(없으면 UTC). 잘못된 시간대는 설정 오류로 알린다(WD-431). */
  private static ZoneId sourceZone(ApiCallConfig.FieldMapping mapping) {
    String tzStr = mapping.sourceTimezone();
    if (tzStr == null || tzStr.isBlank()) {
      return UTC;
    }
    try {
      return ZoneId.of(tzStr);
    } catch (DateTimeException e) {
      throw new ApiCallException(
          ApiCallErrorMessages.invalidTimezone(mapping.sourceField(), tzStr), e);
    }
  }

  private static final ZoneId UTC = ZoneId.of("UTC");

  private LocalDate toLocalDate(Object value, ResolvedMapping rm) {
    String s = value.toString();
    return rm.formatter() != null ? LocalDate.parse(s, rm.formatter()) : LocalDate.parse(s);
  }

  private LocalDateTime toLocalDateTime(Object value, ResolvedMapping rm) {
    String s = value.toString();
    DateTimeFormatter formatter = rm.formatter();

    if (formatter != null) {
      try {
        // Try parsing as ZonedDateTime first (if format includes zone)
        return ZonedDateTime.parse(s, formatter).withZoneSameInstant(UTC).toLocalDateTime();
      } catch (Exception ignored) {
        // Fall through to LocalDateTime parse
      }
      LocalDateTime ldt = LocalDateTime.parse(s, formatter);
      return ZonedDateTime.of(ldt, rm.zone()).withZoneSameInstant(UTC).toLocalDateTime();
    }

    // No format specified — try ISO formats
    try {
      return ZonedDateTime.parse(s).withZoneSameInstant(UTC).toLocalDateTime();
    } catch (Exception ignored) {
      // Fall through
    }
    LocalDateTime ldt = LocalDateTime.parse(s);
    return ZonedDateTime.of(ldt, rm.zone()).withZoneSameInstant(UTC).toLocalDateTime();
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
