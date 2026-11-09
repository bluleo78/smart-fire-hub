package com.smartfirehub.proactive.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * 리포트 마크다운 변환의 안전성 테스트(WD-695).
 *
 * <p>변환 결과는 PDF·메일 템플릿에 th:utext 로 그대로 들어가므로 원시 HTML 은 이스케이프되고, 외부 이미지 주소는 남지 않아야 한다. 일반 마크다운 서식은
 * 예전처럼 그려져야 한다. Spring 없이 직접 만든다.
 */
class ReportRenderUtilsMarkdownSafetyTest {

  private final ReportRenderUtils utils =
      new ReportRenderUtils("http://127.0.0.1:1", "t", new ObjectMapper());

  /** 1x1 투명 PNG */
  private static final String PNG_DATA_URI =
      "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=";

  @Test
  void rawHtmlBlocksAndInline_areEscaped() {
    String html =
        utils.markdownToHtml(
            "<img src=\"http://127.0.0.1:9/x\"/>\n\n<script>alert(1)</script>\n\n"
                + "<iframe src=\"http://evil\"></iframe>\n\n문장 중 <link rel=\"stylesheet\" href=\"http://e/s.css\"/> 끝");

    assertThat(html).doesNotContain("<img").doesNotContain("<script").doesNotContain("<iframe");
    assertThat(html).doesNotContain("<link");
    assertThat(html).contains("&lt;script&gt;").contains("&lt;img").contains("&lt;iframe");
  }

  @Test
  void markdownImage_withExternalUrl_isReplacedByAltText() {
    String html = utils.markdownToHtml("앞 ![내부 차트](http://127.0.0.1:9/a.png \"t\") 뒤");

    assertThat(html).doesNotContain("<img").doesNotContain("127.0.0.1");
    assertThat(html).contains("내부 차트");
  }

  @Test
  void markdownImage_withFileOrSvgDataUrl_isDropped() {
    String html =
        utils.markdownToHtml(
            "![a](file:///etc/hosts) ![b](data:image/svg+xml;base64,PHN2Zy8+) ![c](//host/x.png)");

    assertThat(html).doesNotContain("<img").doesNotContain("file:").doesNotContain("svg");
  }

  @Test
  void markdownImage_withRasterDataUri_isKept() {
    String html = utils.markdownToHtml("![점](" + PNG_DATA_URI + ")");

    assertThat(html).contains("<img src=\"data:image/png;base64,").contains("alt=\"점\"");
  }

  @Test
  void altText_isHtmlEscaped() {
    String html = utils.markdownToHtml("![<b>x</b>](http://e/x.png)");

    assertThat(html).doesNotContain("<b>").doesNotContain("http://e");
  }

  @Test
  void javascriptLink_isSanitized_butLinkTextKept() {
    String html = utils.markdownToHtml("[클릭](javascript:alert(1))");

    assertThat(html).doesNotContain("javascript:").contains("클릭");
  }

  @Test
  void normalMarkdown_stillRenders() {
    String html =
        utils.markdownToHtml(
            "# 제목\n\n**굵게** 와 *기울임*\n\n- 하나\n- 둘\n\n[문서](https://example.com)\n\n"
                + "| A | B |\n|---|---|\n| 1 | 2 |\n");

    assertThat(html).contains("<h1>제목</h1>");
    assertThat(html).contains("<strong>굵게</strong>").contains("<em>기울임</em>");
    assertThat(html).contains("<ul>").contains("<li>하나</li>");
    // sanitizeUrls 가 rel="nofollow" 를 덧붙이므로 href 와 링크 글자만 본다
    assertThat(html).contains("href=\"https://example.com\"").contains(">문서</a>");
    assertThat(html).contains("<table>").contains("<td>1</td>");
  }
}
