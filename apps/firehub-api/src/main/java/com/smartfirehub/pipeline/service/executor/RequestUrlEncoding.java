package com.smartfirehub.pipeline.service.executor;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 직접 URL 의 경로·쿼리를 인코딩해 Python 실행기(httpx)가 받아 주는 URL 을 Java 쪽도 받게 하는 유틸 (WD-936).
 *
 * <p>무엇을: 공백·한글·{@code " < > \ ^ ` { | } [ ]} 같이 URI 에 쓸 수 없는 글자만 {@code %XX} 로 바꾼다. ({@code ^ \ `
 * { } | [ ]} 은 httpx 가 그대로 보내는 경우도 있으나 Java URI·{@code build(true)} 가 날것으로 실을 수 없어 인코딩한다.) 경로·쿼리에
 * 제어 문자(U+0000–U+001F·U+007F)가 있으면 httpx 가 거절하므로 원문을 돌려줘 기존 검사가 거절하게 한다. 이미 올바른 {@code %XX} 와 URI 에
 * 허용되는 글자는 그대로 두므로 결과에 다시 적용해도 같다(멱등).
 *
 * <p>왜: 「테스트 호출」·Java 실행기는 공백·한글이 든 URL 을 URI 파싱 단계에서 거절하지만, 실행기는 받아 주므로 두 경로의 동작을 맞춘다.
 */
public final class RequestUrlEncoding {

  /** {@code <scheme>://} 접두. 이게 아니면 원문을 그대로 돌려준다(기존 검사가 거절). */
  private static final Pattern SCHEME = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*://");

  private static final char[] HEX = "0123456789ABCDEF".toCharArray();

  /** 경로·쿼리에서 인코딩하지 않는 ASCII 구두점(unreserved 의 기호 + reserved 중 쓰이는 것). */
  private static final String PASS_THROUGH = "-._~+&=/?:@!$'()*,;";

  private RequestUrlEncoding() {}

  /**
   * 스킴·authority 는 그대로 두고 경로·쿼리만 인코딩하며 프래그먼트({@code #} 부터 끝)는 버린다.
   *
   * @param url 사용자가 적은 URL (null 가능)
   * @return 인코딩된 URL. null 이면 null, {@code <scheme>://} 로 시작하지 않으면 원문
   */
  public static String encodePathAndQuery(String url) {
    if (url == null) {
      return null;
    }
    Matcher m = SCHEME.matcher(url);
    if (!m.find()) {
      return url;
    }
    // authority = "://" 뒤 첫 '/'·'?'·'#' 전까지. 한 글자도 바꾸지 않는다(호스트 검사는 기존 로직 몫).
    return encodeFrom(url, authorityEnd(url, m.end()));
  }

  /**
   * 리다이렉트 Location 같은 URI 참조(절대·{@code //authority}·상대 경로·{@code ?query})를 실행기(urljoin+httpx)처럼
   * 인코딩한다(WD-991).
   *
   * <p>무엇을: 스킴({@code <scheme>://})이 있으면 {@link #encodePathAndQuery} 와 같고, {@code //authority} 로
   * 시작하면 authority 뒤부터, 그 밖(상대 경로·{@code ?query})은 처음부터 경로·쿼리 규칙으로 인코딩한다. 프래그먼트·제어 문자·이미 있는 %XX 규칙은
   * 같다.
   *
   * <p>왜: Python 은 공백·한글이 든 Location 도 따라가는데 Java {@code RedirectLocations.resolve} 는 URI 문법 검사에서
   * 거절했다.
   *
   * @param reference Location 값 (null 가능)
   * @return 인코딩된 참조. null 이면 null
   */
  public static String encodeReference(String reference) {
    if (reference == null) {
      return null;
    }
    // 앞뒤의 C0 제어 문자·공백(U+0000–U+0020)은 Python urlsplit/WHATWG 처럼 먼저 뗀다 — 안쪽 제어 문자는 아래에서 그대로 거절된다.
    reference = stripC0AndSpace(reference);
    Matcher m = SCHEME.matcher(reference);
    int start = 0;
    if (m.find()) {
      start = authorityEnd(reference, m.end());
    } else if (reference.startsWith("//")) {
      start = authorityEnd(reference, 2);
    }
    return encodeFrom(reference, start);
  }

  /** 앞뒤의 U+0000–U+0020 을 뗀다(WD-991). */
  private static String stripC0AndSpace(String s) {
    int b = 0;
    int e = s.length();
    while (b < e && s.charAt(b) <= 0x20) b++;
    while (e > b && s.charAt(e - 1) <= 0x20) e--;
    return s.substring(b, e);
  }

  /** authority 의 끝 — {@code from} 부터 첫 '/'·'?'·'#' 위치(없으면 문자열 끝). */
  private static int authorityEnd(String url, int from) {
    for (int i = from; i < url.length(); i++) {
      char c = url.charAt(i);
      if (c == '/' || c == '?' || c == '#') {
        return i;
      }
    }
    return url.length();
  }

  /**
   * {@code start} 앞은 그대로 두고 그 뒤(경로·쿼리)만 인코딩한다 — {@link #encodePathAndQuery}·{@link
   * #encodeReference} 공용 루프(WD-991). 프래그먼트는 버리고, 제어 문자가 있으면 원문을 돌려준다.
   */
  private static String encodeFrom(String url, int start) {
    StringBuilder sb = new StringBuilder(url.length() + 16);
    sb.append(url, 0, start);

    int i = start;
    while (i < url.length()) {
      int cp = url.codePointAt(i);
      int len = Character.charCount(cp);
      if (cp == '#') {
        break; // 프래그먼트는 서버로 보내지 않으므로 버린다
      }
      if (cp <= 0x1F || cp == 0x7F) {
        // 제어 문자(U+0000–U+001F·U+007F)가 경로·쿼리(fragment 앞)에 있으면 실행기 httpx 가 InvalidURL 로 거절한다 —
        // 테스트 호출도 거절해야 일치하므로 원문을 그대로 돌려 기존 validateUrl 이 거절하게 한다(WD-936).
        return url;
      }
      if (cp >= Character.MIN_SURROGATE && cp <= Character.MAX_SURROGATE) {
        // 짝 없는 서로게이트는 UTF-8 로 옮길 수 없다 — getBytes 가 조용히 '?'(%3F)로 바꿔 다른 URL 을 부르게 되므로, 원문을 그대로 돌려
        // 이후 URI 조립(build)이 「요청 URL 을 만들 수 없습니다」로 거절하게 한다(WD-936 리뷰 후속). httpx 도 이 글자를 거절한다.
        return url;
      }
      if (cp == '%') {
        if (i + 2 < url.length() && isHex(url.charAt(i + 1)) && isHex(url.charAt(i + 2))) {
          sb.append(url, i, i + 3); // 올바른 %XX 는 대소문자 그대로 유지
          i += 3;
          continue;
        }
        sb.append("%25"); // 홀로 있는 % 는 리터럴이므로 %25
      } else if (isPassThrough(cp)) {
        sb.append((char) cp);
      } else {
        // 공백·금지 ASCII·비ASCII: UTF-8 바이트마다 %XX (대문자 16진)
        for (byte b : url.substring(i, i + len).getBytes(StandardCharsets.UTF_8)) {
          sb.append('%').append(HEX[(b >> 4) & 0xF]).append(HEX[b & 0xF]);
        }
      }
      i += len;
    }
    return sb.toString();
  }

  /**
   * 화면·AI 챗에 보일 URL 을 사람이 읽게 바꾼다 — 비ASCII 글자(한글 등)의 UTF-8 {@code %XX} 묶음만 글자로 되돌리고, ASCII
   * 이스케이프({@code %20}· {@code %2F}·{@code %3D} 등)는 그대로 둔다(WD-936 리뷰 후속).
   *
   * <p>왜: {@link #encodePathAndQuery} 뒤의 표시용 URL 은 한글 경로가 {@code %EA%B2%80…} 로 보여 읽기 어렵다. 브라우저
   * 주소창처럼 글자만 되돌리고, 뜻이 달라질 수 있는 ASCII 이스케이프(구분자 {@code / ? & =}·공백)는 보낸 그대로 보여 실제 요청과 어긋나지 않게 한다.
   * 올바른 UTF-8 이 아닌 묶음은 바꾸지 않는다.
   *
   * @param url 표시용 URL (null 가능)
   * @return 비ASCII 묶음만 되돌린 URL. null 이면 null
   */
  public static String decodeForDisplay(String url) {
    if (url == null || url.indexOf('%') < 0) {
      return url;
    }
    StringBuilder sb = new StringBuilder(url.length());
    int i = 0;
    while (i < url.length()) {
      // %XX 중 0x80 이상 바이트가 이어진 묶음을 모은다 — 한 글자의 UTF-8 바이트는 늘 이렇게 붙어 있다
      int runEnd = i;
      ByteArrayOutputStream run = new ByteArrayOutputStream();
      while (runEnd + 2 < url.length()
          && url.charAt(runEnd) == '%'
          && isHex(url.charAt(runEnd + 1))
          && isHex(url.charAt(runEnd + 2))) {
        int b = Integer.parseInt(url.substring(runEnd + 1, runEnd + 3), 16);
        if (b < 0x80) {
          break;
        }
        run.write(b);
        runEnd += 3;
      }
      if (runEnd == i) {
        sb.append(url.charAt(i));
        i++;
        continue;
      }
      try {
        // 엄격한 디코더 — 잘린 바이트·잘못된 UTF-8 이면 예외로 원문을 남긴다(대체 문자 �로 바꾸지 않는다)
        sb.append(
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(run.toByteArray())));
      } catch (CharacterCodingException e) {
        sb.append(url, i, runEnd);
      }
      i = runEnd;
    }
    return sb.toString();
  }

  /** 그대로 보낼 글자인지 — ASCII 영숫자와 {@link #PASS_THROUGH} 기호만. ASCII 밖은 UTF-8 %XX 로 보내야 해 false. */
  private static boolean isPassThrough(int cp) {
    if (cp >= 0x80) {
      return false;
    }
    return (cp >= 'A' && cp <= 'Z')
        || (cp >= 'a' && cp <= 'z')
        || (cp >= '0' && cp <= '9')
        || PASS_THROUGH.indexOf(cp) >= 0;
  }

  /** 16진 한 자리인지 — 이미 있는 %XX 를 다시 인코딩하지 않으려고(멱등) 판별한다. */
  private static boolean isHex(char c) {
    return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
  }
}
