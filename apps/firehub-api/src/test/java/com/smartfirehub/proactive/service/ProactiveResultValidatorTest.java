package com.smartfirehub.proactive.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.proactive.dto.ProactiveResult;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 프로액티브 실행 결과 발송 게이트 단위 검증 (이슈 #350).
 *
 * <p>핵심 경계 두 가지를 고정한다: (1) 인증 실패 원문이 리포트 본문으로 둔갑한 결과는 거부한다. (2) 장애를 *서술*하는 정상 리포트와
 * report.md만 생성된 리포트는 계속 통과시킨다(FAILED 회귀 방지).
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
}
