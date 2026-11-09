package com.smartfirehub.proactive.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Code;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Image;
import org.commonmark.node.Node;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.Text;
import org.commonmark.renderer.NodeRenderer;
import org.commonmark.renderer.html.HtmlNodeRendererContext;
import org.commonmark.renderer.html.HtmlWriter;

/**
 * 마크다운 이미지({@code ![alt](url)})를 안전하게 그리는 commonmark 노드 렌더러(WD-695).
 *
 * <p>리포트 본문은 AI 가 만든 마크다운이다. 그대로 {@code <img src="http://…">} 로 내보내면 PDF(Flying Saucer)를 만드는 서버나
 * 메일을 여는 클라이언트가 그 주소로 요청을 보낸다(SSRF·추적 픽셀). 그래서 래스터 {@code data:image/…} 만 이미지로 남기고, 그 밖의 주소는 이미지를
 * 버리고 대체 텍스트만 글자로 찍는다. SVG 는 안에 외부 참조를 품을 수 있어 허용하지 않는다.
 */
class DataImageOnlyNodeRenderer implements NodeRenderer {

  /** 허용하는 이미지 주소 — 래스터 형식의 data URI 만. */
  private static final Pattern ALLOWED_IMAGE_SRC =
      Pattern.compile(
          "^data:image/(png|jpe?g|gif|webp);base64,[A-Za-z0-9+/=\\s]+$", Pattern.CASE_INSENSITIVE);

  private final HtmlNodeRendererContext context;
  private final HtmlWriter html;

  DataImageOnlyNodeRenderer(HtmlNodeRendererContext context) {
    this.context = context;
    this.html = context.getWriter();
  }

  @Override
  public Set<Class<? extends Node>> getNodeTypes() {
    return Set.of(Image.class);
  }

  /** 허용된 data 이미지만 {@code <img>} 로, 나머지는 대체 텍스트(이스케이프된 글자)로 그린다. */
  @Override
  public void render(Node node) {
    Image image = (Image) node;
    String alt = altText(image);
    String destination = image.getDestination();
    if (!isAllowedImageSrc(destination)) {
      // 외부 주소는 버리고 사람이 읽을 대체 텍스트만 남긴다 — text() 가 HTML 이스케이프한다
      html.text(alt);
      return;
    }
    Map<String, String> attrs = new LinkedHashMap<>();
    attrs.put("src", context.encodeUrl(destination));
    attrs.put("alt", alt);
    if (image.getTitle() != null) {
      attrs.put("title", image.getTitle());
    }
    html.tag("img", context.extendAttributes(image, "img", attrs), true);
  }

  /** 이미지 src 로 허용되는 값인지 — 래스터 data URI 만 true. */
  static boolean isAllowedImageSrc(String destination) {
    return destination != null && ALLOWED_IMAGE_SRC.matcher(destination.trim()).matches();
  }

  /** 이미지 노드 자식에서 대체 텍스트를 모은다(commonmark 기본 렌더러의 alt 처리와 같은 규칙). */
  private static String altText(Image image) {
    StringBuilder sb = new StringBuilder();
    image.accept(
        new AbstractVisitor() {
          @Override
          public void visit(Text text) {
            sb.append(text.getLiteral());
          }

          @Override
          public void visit(Code code) {
            sb.append(code.getLiteral());
          }

          @Override
          public void visit(SoftLineBreak softLineBreak) {
            sb.append('\n');
          }

          @Override
          public void visit(HardLineBreak hardLineBreak) {
            sb.append('\n');
          }
        });
    return sb.toString();
  }
}
