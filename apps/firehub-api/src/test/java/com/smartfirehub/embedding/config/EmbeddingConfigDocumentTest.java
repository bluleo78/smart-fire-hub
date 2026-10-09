package com.smartfirehub.embedding.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * embedding.config 문서 직렬화 형태({v:1, provider, model, baseUrl, dimension, secret:{apiKey}})를 고정한다.
 */
class EmbeddingConfigDocumentTest {

  @Test
  void roundTripKeepsAllFields() {
    String json =
        EmbeddingConfigDocument.toJson(
            EmbeddingProviderType.OPENAI,
            "text-embedding-3-small",
            "https://api.openai.com",
            1536,
            "iv:cipher",
            "SELF_HOSTED");
    assertThat(json).contains("\"v\":1").contains("\"secret\":{\"apiKey\":\"iv:cipher\"}");

    EmbeddingConfigDocument.Parsed p = EmbeddingConfigDocument.parse(json);
    assertThat(p.provider()).isEqualTo(EmbeddingProviderType.OPENAI);
    assertThat(p.model()).isEqualTo("text-embedding-3-small");
    assertThat(p.baseUrl()).isEqualTo("https://api.openai.com");
    assertThat(p.dimension()).isEqualTo(1536);
    assertThat(p.apiKeyCipher()).isEqualTo("iv:cipher");
    assertThat(p.hosting()).isEqualTo("SELF_HOSTED");
  }

  @Test
  void documentWithoutHostingParsesAsExternal() {
    // 호스팅 선언 이전에 저장된 문서 — 기본 외부(보수적).
    EmbeddingConfigDocument.Parsed p =
        EmbeddingConfigDocument.parse("{\"v\":1,\"provider\":\"OLLAMA\",\"model\":\"m\"}");
    assertThat(p.hosting()).isEqualTo("EXTERNAL");
  }

  @Test
  void unknownProviderIsRejected() {
    // VOYAGE 는 죽은 선택지라 목록에서 뺐다 — 손으로 넣은 행도 해석하지 않는다.
    assertThatThrownBy(
            () ->
                EmbeddingConfigDocument.parse("{\"v\":1,\"provider\":\"VOYAGE\",\"model\":\"m\"}"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("OLLAMA, OPENAI");
  }

  @Test
  void configToStringNeverPrintsApiKey() {
    EmbeddingConfig cfg =
        new EmbeddingConfig(
            EmbeddingProviderType.OPENAI, "m", "https://x", "sk-secret-value", 1536);
    assertThat(cfg.toString()).doesNotContain("sk-secret-value");
  }
}
