package com.smartfirehub.pipeline.service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 데이터 변경(DATASET_CHANGE) 트리거 config 값 해석 규칙(WD-225).
 *
 * <p>config 는 JSON 을 {@code Map<String, Object>} 로 받은 것이라 같은 「60」이
 * Integer·Long·Double·BigDecimal·문자열 중 무엇으로든 들어올 수 있다. 예전에는 생성·수정·폴러가 각자 {@code (Number)} 로 캐스팅해
 * 문자열·null 이 500 이 되고, 2^32+60 처럼 int 를 넘는 값은 {@code intValue()} 넘침으로 60 이 되어 검증을 통과한 채 원래 값이
 * 저장됐으며, 30.9 같은 소수도 잘려서 통과했다. 해석을 이 한곳에 모아 생성·수정·폴러가 같은 값을 보게 한다.
 *
 * <p>정책: JSON 정수(Integer·Long·BigInteger), 소수부가 0 인 실수(30.0), 정수 문자열("60", 앞뒤 공백 허용)을 정수로 받는다.
 * 소수(30.9)· 숫자 아님·int 범위 넘침은 형식 오류다. 키 없음·null 은 기본값을 쓴다.
 */
final class DatasetChangeTriggerConfig {

  static final String POLLING_INTERVAL_KEY = "pollingIntervalSeconds";
  static final String DEBOUNCE_KEY = "debounceSeconds";
  static final String DATASET_IDS_KEY = "datasetIds";

  /** 저장 검증이 쓰는 폴링 주기 기본값(초) — 키가 없을 때. */
  static final int DEFAULT_POLLING_INTERVAL_SECONDS = 60;

  /** 저장 검증이 쓰는 디바운스 기본값(초) — 키가 없을 때. 폴러의 기본값(60)과 다르지만 이번 범위에서는 기존 값을 유지한다. */
  static final int DEFAULT_DEBOUNCE_SECONDS_ON_SAVE = 0;

  static final String POLLING_INTERVAL_MESSAGE =
      "폴링 주기(pollingIntervalSeconds)는 30~3600 사이의 정수(초)여야 합니다.";
  static final String DEBOUNCE_MESSAGE = "디바운스(debounceSeconds)는 0~3600 사이의 정수(초)여야 합니다.";
  static final String DATASET_IDS_REQUIRED_MESSAGE =
      "데이터 변경 트리거는 감시할 데이터셋(datasetIds)을 하나 이상 지정해야 합니다.";
  static final String DATASET_ID_FORMAT_MESSAGE = "감시할 데이터셋(datasetIds)의 각 항목은 데이터셋 id(정수)여야 합니다.";

  /** 정수 문자열 — 부호 하나와 숫자만(지수·소수점 표기는 받지 않는다). */
  private static final Pattern INTEGER_TEXT = Pattern.compile("[+-]?\\d+");

  private DatasetChangeTriggerConfig() {}

  /**
   * 값을 long 정수로 해석한다. null 이면 null, 정수로 읽을 수 없으면(소수·숫자 아님·long 넘침) {@link NumberFormatException}.
   *
   * <p>Boolean 등 숫자가 아닌 타입은 형식 오류다. Double·Float 은 유한하고 소수부가 0 일 때만 받는다.
   */
  static Long parseWholeNumber(Object raw) {
    if (raw == null) {
      return null;
    }
    if (raw instanceof Integer
        || raw instanceof Long
        || raw instanceof Short
        || raw instanceof Byte) {
      return ((Number) raw).longValue();
    }
    try {
      if (raw instanceof BigInteger bi) {
        return bi.longValueExact();
      }
      if (raw instanceof BigDecimal bd) {
        // 30.0 은 받고 30.9 는 거부한다 — 소수부가 0 이 아니면 longValueExact 가 ArithmeticException 을 던진다.
        return bd.longValueExact();
      }
      if (raw instanceof Double || raw instanceof Float) {
        double d = ((Number) raw).doubleValue();
        if (!Double.isFinite(d)) {
          throw new NumberFormatException("유한하지 않은 수");
        }
        return new BigDecimal(d).longValueExact();
      }
    } catch (ArithmeticException e) {
      throw new NumberFormatException("정수가 아니거나 범위를 넘는 수");
    }
    if (raw instanceof String s) {
      String text = s.trim();
      if (!INTEGER_TEXT.matcher(text).matches()) {
        throw new NumberFormatException("정수 문자열이 아님");
      }
      // long 을 넘는 긴 숫자열은 parseLong 이 NumberFormatException 을 던진다.
      return Long.parseLong(text);
    }
    throw new NumberFormatException("숫자가 아닌 타입");
  }

  /**
   * 저장 시 정수 초 값을 검증한다. 키 없음·null 이면 기본값, 정수가 아니거나 [min, max] 밖이면 400(IllegalArgumentException).
   *
   * <p>long 으로 먼저 읽은 뒤 범위를 보므로 int 넘침 값이 잘린 값으로 통과하지 않는다.
   */
  static int requireSecondsInRange(Object raw, int defaultValue, int min, int max, String message) {
    Long value;
    try {
      value = parseWholeNumber(raw);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(message);
    }
    if (value == null) {
      return defaultValue;
    }
    if (value < min || value > max) {
      throw new IllegalArgumentException(message);
    }
    return value.intValue();
  }

  /** 폴링 주기를 검증·해석한다(30~3600, 키 없음·null → 60). */
  static int requirePollingInterval(Object raw) {
    return requireSecondsInRange(
        raw, DEFAULT_POLLING_INTERVAL_SECONDS, 30, 3600, POLLING_INTERVAL_MESSAGE);
  }

  /** 디바운스를 검증·해석한다(0~3600, 키 없음·null → 0). */
  static int requireDebounce(Object raw) {
    return requireSecondsInRange(raw, DEFAULT_DEBOUNCE_SECONDS_ON_SAVE, 0, 3600, DEBOUNCE_MESSAGE);
  }

  /**
   * 감시 목록의 각 원소를 데이터셋 id(long)로 해석한다. 원소가 정수가 아니면(null·소수·숫자 아님) 400. 목록 자체의 존재·비어 있음은 호출부가 판정한다.
   *
   * <p>숫자 문자열("1")도 정수로 받아 Long 으로 정규화한다 — 폴러는 저장된 목록을 숫자로 읽으므로 문자열로 남으면 실행 시 깨진다.
   */
  static List<Long> requireDatasetIdElements(List<?> rawIds) {
    List<Long> ids = new ArrayList<>(rawIds.size());
    for (Object o : rawIds) {
      Long id;
      try {
        id = parseWholeNumber(o);
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException(DATASET_ID_FORMAT_MESSAGE);
      }
      if (id == null) {
        throw new IllegalArgumentException(DATASET_ID_FORMAT_MESSAGE);
      }
      ids.add(id);
    }
    return ids;
  }

  /**
   * 저장할 config 사본을 만들어 폴링 주기·디바운스·감시 목록을 정규화된 값으로 바꿔 넣는다. 키가 있었는데 값이 null 이면 키를 지운다(기본값이 쓰이도록). 키가
   * 없던 항목은 넣지 않는다 — 저장 config 의 모양을 요청과 같게 유지한다. 감시 목록은 List 일 때만 원소를 정규화한다.
   */
  static Map<String, Object> normalizedForSave(Map<String, Object> config) {
    Map<String, Object> out = new HashMap<>(config);
    if (out.containsKey(POLLING_INTERVAL_KEY)) {
      Object raw = out.get(POLLING_INTERVAL_KEY);
      int v = requirePollingInterval(raw);
      if (raw == null) {
        out.remove(POLLING_INTERVAL_KEY);
      } else {
        out.put(POLLING_INTERVAL_KEY, v);
      }
    }
    if (out.containsKey(DEBOUNCE_KEY)) {
      Object raw = out.get(DEBOUNCE_KEY);
      int v = requireDebounce(raw);
      if (raw == null) {
        out.remove(DEBOUNCE_KEY);
      } else {
        out.put(DEBOUNCE_KEY, v);
      }
    }
    if (out.get(DATASET_IDS_KEY) instanceof List<?> ids) {
      out.put(DATASET_IDS_KEY, requireDatasetIdElements(ids));
    }
    return out;
  }

  /**
   * 폴러용 관대한 해석 — 저장된 값이 정수로 읽히면 그 값, 키 없음·null·읽을 수 없는 값(검증 이전에 저장된 옛 행)이면 기본값.
   *
   * <p>폴러는 사용자에게 오류를 돌려줄 수 없으므로 던지지 않는다. int 를 넘는 옛 값(예: 2^32+60)은 읽을 수 없는 값으로 보아 기본값을 쓴다 — 예전
   * {@code intValue()} 넘침으로 우연히 나오던 값에 기대지 않는다.
   */
  static int secondsOrDefault(Object raw, int defaultValue) {
    try {
      Long value = parseWholeNumber(raw);
      if (value == null || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
        return defaultValue;
      }
      return value.intValue();
    } catch (NumberFormatException e) {
      return defaultValue;
    }
  }
}
