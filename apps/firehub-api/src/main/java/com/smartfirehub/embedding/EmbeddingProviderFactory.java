package com.smartfirehub.embedding;

import com.smartfirehub.embedding.config.EmbeddingConfig;
import com.smartfirehub.embedding.config.EmbeddingConfigService;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 현재 테넌트의 임베딩 설정 문서({@code embedding.config})로 활성 EmbeddingProvider 를 만든다(#713). 기대 차원은
 * 문서의 {@code dimension}(저장 시 probe 로 측정한 값)이고, 응답 차원이 다르면 provider 가 EmbeddingException 을 던진다.
 */
@Component
public class EmbeddingProviderFactory {

  // 임베딩 응답 메모리 버퍼 한계. WebFlux 기본값은 256KB 인데, 1024차원 벡터는 JSON 으로 직렬화 시 약 14KB/개라
  // 배치 임베딩(문서 인제스트·재임베딩은 한 번에 수십~수백 청크) 응답이 256KB 를 쉽게 초과해
  // DataBufferLimitException 이 난다. 넉넉히 32MB 로 상향한다(≈ 벡터 2천여 개 여유).
  private static final int MAX_RESPONSE_BYTES = 32 * 1024 * 1024;

  private final EmbeddingConfigService configService;
  private final WebClient.Builder webClientBuilder;

  public EmbeddingProviderFactory(
      EmbeddingConfigService configService, WebClient.Builder webClientBuilder) {
    this.configService = configService;
    this.webClientBuilder = webClientBuilder;
  }

  /**
   * 현재 테넌트 설정 기준 활성 provider. 미설정(행 없음·컨텍스트 없음)이면 {@link EmbeddingNotConfiguredException}.
   * 배경 잡은 {@code TenantContext.runScoped} 안에서 불러야 해당 테넌트 설정을 읽는다.
   */
  public EmbeddingProvider current() {
    EmbeddingConfig cfg = configService.resolve().orElseThrow(EmbeddingNotConfiguredException::new);
    return create(cfg, cfg.dimension());
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

  /** probe 전용 "차원 미검증" 표식. provider 는 dimension <= 0 이면 응답 길이를 비교하지 않는다. */
  public static final int UNCHECKED_DIMENSION = 0;

  /**
   * 설정 문서 하나로 provider 를 만든다. {@code expectedDimension} 은 응답 차원 검증 기준이다
   * ({@link #UNCHECKED_DIMENSION} 이면 검증하지 않음 — probe 용).
   */
  public EmbeddingProvider create(EmbeddingConfig cfg, int expectedDimension) {
    return switch (cfg.provider()) {
      case OLLAMA ->
          new OllamaEmbeddingProvider(
              embeddingWebClient(cfg.baseUrl()).build(), cfg.model(), expectedDimension);
      case OPENAI -> {
        // OpenAI 는 Bearer 인증 필수 — 키 없이 호출하면 공급자 401 보다 먼저 명확히 멈춘다.
        if (cfg.apiKey() == null || cfg.apiKey().isBlank()) {
          throw new EmbeddingException("OpenAI 임베딩 provider 에는 API 키가 필요합니다");
        }
        yield new OpenAiEmbeddingProvider(
            embeddingWebClient(cfg.baseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + cfg.apiKey())
                .build(),
            cfg.model(),
            expectedDimension);
      }
    };
  }

  /** 실제 임베딩 1건을 호출해 차원을 잰다(저장 전 검증·연결 테스트). 실패는 EmbeddingException. */
  public int probeDimension(EmbeddingConfig cfg) {
    return create(cfg, UNCHECKED_DIMENSION).embed(List.of("probe")).get(0).length;
  }
}
