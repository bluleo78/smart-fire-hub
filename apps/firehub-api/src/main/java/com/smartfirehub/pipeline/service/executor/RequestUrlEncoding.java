package com.smartfirehub.pipeline.service.executor;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 직접 URL 의 경로·쿼리를 인코딩해 Python 실행기(httpx)가 받아 주는 URL 을 Java 쪽도 받게 하는 유틸 (WD-936).
 *
 * <p>무엇을: 공백·한글·{@code " < > \ ^ ` { | } [ ]} 같이 URI 에 쓸 수 없는 글자만 {@code %XX} 로 바꾼다. ({@code ^ \ ` { } | [ ]} 은 httpx 가 그대로 보내는 경우도 있으나 Java URI·{@code build(true)} 가 날것으로 실을 수 없어 인코딩한다.) 경로·쿼리에 제어 문자(U+0000–U+001F·U+007F)가 있으면 httpx 가 거절하므로 원문을 돌려줘 기존 검사가 거절하게 한다. 이미 올바른 {@code
 * %XX} 와 URI 에 허용되는 글자는 그대로 두므로 결과에 다시 적용해도 같다(멱등).
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
    int authStart = m.end();
    int authEnd = url.length();
    for (int i = authStart; i < url.length(); i++) {
      char c = url.charAt(i);
      if (c == '/' || c == '?' || c == '#') {
        authEnd = i;
        break;
      }
    }
    StringBuilder sb = new StringBuilder(url.length() + 16);
    sb.append(url, 0, authEnd);

    int i = authEnd;
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
