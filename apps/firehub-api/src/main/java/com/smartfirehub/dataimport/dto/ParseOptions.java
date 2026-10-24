package com.smartfirehub.dataimport.dto;

import java.io.Serializable;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * CSV 파싱 옵션. 사용자가 명시적으로 인코딩을 지정하지 않으면 {@code AUTO}로 두고 {@link #detectEncoding(byte[])}가 BOM과
 * CharsetDecoder strict 검증을 통해 결정한다.
 */
public record ParseOptions(
    String delimiter, // ",", "\t", ";", "|", or custom single char. Default ","
    String encoding, // "AUTO", "UTF-8", "UTF-16LE", "UTF-16BE", "EUC-KR", "CP949", "MS949". Default
    // "AUTO"
    boolean hasHeader, // Default true
    int skipRows // Rows to skip before header. Default 0
    ) implements Serializable {

  // MS949는 CP949의 표준 JVM 별칭이며 EUC-KR(부분집합)도 디코딩 가능하므로
  // 한국어 CSV의 AUTO 폴백 기본 라벨로 사용한다.
  // 순서가 고정된 List 로 둔다 — 오류 문구에 허용 값을 나열하므로 Set.of 처럼 실행마다 순서가 바뀌면 안 된다(WD-250).
  private static final java.util.List<String> ALLOWED_ENCODINGS =
      java.util.List.of("AUTO", "UTF-8", "UTF-16LE", "UTF-16BE", "EUC-KR", "CP949", "MS949");

  /** 허용하지 않는 인코딩일 때의 400 문구 — 사용자 입력은 되풀이하지 않고 고를 수 있는 값만 알려 준다(WD-250). */
  public static final String UNSUPPORTED_ENCODING_MESSAGE =
      "지원하지 않는 인코딩입니다. 사용할 수 있는 값: " + String.join(", ", ALLOWED_ENCODINGS);

  public ParseOptions {
    if (delimiter == null || delimiter.isEmpty()) delimiter = ",";
    if (encoding == null || encoding.isEmpty()) encoding = "AUTO";
    if (!ALLOWED_ENCODINGS.contains(encoding)) {
      throw new IllegalArgumentException(UNSUPPORTED_ENCODING_MESSAGE);
    }
  }

  public static ParseOptions defaults() {
    return new ParseOptions(",", "AUTO", true, 0);
  }

  /**
   * 바이트 배열로부터 인코딩을 감지한다.
   *
   * <p>우선순위:
   *
   * <ol>
   *   <li>UTF-8 BOM ({@code EF BB BF}) → UTF-8
   *   <li>UTF-16 LE/BE BOM → UTF-16LE / UTF-16BE
   *   <li>{@link CharsetDecoder}로 strict UTF-8 디코딩 성공 → UTF-8. {@code endOfInput=false}로 호출하므로 peek
   *       버퍼 끝에서 멀티바이트 시퀀스가 잘려 있어도 MALFORMED이 아닌 UNDERFLOW로 처리되어 유효 판정된다. (이전 구현은 truncation을
   *       invalid로 보고 EUC-KR로 잘못 폴백하던 회귀가 있었다 — #263)
   *   <li>그 외 → MS949 (CP949 표준 별칭, EUC-KR 호환)
   * </ol>
   */
  public static String detectEncoding(byte[] data) {
    if (data == null || data.length == 0) return "UTF-8";
    Bom bom = detectBom(data);
    if (bom != null) return bom.encoding();
    if (isStrictUtf8(data)) return "UTF-8";
    return "MS949";
  }

  /**
   * 파일 앞의 BOM(바이트 순서 표시) 판별 결과.
   *
   * @param encoding BOM 이 가리키는 실제 인코딩
   * @param length 본문 앞에서 건너뛸 BOM 바이트 수
   */
  public record Bom(String encoding, int length) {}

  /**
   * 데이터 앞의 BOM 을 판별한다(WD-245). 없으면 null.
   *
   * <p>엑셀 「CSV UTF-8」은 앞에 {@code EF BB BF} 를 붙인다. 이 바이트를 그대로 디코딩하면 첫 헤더가 U+FEFF 로 시작해 데이터셋 열과 매핑되지
   * 않으므로, CSV 리더는 이 결과로 BOM 을 건너뛰고 BOM 이 가리키는 인코딩으로 읽는다. 최소 앞 3바이트만 있으면 판별된다.
   */
  public static Bom detectBom(byte[] d) {
    if (d == null) return null;
    if (hasUtf8Bom(d)) return new Bom("UTF-8", 3);
    if (hasUtf16LeBom(d)) return new Bom("UTF-16LE", 2);
    if (hasUtf16BeBom(d)) return new Bom("UTF-16BE", 2);
    return null;
  }

  /** UTF-8 BOM (EF BB BF) 시작 여부. */
  private static boolean hasUtf8Bom(byte[] d) {
    return d.length >= 3 && (d[0] & 0xFF) == 0xEF && (d[1] & 0xFF) == 0xBB && (d[2] & 0xFF) == 0xBF;
  }

  /** UTF-16 LE BOM (FF FE) 시작 여부. */
  private static boolean hasUtf16LeBom(byte[] d) {
    return d.length >= 2 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xFE;
  }

  /** UTF-16 BE BOM (FE FF) 시작 여부. */
  private static boolean hasUtf16BeBom(byte[] d) {
    return d.length >= 2 && (d[0] & 0xFF) == 0xFE && (d[1] & 0xFF) == 0xFF;
  }

  /**
   * 표준 {@link CharsetDecoder}로 UTF-8 strict 디코딩 가능 여부를 판정한다.
   *
   * <p>핵심: {@code decode(in, out, false)}로 호출하여 마지막에 멀티바이트 시퀀스가 잘려 있어도 MALFORMED이 아닌 UNDERFLOW로
   * 처리되도록 한다 (peek 버퍼 경계 truncation 대응). 디코딩 도중 한 곳이라도 MALFORMED/UNMAPPABLE이면 UTF-8이 아니라고 판정한다.
   *
   * <p>ASCII-only 데이터도 strict 디코딩이 성공하므로 UTF-8로 판정된다 — 의도된 동작.
   */
  private static boolean isStrictUtf8(byte[] data) {
    CharsetDecoder decoder =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    ByteBuffer in = ByteBuffer.wrap(data);
    // UTF-8 → UTF-16 변환 시 char 개수는 항상 byte 개수 이하이므로
    // data.length 크기 CharBuffer면 OVERFLOW가 발생하지 않는다.
    CharBuffer out = CharBuffer.allocate(Math.max(16, data.length));
    CoderResult result = decoder.decode(in, out, false);
    return !result.isMalformed() && !result.isUnmappable();
  }
}
