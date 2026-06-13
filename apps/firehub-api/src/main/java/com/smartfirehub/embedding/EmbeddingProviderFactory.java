package com.smartfirehub.embedding;

import com.smartfirehub.settings.service.SettingsService;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * system_settings(embedding.*) 를 읽어 활성 EmbeddingProvider 를 만든다. Phase 1 은 OLLAMA 만 구현. VOYAGE/OPENAI
 * 는 후속에서 추가.
 */
@Component
public class EmbeddingProviderFactory {
  // 배포 단위 고정 차원 (pgvector 컬럼 vector(1024) 와 일치)
  // vector(1024) in V62 와 일치해야 함
  private static final int DIMENSION = 1024;

  private final SettingsService settingsService;
  private final WebClient.Builder webClientBuilder;

  public EmbeddingProviderFactory(
      SettingsService settingsService, WebClient.Builder webClientBuilder) {
    this.settingsService = settingsService;
    this.webClientBuilder = webClientBuilder;
  }

  /** 현재 설정 기준 활성 provider 반환. */
  public EmbeddingProvider current() {
    // getValue 는 Optional<String> 반환 — 빈 값/미설정 시 기본값으로 폴백한다.
    String provider = settingValue("embedding.provider", "OLLAMA");
    String model = settingValue("embedding.model", "bge-m3");
    String baseUrl = settingValue("embedding.base_url", "http://host.docker.internal:11434");
    return switch (provider) {
      case "OLLAMA" ->
          // clone() 으로 공유 빌더의 독립 복사본을 만들어 baseUrl 설정 (스레드 안전, 공유 상태 비변형)
          new OllamaEmbeddingProvider(
              webClientBuilder.clone().baseUrl(baseUrl).build(), model, DIMENSION);
      // SettingsService 검증은 VOYAGE/OPENAI 도 허용하므로, 해당 provider 구현 추가 시 여기에 case 를 반드시 추가해야 한다.
      default ->
          throw new EmbeddingException(
              "지원하지 않는 임베딩 provider: " + provider + " (Phase 1 은 OLLAMA 만)");
    };
  }

  /** system_settings 단일 값 조회 — 미설정/빈 문자열이면 기본값을 사용한다. */
  private String settingValue(String key, String defaultValue) {
    return settingsService.getValue(key).filter(v -> !v.isBlank()).orElse(defaultValue);
  }

  public int dimension() {
    return DIMENSION;
  }
}
