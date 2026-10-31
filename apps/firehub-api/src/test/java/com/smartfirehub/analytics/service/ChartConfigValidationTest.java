package com.smartfirehub.analytics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.analytics.dto.ChartResponse;
import com.smartfirehub.analytics.dto.CreateChartRequest;
import com.smartfirehub.analytics.dto.CreateSavedQueryRequest;
import com.smartfirehub.analytics.dto.UpdateChartRequest;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * 차트 종류·config 값 검사 (WD-413).
 *
 * <p>예전에는 {@code yAxis} 가 문자열이거나 {@code xAxis} 가 숫자인 config 도 201 로 저장돼 편집 화면이 깨지거나 빈 차트가 됐고, 잘못된
 * 종류는 DB 제약에 걸려 409 로만 안내됐다. 이제 값이 있으면 타입을 보고 {@link IllegalArgumentException}(전역 핸들러가 400 + 문구)으로
 * 거부한다. 축 자체가 없는 저장은 막지 않는다 — 화면이 다시 고르게 안내한다.
 */
@Transactional
class ChartConfigValidationTest extends IntegrationTestBase {

  @Autowired private ChartService chartService;
  @Autowired private SavedQueryService savedQueryService;
  @Autowired private DSLContext dsl;

  private Long ownerUserId;
  private Long savedQueryId;

  @BeforeEach
  void setUp() {
    ownerUserId =
        dsl.insertInto(DSL.table(DSL.name("user")))
            .set(
                DSL.field(DSL.name("user", "username"), String.class),
                "chart_cfg_owner_" + System.nanoTime())
            .set(DSL.field(DSL.name("user", "password"), String.class), "password")
            .set(DSL.field(DSL.name("user", "name"), String.class), "Chart Cfg Owner")
            .set(
                DSL.field(DSL.name("user", "email"), String.class),
                "chart_cfg_" + System.nanoTime() + "@example.com")
            .returning(DSL.field(DSL.name("user", "id"), Long.class))
            .fetchOne()
            .get(DSL.field(DSL.name("user", "id"), Long.class));
    savedQueryId =
        savedQueryService
            .create(
                new CreateSavedQueryRequest("Cfg SQ", null, "SELECT 1 AS n", null, null, false),
                ownerUserId)
            .id();
  }

  private ChartResponse create(String type, Map<String, Object> config) {
    return chartService.create(
        new CreateChartRequest("Cfg Chart", null, savedQueryId, type, config, false), ownerUserId);
  }

  @Test
  void create_yAxisString_rejectedWithKoreanReason() {
    assertThatThrownBy(() -> create("BAR", Map.of("xAxis", "region", "yAxis", "amount")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("차트 설정 yAxis 은(는) 문자열 목록(배열)이어야 합니다.");
  }

  @Test
  void create_nonStringValues_rejected() {
    assertThatThrownBy(() -> create("BAR", Map.of("xAxis", 123, "yAxis", List.of("a"))))
        .hasMessage("차트 설정 xAxis 은(는) 컬럼 이름(문자열)이어야 합니다.");
    assertThatThrownBy(() -> create("BAR", Map.of("xAxis", "r", "yAxis", List.of(1, Map.of("a", 2)))))
        .hasMessage("차트 설정 yAxis 은(는) 문자열 목록(배열)이어야 합니다.");
    assertThatThrownBy(() -> create("BAR", Map.of("xAxis", List.of("r"), "yAxis", List.of("a"))))
        .hasMessage("차트 설정 xAxis 은(는) 컬럼 이름(문자열)이어야 합니다.");
    assertThatThrownBy(() -> create("BAR", Map.of("xAxis", "r", "yAxis", List.of("a"), "stacked", "yes")))
        .hasMessage("차트 설정 stacked 은(는) true 또는 false 여야 합니다.");
  }

  @Test
  void create_gaugeRange_mustBeNumbersWithMinBelowMax() {
    assertThatThrownBy(
            () -> create("GAUGE", Map.of("yAxis", List.of("v"), "min", 100, "max", -5)))
        .hasMessage("차트 설정 min 은 max 보다 작아야 합니다.");
    assertThatThrownBy(() -> create("GAUGE", Map.of("yAxis", List.of("v"), "target", "abc")))
        .hasMessage("차트 설정 target 은(는) 숫자여야 합니다.");
  }

  /** 잘못된 종류는 DB 제약의 409 대신 400 + 허용 목록으로 안내한다. */
  @Test
  void create_histogramBins_mustBeIntegerWithinRange() {
    for (Object bins : List.of(0, -3, 2.5, 1001)) {
      assertThatThrownBy(() -> create("HISTOGRAM", Map.of("xAxis", "v", "bins", bins)))
          .hasMessage("차트 설정 bins 은(는) 1 이상 1000 이하의 정수여야 합니다.");
    }
    assertThat(create("HISTOGRAM", Map.of("xAxis", "v", "bins", 12)).id()).isNotNull();
    assertThat(create("HISTOGRAM", Map.of("xAxis", "v", "bins", 1000.0)).id()).isNotNull();
  }

  @Test
  void create_unknownChartType_rejectedWithAllowedList() {
    assertThatThrownBy(() -> create("FOO", Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageStartingWith("지원하지 않는 차트 종류입니다: FOO (가능한 종류: BAR, LINE");
  }

  /** 축 없이도 저장된다 — TABLE 처럼 정상인 종류와 작성 중 저장을 막지 않는다(화면이 다시 고르게 안내). */
  @Test
  void create_missingAxes_stillAllowed() {
    assertThat(create("TABLE", Map.of()).id()).isNotNull();
    Map<String, Object> withNull = new HashMap<>();
    withNull.put("xAxis", "region");
    withNull.put("groupBy", null);
    assertThat(create("BAR", withNull).id()).isNotNull();
  }

  @Test
  void update_invalidConfigOrType_rejected() {
    Long id = create("BAR", Map.of("xAxis", "r", "yAxis", List.of("a"))).id();

    assertThatThrownBy(
            () ->
                chartService.update(
                    id,
                    new UpdateChartRequest(null, null, null, Map.of("yAxis", "a"), null),
                    ownerUserId))
        .hasMessage("차트 설정 yAxis 은(는) 문자열 목록(배열)이어야 합니다.");
    assertThatThrownBy(
            () ->
                chartService.update(
                    id, new UpdateChartRequest(null, null, "FOO", null, null), ownerUserId))
        .hasMessageStartingWith("지원하지 않는 차트 종류입니다: FOO");
  }

  /** 예전에 저장된 잘못된 config 가 있어도 이름만 바꾸는 저장은 막지 않는다 — 요청에 실린 것만 본다. */
  @Test
  void update_renameOnly_allowedEvenIfStoredConfigIsMalformed() {
    Long id = create("BAR", Map.of("xAxis", "r", "yAxis", List.of("a"))).id();
    dsl.execute(
        "update chart set config = '{\"xAxis\":\"r\",\"yAxis\":\"a\"}'::jsonb where id = ?", id);

    ChartResponse renamed =
        chartService.update(
            id, new UpdateChartRequest("새 이름", null, null, null, null), ownerUserId);

    assertThat(renamed.name()).isEqualTo("새 이름");
  }
}
