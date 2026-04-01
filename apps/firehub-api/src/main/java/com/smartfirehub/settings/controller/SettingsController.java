package com.smartfirehub.settings.controller;

import com.smartfirehub.global.security.RequirePermission;
import com.smartfirehub.settings.dto.SettingResponse;
import com.smartfirehub.settings.dto.UpdateSettingsRequest;
import com.smartfirehub.settings.service.SettingsService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/settings")
@RequiredArgsConstructor
public class SettingsController {

  private final SettingsService settingsService;

  @GetMapping
  @RequirePermission("ai:settings")
  public ResponseEntity<List<SettingResponse>> getSettings(@RequestParam String prefix) {
    return ResponseEntity.ok(settingsService.getByPrefix(prefix));
  }

  @GetMapping("/ai-api-key")
  @RequirePermission("ai:settings")
  public ResponseEntity<java.util.Map<String, String>> getDecryptedAiApiKey() {
    return settingsService
        .getDecryptedApiKey()
        .map(key -> ResponseEntity.ok(java.util.Map.of("apiKey", key)))
        .orElse(ResponseEntity.ok(java.util.Map.of("apiKey", "")));
  }

  @PutMapping
  @RequirePermission("ai:settings")
  public ResponseEntity<Void> updateSettings(
      Authentication authentication, @Valid @RequestBody UpdateSettingsRequest request) {
    Long userId = (Long) authentication.getPrincipal();
    settingsService.updateSettings(request.settings(), userId);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/smtp")
  @RequirePermission("settings:write")
  public ResponseEntity<List<SettingResponse>> getSmtpSettings() {
    return ResponseEntity.ok(settingsService.getSmtpSettings());
  }

  @PutMapping("/smtp")
  @RequirePermission("settings:write")
  public ResponseEntity<Void> updateSmtpSettings(
      Authentication authentication, @RequestBody Map<String, String> settings) {
    Long userId = (Long) authentication.getPrincipal();
    settingsService.updateSmtpSettings(settings, userId);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/smtp/test")
  @RequirePermission("settings:write")
  public ResponseEntity<Map<String, Object>> testSmtpSettings(Authentication authentication) {
    // SMTP 연결 테스트 — 현재 설정으로 실제 연결 확인
    try {
      Map<String, String> config = settingsService.getSmtpConfig();
      String host = config.getOrDefault("smtp.host", "");
      if (host.isBlank()) {
        return ResponseEntity.ok(Map.of("success", false, "message", "SMTP 호스트가 설정되지 않았습니다"));
      }

      JavaMailSenderImpl sender = new JavaMailSenderImpl();
      sender.setHost(host);
      String portStr = config.getOrDefault("smtp.port", "587");
      sender.setPort(portStr.isBlank() ? 587 : Integer.parseInt(portStr));
      String username = config.getOrDefault("smtp.username", "");
      if (!username.isBlank()) sender.setUsername(username);
      String password = config.getOrDefault("smtp.password", "");
      if (!password.isBlank()) sender.setPassword(password);

      Properties props = sender.getJavaMailProperties();
      props.put("mail.transport.protocol", "smtp");
      props.put("mail.smtp.auth", !username.isBlank() ? "true" : "false");
      props.put("mail.smtp.connectiontimeout", "10000");
      props.put("mail.smtp.timeout", "10000");
      String starttls = config.getOrDefault("smtp.starttls", "true");
      if ("true".equalsIgnoreCase(starttls)) {
        props.put("mail.smtp.starttls.enable", "true");
      }

      sender.testConnection();
      return ResponseEntity.ok(Map.of("success", true, "message", "SMTP 연결 성공"));
    } catch (Exception e) {
      return ResponseEntity.ok(Map.of("success", false, "message", e.getMessage()));
    }
  }
}
