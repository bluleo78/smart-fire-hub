package com.smartfirehub.apiconnection.controller;

import com.smartfirehub.apiconnection.dto.ApiConnectionResponse;
import com.smartfirehub.apiconnection.dto.ApiConnectionSelectableResponse;
import com.smartfirehub.apiconnection.dto.CreateApiConnectionRequest;
import com.smartfirehub.apiconnection.dto.TestConnectionResponse;
import com.smartfirehub.apiconnection.dto.UpdateApiConnectionRequest;
import com.smartfirehub.apiconnection.service.ApiConnectionService;
import com.smartfirehub.global.security.RequirePermission;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/api-connections")
@RequiredArgsConstructor
public class ApiConnectionController {

  private final ApiConnectionService apiConnectionService;

  @GetMapping
  @RequirePermission("apiconnection:read")
  public ResponseEntity<List<ApiConnectionResponse>> getApiConnections() {
    return ResponseEntity.ok(apiConnectionService.getAll());
  }

  @GetMapping("/{id}")
  @RequirePermission("apiconnection:read")
  public ResponseEntity<ApiConnectionResponse> getApiConnectionById(@PathVariable Long id) {
    return ResponseEntity.ok(apiConnectionService.getById(id));
  }

  @PostMapping
  @RequirePermission("apiconnection:write")
  public ResponseEntity<ApiConnectionResponse> createApiConnection(
      @Valid @RequestBody CreateApiConnectionRequest request, Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    ApiConnectionResponse response = apiConnectionService.create(request, userId);
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
  }

  @PutMapping("/{id}")
  @RequirePermission("apiconnection:write")
  public ResponseEntity<ApiConnectionResponse> updateApiConnection(
      @PathVariable Long id, @Valid @RequestBody UpdateApiConnectionRequest request) {
    return ResponseEntity.ok(apiConnectionService.update(id, request));
  }

  @DeleteMapping("/{id}")
  @RequirePermission("apiconnection:delete")
  public ResponseEntity<Void> deleteApiConnection(@PathVariable Long id) {
    apiConnectionService.delete(id);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/{id}/decrypted")
  @RequirePermission("apiconnection:read")
  public ResponseEntity<java.util.Map<String, String>> getDecryptedApiConnection(
      @PathVariable Long id) {
    return ResponseEntity.ok(apiConnectionService.getDecryptedAuthConfig(id));
  }

  /** 파이프라인 스텝에서 사용할 API 연결 slim 목록. 일반 사용자도 접근 가능하나 민감 필드(authConfig 등)는 제외되어 내려간다. */
  @GetMapping("/selectable")
  public List<ApiConnectionSelectableResponse> getSelectable() {
    return apiConnectionService.findSelectable();
  }

  /** 저장된 API 연결의 상태를 즉시 점검한다. healthCheckPath 기반 GET 요청으로 2xx면 UP, 그 외 DOWN 처리 + DB 반영. */
  @PostMapping("/{id}/test")
  @RequirePermission("apiconnection:write")
  public TestConnectionResponse testConnection(@PathVariable Long id) {
    return apiConnectionService.testConnection(id);
  }

  /** 모든 헬스체크 가능 연결을 즉시 갱신하는 비동기 Job을 시작한다. 응답은 jobId를 반환하며, 진행률은 SSE로 확인 가능하다. */
  @PostMapping("/refresh-all")
  @RequirePermission("apiconnection:write")
  public Map<String, String> refreshAll(Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    String jobId = apiConnectionService.refreshAllAsync(userId);
    return Map.of("jobId", jobId);
  }
}
