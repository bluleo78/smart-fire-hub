package com.smartfirehub.proactive.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** ProactiveResult 알림 요약·수치 검증 직렬화 단위 검증(WD-767). */
class ProactiveResultTest {

  private static final String NOTICE = "⚠ 출처를 확인하지 못한 수치 3개가 있습니다 — 실행 결과 화면에서 확인하세요.";

  private ProactiveResult result(ProactiveResult.NumberCheck check) {
    return new ProactiveResult("제목", List.of(), null, null, "요약", check);
  }

  /** 출처 미확인 수치가 있으면 경고 문구가 요약 앞에 오고 빈 줄 뒤에 요약이 이어진다(채널이 본문을 잘라도 경고가 남게). */
  @Test
  void notificationSummary_미확인수치가_있으면_앞에_경고를_둔다() {
    ProactiveResult r =
        result(
            new ProactiveResult.NumberCheck(
                true, List.of(new ProactiveResult.UnverifiedNumber("3.5억", "매출 3.5억")), 3));

    assertThat(r.notificationSummary()).isEqualTo(NOTICE + "\n\n요약");
  }

  /** 요약이 비었거나 공백이면 경고 줄만 남는다. */
  @Test
  void notificationSummary_요약이_비면_경고만() {
    ProactiveResult.NumberCheck check = new ProactiveResult.NumberCheck(true, List.of(), 3);
    ProactiveResult empty = new ProactiveResult("제목", List.of(), null, null, null, check);
    ProactiveResult blank = new ProactiveResult("제목", List.of(), null, null, "  \n", check);

    assertThat(empty.notificationSummary()).isEqualTo(NOTICE);
    assertThat(blank.notificationSummary()).isEqualTo(NOTICE);
  }

  /** total 0 이거나 numberCheck 가 null 이면 effectiveSummary 와 같다. */
  @Test
  void notificationSummary_표식이_없으면_effectiveSummary_와_같다() {
    ProactiveResult zero = result(new ProactiveResult.NumberCheck(true, List.of(), 0));
    ProactiveResult none = result(null);

    assertThat(zero.notificationSummary()).isEqualTo(zero.effectiveSummary()).isEqualTo("요약");
    assertThat(none.notificationSummary()).isEqualTo(none.effectiveSummary()).isEqualTo("요약");
  }

  /** effectiveSummary 는 검증기·PDF 가 쓰므로 경고가 섞이면 안 된다. */
  @Test
  void effectiveSummary_는_경고를_포함하지_않는다() {
    ProactiveResult r = result(new ProactiveResult.NumberCheck(true, List.of(), 3));

    assertThat(r.effectiveSummary()).isEqualTo("요약");
  }

  /** result JSONB 저장 모양 — convertValue 결과에 numberCheck.unverifiedTotal 이 들어간다. */
  @Test
  @SuppressWarnings("unchecked")
  void convertValue_결과에_numberCheck가_들어간다() {
    ProactiveResult r =
        result(
            new ProactiveResult.NumberCheck(
                true, List.of(new ProactiveResult.UnverifiedNumber("3.5억", "매출 3.5억")), 3));

    Map<String, Object> map =
        JsonMapper.builderWithJackson2Defaults().build().convertValue(r, Map.class);

    Map<String, Object> check = (Map<String, Object>) map.get("numberCheck");
    assertThat(check).containsEntry("checked", true).containsEntry("unverifiedTotal", 3);
    assertThat((List<Map<String, Object>>) check.get("unverified"))
        .singleElement()
        .satisfies(
            u -> assertThat(u).containsEntry("text", "3.5억").containsEntry("context", "매출 3.5억"));
  }

  /** 검증하지 않은 실행(checked=false)은 total 이 있어도 알림에 표식을 붙이지 않는다. */
  @Test
  void notificationSummary_checked_false면_표식이_없다() {
    ProactiveResult r = result(new ProactiveResult.NumberCheck(false, List.of(), 2));

    assertThat(r.notificationSummary()).isEqualTo("요약");
  }

  /** 옛 저장 행(numberCheck 키 없음)을 읽어도 numberCheck 는 null 로 복원된다. */
  @Test
  void convertValue_numberCheck_없는_옛_결과는_null() {
    Map<String, Object> old = Map.of("title", "제목", "summary", "요약", "sections", List.of());

    ProactiveResult r =
        JsonMapper.builderWithJackson2Defaults().build().convertValue(old, ProactiveResult.class);

    assertThat(r.numberCheck()).isNull();
  }
}
