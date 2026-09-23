package com.smartfirehub.dataset.rowsearch;

/** 검색 대상 필드가 지정되지 않은 데이터셋에서 행 검색·재색인을 요청했을 때(400). */
public class SearchIndexNotConfiguredException extends RuntimeException {
  /** 사용자가 바로 조치할 수 있도록 검색 탭 안내를 메시지에 담는다. */
  public SearchIndexNotConfiguredException(long datasetId) {
    super(
        "데이터셋 "
            + datasetId
            + " 은(는) 검색 대상 필드가 지정되지 않았습니다. 데이터셋 상세의 '검색' 탭에서 필드를 지정하세요.");
  }
}
