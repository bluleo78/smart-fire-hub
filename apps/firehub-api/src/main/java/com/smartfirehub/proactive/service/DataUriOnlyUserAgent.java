package com.smartfirehub.proactive.service;

import java.io.InputStream;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.xhtmlrenderer.pdf.ITextOutputDevice;
import org.xhtmlrenderer.pdf.ITextUserAgent;

/**
 * Flying Saucer 가 문서 안의 외부 자원을 가져오지 못하게 막는 UserAgent(WD-695).
 *
 * <p>PDF 폴백 경로의 섹션 본문은 사용자 프롬프트로 AI 가 만든 내용이다. 기본 UserAgent 는 {@code <img src>}·CSS {@code url()}·
 * {@code <link>} 의 절대 URL(http·https·file·jar·ftp …)을 서버에서 그대로 열기 때문에, 내부망 주소를 넣으면 서버가 대신 요청을 보내는
 * SSRF 가 된다.
 *
 * <p>Flying Saucer 9.4 에서 모든 자원 로딩(CSS·XML·바이너리·이미지)은 {@link #resolveAndOpenStream(String)} 을 거친다.
 * 예외는 {@code data:} 이미지로, {@link ITextUserAgent#getImageResource(String)} 가 스트림을 열기 전에 직접 디코딩한다(차트
 * 이미지가 이 길을 쓴다). 그래서 여기서 {@code data:} 가 아닌 URI 를 전부 {@code null}(=자원 없음)로 돌려주면 정당한 차트 이미지는 그대로
 * 그려지고 외부 요청은 하나도 나가지 않는다. {@code null} 은 기본 구현이 I/O 실패 때 쓰는 계약이라 호출부가 이미 견딘다.
 *
 * <p>폰트는 {@code FontResolver.addFont} 로, DOCTYPE 의 DTD 는 Flying Saucer 내장 엔티티 리졸버로 읽으므로 이 UserAgent
 * 를 거치지 않는다.
 */
@Slf4j
class DataUriOnlyUserAgent extends ITextUserAgent {

  DataUriOnlyUserAgent(ITextOutputDevice outputDevice, int dotsPerPixel) {
    super(outputDevice, dotsPerPixel);
  }

  /**
   * {@code data:} URI 만 기본 처리(내장 base64 폰트 디코딩)에 넘기고, 나머지는 열지 않는다.
   *
   * <p>상대 경로는 기본 URL 이 없으면 작업 디렉터리 기준 {@code file:} 로 풀리므로, 원문이 아니라 {@link #resolveURI(String)} 로 푼
   * 뒤의 스킴으로 판단한다.
   */
  @Override
  protected InputStream resolveAndOpenStream(String uri) {
    String resolved = resolveURI(uri);
    if (isDataUri(resolved)) {
      return super.resolveAndOpenStream(uri);
    }
    // URL 원문은 남기지 않는다 — 내부 주소·토큰이 로그로 새지 않게 스킴만 기록한다
    log.warn("PDF 렌더링: 외부 자원 로딩 차단 (scheme={})", schemeOf(resolved));
    return null;
  }

  /** 하위 클래스나 향후 버전이 resolveAndOpenStream 을 우회하더라도 실제 연결은 열리지 않도록 한 번 더 막는다. */
  @Override
  protected InputStream openStream(String uri) {
    log.warn("PDF 렌더링: 외부 자원 연결 차단 (scheme={})", schemeOf(uri));
    return null;
  }

  /** 대소문자 무시하고 {@code data:} 스킴인지 본다. */
  static boolean isDataUri(String uri) {
    return uri != null && uri.regionMatches(true, 0, "data:", 0, 5);
  }

  /** 로그용 스킴만 뽑는다(없으면 "none"). */
  private static String schemeOf(String uri) {
    if (uri == null) {
      return "none";
    }
    int idx = uri.indexOf(':');
    // 기본 로케일(터키어 등)에 따라 대소문자 변환이 달라지지 않도록 ROOT 로 고정한다(아키텍처 규칙)
    return idx > 0 && idx <= 10 ? uri.substring(0, idx).toLowerCase(Locale.ROOT) : "none";
  }
}
