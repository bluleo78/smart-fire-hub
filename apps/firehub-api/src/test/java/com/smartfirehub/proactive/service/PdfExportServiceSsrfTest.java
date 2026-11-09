package com.smartfirehub.proactive.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.proactive.dto.ProactiveResult;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.xhtmlrenderer.extend.UserAgentCallback;
import org.xhtmlrenderer.pdf.ITextRenderer;
import org.xhtmlrenderer.resource.CSSResource;

/**
 * Flying Saucer 폴백 경로의 SSRF 회귀 테스트(WD-695).
 *
 * <p>AI 가 만든 섹션 본문에 절대 URL 자원(img src·CSS url())이 들어가도 PDF 를 만드는 서버가 그 URL 로 요청을 보내면 안 된다. 로컬
 * HttpServer 를 띄워 요청이 한 건이라도 들어오는지 센다. Spring 컨텍스트 없이 서비스를 직접 조립해 빠르게 돈다.
 */
class PdfExportServiceSsrfTest {

  private HttpServer server;
  private final AtomicInteger hits = new AtomicInteger();
  private final List<String> hitPaths = Collections.synchronizedList(new ArrayList<>());
  private String base;

  /** 요청 수만 세고 바로 404 를 돌려주는 리스너 — 렌더러가 응답을 기다리며 멈추지 않게 한다. */
  @BeforeEach
  void startListener() throws Exception {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/",
        exchange -> {
          hits.incrementAndGet();
          hitPaths.add(exchange.getRequestURI().getPath());
          exchange.sendResponseHeaders(404, -1);
          exchange.close();
        });
    server.start();
    base = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @AfterEach
  void stopListener() {
    server.stop(0);
  }

  /** 운영과 같은 규칙(SpEL·classpath templates/·HTML 모드)으로 PDF 템플릿을 처리하는 엔진. */
  private static SpringTemplateEngine templateEngine() {
    ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
    resolver.setPrefix("templates/");
    resolver.setSuffix(".html");
    resolver.setTemplateMode(TemplateMode.HTML);
    resolver.setCharacterEncoding("UTF-8");
    SpringTemplateEngine engine = new SpringTemplateEngine();
    engine.setTemplateResolver(resolver);
    return engine;
  }

  /** htmlContent 가 없어 곧장 Flying Saucer 경로를 타는 결과. */
  private static ProactiveResult sectionsOnly(String content) {
    return new ProactiveResult(
        "SSRF 점검",
        List.of(new ProactiveResult.Section("s1", "본문", content, "text", null)),
        new ProactiveResult.Usage(1, 1, 2),
        null,
        null);
  }

  /**
   * 렌더러 층: 마크다운 변환을 건너뛰고 원시 XHTML 을 그대로 넣어도(=마크다운 방어가 뚫렸다고 가정) Flying Saucer 가 외부 URL 을 가져오지 않아야
   * 한다.
   */
  @Test
  void flyingSaucer_doesNotFetchExternalResources_evenWithRawHtml() {
    ReportRenderUtils utils = mock(ReportRenderUtils.class);
    List<Map<String, Object>> sections = new ArrayList<>();
    Map<String, Object> section = new HashMap<>();
    section.put("label", "본문");
    section.put(
        "content",
        "<p>내용<img src=\""
            + base
            + "/img.png\"/></p>"
            + "<div style=\"background:url("
            + base
            + "/bg.png); height:20px\">배경</div>"
            + "<link rel=\"stylesheet\" href=\""
            + base
            + "/link.css\"/>"
            + "<style>@import url("
            + base
            + "/import.css);</style>"
            + "<img src=\"file:///etc/hosts\"/>");
    sections.add(section);
    when(utils.buildTemplateSections(any())).thenReturn(sections);
    when(utils.renderChartImages(anyList())).thenReturn(List.of());

    PdfExportService service =
        new PdfExportService(templateEngine(), utils, "http://127.0.0.1:1", "t");

    byte[] pdf = service.generatePdf(sectionsOnly("ignored"), "job");

    assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
    assertThat(hitPaths).as("외부 URL 요청 경로").isEmpty();
    assertThat(hits.get()).isZero();
  }

  /** 끝단 간 층: 실제 마크다운 변환기를 거친 AI 본문(마크다운 이미지·원시 img)도 서버 요청을 만들지 않아야 한다. */
  @Test
  void flyingSaucer_withRealMarkdown_doesNotFetchImageUrls() {
    ReportRenderUtils utils = new ReportRenderUtils("http://127.0.0.1:1", "t", new ObjectMapper());
    PdfExportService service =
        new PdfExportService(templateEngine(), utils, "http://127.0.0.1:1", "t");

    String markdown =
        "# 제목\n\n**굵게** 본문\n\n![차트](" + base + "/md.png)\n\n<img src=\"" + base + "/raw.png\"/>\n";

    byte[] pdf = service.generatePdf(sectionsOnly(markdown), "job");

    assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
    assertThat(hitPaths).as("외부 URL 요청 경로").isEmpty();
    assertThat(hits.get()).isZero();
  }

  /** UserAgent 단위: http CSS·file 바이너리·jar 자원은 열지 않는다. */
  @Test
  void userAgent_returnsNothingForNonDataUris() {
    ITextRenderer renderer = PdfExportService.createRenderer();
    UserAgentCallback ua = renderer.getSharedContext().getUserAgentCallback();

    assertThat(ua).isInstanceOf(DataUriOnlyUserAgent.class);
    assertThat(ua.getBinaryResource("file:///etc/hosts")).isNull();
    assertThat(ua.getBinaryResource(base + "/font.ttf")).isNull();
    CSSResource css = ua.getCSSResource(base + "/s.css");
    assertThat(
            css.getResourceInputSource() == null
                || css.getResourceInputSource().getByteStream() == null)
        .as("CSS 스트림이 열리지 않아야 한다")
        .isTrue();
    assertThat(ua.getImageResource(base + "/i.png").getImage()).isNull();
    assertThat(hits.get()).isZero();
  }

  /** 정당한 자원(차트 data URI 이미지·한글 폰트)은 차단 후에도 그대로 PDF 에 들어가야 한다. */
  @Test
  void chartDataUriImage_andKoreanText_stillRender() throws Exception {
    ReportRenderUtils utils = mock(ReportRenderUtils.class);
    List<Map<String, Object>> sections = new ArrayList<>();
    Map<String, Object> section = new HashMap<>();
    section.put("label", "차트");
    section.put("content", "<p>한글 본문</p>");
    section.put("chartDataUri", PNG_DATA_URI);
    sections.add(section);
    when(utils.buildTemplateSections(any())).thenReturn(sections);
    when(utils.renderChartImages(anyList())).thenReturn(List.of());

    PdfExportService service =
        new PdfExportService(templateEngine(), utils, "http://127.0.0.1:1", "t");
    byte[] pdf = service.generatePdf(sectionsOnly("ignored"), "job");

    try (PDDocument doc = PDDocument.load(pdf)) {
      assertThat(new PDFTextStripper().getText(doc)).contains("한글 본문");
      PDResources resources = doc.getPage(0).getResources();
      boolean hasImage = false;
      for (COSName name : resources.getXObjectNames()) {
        if (resources.getXObject(name) instanceof PDImageXObject) {
          hasImage = true;
        }
      }
      assertThat(hasImage).as("차트 data URI 이미지가 PDF 에 들어갔는지").isTrue();
    }
  }

  /** 1x1 PNG */
  private static final String PNG_DATA_URI =
      "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=";
}
