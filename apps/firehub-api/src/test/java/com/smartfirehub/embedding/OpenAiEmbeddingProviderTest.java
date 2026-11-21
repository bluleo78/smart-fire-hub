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
    // 팩토리가 하듯 baseUrl 과 Bearer 헤더를 주입한 WebClient 를 만든다. 코덱은 날 builder() 의 Jackson 3 — 운영(자동 구성 빌더)과
    // 같은 계열이다(WD-793).
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

    // 요청 검증: /v1/embeddings, Bearer 헤더, dimensions 파라미터는 보내지 않는다(native 차원, #713)
    RecordedRequest req = server.takeRequest();
    assertThat(req.getPath()).isEqualTo("/v1/embeddings");
    assertThat(req.getHeader(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer sk-test");
    String body = req.getBody().readUtf8();
    assertThat(body).doesNotContain("dimensions");
    assertThat(body).contains("text-embedding-3-small");
  }

  @Test
  void uncheckedDimensionAcceptsAnyLength() {
    // probe 는 차원을 모르는 채 부른다 — UNCHECKED_DIMENSION(0) 이면 길이 검증을 건너뛴다.
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody("{\"data\":[{\"index\":0,\"embedding\":[0.1,0.2,0.3]}]}"));
    assertThat(provider(EmbeddingProviderFactory.UNCHECKED_DIMENSION).embed(List.of("a")).get(0))
        .hasSize(3);
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
    server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody("{}"));
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

  /** WD-863: HTTP 404 는 접두어 없는 원인 문장으로 나온다. */
  @Test
  void http404GivesCauseSentence() {
    server.enqueue(new MockResponse().setResponseCode(404));
    assertThatThrownBy(() -> provider(2).embed(List.of("a")))
        .isInstanceOf(EmbeddingException.class)
        .hasMessage("임베딩 서버에서 요청한 주소나 모델을 찾을 수 없습니다(HTTP 404) — Base URL 과 모델 이름을 확인하세요");
  }

  /** WD-863: HTTP 503 은 서버 오류 문장으로 나온다. */
  @Test
  void http503GivesServerErrorSentence() {
    server.enqueue(new MockResponse().setResponseCode(503));
    assertThatThrownBy(() -> provider(2).embed(List.of("a")))
        .isInstanceOf(EmbeddingException.class)
        .hasMessage("임베딩 서버 오류(HTTP 503) — 서버 상태를 확인하세요");
  }

  /** WD-863: 서버가 꺼진 닫힌 포트는 연결 거부 문장(OS 에 따라 연결 실패 문장)으로 나온다. */
  @Test
  void connectionRefusedGivesConnectionSentence() throws Exception {
    OpenAiEmbeddingProvider p = provider(2);
    server.shutdown();
    assertThatThrownBy(() -> p.embed(List.of("a")))
        .isInstanceOf(EmbeddingException.class)
        .message()
        .isIn(
            "임베딩 서버가 연결을 거부했습니다 — Base URL 의 주소·포트와 서버 실행 여부를 확인하세요",
            "임베딩 서버에 연결하지 못했습니다 — Base URL 과 네트워크 상태를 확인하세요");
  }
}
