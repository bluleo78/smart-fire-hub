package com.smartfirehub.embedding;

import java.util.Objects;

/**
 * 임베딩 공간 = (차원, 모델). 벡터는 같은 공간 안에서만 거리 비교가 의미 있다.
 *
 * <p>검색은 이 공간의 차원 테이블 한 개 + {@code embedding_model = model} 조건만 보고(#392 — 모델 전환 중 옛 모델 벡터가 섞이지 않게),
 * 쓰기와 재임베딩 판정식도 같은 키를 쓴다.
 */
public record EmbeddingSpace(EmbeddingDimension dimension, String model) {

  public EmbeddingSpace {
    Objects.requireNonNull(dimension, "dimension");
    if (model == null || model.isBlank()) {
      throw new IllegalArgumentException("임베딩 모델은 비어있을 수 없습니다");
    }
  }

  /** 활성 provider 의 공간. provider 차원이 미지원이면 {@link EmbeddingDimension#of} 가 던진다. */
  public static EmbeddingSpace of(EmbeddingProvider provider) {
    return new EmbeddingSpace(EmbeddingDimension.of(provider.dimension()), provider.modelId());
  }
}
