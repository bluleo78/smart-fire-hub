package com.smartfirehub.dataset.rowsearch.dto;

import java.util.List;
import java.util.Map;

/**
 * 행 검색 응답. indexStatus.status: IDLE | SYNCING | ERROR | STALE(원본 교체 직후, 결과 없음).
 *
 * <p>첫 스윕 전에는 SYNCING/ERROR 와 빈 결과가, 임베딩 모델 변경 후 재색인 전 SEMANTIC 검색에는 SYNCING 과 빈 결과가
 * 온다(HYBRID 는 키워드만으로 답하고 degraded=true).
 */
public record RowSearchResponse(IndexStatus indexStatus, boolean degraded, List<Hit> hits) {

  /** 검색 시점의 색인 상태와 진행률(색인된 행 수 / 원본 행 수). */
  public record IndexStatus(String status, long indexedRows, long totalRows) {}

  /** matchedBy: 어느 검색에서 걸렸는지(SEMANTIC/KEYWORD) — 에이전트 설명용. */
  public record Hit(long rowId, double score, List<String> matchedBy, Map<String, Object> row) {}
}
