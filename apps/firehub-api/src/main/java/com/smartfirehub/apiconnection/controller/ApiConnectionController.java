package com.smartfirehub.apiconnection.controller;

import com.smartfirehub.apiconnection.dto.ApiConnectionResponse;
import com.smartfirehub.apiconnection.dto.CreateApiConnectionRequest;
import com.smartfirehub.apiconnection.dto.UpdateApiConnectionRequest;
import com.smartfirehub.apiconnection.service.ApiConnectionService;
import com.smartfirehub.global.security.RequirePermission;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/api-connections")
public class ApiConnectionController {

  private final ApiConnectionService apiConnectionService;

  public ApiConnectionController(ApiConnectionService apiConnectionService) {
    this.apiConnectionService = apiConnectionService;
  }

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
}
