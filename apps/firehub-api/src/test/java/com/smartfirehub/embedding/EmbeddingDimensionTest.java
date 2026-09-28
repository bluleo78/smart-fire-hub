package com.smartfirehub.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** EmbeddingDimension: 지원 차원 판정과 테이블명 단일 출처를 고정한다. */
class EmbeddingDimensionTest {

  @Test
  void ofMapsSupportedSizes() {
    assertThat(EmbeddingDimension.of(1024)).isEqualTo(EmbeddingDimension.D1024);
    assertThat(EmbeddingDimension.of(1536)).isEqualTo(EmbeddingDimension.D1536);
  }

  @Test
  void ofRejectsUnsupportedSizeWithExactMessage() {
    assertThatThrownBy(() -> EmbeddingDimension.of(768))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("지원하지 않는 차원 768 (지원: 1024, 1536)");
  }

  @Test
  void tableNamesComeFromTheEnumOnly() {
    assertThat(EmbeddingDimension.D1024.chunkTable()).isEqualTo("document_chunk_vec_1024");
    assertThat(EmbeddingDimension.D1536.datasetTable()).isEqualTo("dataset_embedding_vec_1536");
  }
}
