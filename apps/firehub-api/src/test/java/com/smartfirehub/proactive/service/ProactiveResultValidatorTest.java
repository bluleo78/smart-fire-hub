package com.smartfirehub.proactive.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.proactive.dto.ProactiveResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 프로액티브 실행 결과 발송 게이트 단위 검증 (이슈 #350).
 *
 * <p>핵심 경계 두 가지를 고정한다: (1) 인증 실패 원문이 리포트 본문으로 둔갑한 결과는 거부한다. (2) 장애를 *서술*하는 정상 리포트와 report.md만 생성된
 * 리포트는 계속 통과시킨다(FAILED 회귀 방지).
 */
class ProactiveResultValidatorTest {

  private static ProactiveResult result(
      String htmlContent, String summary, List<ProactiveResult.Section> sections) {
    return new ProactiveResult("리포트", sections, null, htmlContent, summary);
  }

  private static ProactiveResult.Section section(String content) {
    return new ProactiveResult.Section("content", "분석 결과", content, "text", null);
  }

  @Test
  void rejects_authFailureTextPromotedToReportBody() {
    // 실측된 결함 상태(execution 410): summary 없음 + sections[0]가 401 원문
    ProactiveResult r =
        result(
            "",
            "",
            List.of(
                section(
                    "Failed to authenticate. API Error: 401"
                        + " {\"type\":\"error\",\"error\":{\"type\":\"authentication_error\","
                        + "\"message\":\"Invalid bearer token\"},\"request_id\":\"req_011Cd\"}")));

    assertThat(ProactiveResultValidator.findRejectionReason(r))
        .isPresent()
        .get()
        .asString()
        .contains("failed to authenticate");
  }

  @Test
  void rejects_emptyResult() {
    assertThat(ProactiveResultValidator.findRejectionReason(result("", "", List.of()))).isPresent();
    assertThat(ProactiveResultValidator.findRejectionReason(result(null, null, null))).isPresent();
    assertThat(ProactiveResultValidator.findRejectionReason(result("", "  ", List.of(section("")))))
        .isPresent();
  }

  @Test
  void rejects_nullResult() {
    assertThat(ProactiveResultValidator.findRejectionReason(null)).isPresent();
  }

  @Test
  void accepts_normalHtmlReport() {
    ProactiveResult r =
        result("<html><body>일간 KPI 리포트</body></html>", "오늘 처리량은 전일 대비 12% 증가했습니다.", List.of());

    assertThat(ProactiveResultValidator.findRejectionReason(r)).isEmpty();
  }

  /** report-writer가 report.md만 생성한 경우 htmlContent/summary는 비고 sections만 채워진다 — 정상 리포트다. */
  @Test
  void accepts_markdownOnlyReport() {
    ProactiveResult r = result("", "", List.of(section("## 요약\n어제 대비 유입이 8% 늘었습니다.")));

    assertThat(ProactiveResultValidator.findRejectionReason(r)).isEmpty();
  }

  /** 회귀 방지: 리포트가 장애를 서술하며 같은 오류 문구를 인용해도 실패로 처리하면 안 된다. */
  @Test
  void accepts_reportThatQuotesAuthErrorInsideBody() {
    ProactiveResult r =
        result(
            "",
            "외부 API 연결 3건이 실패했습니다. 수집 로그에 API Error: 401 authentication_error 가 기록되어 자격 증명 갱신이 필요합니다.",
            List.of());

    assertThat(ProactiveResultValidator.findRejectionReason(r)).isEmpty();
  }

  /** 사용자 노출 문구에 오류 원문·request_id가 섞이면 안 된다 (#313 원칙). */
  @Test
  void userFacingMessage_containsNoRawErrorDetail() {
    assertThat(ProactiveResultValidator.USER_FACING_FAILURE_MESSAGE)
        .doesNotContain("401")
        .doesNotContain("request_id")
        .doesNotContain("authentication_error")
        .contains("AI 인증 정보");
  }

  /**
   * WD-765: reportFilesMissing 은 ai-agent→api 사이 내부 신호라 저장·응답 JSON 에 남기지 않는다. 실행기가 저장하는 결과
   * 맵(updateResult)에는 필드가 없고, 필드가 있는 옛 결과 행·필드가 없는 결과 행 모두 컨트롤러처럼 다시 읽을 수 있다(늘 false). 5인자 보조 생성자가
   * 있어도 Jackson 은 레코드 정식 생성자를 써야 한다.
   */
  @Test
  void 결과_맵에는_reportFilesMissing_이_저장되지_않고_다시_읽으면_false다() {
    ObjectMapper mapper = new ObjectMapper();
    ProactiveResult saved = new ProactiveResult("리포트", List.of(), null, "<p>x</p>", "요약", true);
    Map<String, Object> savedMap = mapper.convertValue(saved, new TypeReference<>() {});
    assertThat(savedMap).doesNotContainKey("reportFilesMissing").containsEntry("summary", "요약");
    ProactiveResult reread = mapper.convertValue(savedMap, ProactiveResult.class);
    assertThat(reread.reportFilesMissing()).isFalse();
    assertThat(reread.htmlContent()).isEqualTo("<p>x</p>");

    // 이 변경 전에 저장돼 필드가 남아 있는 행도 실패 없이 읽히고, 값은 무시된다
    Map<String, Object> rowWithFlag =
        Map.of("title", "리포트", "htmlContent", "<p>x</p>", "reportFilesMissing", true);
    ProactiveResult withFlag = mapper.convertValue(rowWithFlag, ProactiveResult.class);
    assertThat(withFlag.reportFilesMissing()).isFalse();
    assertThat(withFlag.htmlContent()).isEqualTo("<p>x</p>");

    Map<String, Object> legacyRow = Map.of("title", "리포트", "htmlContent", "<p>x</p>");
    ProactiveResult legacy = mapper.convertValue(legacyRow, ProactiveResult.class);
    assertThat(legacy.reportFilesMissing()).isFalse();
    assertThat(legacy.htmlContent()).isEqualTo("<p>x</p>");
  }

  /**
   * WD-765: 저장 실패 문구는 「서명이 아닌 거절(빈 결과) + reportFilesMissing=true」일 때만이다. 인증·한도 실패 서명으로 거절된 결과는 파일이
   * 없었어도 인증 문구가 우선이다(ai-agent 의 「인증·한도 신호가 늘 우선」 규칙과 같다). 사용자 문구에 오류 원문이 섞이지 않는다.
   */
  @Test
  void userFacingRejectionMessage_빈_결과이고_리포트_파일이_없을_때만_저장_실패_문구다() {
    // 빈 결과 + true → 저장 실패
    assertThat(
            ProactiveResultValidator.userFacingRejectionMessage(
                new ProactiveResult("t", List.of(), null, null, null, true)))
        .isEqualTo(ProactiveResultValidator.REPORT_NOT_SAVED_MESSAGE)
        .contains("리포트 파일을 저장하지 못해")
        .doesNotContain("인증");
    // 인증·한도 서명 + true → 인증 문구 우선
    assertThat(
            ProactiveResultValidator.userFacingRejectionMessage(
                new ProactiveResult(
                    "t", List.of(section("API Error: 429 rate limited")), null, null, null, true)))
        .isEqualTo(ProactiveResultValidator.USER_FACING_FAILURE_MESSAGE);
    assertThat(
            ProactiveResultValidator.userFacingRejectionMessage(
                new ProactiveResult("t", List.of(), null, null, "Failed to authenticate.", true)))
        .isEqualTo(ProactiveResultValidator.USER_FACING_FAILURE_MESSAGE);
    // 빈 결과 + false → 인증 문구
    assertThat(
            ProactiveResultValidator.userFacingRejectionMessage(
                new ProactiveResult("t", List.of(), null, null, null)))
        .isEqualTo(ProactiveResultValidator.USER_FACING_FAILURE_MESSAGE);
    // null → 인증 문구
    assertThat(ProactiveResultValidator.userFacingRejectionMessage(null))
        .isEqualTo(ProactiveResultValidator.USER_FACING_FAILURE_MESSAGE);
  }
}
