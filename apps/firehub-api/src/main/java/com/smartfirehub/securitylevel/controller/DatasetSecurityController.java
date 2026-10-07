package com.smartfirehub.securitylevel.controller;

import com.smartfirehub.global.security.RequirePermission;
import com.smartfirehub.securitylevel.access.ClearanceResolver;
import com.smartfirehub.securitylevel.dto.AccessGrantResponse;
import com.smartfirehub.securitylevel.dto.AddAccessGrantRequest;
import com.smartfirehub.securitylevel.dto.ChangeDatasetLevelRequest;
import com.smartfirehub.securitylevel.dto.GrantCandidatesResponse;
import com.smartfirehub.securitylevel.service.DatasetSecurityService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * 데이터셋 등급·허용 목록 API. 경로가 {@code /api/v1/datasets/{id}/**} 라 DatasetAccessInterceptor 가 VIEW 를 먼저
 * 강제한다 — 볼 수 없는 데이터셋의 등급·목록을 바꾸거나 읽을 수 없다.
 */
@RestController
@RequestMapping("/api/v1/datasets/{id}")
@RequiredArgsConstructor
public class DatasetSecurityController {

  private final DatasetSecurityService service;
  private final ClearanceResolver clearanceResolver;

  /** 등급 변경 — 본인 자격 초과 금지·하향 사유 규칙은 서비스가 강제한다. */
  @PutMapping("/security-level")
  @RequirePermission("dataset:classify")
  public ResponseEntity<Void> changeLevel(
      @PathVariable Long id, @Valid @RequestBody ChangeDatasetLevelRequest req) {
    service.changeLevel(id, req, clearanceResolver.current());
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/access-grants")
  @RequirePermission("dataset:read")
  public List<AccessGrantResponse> listGrants(@PathVariable Long id) {
    return service.listGrants(id);
  }

  /** 추가 후보(활성 멤버·역할 이름만). 리터럴 경로가 {grantId} 변수 경로보다 우선한다. */
  @GetMapping("/access-grants/candidates")
  @RequirePermission("dataset:grant")
  public GrantCandidatesResponse candidates(@PathVariable Long id) {
    return service.candidates();
  }

  @PostMapping("/access-grants")
  @RequirePermission("dataset:grant")
  public AccessGrantResponse addGrant(
      @PathVariable Long id, @RequestBody AddAccessGrantRequest req, Authentication auth) {
    return service.addGrant(id, req, (Long) auth.getPrincipal());
  }

  @DeleteMapping("/access-grants/{grantId}")
  @RequirePermission("dataset:grant")
  public ResponseEntity<Void> removeGrant(
      @PathVariable Long id, @PathVariable Long grantId, Authentication auth) {
    service.removeGrant(id, grantId, (Long) auth.getPrincipal());
    return ResponseEntity.noContent().build();
  }
}
