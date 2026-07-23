package com.smartfirehub.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.smartfirehub.settings.service.SettingsService;
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

/** EmbeddingProviderFactory 단위 테스트 — system_settings 값에 따른 provider 선택을 검증한다. */
@ExtendWith(MockitoExtension.class)
class EmbeddingProviderFactoryTest {

  @Mock private SettingsService settingsService;
  private EmbeddingProviderFactory factory;

  @BeforeEach
  void setUp() {
    factory = new EmbeddingProviderFactory(settingsService, WebClient.builder());
  }

  @Test
  void ollama_isDefaultProvider() {
    // provider 미설정 → OLLAMA 기본값 → OllamaEmbeddingProvider
    when(settingsService.getValue("embedding.provider")).thenReturn(Optional.empty());
    lenient().when(settingsService.getValue("embedding.model")).thenReturn(Optional.empty());
    lenient().when(settingsService.getValue("embedding.base_url")).thenReturn(Optional.empty());

    EmbeddingProvider provider = factory.current();

    assertThat(provider).isInstanceOf(OllamaEmbeddingProvider.class);
    assertThat(provider.modelId()).isEqualTo("bge-m3");
    assertThat(provider.dimension()).isEqualTo(1024);
  }

  @Test
  void openai_withApiKey_buildsOpenAiProviderWithDefaultModel() {
    // provider=OPENAI + api_key 존재 → OpenAiEmbeddingProvider, 모델 미설정 시 text-embedding-3-small 기본값
    when(settingsService.getValue("embedding.provider")).thenReturn(Optional.of("OPENAI"));
    when(settingsService.getDecryptedEmbeddingApiKey()).thenReturn(Optional.of("sk-test"));
    lenient().when(settingsService.getValue("embedding.model")).thenReturn(Optional.empty());
    lenient().when(settingsService.getValue("embedding.base_url")).thenReturn(Optional.empty());

    EmbeddingProvider provider = factory.current();

    assertThat(provider).isInstanceOf(OpenAiEmbeddingProvider.class);
    assertThat(provider.modelId()).isEqualTo("text-embedding-3-small");
    assertThat(provider.dimension()).isEqualTo(1024);
  }

  @Test
  void openai_withoutApiKey_throws() {
    // provider=OPENAI 인데 api_key 미설정 → 조기 실패
    when(settingsService.getValue("embedding.provider")).thenReturn(Optional.of("OPENAI"));
    when(settingsService.getDecryptedEmbeddingApiKey()).thenReturn(Optional.empty());

    assertThatThrownBy(() -> factory.current())
        .isInstanceOf(EmbeddingException.class)
        .hasMessageContaining("api_key");
  }

  @Test
  void unsupportedProvider_throws() {
    // 미구현 provider(VOYAGE 등) 활성화 시 조기 실패
    when(settingsService.getValue("embedding.provider")).thenReturn(Optional.of("VOYAGE"));

    assertThatThrownBy(() -> factory.current())
        .isInstanceOf(EmbeddingException.class)
        .hasMessageContaining("지원하지 않는");
  }

  @Test
  void largeBatchResponse_exceedingDefaultBuffer_isParsed() throws Exception {
    // 기본 256KB WebFlux 버퍼를 초과하는 대용량 배치 응답도 파싱돼야 한다(maxInMemorySize 상향 회귀 검증).
    // 상향이 없으면 여기서 DataBufferLimitException 이 발생한다.
    MockWebServer server = new MockWebServer();
    server.start();
    try {
      when(settingsService.getValue("embedding.provider")).thenReturn(Optional.of("OLLAMA"));
      lenient().when(settingsService.getValue("embedding.model")).thenReturn(Optional.empty());
      when(settingsService.getValue("embedding.base_url"))
          .thenReturn(Optional.of(server.url("/").toString()));

      int count = 40; // 40 × 1024차원 ≈ 370KB JSON → 기본 256KB 초과
      server.enqueue(
          new MockResponse()
              .setHeader("Content-Type", "application/json")
              .setBody(bigEmbeddingsBody(count, 1024)));

      List<String> texts = new ArrayList<>();
      for (int i = 0; i < count; i++) {
        texts.add("t" + i);
      }

      List<float[]> out = factory.current().embed(texts);

      assertThat(out).hasSize(count);
      assertThat(out.get(0)).hasSize(1024);
    } finally {
      server.shutdown();
    }
  }

  /** {"embeddings":[[...dim개...], ...count개...]} 형태의 대용량 Ollama 응답 본문을 만든다. */
  private static String bigEmbeddingsBody(int count, int dim) {
    StringBuilder sb = new StringBuilder("{\"embeddings\":[");
    for (int i = 0; i < count; i++) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append('[');
      for (int j = 0; j < dim; j++) {
        if (j > 0) {
          sb.append(',');
        }
        sb.append("0.123456");
      }
      sb.append(']');
    }
    return sb.append("]}").toString();
  }
}
