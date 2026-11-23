package com.smartfirehub.pipeline.service.executor;

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
   * 이스케이프({@code %20}· {@code %2F}·{@code %3D} 등)는 그대로 둔다(WD-936 리뷰 후속, WD-1000 보강).
   *
   * <p>왜: {@link #encodePathAndQuery} 뒤의 표시용 URL 은 한글 경로가 {@code %EA%B2%80…} 로 보여 읽기 어렵다. 브라우저
   * 주소창처럼 글자만 되돌리고, 뜻이 달라질 수 있는 ASCII 이스케이프(구분자 {@code / ? & =}·공백)는 보낸 그대로 보여 실제 요청과 어긋나지 않게 한다.
   *
   * <p>WD-1000 규칙: (1) UTF-8 을 한 글자씩 해석한다 — 잘못된 바이트(고아 연속 바이트·잘린 묶음·과잉 길이·서로게이트·U+10FFFF 초과)는 그
   * 바이트만 인코딩으로 두고 앞뒤 정상 글자는 푼다. (2) 방향 제어·제로폭 같은 보이지 않는 글자(FORMAT)·줄·문단·공백류 구분자·제어(C1 포함)·사적 사용·미할당
   * 글자는 풀면 화면에서 사라지거나 순서·모양이 속이므로 입력의 {@code %XX} 원문(대소문자 포함)을 그대로 남긴다. (3) 그 밖에도 남기는 글자: 오른쪽→왼쪽 방향
   * 글자(히브리·아랍 등, 주변의 {@code /}·숫자 순서를 뒤섞음), 감싸는 표식(ENCLOSING_MARK), 몽골 변형 선택자·모음 구분자(U+180B–180F)와
   * 크메르 보이지 않는 모음(U+17B4–17B5), 그리고 ASCII 글자 바로 뒤에 오는 결합 표식(U+0338 처럼 {@code /}·글자 위에 덧씌워 모양을 바꿈).
   * 비ASCII 글자 뒤의 결합 표식(NFD 한글·악센트)은 풀린다. 이 규칙 밖의 모든 모양 속임을 막는다고 보장하지는 않는다.
   *
   * @param url 표시용 URL (null 가능)
   * @return 보이는 비ASCII 글자만 되돌린 URL. null 이면 null
   */
  public static String decodeForDisplay(String url) {
    if (url == null || url.indexOf('%') < 0) {
      return url;
    }
    StringBuilder sb = new StringBuilder(url.length());
    int i = 0;
    while (i < url.length()) {
      int lead = hexByteAt(url, i);
      if (lead < 0x80) {
        // % 가 아니거나 깨진 이스케이프(-1)·ASCII 이스케이프(<0x80)는 한 글자 그대로 복사한다 — ASCII 는 풀지 않는다
        sb.append(url.charAt(i));
        i++;
        continue;
      }
      int n = utf8Length(lead);
      int cp = n == 0 ? -1 : decodeSequence(url, i, n, lead);
      if (cp < 0) {
        // 잘못된 UTF-8: 선두 %XX 한 바이트만 원문으로 두고 다음 위치부터 다시 본다(뒤의 정상 글자는 살린다)
        sb.append(url, i, i + 3);
        i += 3;
        continue;
      }
      if (isHiddenType(cp) || overlaysAscii(cp, sb)) {
        sb.append(url, i, i + 3 * n); // 보이지 않거나 모양을 속이는 글자는 원문 %XX 그대로
      } else {
        sb.appendCodePoint(cp);
      }
      i += 3 * n;
    }
    return sb.toString();
  }

  /**
   * 화면·AI 챗에 보일 URL 을 만든다 — 날것의 숨은 글자까지 {@link #encodePathAndQuery} 로 인코딩한 뒤 {@link #decodeForDisplay} 로 보이는
   * 글자만 되돌린다(WD-1000). 미리보기·연결 테스트·실행 기록이 같은 표시 규칙을 쓰게 한 곳에 모은다. 이미 인코딩된 URL 에 써도 같다(멱등).
   *
   * @param url 사용자 정보·인증 키를 이미 가린 URL (null 가능)
   * @return 표시용 URL. null 이면 null
   */
  public static String toDisplayUrl(String url) {
    return decodeForDisplay(encodePathAndQuery(url));
  }

  /** 결합 표식이 바로 앞 출력 ASCII 글자에 덧씌워지는지 — {@code /}·글자 모양을 바꾸므로 원문을 남긴다. 비ASCII 글자 뒤(NFD 한글 등)는 푼다. */
  private static boolean overlaysAscii(int cp, StringBuilder sb) {
    return Character.getType(cp) == Character.NON_SPACING_MARK
        && sb.length() > 0
        && sb.charAt(sb.length() - 1) < 0x80;
  }

  /** {@code url} 의 i 위치가 올바른 {@code %XX} 면 그 바이트 값(0–255), 아니면 -1. */
  private static int hexByteAt(String url, int i) {
    if (i + 2 < url.length()
        && url.charAt(i) == '%'
        && isHex(url.charAt(i + 1))
        && isHex(url.charAt(i + 2))) {
      return (Character.digit(url.charAt(i + 1), 16) << 4) | Character.digit(url.charAt(i + 2), 16);
    }
    return -1;
  }

  /** UTF-8 선두 바이트가 기대하는 전체 바이트 수. 선두가 될 수 없는 바이트(C0·C1·F5 이상·연속 바이트)는 0. */
  private static int utf8Length(int lead) {
    if (lead >= 0xC2 && lead <= 0xDF) {
      return 2;
    }
    if (lead >= 0xE0 && lead <= 0xEF) {
      return 3;
    }
    if (lead >= 0xF0 && lead <= 0xF4) {
      return 4;
    }
    return 0;
  }

  /**
   * i 위치부터 n 바이트({@code %XX} n 개)를 UTF-8 코드 포인트로 조립한다. 뒤따르는 바이트가 {@code %XX} 가 아니거나 0x80–0xBF 가
   * 아니면, 과잉 길이·서로게이트·U+10FFFF 초과면 -1.
   */
  private static int decodeSequence(String url, int i, int n, int lead) {
    int cp = lead & (0xFF >> (n + 1)); // 선두 바이트의 데이터 비트
    for (int k = 1; k < n; k++) {
      int b = hexByteAt(url, i + 3 * k);
      if (b < 0x80 || b > 0xBF) {
        return -1;
      }
      cp = (cp << 6) | (b & 0x3F);
    }
    int min = n == 2 ? 0x80 : n == 3 ? 0x800 : 0x10000;
    if (cp < min || cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) {
      return -1; // 과잉 길이·범위 밖·서로게이트
    }
    return cp;
  }

  /** 화면에서 보이지 않거나 모양·순서를 속일 수 있어 인코딩으로 남길 글자 종류인지. */
  private static boolean isHiddenType(int cp) {
    // 한글 채움(U+115F·1160·3164·FFA0)·점자 빈칸(U+2800)·결합 자소 결합자(U+034F)·변형 선택자는
    // 범주가 글자/기호/결합 표식이라 위 범주 검사에 안 걸리지만 화면에선 비어 보여 표시 URL 을 속일 수 있다
    // (WD-1000 최종 리뷰). 그 밖의 NON_SPACING_MARK(NFD 한글·악센트)는 풀어야 하므로 제외하지 않는다.
    if (cp == 0x115F
        || cp == 0x1160
        || cp == 0x3164
        || cp == 0xFFA0
        || cp == 0x2800
        || cp == 0x034F
        || (cp >= 0xFE00 && cp <= 0xFE0F)
        || (cp >= 0xE0100 && cp <= 0xE01EF)) {
      return true;
    }
    // 방향이 오른쪽→왼쪽인 글자는 주변 / 와 숫자의 표시 순서를 바꾼다. 감싸는 표식·몽골·크메르 보이지 않는 글자도 남긴다.
    byte dir = Character.getDirectionality(cp);
    if (dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT
        || dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
        || (cp >= 0x180B && cp <= 0x180F)
        || (cp >= 0x17B4 && cp <= 0x17B5)) {
      return true;
    }
    switch (Character.getType(cp)) {
      case Character.ENCLOSING_MARK:
      case Character.FORMAT:
      case Character.LINE_SEPARATOR:
      case Character.PARAGRAPH_SEPARATOR:
      case Character.SPACE_SEPARATOR:
      case Character.CONTROL:
      case Character.PRIVATE_USE:
      case Character.UNASSIGNED:
        return true;
      default:
        return false;
    }
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
