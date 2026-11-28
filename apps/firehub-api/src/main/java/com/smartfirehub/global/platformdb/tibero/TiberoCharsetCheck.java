package com.smartfirehub.global.platformdb.tibero;

import com.smartfirehub.global.validation.TextRules;
import java.nio.charset.StandardCharsets;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Tibero DB 문자셋이 UTF-8 인지 실측으로 판정한다(WD-1182).
 *
 * <p>문자셋 이름({@code NLS_CHARACTERSET})을 읽는 대신 실제로 문자열을 DB 에 왕복시킨다. 이름은 AL32UTF8·UTF8 외에도 별칭이 있어 목록으로 판정하면
 * 어긋나기 쉽고, 우리가 걱정하는 것은 이름이 아니라 "특수 공백·한글이 '?' 로 접히는가" 이기 때문이다. 표본({@link #SAMPLE})은 이름 중복 검사가 다루는 앞뒤 특수 공백
 * 문자 전체와 한글 한 글자다. 왕복 결과가 같고 DB 가 센 바이트 수({@code LENGTHB})가 UTF-8 바이트 수와 같으면 UTF-8 로 본다 — 비 UTF-8 이면
 * 문자셋 밖 글자는 '?' 로 바뀌거나 바이트 수가 달라진다.
 *
 * <p>이 클래스는 판정만 한다. 어떻게 반응할지(경고만, 막지 않음)는 호출자({@link TiberoCharsetStartupCheck})가 정한다.
 */
public final class TiberoCharsetCheck {

  /** 판정 표본 — 앞뒤 특수 공백 문자 전체 + 한글. 비 UTF-8 Tibero 에서는 일부가 '?' 로 접힌다. */
  public static final String SAMPLE = TextRules.EDGE_SPACE_CHARS + "가";

  /** 표본을 왕복시키고 DB 쪽 바이트 수도 함께 읽는 질의. 두 자리 모두 SAMPLE 을 바인딩한다. */
  public static final String PROBE_SQL = "SELECT ?, LENGTHB(?) FROM DUAL";

  /** 판정 결과. */
  public enum Result {
    UTF8,
    NOT_UTF8,
    UNKNOWN
  }

  private TiberoCharsetCheck() {}

  /**
   * 왕복 문자열과 DB 바이트 수로 판정한다. 둘 중 하나라도 null 이면 알 수 없음, 왕복이 표본과 같고 바이트 수가 UTF-8 바이트 수와 같으면 UTF8, 아니면 NOT_UTF8.
   */
  public static Result judge(String roundTripped, Integer byteLength) {
    if (roundTripped == null || byteLength == null) {
      return Result.UNKNOWN;
    }
    boolean same = SAMPLE.equals(roundTripped);
    boolean bytesMatch = byteLength == SAMPLE.getBytes(StandardCharsets.UTF_8).length;
    return same && bytesMatch ? Result.UTF8 : Result.NOT_UTF8;
  }

  /** DB 에 표본을 왕복시켜 판정한다. 행이 없으면 UNKNOWN. 예외는 그대로 던진다 — 정책은 호출자가 정한다. */
  public static Result probe(JdbcTemplate jdbc) {
    Result result =
        jdbc.query(
            PROBE_SQL,
            rs -> {
              if (!rs.next()) {
                return Result.UNKNOWN;
              }
              String back = rs.getString(1);
              int bytes = rs.getInt(2);
              Integer byteLength = rs.wasNull() ? null : bytes;
              return judge(back, byteLength);
            },
            SAMPLE,
            SAMPLE);
    return result == null ? Result.UNKNOWN : result;
  }
}
