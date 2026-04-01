package com.smartfirehub.proactive.controller;

import com.smartfirehub.global.security.RequirePermission;
import com.smartfirehub.proactive.dto.ProactiveMessageResponse;
import com.smartfirehub.proactive.repository.ProactiveMessageRepository;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/proactive/messages")
@RequiredArgsConstructor
public class ProactiveMessageController {

  private final ProactiveMessageRepository messageRepository;

  @GetMapping
  @RequirePermission("proactive:read")
  public ResponseEntity<List<ProactiveMessageResponse>> getMessages(
      @RequestParam(defaultValue = "20") int limit,
      @RequestParam(defaultValue = "0") int offset,
      Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    return ResponseEntity.ok(messageRepository.findByUserId(userId, limit, offset));
  }

  @GetMapping("/unread-count")
  @RequirePermission("proactive:read")
  public ResponseEntity<Map<String, Integer>> getUnreadCount(Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    int count = messageRepository.countUnreadByUserId(userId);
    return ResponseEntity.ok(Map.of("count", count));
  }

  @PutMapping("/{id}/read")
  @RequirePermission("proactive:read")
  public ResponseEntity<Void> markAsRead(@PathVariable Long id, Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    messageRepository.markAsRead(id, userId);
    return ResponseEntity.noContent().build();
  }

  @PutMapping("/read-all")
  @RequirePermission("proactive:read")
  public ResponseEntity<Void> markAllAsRead(Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    messageRepository.markAllAsRead(userId);
    return ResponseEntity.noContent().build();
  }
}
