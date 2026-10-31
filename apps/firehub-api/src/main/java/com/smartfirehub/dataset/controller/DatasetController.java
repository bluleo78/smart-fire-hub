package com.smartfirehub.dataset.controller;

import com.smartfirehub.dataset.dto.*;
import com.smartfirehub.dataset.dto.SpatialFilter;
import com.smartfirehub.dataset.search.DatasetEmbeddingBackfillService;
import com.smartfirehub.dataset.search.DatasetSearchHit;
import com.smartfirehub.dataset.search.DatasetSearchRequest;
import com.smartfirehub.dataset.search.DatasetSearchService;
import com.smartfirehub.dataset.service.ApiImportService;
import com.smartfirehub.dataset.service.DatasetDataService;
import com.smartfirehub.dataset.service.DatasetFavoriteService;
import com.smartfirehub.dataset.service.DatasetService;
import com.smartfirehub.dataset.service.DatasetTagService;
import com.smartfirehub.global.dto.PageResponse;
import com.smartfirehub.global.security.RequirePermission;
import com.smartfirehub.securitylevel.access.ClearanceResolver;
import com.smartfirehub.securitylevel.service.SecurityAuditRecorder;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/datasets")
@RequiredArgsConstructor
public class DatasetController {

  private final DatasetService datasetService;
  private final DatasetDataService datasetDataService;
  private final DatasetFavoriteService datasetFavoriteService;
  private final DatasetTagService datasetTagService;
  private final ApiImportService apiImportService;
  private final DatasetSearchService datasetSearchService;
  private final ClearanceResolver clearanceResolver;
  private final DatasetEmbeddingBackfillService datasetEmbeddingBackfillService;

  /** 감사 등급(audit_access) 데이터셋 행 조회 기록(스펙 §4.6). */
  private final SecurityAuditRecorder auditRecorder;

  @GetMapping
  @RequirePermission("dataset:read")
  public ResponseEntity<PageResponse<DatasetResponse>> getDatasets(
      @RequestParam(required = false) Long categoryId,
      @RequestParam(required = false) String storageType,
      @RequestParam(required = false) String originType,
      @RequestParam(required = false) String search,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "false") boolean favoriteOnly,
      @RequestParam(required = false) Long securityLevelId,
      Authentication authentication) {
    page = Math.max(0, page);
    size = Math.max(1, Math.min(size, 100));
    Long userId = (Long) authentication.getPrincipal();
    PageResponse<DatasetResponse> response =
        datasetService.getDatasets(
            categoryId,
            storageType,
            originType,
            search,
            page,
            size,
            userId,
            status,
            favoriteOnly,
            securityLevelId);
    return ResponseEntity.ok(response);
  }

  /** 데이터셋 카탈로그 시맨틱+키워드 하이브리드 검색 (AI discovery 진입점). */
  @PostMapping("/search")
  @RequirePermission("dataset:read")
  public List<DatasetSearchHit> searchDatasets(@RequestBody DatasetSearchRequest request) {
    // find_datasets(ai-agent)도 이 경로 — 이름·설명이 결과로 나가므로 가시성 조건 필수(스펙 §4.2 1행).
    return datasetSearchService.search(request, clearanceResolver.current());
  }

  /**
   * 관리자용 데이터셋 검색 인덱스 수동 백필. 전체 데이터셋의 source_text 를 동기 적재한 뒤 임베딩 재색인을 비동기 분산한다. 인덱스를 변경하므로
   * dataset:write 권한을 요구한다.
   */
  @PostMapping("/embedding/backfill")
  @RequirePermission("dataset:write")
  public ResponseEntity<Map<String, Integer>> backfillEmbeddings() {
    int count = datasetEmbeddingBackfillService.backfillAll();
    return ResponseEntity.accepted().body(Map.of("scheduled", count));
  }

  @PostMapping
  @RequirePermission("dataset:write")
  public ResponseEntity<DatasetDetailResponse> createDataset(
      @Valid @RequestBody CreateDatasetRequest request, Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    DatasetDetailResponse dataset = datasetService.createDataset(request, userId);
    return ResponseEntity.status(HttpStatus.CREATED).body(dataset);
  }

  @GetMapping("/{id}")
  @RequirePermission("dataset:read")
  public ResponseEntity<DatasetDetailResponse> getDatasetById(
      @PathVariable Long id, Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    DatasetDetailResponse dataset = datasetService.getDatasetById(id, userId);
    return ResponseEntity.ok(dataset);
  }

  @PutMapping("/{id}")
  @RequirePermission("dataset:write")
  public ResponseEntity<Void> updateDataset(
      @PathVariable Long id,
      @Valid @RequestBody UpdateDatasetRequest request,
      Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    datasetService.updateDataset(id, request, userId);
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/{id}")
  @RequirePermission("dataset:delete")
  public ResponseEntity<Void> deleteDataset(@PathVariable Long id) {
    datasetService.deleteDataset(id);
    return ResponseEntity.noContent().build();
  }

  /** 데이터셋을 참조하는 파이프라인/대시보드/스마트잡 집계. 삭제 전 영향 범위 확인용. */
  @GetMapping("/{id}/references")
  @RequirePermission("dataset:read")
  public ResponseEntity<DatasetReferencesResponse> getReferences(
      @PathVariable Long id, Authentication authentication) {
    // 조회자 — 볼 수 없는 대시보드 이름을 가리는 데 쓴다(WD-143)
    Long userId = (Long) authentication.getPrincipal();
    return ResponseEntity.ok(datasetService.getReferences(id, userId));
  }

  @PostMapping("/{id}/columns")
  @RequirePermission("dataset:write")
  public ResponseEntity<DatasetColumnResponse> addColumn(
      @PathVariable Long id,
      @Valid @RequestBody AddColumnRequest request,
      Authentication authentication) {
    // 변경 감사(WD-463)의 행위자로 남기기 위해 요청 사용자를 넘긴다.
    DatasetColumnResponse column =
        datasetService.addColumn(id, request, (Long) authentication.getPrincipal());
    return ResponseEntity.status(HttpStatus.CREATED).body(column);
  }

  @PutMapping("/{id}/columns/{columnId}")
  @RequirePermission("dataset:write")
  public ResponseEntity<Void> updateColumn(
      @PathVariable Long id,
      @PathVariable Long columnId,
      @Valid @RequestBody UpdateColumnRequest request,
      Authentication authentication) {
    // 변경 감사(WD-463)의 행위자로 남기기 위해 요청 사용자를 넘긴다.
    datasetService.updateColumn(id, columnId, request, (Long) authentication.getPrincipal());
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/{id}/columns/{columnId}")
  @RequirePermission("dataset:write")
  public ResponseEntity<Void> deleteColumn(
      @PathVariable Long id, @PathVariable Long columnId, Authentication authentication) {
    // 변경 감사(WD-463)의 행위자로 남기기 위해 요청 사용자를 넘긴다.
    datasetService.deleteColumn(id, columnId, (Long) authentication.getPrincipal());
    return ResponseEntity.noContent().build();
  }

  // 데이터셋의 기본 키 컬럼 집합을 한 번에 갱신한다 (#117).
  // 단일 컬럼 PUT 으로는 복합 PK 중간 상태가 unique 하지 않아 실패하므로 일괄 변경 엔드포인트가 필요하다.
  @PutMapping("/{id}/primary-keys")
  @RequirePermission("dataset:write")
  public ResponseEntity<Void> updatePrimaryKeys(
      @PathVariable Long id,
      @RequestBody UpdatePrimaryKeysRequest request,
      Authentication authentication) {
    // 변경 감사(WD-463)의 행위자로 남기기 위해 요청 사용자를 넘긴다.
    datasetService.updatePrimaryKeys(id, request.columnIds(), (Long) authentication.getPrincipal());
    return ResponseEntity.noContent().build();
  }

  @PutMapping("/{id}/columns/reorder")
  @RequirePermission("dataset:write")
  public ResponseEntity<Void> reorderColumns(
      @PathVariable Long id,
      @RequestBody ReorderColumnsRequest request,
      Authentication authentication) {
    // 변경 감사(WD-463)의 행위자로 남기기 위해 요청 사용자를 넘긴다.
    datasetService.reorderColumns(id, request, (Long) authentication.getPrincipal());
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/{id}/stats")
  @RequirePermission("data:read")
  public ResponseEntity<List<ColumnStatsResponse>> getDatasetStats(@PathVariable Long id) {
    List<ColumnStatsResponse> stats = datasetDataService.getDatasetStats(id);
    return ResponseEntity.ok(stats);
  }

  @PostMapping("/{id}/data/delete")
  @RequirePermission("data:delete")
  public ResponseEntity<DataDeleteResponse> deleteDataRows(
      @PathVariable Long id,
      @RequestBody DataDeleteRequest request,
      Authentication authentication) {
    if (request.rowIds() == null || request.rowIds().isEmpty()) {
      return ResponseEntity.badRequest().build();
    }
    if (request.rowIds().size() > 1000) {
      return ResponseEntity.badRequest().build();
    }
    // 변경 감사(WD-463)의 행위자로 남기기 위해 요청 사용자를 넘긴다.
    DataDeleteResponse response =
        datasetDataService.deleteDataRows(
            id, request.rowIds(), (Long) authentication.getPrincipal());
    return ResponseEntity.ok(response);
  }

  @PostMapping("/{id}/data/truncate")
  @RequirePermission("data:delete")
  public ResponseEntity<DataDeleteResponse> truncateDatasetData(@PathVariable Long id) {
    DataDeleteResponse response = datasetDataService.truncateDatasetData(id);
    return ResponseEntity.ok(response);
  }

  @GetMapping("/{id}/data/count")
  @RequirePermission("data:read")
  public ResponseEntity<RowCountResponse> getRowCount(@PathVariable Long id) {
    RowCountResponse response = datasetDataService.getRowCount(id);
    return ResponseEntity.ok(response);
  }

  @PostMapping("/{id}/data/replace")
  @RequirePermission("data:import")
  public ResponseEntity<BatchRowDataResponse> replaceDatasetData(
      @PathVariable Long id, @Valid @RequestBody BatchRowDataRequest request) {
    BatchRowDataResponse response = datasetDataService.replaceDatasetData(id, request);
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
  }

  @GetMapping("/{id}/data")
  @RequirePermission("data:read")
  public ResponseEntity<DataQueryResponse> getDatasetData(
      @PathVariable Long id,
      @RequestParam(required = false) String search,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size,
      @RequestParam(required = false) String sortBy,
      @RequestParam(defaultValue = "ASC") String sortDir,
      @RequestParam(defaultValue = "true") boolean includeTotalCount,
      // ---- Phase 1-2: 공간 필터 (모두 선택적) ----
      @RequestParam(required = false) String spatialColumn,
      @RequestParam(required = false) Double nearbyLon,
      @RequestParam(required = false) Double nearbyLat,
      @RequestParam(required = false) Double nearbyRadius,
      @RequestParam(required = false) Double bboxMinLon,
      @RequestParam(required = false) Double bboxMinLat,
      @RequestParam(required = false) Double bboxMaxLon,
      @RequestParam(required = false) Double bboxMaxLat,
      Authentication authentication) {
    // 스펙 §4.6 — 감사 등급(audit_access) 데이터셋의 행 조회 기록. 인터셉터가 VIEW 를 이미 통과시켰다.
    recordRowView(authentication, id);
    page = Math.max(0, page);
    size = Math.max(1, Math.min(size, 200));
    String sanitizedSearch =
        search != null && search.length() > 200 ? search.substring(0, 200) : search;
    if (!"ASC".equalsIgnoreCase(sortDir) && !"DESC".equalsIgnoreCase(sortDir)) {
      sortDir = "ASC";
    }

    // SpatialFilter 조립
    SpatialFilter spatialFilter = null;
    if (nearbyLon != null && nearbyLat != null && nearbyRadius != null) {
      spatialFilter =
          new SpatialFilter.Nearby(
              spatialColumn, nearbyLon, nearbyLat, Math.max(1, Math.min(nearbyRadius, 100000)));
    } else if (bboxMinLon != null
        && bboxMinLat != null
        && bboxMaxLon != null
        && bboxMaxLat != null) {
      spatialFilter =
          new SpatialFilter.Bbox(spatialColumn, bboxMinLon, bboxMinLat, bboxMaxLon, bboxMaxLat);
    }

    DataQueryResponse response =
        datasetDataService.getDatasetData(
            id,
            sanitizedSearch,
            page,
            size,
            sortBy,
            sortDir.toUpperCase(),
            includeTotalCount,
            spatialFilter);
    return ResponseEntity.ok(response);
  }

  // --- Phase 3-3: Favorites ---

  @PostMapping("/{id}/favorite")
  @RequirePermission("dataset:read")
  public ResponseEntity<FavoriteToggleResponse> toggleFavorite(
      @PathVariable Long id, Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    FavoriteToggleResponse response = datasetFavoriteService.toggleFavorite(id, userId);
    return ResponseEntity.ok(response);
  }

  /** 즐겨찾기 추가 — 이미 추가돼 있어도 추가 상태로 둔다(멱등, WD-388). 웹은 토글 대신 이것과 아래 DELETE 를 쓴다. */
  @PutMapping("/{id}/favorite")
  @RequirePermission("dataset:read")
  public ResponseEntity<FavoriteToggleResponse> addFavorite(
      @PathVariable Long id, Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    return ResponseEntity.ok(datasetFavoriteService.setFavorite(id, userId, true));
  }

  /** 즐겨찾기 해제 — 이미 해제돼 있어도 해제 상태로 둔다(멱등, WD-388). */
  @DeleteMapping("/{id}/favorite")
  @RequirePermission("dataset:read")
  public ResponseEntity<FavoriteToggleResponse> removeFavorite(
      @PathVariable Long id, Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    return ResponseEntity.ok(datasetFavoriteService.setFavorite(id, userId, false));
  }

  // --- Phase 4-3: Tags ---

  @GetMapping("/tags")
  @RequirePermission("dataset:read")
  public ResponseEntity<List<String>> getAllTags() {
    return ResponseEntity.ok(datasetTagService.getAllDistinctTags());
  }

  @PostMapping("/{id}/tags")
  @RequirePermission("dataset:write")
  public ResponseEntity<Void> addTag(
      @PathVariable Long id, @RequestBody AddTagRequest request, Authentication authentication) {
    // 거절 사유를 한국어로 돌려준다(WD-145) — 예전에는 빈 400/409 라 화면이 「태그 추가에 실패했습니다」만 보였다.
    // 400 은 IllegalArgumentException, 중복 409 는 서비스의 IllegalStateException 을 전역 핸들러가 본문과 함께 내려준다.
    validateTagName(request.tagName());
    Long userId = (Long) authentication.getPrincipal();
    datasetTagService.addTag(id, request.tagName(), userId);
    return ResponseEntity.status(HttpStatus.CREATED).build();
  }

  /** 태그 이름 규칙: 1~50자, 한글·영문·숫자·밑줄·하이픈만. 웹 입력창(DatasetDetailPage)의 사전 검사와 같은 규칙·문구다. */
  private static void validateTagName(String tagName) {
    if (tagName == null || tagName.isBlank()) {
      throw new IllegalArgumentException("태그 이름을 입력하세요.");
    }
    if (tagName.length() > 50) {
      throw new IllegalArgumentException("태그는 50자 이하로 입력하세요.");
    }
    if (!tagName.matches("[a-zA-Z0-9가-힣_\\-]+")) {
      throw new IllegalArgumentException(
          "태그에는 한글·영문·숫자·밑줄(_)·하이픈(-)만 쓸 수 있습니다. 띄어쓰기는 밑줄(_)이나 하이픈(-)으로 바꿔 입력하세요.");
    }
  }

  @DeleteMapping("/{id}/tags/{tagName}")
  @RequirePermission("dataset:write")
  public ResponseEntity<Void> deleteTag(
      @PathVariable Long id, @PathVariable String tagName, Authentication authentication) {
    // 변경 감사(WD-463)의 행위자로 남기기 위해 요청 사용자를 넘긴다.
    datasetTagService.deleteTag(id, tagName, (Long) authentication.getPrincipal());
    return ResponseEntity.noContent().build();
  }

  // --- Phase 6-1: Status ---

  @PutMapping("/{id}/status")
  @RequirePermission("dataset:write")
  public ResponseEntity<DatasetDetailResponse> updateStatus(
      @PathVariable Long id,
      @Valid @RequestBody UpdateStatusRequest request,
      Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    DatasetDetailResponse response = datasetService.updateStatus(id, request, userId);
    return ResponseEntity.ok(response);
  }

  // --- Phase 6-4: Description Propagation ---

  @PostMapping("/{id}/propagate-descriptions")
  @RequirePermission("dataset:write")
  public ResponseEntity<Void> propagateDescriptions(@PathVariable Long id) {
    datasetDataService.propagateDescriptions(id);
    return ResponseEntity.noContent().build();
  }

  // =========================================================================
  // SQL Query (Ad-hoc)
  // =========================================================================

  @PostMapping("/{id}/query")
  @RequirePermission("data:import")
  public ResponseEntity<SqlQueryResponse> executeQuery(
      @PathVariable Long id,
      @Valid @RequestBody SqlQueryRequest request,
      Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    SqlQueryResponse response = datasetDataService.executeQuery(id, request, userId);
    return ResponseEntity.ok(response);
  }

  @GetMapping("/{id}/queries")
  @RequirePermission("data:read")
  public ResponseEntity<PageResponse<QueryHistoryResponse>> getQueryHistory(
      @PathVariable Long id,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    page = Math.max(0, page);
    size = Math.max(1, Math.min(size, 100));
    PageResponse<QueryHistoryResponse> response =
        datasetDataService.getQueryHistory(id, page, size);
    return ResponseEntity.ok(response);
  }

  // =========================================================================
  // Manual Row Entry
  // =========================================================================

  @PostMapping("/{id}/data/rows")
  @RequirePermission("data:import")
  public ResponseEntity<RowDataResponse> addRow(
      @PathVariable Long id,
      @Valid @RequestBody RowDataRequest request,
      Authentication authentication) {
    // 변경 감사(WD-463)의 행위자로 남기기 위해 요청 사용자를 넘긴다.
    RowDataResponse response =
        datasetDataService.addRow(id, request, (Long) authentication.getPrincipal());
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
  }

  @PostMapping("/{id}/data/rows/batch")
  @RequirePermission("data:import")
  public ResponseEntity<BatchRowDataResponse> addRowsBatch(
      @PathVariable Long id, @Valid @RequestBody BatchRowDataRequest request) {
    BatchRowDataResponse response = datasetDataService.addRowsBatch(id, request);
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
  }

  @PutMapping("/{id}/data/rows/{rowId}")
  @RequirePermission("data:import")
  public ResponseEntity<Void> updateRow(
      @PathVariable Long id,
      @PathVariable Long rowId,
      @Valid @RequestBody RowDataRequest request,
      Authentication authentication) {
    // 변경 감사(WD-463)의 행위자로 남기기 위해 요청 사용자를 넘긴다.
    datasetDataService.updateRow(id, rowId, request, (Long) authentication.getPrincipal());
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/{id}/data/rows/{rowId}")
  @RequirePermission("data:read")
  public ResponseEntity<RowDataResponse> getRow(
      @PathVariable Long id, @PathVariable Long rowId, Authentication authentication) {
    // 스펙 §4.6 — 감사 등급 데이터셋의 단건 행 조회 기록(인터셉터가 VIEW 를 이미 통과시켰다).
    recordRowView(authentication, id);
    RowDataResponse response = datasetDataService.getRow(id, rowId);
    return ResponseEntity.ok(response);
  }

  // --- API Import ---

  @PostMapping("/{id}/api-import")
  @RequirePermission("pipeline:write")
  public ResponseEntity<ApiImportResponse> createApiImport(
      @PathVariable Long id, @RequestBody ApiImportRequest request, Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    ApiImportResponse response = apiImportService.createApiImport(id, request, userId);
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
  }

  // =========================================================================
  // Clone/Copy Dataset
  // =========================================================================

  @PostMapping("/{id}/clone")
  @RequirePermission("dataset:write")
  public ResponseEntity<DatasetDetailResponse> cloneDataset(
      @PathVariable Long id,
      @Valid @RequestBody CloneDatasetRequest request,
      Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    DatasetDetailResponse response = datasetService.cloneDataset(id, request, userId);
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
  }

  /**
   * 행 조회 접근 감사. 사용자 id 는 인증 주체(Long)에서 바로 꺼낸다 — 자격 전체 계산(ClearanceResolver)은 필요 없다. 감사 등급 여부 판정과 1분
   * 합치기는 기록기가 한다.
   */
  private void recordRowView(Authentication authentication, long datasetId) {
    if (authentication != null && authentication.getPrincipal() instanceof Long userId) {
      auditRecorder.recordAccess(
          userId, SecurityAuditRecorder.AccessKind.ROW_VIEW, List.of(datasetId));
    }
  }
}
