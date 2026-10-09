package com.smartfirehub.dataimport.controller;

import com.smartfirehub.dataimport.dto.ExportEstimate;
import com.smartfirehub.dataimport.dto.ExportRequest;
import com.smartfirehub.dataimport.dto.ExportResult;
import com.smartfirehub.dataimport.service.DataExportService;
import com.smartfirehub.global.security.RequirePermission;
import com.smartfirehub.job.dto.AsyncJobStatusResponse;
import com.smartfirehub.job.repository.AsyncJobRepository;
import com.smartfirehub.job.service.AsyncJobService;
import com.smartfirehub.securitylevel.access.DatasetAccessGuard;
import com.smartfirehub.user.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class DataExportController {

  private final DataExportService exportService;
  private final AsyncJobService asyncJobService;
  private final UserRepository userRepository;

  /** 내보내기 정책 강제(스펙 §4.4) — 인터셉터 VIEW 다음, 서비스 @Transactional 밖에서 판정한다. */
  private final DatasetAccessGuard guard;

  /** 비동기 내보내기 파일의 대상 데이터셋 조회(다운로드 시점 재판정용). */
  private final AsyncJobRepository asyncJobRepository;

  @GetMapping("/datasets/{datasetId}/export/estimate")
  @RequirePermission("data:export")
  public ResponseEntity<ExportEstimate> estimateExport(
      @PathVariable Long datasetId, @RequestParam(required = false) String search) {
    ExportRequest request = new ExportRequest(null, null, search, null);
    return ResponseEntity.ok(exportService.estimateExport(datasetId, request));
  }

  /**
   * 동기/비동기 내보내기 엔드포인트.
   *
   * <p>비동기 경로: jobId를 JSON으로 반환. 동기 경로: SpringMVC의 ResponseEntity 반환 방식으로는 ResponseEntity&lt;?&gt;
   * 제네릭 미지정 시 StreamingResponseBodyReturnValueHandler가 작동하지 않으므로(HttpMessageNotWritableException),
   * HttpServletResponse에 직접 쓰는 방식으로 처리한다.
   */
  @PostMapping("/datasets/{datasetId}/export")
  @RequirePermission("data:export")
  public ResponseEntity<?> exportDataset(
      @PathVariable Long datasetId,
      @Valid @RequestBody ExportRequest request,
      HttpServletRequest httpRequest,
      HttpServletResponse httpResponse,
      Authentication authentication)
      throws IOException {

    // 등급 export_policy 강제 — 거부는 403 POLICY_BLOCKED + 감사(REQUIRES_NEW 라 롤백에 휩쓸리지 않는다).
    guard.requireExport(datasetId);

    Long userId = (Long) authentication.getPrincipal();
    String username =
        userRepository.findById(userId).map(u -> u.name()).orElse(String.valueOf(userId));
    String ipAddress = httpRequest.getRemoteAddr();
    String userAgent = httpRequest.getHeader("User-Agent");

    ExportResult result =
        exportService.exportDataset(datasetId, request, userId, username, ipAddress, userAgent);

    if (result.async()) {
      return ResponseEntity.accepted().body(Map.of("jobId", result.jobId()));
    }

    // 동기 경로: ResponseEntity<?>의 제네릭 소거로 StreamingResponseBodyReturnValueHandler가
    // 매칭되지 않아 HttpMessageNotWritableException이 발생한다.
    // HttpServletResponse에 직접 쓰는 방식으로 우회한다.
    httpResponse.setStatus(HttpServletResponse.SC_OK);
    httpResponse.setContentType(result.contentType());
    httpResponse.setHeader("Content-Disposition", buildContentDisposition(result.filename()));
    result.streamingBody().writeTo(httpResponse.getOutputStream());
    httpResponse.flushBuffer();
    return null;
  }

  @GetMapping("/exports/{jobId}/file")
  @RequirePermission("data:export")
  public ResponseEntity<StreamingResponseBody> downloadExportFile(
      @PathVariable String jobId, Authentication authentication) {

    Long userId = (Long) authentication.getPrincipal();
    Path filePath = exportService.getExportFile(jobId, userId);

    // Review Focus 4 — 작업 생성 뒤 등급이 오를 수 있으므로 다운로드 시점 자격·등급으로 다시 판정한다. 데이터셋 내보내기 작업이
    // 아니면(대상 없음) 판정하지 않는다.
    asyncJobRepository.findDatasetResourceId(jobId).ifPresent(guard::requireExport);

    AsyncJobStatusResponse job = asyncJobService.getJobStatus(jobId, userId);
    String filename = (String) job.metadata().getOrDefault("filename", "export");
    String contentType =
        (String) job.metadata().getOrDefault("contentType", "application/octet-stream");

    StreamingResponseBody body =
        outputStream -> {
          try (InputStream is = Files.newInputStream(filePath)) {
            is.transferTo(outputStream);
          }
        };

    return ResponseEntity.ok()
        .header("Content-Type", contentType)
        .header("Content-Disposition", buildContentDisposition(filename))
        .header("Content-Length", String.valueOf(filePath.toFile().length()))
        .body(body);
  }

  private String buildContentDisposition(String filename) {
    String sanitized = filename.replaceAll("[^a-zA-Z0-9가-힣._\\-]", "_");
    String encoded = URLEncoder.encode(sanitized, StandardCharsets.UTF_8).replace("+", "%20");
    return "attachment; filename=\"" + sanitized + "\"; filename*=UTF-8''" + encoded;
  }
}
