package com.smartfirehub.document.dto;

import java.util.List;

/** 문서 의미검색 요청. datasetIds 생략/빈값이면 전역 검색, topK 기본 5(최대 20). */
public record DocumentSearchRequest(String query, List<Long> datasetIds, Integer topK) {

  public DocumentSearchRequest {
    if (topK == null || topK < 1) topK = 5;
    if (topK > 20) topK = 20;
  }
}
