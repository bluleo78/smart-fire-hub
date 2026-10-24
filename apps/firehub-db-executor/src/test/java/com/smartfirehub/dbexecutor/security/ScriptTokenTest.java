package com.smartfirehub.dbexecutor.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 스크립트 1회용 토큰 검증(WD-78, Ruling 16). api 와 공유하는 고정 벡터({@code script-token-vectors.json})로 서명 형식이 두
 * 쪽에서 같음을 고정하고, 변조·만료·형식 오류·빈 키는 모두 거부되는지 본다.
 */
class ScriptTokenTest {

  private static final JsonNode FIXTURE = load();
  private static final String KEY = FIXTURE.get("key").asText();

  private static JsonNode load() {
    try {
      return new ObjectMapper()
          .readTree(
              Path.of("../firehub-api/src/test/resources/fixtures/script-token-vectors.json")
                  .toFile());
    } catch (Exception e) {
      throw new IllegalStateException("스크립트 토큰 벡터를 읽지 못했다", e);
    }
  }

  private static JsonNode first() {
    return FIXTURE.get("vectors").get(0);
  }

  @Test
  void vectors_verifyAndYieldSignedClaims() {
    for (JsonNode v : FIXTURE.get("vectors")) {
      long exp = v.get("expEpochSeconds").asLong();
      ScriptToken.Claims c = ScriptToken.verify(v.get("token").asText(), KEY, exp - 1);
      assertThat(c).as(v.get("token").asText()).isNotNull();
      assertThat(c.tenantId()).isEqualTo(v.get("tenantId").asLong());
      assertThat(c.slot()).isEqualTo(v.get("slot").asInt());
      assertThat(c.epoch()).isEqualTo(v.get("epoch").asLong());
      assertThat(c.expiresAt()).isEqualTo(exp);
    }
  }

  @Test
  void expired_atOrAfterExp_rejected() {
    String token = first().get("token").asText();
    long exp = first().get("expEpochSeconds").asLong();
    assertThat(ScriptToken.verify(token, KEY, exp)).isNull();
    assertThat(ScriptToken.verify(token, KEY, exp + 3600)).isNull();
  }

  @Test
  void wrongKey_orEmptyKey_rejected() {
    String token = first().get("token").asText();
    long now = first().get("expEpochSeconds").asLong() - 1;
    assertThat(ScriptToken.verify(token, "other-key", now)).isNull();
    assertThat(ScriptToken.verify(token, "", now)).isNull();
    assertThat(ScriptToken.verify(token, "   ", now)).isNull();
    assertThat(ScriptToken.verify(token, null, now)).isNull();
  }

  /** 서명 대상 칸(테넌트·슬롯·세대·만료)을 하나라도 바꾸면 서명이 맞지 않는다 — 다른 테넌트·슬롯·세대로 바꿔 쓰지 못한다. */
  @Test
  void tamperedFields_rejected() {
    String token = first().get("token").asText(); // v1.7.3.2.1700000000.<sig>
    long now = first().get("expEpochSeconds").asLong() - 1;
    String sig = token.substring(token.lastIndexOf('.') + 1);
    for (String forged :
        new String[] {
          "v1.8.3.2.1700000000." + sig, // 다른 테넌트
          "v1.7.4.2.1700000000." + sig, // 다른 슬롯
          "v1.7.3.3.1700000000." + sig, // 다른 세대
          "v1.7.3.2.1800000000." + sig, // 만료 연장
          "v2.7.3.2.1700000000." + sig, // 다른 버전
          token.substring(0, token.length() - 1) + (token.endsWith("A") ? "B" : "A"), // 서명 변조
        }) {
      assertThat(ScriptToken.verify(forged, KEY, now)).as(forged).isNull();
    }
  }

  @Test
  void malformed_rejected() {
    long now = 1;
    String sig = "RH248H7oXbuOkEw2d2Hswv3q3nU2k5UFS4g_cu5I69k";
    for (String bad :
        new String[] {
          "",
          "v1",
          "v1.7.3.2.1700000000",
          "v1.7.3.2.1700000000." + sig + ".x",
          "v1.07.3.2.1700000000." + sig, // 앞자리 0
          "v1.-7.3.2.1700000000." + sig,
          "v1.0.3.2.1700000000." + sig, // 테넌트 0
          "v1.7.0.2.1700000000." + sig, // 슬롯 0
          "v1.7.11.2.1700000000." + sig, // 슬롯 11
          "v1.7.3.-1.1700000000." + sig,
          "v1.7.3.2.abc." + sig,
          "v1.99999999999999999999.3.2.1700000000." + sig, // long 범위 밖
          "v1.7.3.2.1700000000." + sig + "=", // 패딩
          "v1.7.3.2.1700000000.", // 빈 서명
          "v1.7.3.2.1700000000." + "x".repeat(400),
        }) {
      assertThat(ScriptToken.verify(bad, KEY, now)).as(bad).isNull();
    }
    assertThat(ScriptToken.verify(null, KEY, now)).isNull();
  }

  @Test
  void claims_toStringHasNoSecret() {
    ScriptToken.Claims c = ScriptToken.verify(first().get("token").asText(), KEY, 1);
    assertThat(c.toString()).doesNotContain(KEY).doesNotContain("RH248H7o");
  }

  /** 공유 벡터의 rejected: 같은 키로 올바르게 서명됐어도 거부한다(19자리 long 넘침 — 숫자 변환 단계에서). */
  @Test
  void sharedRejectedVectors_rejected() {
    int checked = 0;
    for (JsonNode v : FIXTURE.get("rejected")) {
      assertThat(
              ScriptToken.verify(
                  v.get("token").asText(), KEY, v.get("verifyAtEpochSeconds").asLong()))
          .as(v.get("reason").asText())
          .isNull();
      checked++;
    }
    assertThat(checked).isGreaterThanOrEqualTo(1);
  }

  /** 넘침 토큰은 숫자 모양 검사(19자리)는 통과한다 — 거부가 숫자 변환(NumberFormatException) 분기에서 일어남을 보장하는 전제. */
  @Test
  void overflowTenant_passesShapeButFailsParse() {
    String overflow = "9223372036854775808";
    assertThat(overflow).hasSize(19).matches("[1-9][0-9]{0,18}");
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> Long.parseLong(overflow))
        .isInstanceOf(NumberFormatException.class);
  }

  /** 키는 32바이트 이상이어야 한다 — 짧은 키로 서명한 토큰은 서명이 맞아도 거부한다(fail-closed). */
  @Test
  void shortKey_rejectedEvenWithMatchingSignature() {
    String shortKey = "k".repeat(31);
    String token = signed(shortKey, "v1.7.3.2.1700000000");
    assertThat(ScriptToken.verify(token, shortKey, 1)).isNull();
    String okKey = "k".repeat(32);
    assertThat(ScriptToken.verify(signed(okKey, "v1.7.3.2.1700000000"), okKey, 1)).isNotNull();
    // 바이트 기준: 한글 11자 = 33바이트(UTF-8)라 통과한다.
    String koreanKey = "가".repeat(11);
    assertThat(ScriptToken.verify(signed(koreanKey, "v1.7.3.2.1700000000"), koreanKey, 1))
        .isNotNull();
  }

  private static String signed(String key, String message) {
    return message + "." + ScriptToken.sign(key, message);
  }
}
