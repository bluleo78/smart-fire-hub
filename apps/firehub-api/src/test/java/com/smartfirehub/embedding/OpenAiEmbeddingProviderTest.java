package com.smartfirehub.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;

/** OpenAiEmbeddingProvider 단위 테스트 — MockWebServer로 /v1/embeddings 응답을 모킹한다. */
class OpenAiEmbeddingProviderTest {
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

  private OpenAiEmbeddingProvider provider(int dimension) {
    // 팩토리가 하듯 baseUrl 과 Bearer 헤더를 주입한 WebClient 를 만든다.
    WebClient client =
        WebClient.builder()
            .baseUrl(server.url("/").toString())
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer sk-test")
            .build();
    return new OpenAiEmbeddingProvider(client, "text-embedding-3-small", dimension);
  }

  @Test
  void embedReturnsVectorsInIndexOrder() throws Exception {
    // index 가 뒤섞여 와도 index 순서대로 재배치돼야 한다.
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody(
                "{\"data\":[{\"index\":1,\"embedding\":[0.3,0.4]},"
                    + "{\"index\":0,\"embedding\":[0.1,0.2]}]}"));

    List<float[]> out = provider(2).embed(List.of("a", "b"));

    assertThat(out).hasSize(2);
    assertThat(out.get(0)).containsExactly(0.1f, 0.2f);
    assertThat(out.get(1)).containsExactly(0.3f, 0.4f);

    // 요청 검증: /v1/embeddings, Bearer 헤더, dimensions 축소 파라미터 포함
    RecordedRequest req = server.takeRequest();
    assertThat(req.getPath()).isEqualTo("/v1/embeddings");
    assertThat(req.getHeader(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer sk-test");
    String body = req.getBody().readUtf8();
    assertThat(body).contains("\"dimensions\":2");
    assertThat(body).contains("text-embedding-3-small");
  }

  @Test
  void embedThrowsOnDimensionMismatch() {
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody("{\"data\":[{\"index\":0,\"embedding\":[0.1,0.2,0.3]}]}"));
    assertThatThrownBy(() -> provider(2).embed(List.of("a")))
        .isInstanceOf(EmbeddingException.class)
        .hasMessageContaining("dimension");
  }

  @Test
  void embedThrowsOnRowCountMismatch() {
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody("{\"data\":[{\"index\":0,\"embedding\":[0.1,0.2]}]}"));
    assertThatThrownBy(() -> provider(2).embed(List.of("a", "b")))
        .isInstanceOf(EmbeddingException.class)
        .hasMessageContaining("불일치");
  }

  @Test
  void embedThrowsWhenDataMissing() {
    server.enqueue(
        new MockResponse().setHeader("Content-Type", "application/json").setBody("{}"));
    assertThatThrownBy(() -> provider(2).embed(List.of("a")))
        .isInstanceOf(EmbeddingException.class)
        .hasMessageContaining("data");
  }

  @Test
  void embedThrowsOnServerError() {
    server.enqueue(new MockResponse().setResponseCode(401));
    assertThatThrownBy(() -> provider(2).embed(List.of("a")))
        .isInstanceOf(EmbeddingException.class);
  }
}
