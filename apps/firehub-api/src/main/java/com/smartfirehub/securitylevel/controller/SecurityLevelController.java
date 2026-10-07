package com.smartfirehub.securitylevel.controller;

import com.smartfirehub.global.security.RequirePermission;
import com.smartfirehub.securitylevel.dto.DeleteSecurityLevelRequest;
import com.smartfirehub.securitylevel.dto.MyClearanceResponse;
import com.smartfirehub.securitylevel.dto.SecurityLevelRequest;
import com.smartfirehub.securitylevel.dto.SecurityLevelResponse;
import com.smartfirehub.securitylevel.dto.SecurityLevelUsage;
import com.smartfirehub.securitylevel.service.SecurityLevelService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * 보안 등급 관리 API(스펙 §2.1, §4.7). 목록·내 자격은 인증만 요구한다 — 배지·등급 선택 UI 가 모든 사용자에게 필요하다(등급 이름·정책은 비밀이 아니다).
 * 사용량과 변경은 security:settings.
 */
@RestController
@RequestMapping("/api/v1/security-levels")
@RequiredArgsConstructor
public class SecurityLevelController {

  private final SecurityLevelService service;

  @GetMapping
  public List<SecurityLevelResponse> list() {
    return service.list();
  }

  @GetMapping("/my-clearance")
  public MyClearanceResponse myClearance() {
    return service.myClearance();
  }

  @GetMapping("/usage")
  @RequirePermission("security:settings")
  public List<SecurityLevelUsage> usage() {
    return service.usage();
  }

  @PostMapping
  @RequirePermission("security:settings")
  public SecurityLevelResponse create(
      @Valid @RequestBody SecurityLevelRequest req, Authentication auth) {
    return service.create(req, (Long) auth.getPrincipal());
  }

  @PutMapping("/{id}")
  @RequirePermission("security:settings")
  public SecurityLevelResponse update(
      @PathVariable Long id, @Valid @RequestBody SecurityLevelRequest req, Authentication auth) {
    return service.update(id, req, (Long) auth.getPrincipal());
  }

  @PutMapping("/{id}/default")
  @RequirePermission("security:settings")
  public ResponseEntity<Void> setDefault(@PathVariable Long id, Authentication auth) {
    service.setDefault(id, (Long) auth.getPrincipal());
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/{id}")
  @RequirePermission("security:settings")
  public ResponseEntity<Void> delete(
      @PathVariable Long id,
      @RequestBody(required = false) DeleteSecurityLevelRequest req,
      Authentication auth) {
    service.delete(id, req, (Long) auth.getPrincipal());
    return ResponseEntity.noContent().build();
  }
}
