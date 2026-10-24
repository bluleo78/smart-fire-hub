package com.smartfirehub.pipeline.service.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * 스크립트 1회용 토큰 발급(WD-78, Ruling 16). db-executor 와 공유하는 고정 벡터({@code script-token-vectors.json})를 같은
 * 키·시각으로 정확히 만들어 두 쪽 형식(패딩 없는 base64url 포함)을 고정한다. 키가 비면 발급 시점에 실패한다(fail-closed).
 */
class ScriptTokenIssuerTest {

  private static final JsonNode FIXTURE = load();

  /** 32바이트 이상의 테스트 키 두 개. */
  private static final String KEY_A = "issuer-test-key-a-0123456789abcdef";

  private static final String KEY_B = "issuer-test-key-b-0123456789abcdef";

  private static JsonNode load() {
    try {
      return new ObjectMapper()
          .readTree(Path.of("src/test/resources/fixtures/script-token-vectors.json").toFile());
    } catch (Exception e) {
      throw new IllegalStateException("스크립트 토큰 벡터를 읽지 못했다", e);
    }
  }

  private static ScriptTokenIssuer issuer(String key, long nowEpochSeconds) {
    return new ScriptTokenIssuer(
        key, Clock.fixed(Instant.ofEpochSecond(nowEpochSeconds), ZoneOffset.UTC));
  }

  @Test
  void issue_matchesSharedVectors() {
    String key = FIXTURE.get("key").asText();
    for (JsonNode v : FIXTURE.get("vectors")) {
      String token =
          issuer(key, v.get("issuedAtEpochSeconds").asLong())
              .issue(
                  v.get("tenantId").asLong(),
                  v.get("slot").asInt(),
                  v.get("epoch").asLong(),
                  Duration.ofSeconds(v.get("ttlSeconds").asLong()));
      assertThat(token).isEqualTo(v.get("token").asText());
    }
  }

  @Test
  void issue_differsByTenantSlotEpochAndKey() {
    ScriptTokenIssuer a = issuer(KEY_A, 1_000);
    String base = a.issue(7, 3, 2, Duration.ofMinutes(10));
    assertThat(a.issue(8, 3, 2, Duration.ofMinutes(10))).isNotEqualTo(base);
    assertThat(a.issue(7, 4, 2, Duration.ofMinutes(10))).isNotEqualTo(base);
    assertThat(a.issue(7, 3, 3, Duration.ofMinutes(10))).isNotEqualTo(base);
    String otherKey = issuer(KEY_B, 1_000).issue(7, 3, 2, Duration.ofMinutes(10));
    assertThat(otherKey.substring(otherKey.lastIndexOf('.')))
        .isNotEqualTo(base.substring(base.lastIndexOf('.')));
    assertThat(base).startsWith("v1.7.3.2.1600.");
  }

  /** 키가 비면 발급하지 않는다 — 빈 키 HMAC 은 누구나 만들 수 있다. 빈 앱 기동은 막지 않는다(PG 배포에는 키가 없다). */
  @Test
  void emptyKey_throwsOnIssue_notOnConstruction() {
    for (String key : new String[] {"", "   ", null}) {
      ScriptTokenIssuer i = issuer(key, 1_000);
      assertThatThrownBy(() -> i.issue(7, 3, 0, Duration.ofMinutes(5)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("app.db-executor.script-token-key");
    }
  }

  @Test
  void invalidArguments_rejected() {
    ScriptTokenIssuer i = issuer(KEY_A, 1_000);
    assertThatThrownBy(() -> i.issue(0, 3, 0, Duration.ofMinutes(5)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> i.issue(7, 0, 0, Duration.ofMinutes(5)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> i.issue(7, 11, 0, Duration.ofMinutes(5)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> i.issue(7, 3, -1, Duration.ofMinutes(5)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> i.issue(7, 3, 0, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> i.issue(7, 3, 0, null)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void toString_hidesKey() {
    assertThat(issuer(KEY_A, 1_000).toString()).doesNotContain(KEY_A);
  }

  /** 키는 32바이트(UTF-8) 이상이어야 한다 — 짧으면 발급하지 않는다(db-executor 검증기와 같은 규칙, fail-closed). */
  @Test
  void shortKey_throwsOnIssue() {
    assertThatThrownBy(() -> issuer("k".repeat(31), 1_000).issue(7, 3, 0, Duration.ofMinutes(5)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("32");
    assertThat(issuer("k".repeat(32), 1_000).issue(7, 3, 0, Duration.ofMinutes(5)))
        .startsWith("v1.");
  }

  /**
   * 배포 환경변수 {@code DB_EXECUTOR_SCRIPT_TOKEN_KEY} 가 api {@code application.yml} 을 거쳐 발급기가 읽는 {@code
   * app.db-executor.script-token-key} 로 들어간다(리뷰 Important 2 확인). 값이 없으면 빈 문자열(발급 시 실패)이다.
   */
  @Test
  void applicationYml_mapsEnvVarToIssuerProperty() throws Exception {
    java.util.List<org.springframework.core.env.PropertySource<?>> yml =
        new org.springframework.boot.env.YamlPropertySourceLoader()
            .load(
                "application.yml",
                new org.springframework.core.io.FileSystemResource(
                    "src/main/resources/application.yml"));
    org.springframework.core.env.StandardEnvironment env =
        new org.springframework.core.env.StandardEnvironment();
    yml.forEach(env.getPropertySources()::addLast);
    assertThat(env.getProperty("app.db-executor.script-token-key")).isEmpty();

    env.getPropertySources()
        .addFirst(
            new org.springframework.core.env.MapPropertySource(
                "deploy-env", java.util.Map.of("DB_EXECUTOR_SCRIPT_TOKEN_KEY", KEY_B)));
    assertThat(env.getProperty("app.db-executor.script-token-key")).isEqualTo(KEY_B);
  }
}
