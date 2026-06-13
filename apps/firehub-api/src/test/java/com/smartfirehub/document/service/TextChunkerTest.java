package com.smartfirehub.document.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.document.dto.Chunk;
import java.util.List;
import org.junit.jupiter.api.Test;

class TextChunkerTest {

  private final TextChunker chunker = new TextChunker(20, 5);

  @Test
  void shortTextProducesSingleChunk() {
    List<Chunk> chunks = chunker.chunk("짧은 문서");
    assertThat(chunks).hasSize(1);
    assertThat(chunks.get(0).index()).isZero();
    assertThat(chunks.get(0).content()).isEqualTo("짧은 문서");
    assertThat(chunks.get(0).tokenCount()).isPositive();
  }

  @Test
  void longTextSplitsIntoOverlappingChunks() {
    // A*20 + B*15 + C*15 = 50자; chunkSize=20, overlap=5, step=15
    String text = "A".repeat(20) + "B".repeat(15) + "C".repeat(15);
    List<Chunk> chunks = chunker.chunk(text);

    assertThat(chunks.size()).isGreaterThan(1);
    for (int i = 0; i < chunks.size(); i++) assertThat(chunks.get(i).index()).isEqualTo(i);
    assertThat(chunks).allMatch(c -> c.content().length() <= 20);

    // 오버랩 실증: chunk[1]의 첫 5자 == chunk[0]의 마지막 5자
    String tailOf0 = chunks.get(0).content().substring(chunks.get(0).content().length() - 5);
    String headOf1 = chunks.get(1).content().substring(0, 5);
    assertThat(headOf1).isEqualTo(tailOf0);
  }

  @Test
  void blankTextProducesNoChunks() {
    assertThat(chunker.chunk("   ")).isEmpty();
    assertThat(chunker.chunk(null)).isEmpty();
  }

  @Test
  void normalizesExcessiveWhitespace() {
    List<Chunk> chunks = chunker.chunk("줄1\n\n\n\n줄2");
    assertThat(chunks.get(0).content()).isEqualTo("줄1\n\n줄2");
  }
}
