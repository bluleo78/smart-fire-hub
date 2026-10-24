package com.smartfirehub.dbexecutor.security;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 스크립트 1회용 토큰 검증(WD-78, 스펙 §3.4·§5.2, Ruling 16). api {@code ScriptTokenIssuer} 가 서명한 토큰을 같은 키로
 * 확인한다.
 *
 * <p>형식: {@code v1.<tenantId>.<slot>.<epoch>.<expEpochSeconds>.<base64url(HMAC-SHA256(key,
 * "v1.<tenantId>.<slot>.<epoch>.<exp>"))>} — 서명은 패딩 없는 base64url 이다. 고정 벡터 {@code
 * script-token-vectors.json} 을 api 와 같이 읽어 두 쪽 형식이 갈라지지 않게 한다.
 *
 * <p>왜 엄격하게 읽는가: 숫자 칸은 앞자리 0·부호·공백 없는 십진수만 받는다. 서명이 원문 문자열을 덮으므로 다른 표기는 어차피 서명에서 걸리지만, 서명 계산 전에
 * 모양부터 막아 이상한 입력이 HMAC·숫자 변환까지 가지 않게 한다. 슬롯은 1~10 만 받는다(슬롯 계정 S1..S10).
 *
 * <p>여기서는 서명·만료·모양만 본다. 토큰의 세대(epoch)가 기술자의 {@code scriptTokenEpoch} 보다 낮은지, 본문의 테넌트·슬롯이 토큰과 같은지는
 * 기술자·본문이 있어야 해서 {@code ExecuteController} 가 확인한다. 토큰·키는 어디에도 로그하지 않는다.
 */
public final class ScriptToken {

  /** 필터가 검증한 토큰 값을 컨트롤러에 넘기는 요청 속성 이름. */
  public static final String ATTRIBUTE = ScriptToken.class.getName() + ".claims";

  private static final String VERSION = "v1";

  /** 서명 키 최소 길이(UTF-8 바이트). HMAC-SHA256 출력 길이와 같게 둔다 — 짧은 키는 추측·전수 대입에 약하다(api 발급기와 같은 규칙). */
  public static final int MIN_KEY_BYTES = 32;

  /** 토큰 길이 상한 — 정상 토큰은 100자 안팎이다. 지나치게 긴 입력은 서명 계산 전에 버린다. */
  private static final int MAX_LENGTH = 200;

  private static final Pattern POSITIVE = Pattern.compile("[1-9][0-9]{0,18}");
  private static final Pattern NON_NEGATIVE = Pattern.compile("0|[1-9][0-9]{0,18}");
  private static final Pattern SLOT = Pattern.compile("[1-9]|10");

  /** HMAC-SHA256(32바이트)의 패딩 없는 base64url 은 정확히 43자다. */
  private static final Pattern SIGNATURE = Pattern.compile("[A-Za-z0-9_-]{43}");

  private ScriptToken() {}

  /**
   * 검증된 토큰 값.
   *
   * @param tenantId 토큰이 가리키는 테넌트 — 요청 본문 값 대신 이 값을 쓴다
   * @param slot 읽기 슬롯(1~10)
   * @param epoch 발급 시점의 테넌트 스크립트 토큰 세대 번호
   * @param expiresAt 만료 시각(epoch 초)
   */
  public record Claims(long tenantId, int slot, long epoch, long expiresAt) {}

  /**
   * 토큰을 검증한다. 하나라도 어긋나면 null(호출자는 401) — 어느 단계에서 실패했는지 돌려주지 않는다(추측 단서를 줄이기 위해).
   *
   * @param token {@code Authorization: Script } 뒤의 값
   * @param key 서명 키({@code app.script-token-key}). 비어 있거나 {@link #MIN_KEY_BYTES} 바이트보다 짧으면 스크립트 자격을
   *     열지 않는다(언제나 null, fail-closed)
   * @param nowEpochSeconds 현재 시각(epoch 초) — {@code expiresAt <= now} 면 만료
   */
  public static Claims verify(String token, String key, long nowEpochSeconds) {
    if (token == null
        || key == null
        || key.isBlank()
        || key.getBytes(StandardCharsets.UTF_8).length < MIN_KEY_BYTES
        || token.length() > MAX_LENGTH) {
      return null;
    }
    String[] parts = token.split("\\.", -1);
    if (parts.length != 6
        || !VERSION.equals(parts[0])
        || !POSITIVE.matcher(parts[1]).matches()
        || !SLOT.matcher(parts[2]).matches()
        || !NON_NEGATIVE.matcher(parts[3]).matches()
        || !POSITIVE.matcher(parts[4]).matches()
        || !SIGNATURE.matcher(parts[5]).matches()) {
      return null;
    }
    long tenantId;
    long epoch;
    long exp;
    try {
      tenantId = Long.parseLong(parts[1]);
      epoch = Long.parseLong(parts[3]);
      exp = Long.parseLong(parts[4]);
    } catch (NumberFormatException e) {
      // 19자리라도 long 범위를 넘을 수 있다(예: 99999999999999999999 는 패턴에서 이미 막히지만 9223372036854775808 같은 값).
      return null;
    }
    String signed = String.join(".", parts[0], parts[1], parts[2], parts[3], parts[4]);
    String expected = sign(key, signed);
    // 상수 시간 비교 — 응답 시간으로 서명을 한 글자씩 맞춰 가지 못하게.
    if (!MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.US_ASCII),
        parts[5].getBytes(StandardCharsets.US_ASCII))) {
      return null;
    }
    if (exp <= nowEpochSeconds) {
      return null;
    }
    return new Claims(tenantId, Integer.parseInt(parts[2]), epoch, exp);
  }

  /** {@code base64url(HMAC-SHA256(key, message))}, 패딩 없음 — api 발급기와 같은 계산. */
  static String sign(String key, String message) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] digest = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new IllegalStateException("HMAC-SHA256 을 쓸 수 없습니다", e);
    }
  }
}
