package com.smartfirehub.dataset.dto;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 행 값 맵(컬럼명 → 값)을 JSON 으로 쓸 때 JS double 로 깎이는 숫자만 문자열로 바꾼다(WD-101, WD-104).
 *
 * <p>왜: 브라우저·ai-agent 의 JSON 파서는 숫자를 IEEE double 로 읽으므로 NUMERIC(18,6) 의 {@code 123456789012.123456}
 * 은 {@code 123456789012.12346} 으로, 2^53 을 넘는 BIGINT {@code 9007199254740993} 은 {@code
 * 9007199254740992} 로 바뀌어 화면에 보인다. 그래서 응답에서만 정밀도를 잃는 값을 문자열로 내보낸다.
 *
 * <ul>
 *   <li>{@link BigDecimal} → 항상 {@code stripTrailingZeros().toPlainString()} 문자열 (1.500000 → "1.5",
 *       100.000000 → "100", 0E-6 → "0"). 예전 화면 표시(JS number → String)와 같은 모양이며 지수 표기는 쓰지 않는다.
 *   <li>{@link Long}·{@link BigInteger} → ±(2^53-1) 안이면 숫자 그대로, 밖이면 10진 문자열. 행 id 같은 작은 정수는 웹이
 *       {@code typeof id === 'number'} 로 다루므로 숫자로 남겨야 한다. 데이터셋 컬럼(BIGINT·NUMERIC(18,6))에서는
 *       BigInteger 가 나오지 않지만, 데이터셋 SQL 탭의 사용자 SELECT 는 {@code ::numeric(30,0)} 처럼 정밀도 19 이상·소수 0자리
 *       식을 만들 수 있고 jOOQ 는 이를 BigInteger 로 읽는다(WD-104 실측).
 *   <li>배열({@code Object[]}·{@link List}) → 원소마다 같은 규칙을 적용한 JSON 배열. SQL 탭의 {@code
 *       array_agg(numeric)} 등은 BigDecimal·Long 원소 배열로 오는데, 기본 직렬화는 원소를 숫자로 써서 똑같이 깎인다. 데이터셋 컬럼에는 배열
 *       타입이 없어 데이터셋 행 응답은 이 분기에 닿지 않는다. {@code byte[]} 는 {@code Object[]} 가 아니므로 기본(base64) 그대로다.
 *   <li>그 밖의 값(Integer·Short·Double·Float·날짜·시각·지오메트리 등) → Jackson 기본 직렬화 그대로(기존 응답 형태 유지).
 * </ul>
 *
 * <p>Java Map 값 자체를 바꾸지 않고 JSON 쓰기 단계에서만 바꾸는 이유: 같은 행 조회 경로(PostgresDataRowStore)를 내보내기
 * (DataExportService)·파이프라인 등 Java 소비자도 쓰므로, 값 타입을 바꾸면 그쪽 동작까지 파급된다. 이 직렬화기는 데이터셋 행 응답 DTO({@link
 * DataQueryResponse#rows()}, {@link RowDataResponse#data()})와 데이터셋 SQL 탭 응답({@link
 * SqlQueryResponse#rows()}, WD-104)에만 붙인다 — 전역 ObjectMapper·분석 쿼리 응답은 대상이 아니다.
 */
public class DatasetRowValuesSerializer extends JsonSerializer<Map<String, Object>> {

  /** JS Number.MAX_SAFE_INTEGER(2^53-1) — 이 범위 안의 정수만 JSON 숫자로 내보내도 값이 보존된다. */
  static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

  /** BigInteger 비교용 ±(2^53-1) 경계 — bitLength 로 판정하면 -2^53 에서 한 칸 어긋나므로 양쪽 값과 직접 비교한다. */
  private static final BigInteger MAX_SAFE_BIG = BigInteger.valueOf(MAX_SAFE_INTEGER);

  private static final BigInteger MIN_SAFE_BIG = BigInteger.valueOf(-MAX_SAFE_INTEGER);

  @Override
  public void serialize(Map<String, Object> row, JsonGenerator gen, SerializerProvider provider)
      throws IOException {
    gen.writeStartObject(row);
    for (Map.Entry<String, Object> entry : row.entrySet()) {
      gen.writeFieldName(entry.getKey());
      writeValue(entry.getValue(), gen, provider);
    }
    gen.writeEndObject();
  }

  /** 셀 값 하나를 쓴다 — 정밀도를 잃는 숫자만 문자열, 나머지는 기본 직렬화에 맡긴다. */
  private static void writeValue(Object value, JsonGenerator gen, SerializerProvider provider)
      throws IOException {
    if (value == null) {
      gen.writeNull();
    } else if (value instanceof BigDecimal decimal) {
      // 소수는 범위와 상관없이 double 로 깎일 수 있으므로 항상 문자열로 쓴다. 끝자리 0 은 예전 표시처럼 지운다.
      gen.writeString(decimal.stripTrailingZeros().toPlainString());
    } else if (value instanceof Long l) {
      // Math.abs 는 Long.MIN_VALUE 에서 넘치므로 양쪽 경계를 따로 비교한다.
      if (l > MAX_SAFE_INTEGER || l < -MAX_SAFE_INTEGER) {
        gen.writeString(Long.toString(l));
      } else {
        gen.writeNumber(l);
      }
    } else if (value instanceof BigInteger big) {
      // NUMERIC(p≥19,0) 식 결과(SQL 탭 전용). Long 과 같은 경계로 안전 범위 밖만 문자열로 쓴다(WD-104).
      if (big.compareTo(MAX_SAFE_BIG) > 0 || big.compareTo(MIN_SAFE_BIG) < 0) {
        gen.writeString(big.toString());
      } else {
        gen.writeNumber(big);
      }
    } else if (value instanceof Object[] || value instanceof List<?>) {
      // 배열 원소도 같은 규칙으로 쓴다 — 기본 직렬화는 BigDecimal·Long 원소를 숫자로 써서 깎는다(WD-104).
      // Object[] 는 Arrays.asList(복사 없는 뷰)로 List 와 한 경로로 다룬다.
      List<?> list = value instanceof Object[] array ? Arrays.asList(array) : (List<?>) value;
      gen.writeStartArray();
      for (Object element : list) {
        writeValue(element, gen, provider);
      }
      gen.writeEndArray();
    } else {
      // Integer(최대 2^31)·Double 은 double 로 그대로 표현되고, 날짜·지오메트리 등은 기존 직렬화 형태를 유지해야 한다.
      provider.defaultSerializeValue(value, gen);
    }
  }
}
