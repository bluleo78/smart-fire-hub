package com.smartfirehub.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

/** OllamaEmbeddingProvider 단위 테스트 — MockWebServer로 /api/embed 응답을 모킹한다. */
class OllamaEmbeddingProviderTest {
  private MockWebServer server;

  @BeforeEach
  void setUp() throws Exception {
    server = new MockWebServer();
    server.start();
  }

  @AfterEach
  void tearDown() throws Exception {
    server.shutdown();
  }

  private OllamaEmbeddingProvider provider(int dimension) {
    WebClient client = WebClient.builder().baseUrl(server.url("/").toString()).build();
    return new OllamaEmbeddingProvider(client, "bge-m3", dimension);
  }

  @Test
  void embedReturnsVectorsInOrder() {
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody("{\"embeddings\":[[0.1,0.2],[0.3,0.4]]}"));
    List<float[]> out = provider(2).embed(List.of("a", "b"));
    assertThat(out).hasSize(2);
    assertThat(out.get(0)).containsExactly(0.1f, 0.2f);
    assertThat(out.get(1)).containsExactly(0.3f, 0.4f);
  }

  @Test
  void embedThrowsOnDimensionMismatch() {
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody("{\"embeddings\":[[0.1,0.2,0.3]]}"));
    assertThatThrownBy(() -> provider(2).embed(List.of("a")))
        .isInstanceOf(EmbeddingException.class)
        .hasMessageContaining("dimension");
  }

  @Test
  void embedThrowsOnRowCountMismatch() {
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody("{\"embeddings\":[[0.1,0.2]]}"));
    assertThatThrownBy(() -> provider(2).embed(List.of("a", "b")))
        .isInstanceOf(EmbeddingException.class)
        .hasMessageContaining("불일치");
  }

  @Test
  void embedThrowsOnServerError() {
    server.enqueue(new MockResponse().setResponseCode(500));
    assertThatThrownBy(() -> provider(2).embed(List.of("a")))
        .isInstanceOf(EmbeddingException.class);
  }
}
