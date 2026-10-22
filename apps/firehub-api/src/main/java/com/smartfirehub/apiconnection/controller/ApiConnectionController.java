package com.smartfirehub.apiconnection.controller;

import com.smartfirehub.apiconnection.dto.ApiConnectionReferencesResponse;
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

/**
 * API 연결 관리 REST 컨트롤러.
 *
 * <p>인증정보 원문을 HTTP 로 내보내는 경로는 두지 않는다(WD-175). 예전 {@code GET /{id}/decrypted} 는 읽기 권한({@code
 * apiconnection:read})만으로 토큰·API 키 평문을 돌려줘, 수정 권한이 없는 일반 사용자도 비밀을 통째로 가져갈 수 있었다. 소비자가 없음을 확인하고 지웠다
 * — 실행기·미리보기는 {@link ApiConnectionService#getDecryptedAuthConfig} 를 서버 안에서만 쓴다.
 */
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
      @PathVariable Long id,
      @Valid @RequestBody UpdateApiConnectionRequest request,
      Authentication authentication) {
    // 수정한 사용자를 감사 행위자로 넘긴다(WD-181) — 생성과 같은 방식.
    Long userId = (Long) authentication.getPrincipal();
    return ResponseEntity.ok(apiConnectionService.update(id, request, userId));
  }

  @DeleteMapping("/{id}")
  @RequirePermission("apiconnection:delete")
  public ResponseEntity<Void> deleteApiConnection(
      @PathVariable Long id, Authentication authentication) {
    // 삭제한 사용자를 감사 행위자로 넘긴다(WD-181).
    Long userId = (Long) authentication.getPrincipal();
    apiConnectionService.delete(id, userId);
    return ResponseEntity.noContent().build();
  }

  /** 이 API 연결을 참조하는 파이프라인 집계. 삭제 전 영향 범위 확인용(#605). */
  @GetMapping("/{id}/references")
  @RequirePermission("apiconnection:read")
  public ResponseEntity<ApiConnectionReferencesResponse> getReferences(@PathVariable Long id) {
    return ResponseEntity.ok(apiConnectionService.getReferences(id));
  }

  /** 파이프라인 스텝에서 사용할 API 연결 slim 목록. 일반 사용자도 접근 가능하나 민감 필드(authConfig 등)는 제외되어 내려간다. */
  @GetMapping("/selectable")
  public List<ApiConnectionSelectableResponse> getSelectable() {
    return apiConnectionService.findSelectable();
  }

  /**
   * 저장된 API 연결의 상태를 즉시 점검한다. healthCheckPath 기반 GET 요청으로 2xx면 UP, 그 외 DOWN 처리 + DB 반영.
   *
   * <p>{id:\\d+} 제약으로 숫자 ID만 매핑 → POST /test 와의 라우팅 충돌 방지. (#113)
   */
  @PostMapping("/{id:\\d+}/test")
  @RequirePermission("apiconnection:write")
  public TestConnectionResponse testConnection(@PathVariable Long id) {
    return apiConnectionService.testConnection(id);
  }

  /**
   * (#90) 저장 전 dry-run 헬스체크. payload(인증/URL/경로)만 받아 외부 API 호출을 시도하고 결과를 반환한다. DB 미저장. 사용 시점: "새 API
   * 연결 생성" 다이얼로그에서 입력값 검증.
   */
  @PostMapping("/test")
  @RequirePermission("apiconnection:write")
  public TestConnectionResponse testConnectionPayload(
      @Valid @RequestBody CreateApiConnectionRequest request) {
    return apiConnectionService.testConnectionPayload(request);
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
