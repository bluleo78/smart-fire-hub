package com.smartfirehub.dbexecutor.sql;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Python 실행기 원격 적재(WD-77 Task 6)가 실제로 보내는 값의 수용 검사(실서버 없이). 공유 사례 {@code
 * python-remote-load-wire.json} 의 모든 {@code wire} 행을 {@code wireColumnTypes} 로 {@link
 * LoadValues#coerce} 에 넣어 {@link LoadValues.InvalidValue} 없이 통과하는지, 그리고 의도한 Java 값이 되는지 본다. Python
 * 쪽(test_remote_load.py)이 같은 파일로 원시 입력 → wire 를 고정하므로 두 테스트가 한 계약을 양쪽에서 잡는다.
 */
class PythonRemoteLoadWireTest {

  @SuppressWarnings("unchecked")
  private static Map<String, Object> fixture() throws Exception {
    return new ObjectMapper()
        .readValue(
            Path.of("../firehub-api/src/test/resources/fixtures/python-remote-load-wire.json")
                .toFile(),
            Map.class);
  }

  @Test
  @SuppressWarnings("unchecked")
  void everyWireRow_isAcceptedByLoadValues() throws Exception {
    Map<String, Object> fixture = fixture();
    List<Map<String, Object>> wire = (List<Map<String, Object>>) fixture.get("wire");
    Map<String, String> types = (Map<String, String>) fixture.get("wireColumnTypes");
    assertThat(wire).isNotEmpty();

    for (Map<String, Object> row : wire) {
      for (Map.Entry<String, Object> cell : row.entrySet()) {
        // InvalidValue 가 나면 db-executor 는 요청 전체를 실패시킨다 — 여기서 던지지 않아야 한다.
        LoadValues.coerce(cell.getValue(), types.get(cell.getKey()));
      }
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void wireRows_coerceToTheIntendedJavaValues() throws Exception {
    Map<String, Object> fixture = fixture();
    List<Map<String, Object>> wire = (List<Map<String, Object>>) fixture.get("wire");
    Map<String, String> types = (Map<String, String>) fixture.get("wireColumnTypes");

    Map<String, Object> first = coerceRow(wire.get(0), types);
    assertThat(first.get("id_key")).isEqualTo(7L);
    assertThat((BigDecimal) first.get("amount")).isEqualByComparingTo("12.5");
    assertThat(first.get("name")).isEqualTo("alpha");
    assertThat(first.get("flag")).isEqualTo(true);
    assertThat(first.get("d")).isEqualTo(LocalDate.of(2024, 3, 5));
    // +09:00 은 UTC 로 환산된다.
    assertThat(first.get("ts")).isEqualTo(LocalDateTime.of(2024, 3, 5, 1, 2, 3));

    // 둘째 행: 빈 값에서 온 None 은 NULL 그대로, TEXT 빈 문자열은 그대로, BOOLEAN false.
    Map<String, Object> second = coerceRow(wire.get(1), types);
    assertThat(second.get("id_key")).isEqualTo(2L);
    assertThat(second.get("amount")).isNull();
    assertThat(second.get("name")).isEqualTo("");
    assertThat(second.get("flag")).isEqualTo(false);
    assertThat(second.get("d")).isNull();
    assertThat(second.get("ts")).isNull();

    // 셋째 행: 변환 불가 값이었던 것은 전부 NULL.
    Map<String, Object> third = coerceRow(wire.get(2), types);
    assertThat(third.values()).containsOnlyNulls();
  }

  private static Map<String, Object> coerceRow(Map<String, Object> row, Map<String, String> types) {
    Map<String, Object> out = new java.util.LinkedHashMap<>();
    row.forEach((col, value) -> out.put(col, LoadValues.coerce(value, types.get(col))));
    return out;
  }
}
