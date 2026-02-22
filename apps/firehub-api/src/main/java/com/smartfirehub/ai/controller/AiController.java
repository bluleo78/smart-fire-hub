package com.smartfirehub.ai.controller;

import com.smartfirehub.ai.dto.*;
import com.smartfirehub.ai.service.AiAgentProxyService;
import com.smartfirehub.ai.service.AiSessionService;
import com.smartfirehub.global.security.RequirePermission;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/ai")
public class AiController {

  private final AiSessionService aiSessionService;
  private final AiAgentProxyService aiAgentProxyService;

  public AiController(AiSessionService aiSessionService, AiAgentProxyService aiAgentProxyService) {
    this.aiSessionService = aiSessionService;
    this.aiAgentProxyService = aiAgentProxyService;
  }

  @GetMapping("/sessions")
  @RequirePermission("ai:read")
  public ResponseEntity<List<AiSessionResponse>> getSessions(Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    return ResponseEntity.ok(aiSessionService.getSessions(userId));
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

  @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  @RequirePermission("ai:write")
  public SseEmitter chat(Authentication authentication, @Valid @RequestBody ChatRequest request) {
    Long userId = (Long) authentication.getPrincipal();

    SseEmitter emitter = new SseEmitter(300_000L); // 5 minutes

    aiAgentProxyService.streamChat(emitter, request.message(), request.sessionId(), userId);

    return emitter;
  }
}
