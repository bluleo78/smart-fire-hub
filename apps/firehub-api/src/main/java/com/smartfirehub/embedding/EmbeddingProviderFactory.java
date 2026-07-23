package com.smartfirehub.embedding;

import com.smartfirehub.settings.service.SettingsService;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * system_settings(embedding.*) 를 읽어 활성 EmbeddingProvider 를 만든다. OLLAMA(로컬 bge-m3) 와 OPENAI(클라우드)
 * 를 지원한다. provider 별로 base_url/model 기본값과 인증 방식이 다르므로 각 case 안에서 설정을 읽는다.
 */
@Component
public class EmbeddingProviderFactory {
  // 배포 단위 고정 차원 (pgvector 컬럼 vector(1024) 와 일치)
  // vector(1024) in V62 와 일치해야 함
  private static final int DIMENSION = 1024;

  // 임베딩 응답 메모리 버퍼 한계. WebFlux 기본값은 256KB 인데, 1024차원 벡터는 JSON 으로 직렬화 시 약 14KB/개라
  // 배치 임베딩(문서 인제스트·재임베딩은 한 번에 수십~수백 청크) 응답이 256KB 를 쉽게 초과해
  // DataBufferLimitException 이 난다. 넉넉히 32MB 로 상향한다(≈ 벡터 2천여 개 여유).
  private static final int MAX_RESPONSE_BYTES = 32 * 1024 * 1024;

  private final SettingsService settingsService;
  private final WebClient.Builder webClientBuilder;

  public EmbeddingProviderFactory(
      SettingsService settingsService, WebClient.Builder webClientBuilder) {
    this.settingsService = settingsService;
    this.webClientBuilder = webClientBuilder;
  }

  /** 현재 설정 기준 활성 provider 반환. */
  public EmbeddingProvider current() {
    // getValue 는 Optional<String> 반환 — 빈 값/미설정 시 provider 별 기본값으로 폴백한다.
    String provider = settingValue("embedding.provider", "OLLAMA");
    return switch (provider) {
      case "OLLAMA" -> {
        String model = settingValue("embedding.model", "bge-m3");
        String baseUrl = settingValue("embedding.base_url", "http://host.docker.internal:11434");
        yield new OllamaEmbeddingProvider(embeddingWebClient(baseUrl).build(), model, DIMENSION);
      }
      case "OPENAI" -> {
        // OpenAI 는 Bearer 인증 필수 — 복호화된 embedding.api_key 를 헤더에 주입한다.
        String apiKey =
            settingsService
                .getDecryptedEmbeddingApiKey()
                .orElseThrow(
                    () ->
                        new EmbeddingException(
                            "OpenAI 임베딩 provider 에는 embedding.api_key 설정이 필요합니다"));
        // dimensions 축소를 지원하는 text-embedding-3-* 계열을 기본값으로 사용한다.
        String model = settingValue("embedding.model", "text-embedding-3-small");
        String baseUrl = settingValue("embedding.base_url", "https://api.openai.com");
        yield new OpenAiEmbeddingProvider(
            embeddingWebClient(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .build(),
            model,
            DIMENSION);
      }
      // VOYAGE 는 미구현 — 설정 저장은 허용되나 활성화 시 여기서 조기 실패한다.
      default ->
          throw new EmbeddingException(
              "지원하지 않는 임베딩 provider: " + provider + " (OLLAMA, OPENAI 지원)");
    };
  }

  /**
   * 임베딩 호출용 WebClient 빌더 복사본. clone() 으로 공유 빌더의 독립 복사본을 만들어(스레드 안전, 공유 상태 비변형) baseUrl 과
   * 상향된 응답 버퍼 한계를 적용한다. 배치 임베딩 응답이 기본 256KB 를 초과하는 것을 방지한다.
   */
  private WebClient.Builder embeddingWebClient(String baseUrl) {
    return webClientBuilder
        .clone()
        .baseUrl(baseUrl)
        .codecs(c -> c.defaultCodecs().maxInMemorySize(MAX_RESPONSE_BYTES));
  }

  /** system_settings 단일 값 조회 — 미설정/빈 문자열이면 기본값을 사용한다. */
  private String settingValue(String key, String defaultValue) {
    return settingsService.getValue(key).filter(v -> !v.isBlank()).orElse(defaultValue);
  }

  public int dimension() {
    return DIMENSION;
  }
}
