package com.smartfirehub.pipeline.controller;

import com.smartfirehub.global.dto.ErrorResponse;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.pipeline.dto.TriggerResponse;
import com.smartfirehub.pipeline.repository.TriggerTenantResolver;
import com.smartfirehub.pipeline.service.TriggerService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/triggers")
@RequiredArgsConstructor
public class ExternalTriggerController {

  private final TriggerService triggerService;
  private final TriggerTenantResolver triggerTenantResolver;

  /** API trigger: authenticate via SHA-256 token hash matching. */
  @PostMapping("/api/{token}")
  public ResponseEntity<?> apiTrigger(
      @PathVariable String token,
      @RequestBody(required = false) Map<String, Object> params,
      HttpServletRequest request) {

    String sourceIp = getClientIp(request);

    // 인증 필터를 거치지 않는 경로라 테넌트 컨텍스트가 없다. RLS 우회 해석 함수(V95)로 테넌트를
    // 복원한 뒤, 토큰 재조회·IP 검사·발화를 포함한 나머지 전부를 그 테넌트 컨텍스트 안에서 수행한다.
    // 해석 실패는 기존과 동일하게 401 이다 — 외부 계약을 바꾸지 않는다.
    var ref = triggerService.resolveTenantByApiToken(token).orElse(null);
    if (ref == null) {
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

    return TenantContext.<ResponseEntity<?>>runScopedGet(
        ref.tenantId(), () -> fireApiTrigger(token, params, request, sourceIp));
  }

  /**
   * API 트리거의 본 처리. 테넌트 컨텍스트가 세워진 뒤 호출되므로 여기서 도는 모든 조회·삽입은
   * RLS 아래에 있다. 트리거 재조회는 낭비가 아니라 설계다 — 해석 함수는 id 두 개만 내주고, 실제
   * 데이터는 RLS 가 적용된 일반 경로로만 읽는다.
   */
  private ResponseEntity<?> fireApiTrigger(
      String token, Map<String, Object> params, HttpServletRequest request, String sourceIp) {

    TriggerResponse trigger = triggerService.resolveApiToken(token);
    if (trigger == null) {
      // 바깥 해석은 성공했는데 여기서 안 보인다면 토큰 문제가 아니라 격리 배선 문제다 —
      // GUC 미주입이나 FORCE RLS 로 정책이 소유자에게까지 적용된 경우가 이 형태로 나타난다.
      // 응답은 계약대로 401 을 유지하되, 로그는 반드시 구별되어야 진단이 가능하다.
      log.warn(
          "Tenant {} resolved but API trigger row invisible under RLS (IP: {}) — 격리 배선 확인 필요",
          TenantContext.get(),
          sourceIp);
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

    // allowedIps가 설정된 경우 요청 IP가 허용 목록에 포함되는지 검사 (#127)
    @SuppressWarnings("unchecked")
    List<String> allowedIps = (List<String>) trigger.config().getOrDefault("allowedIps", List.of());
    if (!triggerService.isIpAllowed(sourceIp, allowedIps)) {
      log.warn(
          "API trigger {} blocked: IP {} not in allowedIps {}", trigger.id(), sourceIp, allowedIps);
      return ResponseEntity.status(HttpStatus.FORBIDDEN)
          .body(
              new ErrorResponse(
                  403,
                  "Forbidden",
                  "IP not allowed",
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

    // API 경로와 같은 이유로 여기서도 테넌트를 먼저 복원한다. 서명검증은 복호화를 위해 트리거 행을
    // 읽어야 하므로 반드시 컨텍스트 안(=RLS 아래)에서 돌아야 한다. 해석 실패는 기존과 동일한 404 다.
    var ref = triggerTenantResolver.resolveByWebhookId(webhookId).orElse(null);
    if (ref == null) {
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

    return TenantContext.<ResponseEntity<?>>runScopedGet(
        ref.tenantId(), () -> fireWebhookTrigger(webhookId, body, signature, request, sourceIp));
  }

  /** 웹훅 트리거의 본 처리. 테넌트 컨텍스트 안에서만 호출된다 — 조회·서명검증·발화가 전부 RLS 아래다. */
  private ResponseEntity<?> fireWebhookTrigger(
      String webhookId,
      String body,
      String signature,
      HttpServletRequest request,
      String sourceIp) {

    TriggerResponse trigger = triggerService.findByWebhookId(webhookId);
    if (trigger == null) {
      // API 경로와 같은 이유 — 해석은 됐는데 안 보이면 격리 배선 문제다. 응답은 404 를 유지한다.
      log.warn(
          "Tenant {} resolved but webhook {} invisible under RLS (IP: {}) — 격리 배선 확인 필요",
          TenantContext.get(),
          webhookId,
          sourceIp);
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
