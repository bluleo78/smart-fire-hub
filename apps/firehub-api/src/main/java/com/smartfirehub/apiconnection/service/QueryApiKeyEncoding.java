package com.smartfirehub.apiconnection.service;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * 쿼리 파라미터로 보내는 API 키(API_KEY + placement=query)를 URL 쿼리에 실을 형태로 바꾸는 규칙(WD-182).
 *
 * <p><b>왜 필요한가.</b> 공공데이터포털은 같은 키를 인코딩 키({@code abc%2Bd%3D%3D})와 디코딩 키({@code abc+d==}) 두 형태로 준다.
 * 예전에는 경로마다 처리가 달라 「테스트」는 인코딩 키를 {@code %252B} 로 이중 인코딩하고, 미리보기·Java 실행기는 디코딩 키로 URL 을 만들지 못했으며,
 * Python 실행기는 인코딩 키를 이중 인코딩했다. 이제 연결 테스트·저장 전 테스트·10분 상태 점검·API_CALL 미리보기·Java 실행기가 모두 이 클래스 하나로 키를
 * 인코딩하고, Python 실행기({@code app/services/query_key_encoding.py})는 같은 규칙을 같은 사례 표({@code
 * fixtures/query-api-key-encoding-vectors.json})로 검증한다 — 한쪽만 바꾸면 어느 한쪽 테스트가 깨진다.
 *
 * <p><b>정적 쿼리 파라미터에도 같은 규칙(WD-200).</b> API_CALL 스텝의 「쿼리 파라미터」(이름·값)와 페이지네이션 파라미터도 {@link
 * AuthQueryMasker#build} 에서 이 규칙으로 인코딩한다. 예전에는 미리보기·Java 실행기가 값을 인코딩하지 않고(생 값은 거부) Python 실행기는
 * httpx 로 다시 인코딩해 {@code %2B} 가 {@code %252B} 로 나갔다. Python 쪽({@code _build_request_url})도 같은 함수로
 * 바꿔 미리보기와 실행이 같은 쿼리 문자열을 보낸다 — 공용 사례 표 {@code fixtures/query-param-encoding-vectors.json} 으로 함께
 * 검증한다.
 *
 * <p><b>규칙(방안 C, 자동 판별).</b>
 *
 * <ul>
 *   <li>값에 유효한 퍼센트 인코딩({@code %[0-9A-Fa-f]{2}})이 있으면 이미 인코딩된 키로 본다. 그 {@code %XX} 는 바이트 그대로(대소문자
 *       포함) 보존한다.
 *   <li>나머지 글자는 쿼리 컴포넌트 기준으로 한 번 인코딩한다 — RFC 3986 비예약 문자({@code A-Z a-z 0-9 - . _ ~})만 그대로 두고, 그 밖은
 *       UTF-8 바이트별 {@code %XX}(대문자 hex)로 바꾼다. 공백은 {@code %20}, {@code +} 는 {@code %2B}, {@code =} 는
 *       {@code %3D}, {@code /} 는 {@code %2F}, {@code &}·{@code #} 도 인코딩해 파라미터 주입·URL 잘림을 막는다.
 *   <li>유효하지 않은 {@code %}(뒤에 hex 두 자리가 아님)는 원문 {@code %} 로 보고 {@code %25} 로 인코딩한다.
 * </ul>
 *
 * <p>유효한 {@code %XX} 가 하나도 없는 키(디코딩 키)는 위 규칙이 곧 「전부 한 번 인코딩」이고, 인코딩 키는 「그대로」가 된다. 둘 다 섞인 키(예:
 * {@code %2B} 와 생 {@code =})는 이미 인코딩된 것으로 보되, {@code %XX} 는 보존하고 인코딩이 필요한 나머지 글자만 인코딩한다 — 생 {@code
 * =}·{@code &} 를 그대로 보내면 쿼리 구조가 깨지거나 서버가 다른 값으로 읽으므로, 어느 쪽으로 해석해도 URL 이 유효하게 남는 쪽을 택했다.
 *
 * <p><b>받아들인 한계.</b> 진짜 {@code %} 문자 뒤에 hex 두 자리가 오는 키(예: 디코딩 키 {@code a%41b})는 인코딩된 것으로 잘못 판정된다.
 * 공공 API 키에서는 드문 경우라 받아들였다.
 */
public final class QueryApiKeyEncoding {

  /** 유효한 퍼센트 인코딩 한 조각. 이것이 하나라도 있으면 「이미 인코딩됨」이다. */
  private static final Pattern PERCENT_ESCAPE = Pattern.compile("%[0-9A-Fa-f]{2}");

  private static final char[] HEX = "0123456789ABCDEF".toCharArray();

  private QueryApiKeyEncoding() {}

  /** 값에 유효한 퍼센트 인코딩({@code %XX})이 하나라도 있어 「이미 인코딩된 키」로 보는지. null 은 false. */
  public static boolean isPreEncoded(String value) {
    return value != null && PERCENT_ESCAPE.matcher(value).find();
  }

  /**
   * 키를 쿼리에 실을 형태로 바꾼다(클래스 설명의 규칙). 유효한 {@code %XX} 는 보존하고 비예약 문자가 아닌 나머지는 UTF-8 퍼센트 인코딩한다. 결과는 비예약
   * 문자와 {@code %XX} 로만 이뤄져 {@code UriComponentsBuilder.build(true)} 의 검사를 항상 통과한다.
   *
   * @param value 저장된 키 값(인코딩 키·디코딩 키 어느 쪽이든). null 이면 null
   */
  public static String encode(String value) {
    if (value == null) return null;
    StringBuilder out = new StringBuilder(value.length() + 16);
    int i = 0;
    int n = value.length();
    while (i < n) {
      char c = value.charAt(i);
      if (c == '%' && i + 2 < n && isHex(value.charAt(i + 1)) && isHex(value.charAt(i + 2))) {
        // 유효한 %XX — 이미 인코딩된 조각이므로 대소문자까지 그대로 보존한다(같은 바이트를 보내야 서버가 같은 키로 읽는다).
        out.append(value, i, i + 3);
        i += 3;
        continue;
      }
      if (isUnreserved(c)) {
        out.append(c);
        i++;
        continue;
      }
      // 그 밖의 글자(유효하지 않은 % 포함)는 한 코드 포인트를 UTF-8 바이트로 바꿔 바이트마다 %XX 로 쓴다.
      int cp = value.codePointAt(i);
      int len = Character.charCount(cp);
      byte[] bytes = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8);
      for (byte b : bytes) {
        out.append('%').append(HEX[(b >> 4) & 0x0F]).append(HEX[b & 0x0F]);
      }
      i += len;
    }
    return out.toString();
  }

  /** RFC 3986 비예약 문자(ASCII 영숫자와 {@code - . _ ~}). 한글 등 비 ASCII 글자는 여기에 들지 않는다. */
  private static boolean isUnreserved(char c) {
    return (c >= 'A' && c <= 'Z')
        || (c >= 'a' && c <= 'z')
        || (c >= '0' && c <= '9')
        || c == '-'
        || c == '.'
        || c == '_'
        || c == '~';
  }

  private static boolean isHex(char c) {
    return (c >= '0' && c <= '9') || (c >= 'A' && c <= 'F') || (c >= 'a' && c <= 'f');
  }
}
