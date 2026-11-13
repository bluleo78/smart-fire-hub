package com.smartfirehub.analytics.repository.mybatis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.analytics.dto.CreateChartRequest;
import com.smartfirehub.analytics.repository.mybatis.AnalyticsRows.IdName;
import com.smartfirehub.analytics.repository.mybatis.ChartRows.ChartInsert;
import com.smartfirehub.analytics.repository.mybatis.ChartRows.ChartRow;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 분석 MyBatis 리포지토리의 DB 로 재현할 수 없는 분기(WD-710) — 가짜 매퍼로 고정한다.
 *
 * <ul>
 *   <li>공유 켜기에서 잠근 대상 수와 UPDATE 행 수가 다르면 예외(차트·저장 쿼리). 잠금 아래에서는 같아야 해서 계약 시험으로는 닿지 않는다.
 *   <li>차트 config 의 빈 값·깨진 값 처리(jOOQ 와 같다). config 열은 NOT NULL JSON 이라 DB 에 그런 값을 넣을 수 없다.
 * </ul>
 */
class MyBatisAnalyticsShareAndConfigUnitTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  @DisplayName("shareCharts — 잠근 대상 수와 바꾼 행 수가 다르면 IllegalStateException")
  void shareCharts_countMismatchThrows() {
    ChartMapper mapper = mock(ChartMapper.class);
    when(mapper.selectShareTargetsForUpdate(anyList(), eq(7L)))
        .thenReturn(List.of(new IdName(1L, "a"), new IdName(2L, "b")));
    when(mapper.markShared(anyList())).thenReturn(1);
    MyBatisChartRepository repository = new MyBatisChartRepository(mapper, objectMapper);

    assertThatThrownBy(() -> repository.shareCharts(List.of(1L, 2L), 7L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("locked=2")
        .hasMessageContaining("updated=1");
  }

  @Test
  @DisplayName("shareCharts — 수가 같으면 잠근 대상의 id→이름을 오름차순으로 돌려준다")
  void shareCharts_countMatchReturnsTargets() {
    ChartMapper mapper = mock(ChartMapper.class);
    when(mapper.selectShareTargetsForUpdate(anyList(), anyLong()))
        .thenReturn(List.of(new IdName(9L, "z"), new IdName(3L, "c")));
    when(mapper.markShared(anyList())).thenReturn(2);

    assertThat(new MyBatisChartRepository(mapper, objectMapper).shareCharts(List.of(3L, 9L), 7L))
        .containsExactly(Map.entry(3L, "c"), Map.entry(9L, "z"));
  }

  @Test
  @DisplayName("shareQueries — 잠근 대상 수와 바꾼 행 수가 다르면 IllegalStateException")
  void shareQueries_countMismatchThrows() {
    SavedQueryMapper mapper = mock(SavedQueryMapper.class);
    when(mapper.selectShareTargetsForUpdate(anyList(), eq(7L)))
        .thenReturn(List.of(new IdName(1L, "a")));
    when(mapper.markShared(anyList())).thenReturn(0);
    MyBatisSavedQueryRepository repository = new MyBatisSavedQueryRepository(mapper);

    assertThatThrownBy(() -> repository.shareQueries(List.of(1L), 7L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("locked=1")
        .hasMessageContaining("updated=0");
  }

  @Test
  @DisplayName("차트 config 읽기 — null·빈 값은 빈 맵, 객체가 아니거나 깨진 JSON 도 예외 없이 빈 맵(jOOQ parseJson 과 같다)")
  void readConfig_fallbacks() {
    for (String json : new String[] {null, "", "  ", "[1,2]", "{broken", "\"text\""}) {
      ChartMapper mapper = mock(ChartMapper.class);
      when(mapper.selectDetail(1L, 2L, false)).thenReturn(row(json));
      assertThat(
              new MyBatisChartRepository(mapper, objectMapper)
                  .findById(1L, 2L)
                  .orElseThrow()
                  .config())
          .as(String.valueOf(json))
          .isEmpty();
    }
  }

  @Test
  @DisplayName("차트 config 쓰기 — null·빈 맵·직렬화 실패는 {} 로 저장한다(jOOQ mapToJson 과 같다)")
  void writeConfig_fallbacks() {
    Map<String, Object> unserializable = new HashMap<>();
    unserializable.put("bad", new Object());
    for (Map<String, Object> config :
        Arrays.<Map<String, Object>>asList(null, Map.of(), unserializable)) {
      ChartMapper mapper = mock(ChartMapper.class);
      new MyBatisChartRepository(mapper, objectMapper)
          .insert(new CreateChartRequest("n", null, 1L, "BAR", config, false), 2L);
      ArgumentCaptor<ChartInsert> captor = ArgumentCaptor.forClass(ChartInsert.class);
      verify(mapper).insert(captor.capture());
      assertThat(captor.getValue().getConfigJson()).isEqualTo("{}");
    }
    ChartMapper mapper = mock(ChartMapper.class);
    new MyBatisChartRepository(mapper, objectMapper)
        .insert(new CreateChartRequest("n", null, 1L, "BAR", Map.of("k", 1), false), 2L);
    ArgumentCaptor<ChartInsert> captor = ArgumentCaptor.forClass(ChartInsert.class);
    verify(mapper).insert(captor.capture());
    assertThat(captor.getValue().getConfigJson()).isEqualTo("{\"k\":1}");
  }

  private static ChartRow row(String configJson) {
    LocalDateTime t = LocalDateTime.of(2026, 1, 1, 0, 0);
    return new ChartRow(1L, "c", null, 3L, "q", "BAR", configJson, false, "u", 2L, t, t, 0L);
  }
}
