package com.smartfirehub.apiconnection.controller;

import com.smartfirehub.apiconnection.dto.ApiConnectionResponse;
import com.smartfirehub.apiconnection.dto.CreateApiConnectionRequest;
import com.smartfirehub.apiconnection.dto.UpdateApiConnectionRequest;
import com.smartfirehub.apiconnection.service.ApiConnectionService;
import com.smartfirehub.global.security.RequirePermission;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/api-connections")
public class ApiConnectionController {

    private final ApiConnectionService apiConnectionService;

    public ApiConnectionController(ApiConnectionService apiConnectionService) {
        this.apiConnectionService = apiConnectionService;
    }

    @GetMapping
    @RequirePermission("apiconnection:read")
    public ResponseEntity<List<ApiConnectionResponse>> getAll() {
        return ResponseEntity.ok(apiConnectionService.getAll());
    }

    @GetMapping("/{id}")
    @RequirePermission("apiconnection:read")
    public ResponseEntity<ApiConnectionResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(apiConnectionService.getById(id));
    }

    @PostMapping
    @RequirePermission("apiconnection:write")
    public ResponseEntity<ApiConnectionResponse> create(@RequestBody CreateApiConnectionRequest request) {
        Long userId = Long.parseLong(SecurityContextHolder.getContext().getAuthentication().getName());
        ApiConnectionResponse response = apiConnectionService.create(request, userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{id}")
    @RequirePermission("apiconnection:write")
    public ResponseEntity<ApiConnectionResponse> update(
            @PathVariable Long id,
            @RequestBody UpdateApiConnectionRequest request) {
        return ResponseEntity.ok(apiConnectionService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @RequirePermission("apiconnection:delete")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        apiConnectionService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
