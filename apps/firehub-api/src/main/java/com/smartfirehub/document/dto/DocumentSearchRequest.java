package com.smartfirehub.document.dto;

import java.util.List;

/**
 * 문서 검색 요청. datasetIds 생략/빈값이면 전역 검색, topK 기본 5(최대 20).
 * mode 생략 시 HYBRID(의미+키워드 RRF 융합)가 기본 — 기존 호출자도 자동으로 하이브리드 검색을 받는다.
 */
public record DocumentSearchRequest(
    String query, List<Long> datasetIds, Integer topK, SearchMode mode) {

  // 정규화: topK 범위 보정, mode 기본값 HYBRID. (Jackson 역직렬화도 이 canonical 생성자를 사용)
  public DocumentSearchRequest {
    if (topK == null || topK < 1) topK = 5;
    if (topK > 20) topK = 20;
    if (mode == null) mode = SearchMode.HYBRID;
  }

  // 하위호환: 기존 3-인자 호출(테스트·구버전 코드)을 유지하기 위한 편의 생성자.
  public DocumentSearchRequest(String query, List<Long> datasetIds, Integer topK) {
    this(query, datasetIds, topK, null);
  }
}
