package com.smartfirehub.dataset.rowsearch.dto;

import java.time.OffsetDateTime;
import java.util.List;

/** 검색 탭·에이전트가 보는 색인 상태. status: OFF | SYNCING | IDLE | ERROR */
public record SearchIndexStatusResponse(
    boolean enabled,
    List<String> fields,
    String status,
    long indexedRows,
    long totalRows,
    OffsetDateTime lastSyncedAt,
    String lastError,
    String embeddingModel) {

  /** 검색 대상 필드가 없는(검색 꺼짐) 상태. */
  public static SearchIndexStatusResponse off() {
    return new SearchIndexStatusResponse(false, List.of(), "OFF", 0, 0, null, null, null);
  }
}
