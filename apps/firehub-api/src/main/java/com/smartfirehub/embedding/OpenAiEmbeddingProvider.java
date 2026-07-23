package com.smartfirehub.embedding;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * OpenAI 임베딩 API(/v1/embeddings)를 호출하는 provider.
 *
 * <p>WebClient 에는 팩토리에서 baseUrl 과 {@code Authorization: Bearer <api_key>} 헤더가 이미 주입돼 있다. pgvector
 * 컬럼(vector(1024))과 차원을 맞추기 위해 요청 body 에 {@code dimensions} 를 명시해 축소를 강제한다 —
 * text-embedding-3-* 계열만 dimensions 를 지원하며, ada-002(1536 고정)는 사용할 수 없다.
 */
public class OpenAiEmbeddingProvider implements EmbeddingProvider {
  private final WebClient webClient;
  private final String model;
  private final int dimension;

  public OpenAiEmbeddingProvider(WebClient webClient, String model, int dimension) {
    this.webClient = webClient;
    this.model = model;
    this.dimension = dimension;
  }

  /** 텍스트 배치를 OpenAI /v1/embeddings 로 보내 같은 순서의 임베딩 벡터로 변환한다. */
  @Override
  @SuppressWarnings("unchecked")
  public List<float[]> embed(List<String> texts) {
    // dimensions 를 명시해 응답 벡터를 pgvector 컬럼 차원(1024)으로 축소 요청한다.
    Map<String, Object> body = Map.of("model", model, "input", texts, "dimensions", dimension);
    Map<String, Object> resp;
    try {
      resp =
          webClient
              .post()
              .uri("/v1/embeddings")
              .bodyValue(body)
              .retrieve()
              .bodyToMono(Map.class)
              .timeout(Duration.ofSeconds(120))
              .block();
    } catch (Exception e) {
      // 인증 실패/쿼터 초과/타임아웃 등 — 조용한 폴백 없이 명시적으로 전파한다.
      throw new EmbeddingException("OpenAI 임베딩 호출 실패: " + e.getMessage(), e);
    }
    if (resp == null || !(resp.get("data") instanceof List<?> rows)) {
      throw new EmbeddingException("OpenAI 응답에 data 가 없습니다");
    }
    // 입력 텍스트 수와 반환 임베딩 수가 다르면 순서/매핑이 어긋나므로 조기 실패한다.
    if (rows.size() != texts.size()) {
      throw new EmbeddingException(
          "OpenAI 반환 임베딩 수 불일치: expected=" + texts.size() + " actual=" + rows.size());
    }
    // OpenAI 응답은 각 항목에 index 를 담아 순서를 보장하지만, 방어적으로 index 순서대로 재배치한다.
    float[][] ordered = new float[rows.size()][];
    for (Object item : rows) {
      Map<String, Object> obj = (Map<String, Object>) item;
      if (!(obj.get("index") instanceof Number idxNum)) {
        throw new EmbeddingException("OpenAI 응답에 index 가 없습니다");
      }
      int index = idxNum.intValue();
      if (index < 0 || index >= ordered.length) {
        throw new EmbeddingException("OpenAI 응답 index 범위 오류: " + index);
      }
      ordered[index] = toFloatArray((List<Number>) obj.get("embedding"));
    }
    return List.of(ordered);
  }

  /** 응답 한 행을 float[] 로 변환하며 차원 일치를 검증한다 (pgvector 컬럼과 불일치 시 조기 실패). */
  private float[] toFloatArray(List<Number> row) {
    if (row == null || row.size() != dimension) {
      throw new EmbeddingException(
          "임베딩 dimension 불일치: expected="
              + dimension
              + " actual="
              + (row == null ? "null" : row.size()));
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
