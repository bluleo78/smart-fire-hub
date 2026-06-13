package com.smartfirehub.embedding;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.web.reactive.function.client.WebClient;

/** 호스트 Ollama(/api/embed)를 호출하는 기본 임베딩 provider. */
public class OllamaEmbeddingProvider implements EmbeddingProvider {
  private final WebClient webClient;
  private final String model;
  private final int dimension;

  public OllamaEmbeddingProvider(WebClient webClient, String model, int dimension) {
    this.webClient = webClient;
    this.model = model;
    this.dimension = dimension;
  }

  /** 텍스트 배치를 Ollama /api/embed 로 보내 같은 순서의 임베딩 벡터로 변환한다. */
  @Override
  @SuppressWarnings("unchecked")
  public List<float[]> embed(List<String> texts) {
    Map<String, Object> body = Map.of("model", model, "input", texts);
    Map<String, Object> resp;
    try {
      resp =
          webClient
              .post()
              .uri("/api/embed")
              .bodyValue(body)
              .retrieve()
              .bodyToMono(Map.class)
              .timeout(Duration.ofSeconds(120))
              .block();
    } catch (Exception e) {
      // 서비스 다운/타임아웃/HTTP 오류 등 — 조용한 폴백 없이 명시적으로 전파한다.
      throw new EmbeddingException("Ollama 임베딩 호출 실패: " + e.getMessage(), e);
    }
    if (resp == null || !(resp.get("embeddings") instanceof List<?> rows)) {
      throw new EmbeddingException("Ollama 응답에 embeddings 가 없습니다");
    }
    // 입력 텍스트 수와 반환 임베딩 수가 다르면 순서/매핑이 어긋나므로 조기 실패한다.
    if (rows.size() != texts.size()) {
      throw new EmbeddingException(
          "Ollama 반환 임베딩 수 불일치: expected=" + texts.size() + " actual=" + rows.size());
    }
    return rows.stream().map(row -> toFloatArray((List<Number>) row)).toList();
  }

  /** 응답 한 행을 float[] 로 변환하며 차원 일치를 검증한다 (pgvector 컬럼과 불일치 시 조기 실패). */
  private float[] toFloatArray(List<Number> row) {
    if (row.size() != dimension) {
      throw new EmbeddingException(
          "임베딩 dimension 불일치: expected=" + dimension + " actual=" + row.size());
    }
    float[] v = new float[row.size()];
    for (int i = 0; i < row.size(); i++) {
      v[i] = row.get(i).floatValue();
    }
    return v;
  }

  @Override
  public String modelId() {
    return model;
  }

  @Override
  public int dimension() {
    return dimension;
  }
}
