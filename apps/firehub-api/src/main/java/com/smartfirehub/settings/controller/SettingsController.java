package com.smartfirehub.settings.controller;

import com.smartfirehub.global.security.RequirePermission;
import com.smartfirehub.settings.dto.SettingResponse;
import com.smartfirehub.settings.dto.UpdateSettingsRequest;
import com.smartfirehub.settings.service.SettingsService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/settings")
public class SettingsController {

  private final SettingsService settingsService;

  public SettingsController(SettingsService settingsService) {
    this.settingsService = settingsService;
  }

  @GetMapping
  @RequirePermission("ai:settings")
  public ResponseEntity<List<SettingResponse>> getSettings(@RequestParam String prefix) {
    return ResponseEntity.ok(settingsService.getByPrefix(prefix));
  }

  @PutMapping
  @RequirePermission("ai:settings")
  public ResponseEntity<Void> updateSettings(
      Authentication authentication, @Valid @RequestBody UpdateSettingsRequest request) {
    Long userId = (Long) authentication.getPrincipal();
    settingsService.updateSettings(request.settings(), userId);
    return ResponseEntity.noContent().build();
  }
}
