package com.smartfirehub.securitylevel.controller;

import com.smartfirehub.global.security.RequirePermission;
import com.smartfirehub.securitylevel.access.ClearanceResolver;
import com.smartfirehub.securitylevel.dto.ClearancePreviewResponse;
import com.smartfirehub.securitylevel.dto.RoleClearanceRequest;
import com.smartfirehub.securitylevel.dto.RoleClearanceResponse;
import com.smartfirehub.securitylevel.service.RoleClearanceService;
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

/** 역할 열람 자격 API — 역할 편집과 같은 권한(role:read/role:write). */
@RestController
@RequestMapping("/api/v1/roles/{id}/clearance")
@RequiredArgsConstructor
public class RoleClearanceController {

  private final RoleClearanceService service;
  private final ClearanceResolver clearanceResolver;

  @GetMapping
  @RequirePermission("role:read")
  public RoleClearanceResponse get(@PathVariable Long id) {
    return service.get(id);
  }

  @PostMapping("/preview")
  @RequirePermission("role:write")
  public ClearancePreviewResponse preview(
      @PathVariable Long id, @Valid @RequestBody RoleClearanceRequest req) {
    return service.preview(id, req.securityLevelId(), clearanceResolver.current());
  }

  @PutMapping
  @RequirePermission("role:write")
  public ResponseEntity<Void> set(
      @PathVariable Long id, @Valid @RequestBody RoleClearanceRequest req) {
    service.set(id, req.securityLevelId(), clearanceResolver.current());
    return ResponseEntity.noContent().build();
  }
}
