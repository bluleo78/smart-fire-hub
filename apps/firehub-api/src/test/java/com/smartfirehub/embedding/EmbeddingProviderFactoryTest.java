package com.smartfirehub.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import com.smartfirehub.embedding.config.EmbeddingConfig;
import com.smartfirehub.embedding.config.EmbeddingConfigService;
import com.smartfirehub.embedding.config.EmbeddingProviderType;
import com.smartfirehub.embedding.config.EmbeddingTargetGuard;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;

/** EmbeddingProviderFactory — 현재 테넌트 설정 문서로 provider 를 만들고, 미설정이면 명확히 멈춘다. */
@ExtendWith(MockitoExtension.class)
class EmbeddingProviderFactoryTest {

  @Mock private EmbeddingConfigService configService;
  // 가드는 스텁한다(기본 통과) — DNS 해석·허용 목록 판정은 EmbeddingTargetGuardTest 와 통합 테스트가 본다.
  @Mock private EmbeddingTargetGuard targetGuard;
  private EmbeddingProviderFactory factory;

  @BeforeEach
  void setUp() {
    factory = new EmbeddingProviderFactory(configService, WebClient.builder(), targetGuard);
  }

  @Test
  void guardRejectionAtCallTimeBecomesEmbeddingException() {
    // A2: 저장 뒤 가드를 통과하지 못하게 된 주소는 provider 를 만들기 전에 거부한다(원인 보존).
    EmbeddingConfig cfg =
        new EmbeddingConfig(
            EmbeddingProviderType.OLLAMA, "bge-m3", "http://10.0.0.1:11434", "", 1024);
    when(configService.resolve()).thenReturn(Optional.of(cfg));
    IllegalArgumentException denied = new IllegalArgumentException("차단된 주소");
    doThrow(denied).when(targetGuard).check(EmbeddingProviderType.OLLAMA, "http://10.0.0.1:11434");

    assertThatThrownBy(() -> factory.current())
        .isInstanceOf(EmbeddingException.class)
        .hasMessageContaining("차단된 주소")
        .hasCause(denied);
  }

  @Test
  void unconfiguredTenantThrowsNotConfigured() {
    when(configService.resolve()).thenReturn(Optional.empty());
    assertThatThrownBy(() -> factory.current())
        .isInstanceOf(EmbeddingNotConfiguredException.class)
        .hasMessage("임베딩이 설정되지 않았습니다 (설정 > 임베딩)");
  }

  @Test
  void ollamaConfigBuildsOllamaProviderWithDocumentDimension() {
    when(configService.resolve())
        .thenReturn(
            Optional.of(
                new EmbeddingConfig(
                    EmbeddingProviderType.OLLAMA, "bge-m3", "http://h:11434", "", 1024)));
    EmbeddingProvider p = factory.current();
    assertThat(p).isInstanceOf(OllamaEmbeddingProvider.class);
    assertThat(p.modelId()).isEqualTo("bge-m3");
    assertThat(p.dimension()).isEqualTo(1024);
  }

  @Test
  void openAiConfigBuildsOpenAiProviderWith1536() {
    when(configService.resolve())
        .thenReturn(
            Optional.of(
                new EmbeddingConfig(
                    EmbeddingProviderType.OPENAI,
                    "text-embedding-3-small",
                    "https://api.openai.com",
                    "sk-x",
                    1536)));
    EmbeddingProvider p = factory.current();
    assertThat(p).isInstanceOf(OpenAiEmbeddingProvider.class);
    assertThat(p.dimension()).isEqualTo(1536);
  }

  @Test
  void openAiWithoutKeyThrows() {
    when(configService.resolve())
        .thenReturn(
            Optional.of(
                new EmbeddingConfig(
                    EmbeddingProviderType.OPENAI, "m", "https://api.openai.com", "", 1536)));
    assertThatThrownBy(() -> factory.current())
        .isInstanceOf(EmbeddingException.class)
        .hasMessageContaining("API 키가 필요합니다");
  }

  @Test
  void largeBatchResponse_exceedingDefaultBuffer_isParsed() throws Exception {
    // 기본 256KB WebFlux 버퍼를 넘는 배치 응답도 파싱돼야 한다(maxInMemorySize 상향 회귀 가드).
    MockWebServer server = new MockWebServer();
    server.start();
    try {
      when(configService.resolve())
          .thenReturn(
              Optional.of(
                  new EmbeddingConfig(
                      EmbeddingProviderType.OLLAMA,
                      "bge-m3",
                      server.url("/").toString(),
                      "",
                      1024)));
      int count = 40; // 40 × 1024차원 ≈ 370KB
      server.enqueue(
          new MockResponse()
              .setHeader("Content-Type", "application/json")
              .setBody(bigEmbeddingsBody(count, 1024)));
      List<String> texts = new ArrayList<>();
      for (int i = 0; i < count; i++) texts.add("t" + i);

      List<float[]> out = factory.current().embed(texts);

      assertThat(out).hasSize(count);
      assertThat(out.get(0)).hasSize(1024);
    } finally {
      server.shutdown();
    }
  }

  @Test
  void openAiConfig1536_acceptsMatching1536Response() throws Exception {
    // Task 1 이 OpenAI 요청에서 dimensions 파라미터를 뺐다 — current() 가 설정 문서의
    // dimension(1536)을 기대 차원으로 써서 native 1536 응답을 받아들이는지 본다(#713 회귀 가드).
    MockWebServer server = new MockWebServer();
    server.start();
    try {
      when(configService.resolve())
          .thenReturn(
              Optional.of(
                  new EmbeddingConfig(
                      EmbeddingProviderType.OPENAI,
                      "text-embedding-3-small",
                      server.url("/").toString(),
                      "sk-x",
                      1536)));
      server.enqueue(
          new MockResponse()
              .setHeader("Content-Type", "application/json")
              .setBody(openAiEmbeddingsBody(1536)));

      List<float[]> out = factory.current().embed(List.of("스프링클러"));

      assertThat(out).hasSize(1);
      assertThat(out.get(0)).hasSize(1536);
    } finally {
      server.shutdown();
    }
  }

  @Test
  void openAiConfig1536_rejectsMismatched1024Response() throws Exception {
    // 설정 문서의 dimension(1536)과 실제 응답 차원(1024)이 어긋나면 조용히 저장하지 않고 던진다 —
    // cfg.dimension() 이 UNCHECKED_DIMENSION 이 아니라 실제 기대 차원으로 배선됐다는 증거다.
    MockWebServer server = new MockWebServer();
    server.start();
    try {
      when(configService.resolve())
          .thenReturn(
              Optional.of(
                  new EmbeddingConfig(
                      EmbeddingProviderType.OPENAI,
                      "text-embedding-3-small",
                      server.url("/").toString(),
                      "sk-x",
                      1536)));
      server.enqueue(
          new MockResponse()
              .setHeader("Content-Type", "application/json")
              .setBody(openAiEmbeddingsBody(1024)));

      EmbeddingProvider p = factory.current();
      assertThatThrownBy(() -> p.embed(List.of("스프링클러")))
          .isInstanceOf(EmbeddingException.class)
          .hasMessageContaining("dimension 불일치");
    } finally {
      server.shutdown();
    }
  }

  /** OpenAI /v1/embeddings 응답 형태: {@code data[].embedding}(index 포함), 1건. */
  private static String openAiEmbeddingsBody(int dim) {
    StringBuilder sb = new StringBuilder("{\"data\":[{\"index\":0,\"embedding\":[");
    for (int j = 0; j < dim; j++) {
      if (j > 0) sb.append(',');
      sb.append("0.01");
    }
    sb.append("]}]}");
    return sb.toString();
  }

  private static String bigEmbeddingsBody(int count, int dim) {
    StringBuilder sb = new StringBuilder("{\"embeddings\":[");
    for (int i = 0; i < count; i++) {
      if (i > 0) sb.append(',');
      sb.append('[');
      for (int j = 0; j < dim; j++) {
        if (j > 0) sb.append(',');
        sb.append("0.123456");
      }
      sb.append(']');
    }
    return sb.append("]}").toString();
  }
}
