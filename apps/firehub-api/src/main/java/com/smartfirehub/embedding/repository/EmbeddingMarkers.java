package com.smartfirehub.embedding.repository;

import java.util.List;

/** 표시 열(공간·원문 해시) 쓰기 경로가 함께 쓰는 검사(WD-720). jOOQ·MyBatis 두 구현이 같은 규칙을 지키게 한 곳에 둔다. */
public final class EmbeddingMarkers {

  private EmbeddingMarkers() {}

  /**
   * id·벡터·원문 해시 목록의 길이가 같은지 확인한다. 셋은 같은 순서로 짝을 이룬다.
   *
   * @throws IllegalArgumentException 길이가 다를 때
   */
  public static void requireSameSize(
      List<Long> ids, List<float[]> embeddings, List<String> hashes) {
    if (ids.size() != embeddings.size() || ids.size() != hashes.size()) {
      throw new IllegalArgumentException(
          "id·임베딩·원문 해시 수 불일치: "
              + ids.size()
              + " vs "
              + embeddings.size()
              + " vs "
              + hashes.size());
    }
  }
}
