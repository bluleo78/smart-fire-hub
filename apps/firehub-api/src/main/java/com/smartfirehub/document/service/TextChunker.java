package com.smartfirehub.document.service;

import com.smartfirehub.document.dto.Chunk;
import com.smartfirehub.global.platformdb.mybatis.TiberoTextLimits;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 문자 기반 슬라이딩 윈도우 청킹. chunkSize 문자 단위로 자르되 overlap 문자만큼 겹쳐 문맥 단절을 줄인다. 토큰 수는 임베딩 모델 정밀 토크나이저 없이
 * char/4 로 추정한다(영문 기준 근사, 한국어는 보수적).
 *
 * <p>문자 인덱스 기반 분할은 BMP 텍스트(한국어 포함)를 가정한다. astral-plane 문자(이모지 등)는 서로게이트 페어가 청크 경계에서 분리될 수 있으나 Phase
 * 1 에서는 허용한다.
 */
@Service
public class TextChunker {

  private final int chunkSize;
  private final int overlap;

  public TextChunker(
      @Value("${app.rag.chunk-size:1500}") int chunkSize,
      @Value("${app.rag.chunk-overlap:200}") int overlap) {
    if (overlap >= chunkSize) {
      throw new IllegalArgumentException("overlap 은 chunkSize 보다 작아야 합니다");
    }
    // 청크 본문은 Tibero 플랫폼 DB 의 document_chunk.content(VARCHAR2(65532) 바이트)에 들어가야 한다(WD-741). 기본
    // 1500자는 넉넉하지만
    // 설정으로 키울 수 있어, 어떤 글자로도 들어가는 상한(char 하나 최대 3바이트)을 넘으면 기동 때 막는다 — 실행 중 저장 실패로 문서가 빠지지 않게.
    if (chunkSize > TiberoTextLimits.MAX_TRUNCATE_CHARS) {
      throw new IllegalArgumentException(
          "app.rag.chunk-size 는 "
              + TiberoTextLimits.MAX_TRUNCATE_CHARS
              + " 자 이하여야 합니다: "
              + chunkSize);
    }
    this.chunkSize = chunkSize;
    this.overlap = overlap;
  }

  /** 텍스트를 오버랩 청크로 분할한다. 공백/빈 입력은 빈 목록. */
  public List<Chunk> chunk(String raw) {
    List<Chunk> chunks = new ArrayList<>();
    if (raw == null) return chunks;
    String text = normalize(raw);
    if (text.isEmpty()) return chunks;

    int step = chunkSize - overlap;
    int index = 0;
    for (int start = 0; start < text.length(); start += step) {
      int end = Math.min(start + chunkSize, text.length());
      String content = text.substring(start, end).strip();
      if (!content.isEmpty()) {
        chunks.add(new Chunk(index++, content, estimateTokens(content)));
      }
      if (end == text.length()) break;
    }
    return chunks;
  }

  // 연속 공백/개행을 단일화해 잡음과 무의미한 청크 경계를 줄인다.
  private String normalize(String text) {
    return text.replaceAll("[ \\t]+", " ").replaceAll("\\n{3,}", "\n\n").strip();
  }

  private int estimateTokens(String content) {
    return Math.max(1, (int) Math.ceil(content.length() / 4.0));
  }
}
