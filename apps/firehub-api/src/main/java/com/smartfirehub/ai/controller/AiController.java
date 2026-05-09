package com.smartfirehub.ai.controller;

import com.smartfirehub.ai.dto.*;
import com.smartfirehub.ai.service.AiAgentProxyService;
import com.smartfirehub.ai.service.AiSessionService;
import com.smartfirehub.global.security.RequirePermission;
import com.smartfirehub.settings.service.SettingsService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/ai")
@RequiredArgsConstructor
public class AiController {

  private final AiSessionService aiSessionService;
  private final AiAgentProxyService aiAgentProxyService;
  private final SettingsService settingsService;

  /**
   * AI 세션 목록을 페이지네이션으로 조회한다.
   *
   * <p>page·size 파라미터를 적용하여 전체 세션 대신 요청된 범위만 반환한다. 기본값: page=0, size=20.
   *
   * @param authentication 현재 인증 정보 (userId 추출용)
   * @param page 0-based 페이지 번호 (기본값 0)
   * @param size 페이지당 항목 수 (기본값 20, 최대 100)
   * @return 페이지네이션된 세션 목록 응답
   */
  @GetMapping("/sessions")
  @RequirePermission("ai:read")
  public ResponseEntity<List<AiSessionResponse>> getSessions(
      Authentication authentication,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    Long userId = (Long) authentication.getPrincipal();
    // size 상한 제한: 과도한 요청으로 인한 성능 저하 방지
    int effectiveSize = Math.min(size, 100);
    return ResponseEntity.ok(aiSessionService.getSessions(userId, page, effectiveSize));
  }

  @PostMapping("/sessions")
  @RequirePermission("ai:write")
  public ResponseEntity<AiSessionResponse> createSession(
      Authentication authentication, @Valid @RequestBody CreateAiSessionRequest request) {
    Long userId = (Long) authentication.getPrincipal();
    AiSessionResponse response = aiSessionService.createSession(userId, request);
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
  }

  @PutMapping("/sessions/{id}")
  @RequirePermission("ai:write")
  public ResponseEntity<Void> updateSession(
      Authentication authentication,
      @PathVariable Long id,
      @Valid @RequestBody UpdateAiSessionRequest request) {
    Long userId = (Long) authentication.getPrincipal();
    aiSessionService.updateSessionTitle(userId, id, request.title());
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/sessions/{id}")
  @RequirePermission("ai:write")
  public ResponseEntity<Void> deleteSession(Authentication authentication, @PathVariable Long id) {
    Long userId = (Long) authentication.getPrincipal();
    aiSessionService.deleteSession(userId, id);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/sessions/{sessionId}/messages")
  @RequirePermission("ai:read")
  public ResponseEntity<String> getSessionMessages(
      Authentication authentication, @PathVariable String sessionId) {
    Long userId = (Long) authentication.getPrincipal();
    aiSessionService.verifySessionOwnership(userId, sessionId);
    String history = aiAgentProxyService.getSessionHistory(sessionId);
    return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(history);
  }

  @GetMapping("/auth-status")
  @RequirePermission("ai:settings")
  public ResponseEntity<String> getAuthStatus() {
    Map<String, String> aiSettings = settingsService.getAsMap("ai");
    String agentType = aiSettings.getOrDefault("ai.agent_type", "sdk");
    String result;
    if ("cli".equals(agentType)) {
      result = aiAgentProxyService.verifyCliToken();
    } else {
      result = aiAgentProxyService.verifyApiKey();
    }
    return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(result);
  }

  @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  @RequirePermission("ai:write")
  public SseEmitter chat(Authentication authentication, @RequestBody ChatRequest request) {
    boolean hasMessage = request.message() != null && !request.message().isBlank();
    boolean hasFiles = request.fileIds() != null && !request.fileIds().isEmpty();

    if (!hasMessage && !hasFiles) {
      throw new IllegalArgumentException("message 또는 fileIds 중 하나는 필수입니다");
    }

    Long userId = (Long) authentication.getPrincipal();
    SseEmitter emitter = new SseEmitter(300_000L); // 5 minutes

    aiAgentProxyService.streamChat(
        emitter,
        request.message(),
        request.sessionId(),
        request.fileIds(),
        userId,
        request.navigationContext(),
        request.screenContext());

    return emitter;
  }
}
