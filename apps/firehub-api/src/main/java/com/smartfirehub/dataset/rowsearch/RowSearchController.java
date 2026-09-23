package com.smartfirehub.dataset.rowsearch;

import com.smartfirehub.dataset.rowsearch.dto.RowSearchRequest;
import com.smartfirehub.dataset.rowsearch.dto.RowSearchResponse;
import com.smartfirehub.dataset.rowsearch.dto.SearchIndexStatusResponse;
import com.smartfirehub.dataset.rowsearch.dto.UpdateSearchIndexRequest;
import com.smartfirehub.global.security.RequirePermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 데이터셋 행 검색: 검색 설정(검색 탭)과 행 검색(에이전트 도구) 엔드포인트. */
@RestController
@RequestMapping("/api/v1/datasets/{id}")
@RequiredArgsConstructor
public class RowSearchController {

  private final SearchIndexSettingsService settingsService;
  private final RowSearchService rowSearchService;

  /** 검색 설정·색인 상태 조회. */
  @GetMapping("/search-index")
  @RequirePermission("dataset:read")
  public SearchIndexStatusResponse getSearchIndex(@PathVariable Long id) {
    return settingsService.getStatus(id);
  }

  /** 검색 대상 필드 교체(빈 목록 = 끄기). */
  @PutMapping("/search-index")
  @RequirePermission("dataset:write")
  public SearchIndexStatusResponse updateSearchIndex(
      @PathVariable Long id, @Valid @RequestBody UpdateSearchIndexRequest request) {
    return settingsService.update(id, request.fields());
  }

  /** 수동 재색인 요청 — 실제 색인은 스윕이 비동기로 하므로 202. */
  @PostMapping("/search-index/reindex")
  @RequirePermission("dataset:write")
  public ResponseEntity<Void> reindex(@PathVariable Long id) {
    settingsService.reindex(id);
    return ResponseEntity.accepted().build();
  }

  /** 행 검색(하이브리드·의미·키워드) — 원본 행 조회 권한과 같은 data:read 로 보호한다. */
  @PostMapping("/rows/search")
  @RequirePermission("data:read")
  public RowSearchResponse searchRows(@PathVariable Long id, @RequestBody RowSearchRequest request) {
    return rowSearchService.search(id, request);
  }
}
