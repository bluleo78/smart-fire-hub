package com.smartfirehub.analytics.controller;

import com.smartfirehub.analytics.dto.AnalyticsQueryRequest;
import com.smartfirehub.analytics.dto.AnalyticsQueryResponse;
import com.smartfirehub.analytics.dto.CreateSavedQueryRequest;
import com.smartfirehub.analytics.dto.ExportCheckRequest;
import com.smartfirehub.analytics.dto.ExportCheckResponse;
import com.smartfirehub.analytics.dto.QueryRunExportRequest;
import com.smartfirehub.analytics.dto.SavedQueryListResponse;
import com.smartfirehub.analytics.dto.SavedQueryResponse;
import com.smartfirehub.analytics.dto.SchemaInfoResponse;
import com.smartfirehub.analytics.dto.UpdateSavedQueryRequest;
import com.smartfirehub.analytics.service.AnalyticsQueryExecutionService;
import com.smartfirehub.analytics.service.QueryResultExportService;
import com.smartfirehub.analytics.service.SavedQueryService;
import com.smartfirehub.global.dto.PageResponse;
import com.smartfirehub.global.security.RequirePermission;
import com.smartfirehub.global.util.NormalizedSql;
import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.ClearanceResolver;
import com.smartfirehub.securitylevel.access.DatasetAccessGuard;
import com.smartfirehub.securitylevel.access.SqlAccessMode;
import com.smartfirehub.securitylevel.access.SqlAccessResult;
import com.smartfirehub.securitylevel.sql.GuardedSqlExecutor;
import jakarta.validation.Valid;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/v1/analytics/queries")
@RequiredArgsConstructor
public class SavedQueryController {

  private final SavedQueryService savedQueryService;
  private final AnalyticsQueryExecutionService executionService;
  private final GuardedSqlExecutor guardedSqlExecutor;
  private final ClearanceResolver clearanceResolver;
  private final DatasetAccessGuard datasetAccessGuard;

  /** 실행 기록 id 기반 쿼리 결과 내보내기(스펙 §4.4). */
  private final QueryResultExportService queryResultExportService;

  @GetMapping
  @RequirePermission("analytics:read")
  public ResponseEntity<PageResponse<SavedQueryListResponse>> listQueries(
      @RequestParam(required = false) String search,
      @RequestParam(required = false) String folder,
      @RequestParam(required = false) Boolean sharedOnly,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      Authentication authentication) {
    page = Math.max(0, page);
    size = Math.max(1, Math.min(size, 100));
    Long userId = (Long) authentication.getPrincipal();
    return ResponseEntity.ok(
        savedQueryService.list(search, folder, sharedOnly, userId, page, size));
  }

  @PostMapping
  @RequirePermission("analytics:write")
  public ResponseEntity<SavedQueryResponse> createQuery(
      @Valid @RequestBody CreateSavedQueryRequest request, Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    SavedQueryResponse created = savedQueryService.create(request, userId);
    return ResponseEntity.status(HttpStatus.CREATED).body(created);
  }

  @GetMapping("/schema")
  @RequirePermission("analytics:read")
  public ResponseEntity<SchemaInfoResponse> getSchema(
      @RequestParam(required = false) List<Long> datasetIds) {
    // datasetIds 미지정 시 null 위임 → 서비스 BC 분기로 전체 스키마 반환.
    // 지정 시 (?datasetIds=11 / ?datasetIds=11,7) 해당 데이터셋만 필터링 — ai-agent 응답 크기 절감.
    // 스키마 목록도 열람 가능한 데이터셋만(스펙 §4.2 3행).
    String visibility = datasetAccessGuard.visibleSql(clearanceResolver.current(), "d");
    return ResponseEntity.ok(executionService.getSchemaInfo(datasetIds, visibility));
  }

  @GetMapping("/folders")
  @RequirePermission("analytics:read")
  public ResponseEntity<List<String>> getFolders(Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    return ResponseEntity.ok(savedQueryService.getFolders(userId));
  }

  @PostMapping("/execute")
  @RequirePermission("analytics:read")
  public ResponseEntity<AnalyticsQueryResponse> executeAdHoc(
      @Valid @RequestBody AnalyticsQueryRequest request) {
    int maxRows = request.maxRows() != null ? request.maxRows() : 1000;
    Clearance c = clearanceResolver.current();
    // Web UI 애드혹 쿼리는 항상 readOnly=true 강제 — DELETE/UPDATE 허용 금지 (#66)
    // 보안 등급(S2): 실행자 자격으로 참조 데이터셋을 판정한다. 응답에는 조회자 기준 exportAllowed 가 실린다.
    AnalyticsQueryResponse r = guardedSqlExecutor.executeAnalytics(c, request.sql(), maxRows, true);
    // 성공한 사용자 SELECT 만 실행 기록을 남긴다 — 내보내기는 이 id 로 서버가 다시 판정·실행한다(스펙 §4.4). AI 대행은 남기지 않는다.
    r = queryResultExportService.attachRun(c, request.sql(), maxRows, r);
    return ResponseEntity.ok(r);
  }

  /**
   * 쿼리 결과 내보내기 — 실행 기록 id 기반 서버 재판정·재실행(스펙 §4.4). 클라이언트 rows 는 받지 않는다. 남의 id·없는 id·만료는 같은 404
   * QUERY_RUN_NOT_FOUND.
   */
  @PostMapping("/runs/{runId}/export")
  @RequirePermission("data:export")
  public ResponseEntity<StreamingResponseBody> exportRun(
      @PathVariable UUID runId, @Valid @RequestBody QueryRunExportRequest request) {
    QueryResultExportService.ExportFile f =
        queryResultExportService.export(runId, request.format(), clearanceResolver.current());
    // DataExportController 와 같은 파일 이름 규칙 — 안전 문자만 남기고 RFC 5987 인코딩 이름을 함께 싣는다.
    String sanitized = f.filename().replaceAll("[^a-zA-Z0-9가-힣._\\-]", "_");
    String encoded = URLEncoder.encode(sanitized, StandardCharsets.UTF_8).replace("+", "%20");
    return ResponseEntity.ok()
        .header("Content-Type", f.contentType())
        .header(
            "Content-Disposition",
            "attachment; filename=\"" + sanitized + "\"; filename*=UTF-8''" + encoded)
        .body(f.body());
  }

  /**
   * 화면 표시 데이터(AI 표 위젯)의 내보내기 가능 여부 — 위젯이 다운로드를 보일지 묻는다(UI 수준 차단, 설계 결정 7). 숨김·파싱 실패·정책 위반은 모두
   * false(구분 불가 — 존재 오라클이 되지 않게). 판정만 하고 실행·감사하지 않는다(값 판정, 설계 결정 3).
   */
  @PostMapping("/export-check")
  @RequirePermission("analytics:read")
  public ResponseEntity<ExportCheckResponse> exportCheck(
      @Valid @RequestBody ExportCheckRequest request) {
    Clearance c = clearanceResolver.current();
    boolean allowed;
    try {
      SqlAccessResult r =
          datasetAccessGuard.checkSql(
              c, NormalizedSql.of(request.sql()).text(), SqlAccessMode.INTERACTIVE);
      allowed =
          r.allowed()
              && r.exportAllowed()
              && c.permissions().contains(DatasetAccessGuard.EXPORT_PERMISSION);
    } catch (RuntimeException e) {
      // 정규화·파싱 실패도 false — 실패 사유를 돌려주면 숨김과 구분되는 신호가 된다.
      allowed = false;
    }
    return ResponseEntity.ok(new ExportCheckResponse(allowed));
  }

  @GetMapping("/{id}")
  @RequirePermission("analytics:read")
  public ResponseEntity<SavedQueryResponse> getQuery(
      @PathVariable Long id, Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    return ResponseEntity.ok(savedQueryService.getById(id, userId));
  }

  @PutMapping("/{id}")
  @RequirePermission("analytics:write")
  public ResponseEntity<SavedQueryResponse> updateQuery(
      @PathVariable Long id,
      @Valid @RequestBody UpdateSavedQueryRequest request,
      Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    return ResponseEntity.ok(savedQueryService.update(id, request, userId));
  }

  @DeleteMapping("/{id}")
  @RequirePermission("analytics:write")
  public ResponseEntity<Void> deleteQuery(@PathVariable Long id, Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    savedQueryService.delete(id, userId);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/{id}/execute")
  @RequirePermission("analytics:read")
  public ResponseEntity<AnalyticsQueryResponse> executeSavedQuery(
      @PathVariable Long id,
      @RequestBody(required = false) AnalyticsQueryRequest request,
      Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    int maxRows = request != null && request.maxRows() != null ? request.maxRows() : 1000;
    // #178 analytics:read 권한만 요구하는 엔드포인트이므로 클라이언트 readOnly 값을 무시하고
    // 항상 readOnly=true 강제 — INSERT/UPDATE/DELETE 등 쓰기 SQL 우회 차단.
    return ResponseEntity.ok(savedQueryService.executeById(id, maxRows, true, userId));
  }

  @PostMapping("/{id}/clone")
  @RequirePermission("analytics:write")
  public ResponseEntity<SavedQueryResponse> cloneQuery(
      @PathVariable Long id, Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    SavedQueryResponse cloned = savedQueryService.clone(id, userId);
    return ResponseEntity.status(HttpStatus.CREATED).body(cloned);
  }
}
