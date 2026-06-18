package com.smartfirehub.admin.embedding;

/** GET /admin/embedding/status 응답. embedded = 현재 모델로 임베딩된 수. */
public record EmbeddingStatusResponse(String model, Counts datasets, Counts documentChunks) {
  /** 총 개수와 현재 모델 임베딩 완료 개수 쌍. */
  public record Counts(long total, long embedded) {}
}
