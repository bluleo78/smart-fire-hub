package com.smartfirehub.pipeline.controller;

import com.smartfirehub.global.dto.ErrorResponse;
import com.smartfirehub.pipeline.dto.TriggerResponse;
import com.smartfirehub.pipeline.service.TriggerService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/triggers")
public class ExternalTriggerController {

  private static final Logger log = LoggerFactory.getLogger(ExternalTriggerController.class);

  private final TriggerService triggerService;

  public ExternalTriggerController(TriggerService triggerService) {
    this.triggerService = triggerService;
  }

  /** API trigger: authenticate via SHA-256 token hash matching. */
  @PostMapping("/api/{token}")
  public ResponseEntity<?> apiTrigger(
      @PathVariable String token,
      @RequestBody(required = false) Map<String, Object> params,
      HttpServletRequest request) {

    String sourceIp = getClientIp(request);

    TriggerResponse trigger = triggerService.resolveApiToken(token);
    if (trigger == null) {
      log.warn("Invalid API trigger token from IP: {}", sourceIp);
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .body(
              new ErrorResponse(
                  401,
                  "Unauthorized",
                  "Invalid token",
                  null,
                  Instant.now().toString(),
                  request.getRequestURI()));
    }

    Map<String, Object> fireParams = params != null ? params : Map.of();
    fireParams = new java.util.HashMap<>(fireParams);
    fireParams.put("sourceIp", sourceIp);

    triggerService.fireTrigger(trigger.id(), fireParams);

    return ResponseEntity.ok(
        Map.of(
            "status", "triggered",
            "pipelineId", trigger.pipelineId(),
            "triggerId", trigger.id()));
  }

  /** Webhook trigger: authenticate via optional HMAC-SHA256 signature. */
  @PostMapping("/webhook/{webhookId}")
  public ResponseEntity<?> webhookTrigger(
      @PathVariable String webhookId,
      @RequestBody(required = false) String body,
      @RequestHeader(value = "X-Hub-Signature", required = false) String signature,
      HttpServletRequest request) {

    String sourceIp = getClientIp(request);

    TriggerResponse trigger = triggerService.findByWebhookId(webhookId);
    if (trigger == null) {
      log.warn("Invalid webhook ID {} from IP: {}", webhookId, sourceIp);
      return ResponseEntity.status(HttpStatus.NOT_FOUND)
          .body(
              new ErrorResponse(
                  404,
                  "Not Found",
                  "Webhook not found",
                  null,
                  Instant.now().toString(),
                  request.getRequestURI()));
    }

    // Verify signature if secret is configured
    String secretEncrypted = (String) trigger.config().get("secretEncrypted");
    if (secretEncrypted != null && !secretEncrypted.isEmpty()) {
      if (signature == null || signature.isEmpty()) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(
                new ErrorResponse(
                    401,
                    "Unauthorized",
                    "Missing X-Hub-Signature header",
                    null,
                    Instant.now().toString(),
                    request.getRequestURI()));
      }
      if (!triggerService.verifyWebhookSignature(webhookId, body != null ? body : "", signature)) {
        log.warn("Invalid webhook signature for {} from IP: {}", webhookId, sourceIp);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(
                new ErrorResponse(
                    401,
                    "Unauthorized",
                    "Invalid signature",
                    null,
                    Instant.now().toString(),
                    request.getRequestURI()));
      }
    }

    Map<String, Object> fireParams = new java.util.HashMap<>();
    fireParams.put("sourceIp", sourceIp);
    if (body != null) {
      fireParams.put("payloadSize", body.length());
    }

    triggerService.fireTrigger(trigger.id(), fireParams);

    return ResponseEntity.ok(
        Map.of(
            "status", "triggered",
            "pipelineId", trigger.pipelineId(),
            "triggerId", trigger.id()));
  }

  private String getClientIp(HttpServletRequest request) {
    String xForwardedFor = request.getHeader("X-Forwarded-For");
    if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
      return xForwardedFor.split(",")[0].trim();
    }
    return request.getRemoteAddr();
  }
}
