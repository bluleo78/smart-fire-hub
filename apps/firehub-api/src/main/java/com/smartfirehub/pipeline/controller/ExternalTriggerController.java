package com.smartfirehub.pipeline.controller;

import com.smartfirehub.global.dto.ErrorResponse;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.global.util.ClientIpExtractor;
import com.smartfirehub.pipeline.dto.TriggerFireResult;
import com.smartfirehub.pipeline.dto.TriggerResponse;
import com.smartfirehub.pipeline.repository.TriggerTenantResolver;
import com.smartfirehub.pipeline.service.TriggerService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 외부 시스템이 부르는 트리거 엔드포인트(API 토큰·웹훅). 인증 필터를 거치지 않는 공개 경로라, 식별자로 테넌트를 먼저 해석한 뒤 나머지를 그 테넌트 컨텍스트 안에서
 * 처리한다.
 *
 * <p>응답 계약(WD-152) — 호출자가 "실제로 실행됐는지"를 응답만 보고 알 수 있어야 한다:
 *
 * <ul>
 *   <li>실행을 시작함 → 200 {@code {"status":"triggered", pipelineId, triggerId, executionId}}
 *   <li>조건에 걸려 실행하지 않음(파이프라인 비활성·동시 실행 정책·트리거 꺼짐·워크스페이스 정지(WD-174)) → 409 {@code
 *       {"status":"skipped","reason":"<한국어 사유>"}}
 *   <li>실행 시작 자체가 실패함 → 500 {@code {"status":"failed","reason":"<한국어 사유>"}} — 예외 원문은 내보내지 않는다
 *   <li>토큰 틀림 401 · IP 차단 403 · 웹훅 없음 404 · 서명 불일치 401 은 기존 그대로다
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/triggers")
@RequiredArgsConstructor
public class ExternalTriggerController {

  private final TriggerService triggerService;
  private final TriggerTenantResolver triggerTenantResolver;

  /** 신뢰 프록시 기준 클라이언트 IP 추출기(WD-402) — 허용 IP 검사와 trigger_event sourceIp 에 같은 값을 쓴다. */
  private final ClientIpExtractor clientIpExtractor;

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
   * API 트리거의 본 처리. 테넌트 컨텍스트가 세워진 뒤 호출되므로 여기서 도는 모든 조회·삽입은 RLS 아래에 있다. 트리거 재조회는 낭비가 아니라 설계다 — 해석 함수는
   * id 두 개만 내주고, 실제 데이터는 RLS 가 적용된 일반 경로로만 읽는다.
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

    // 켜짐 여부는 토큰·IP 검사를 통과한 뒤에 fireTrigger 가 판정한다 — 맞는 토큰과 허용된 IP 를 가진 호출자에게만 "꺼져 있음"이 드러난다.
    TriggerFireResult result = triggerService.fireTrigger(trigger.id(), fireParams);
    return toFireResponse(result, trigger);
  }

  /** Webhook trigger: authenticate via optional HMAC-SHA256 signature. */
  @PostMapping("/webhook/{webhookId}")
  public ResponseEntity<?> webhookTrigger(
      @PathVariable String webhookId,
      @RequestBody(required = false) String body,
      @RequestHeader(value = "X-Hub-Signature", required = false) String legacySignature,
      @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature256,
      HttpServletRequest request) {

    String sourceIp = getClientIp(request);
    // GitHub 는 X-Hub-Signature(sha1=…)와 X-Hub-Signature-256(sha256=…)을 함께 보낸다 — SHA-256 쪽이 있으면 그것을
    // 쓴다(WD-404).
    // 예전에는 X-Hub-Signature 만 읽어 sha1 값으로 검증해, 시크릿을 쓰는 GitHub 웹훅이 늘 401 이었다.
    String signature =
        signature256 != null && !signature256.isBlank() ? signature256 : legacySignature;

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

    // 시크릿이 있으면 서명부터 검증한다 — 꺼짐 여부를 포함해 트리거에 관한 어떤 사실도 서명 통과 전에는 드러내지 않는다(WD-152).
    // 서명이 없거나 틀리면 응답은 WD-152 이전과 같다: 켜진 웹훅은 401, 꺼진 웹훅은 404(예전엔 꺼진 웹훅을 해석조차 하지 않아 404 였다).
    // 꺼진 웹훅에 401 을 주면 "이 id 의 웹훅이 존재한다"는 사실이 서명 없이 새어 나간다.
    String secretEncrypted = (String) trigger.config().get("secretEncrypted");
    if (secretEncrypted != null && !secretEncrypted.isEmpty()) {
      if (signature == null || signature.isEmpty()) {
        if (!trigger.isEnabled()) {
          return webhookNotFound(request);
        }
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
        if (!trigger.isEnabled()) {
          return webhookNotFound(request);
        }
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

    TriggerFireResult result = triggerService.fireTrigger(trigger.id(), fireParams);
    return toFireResponse(result, trigger);
  }

  /**
   * 발화 결과를 외부 호출자 응답으로 옮긴다(WD-152). 실행을 시작했을 때만 200 「triggered」이고, 실행하지 않았으면 409 「skipped」+한국어 사유,
   * 시작하다 실패했으면 500 「failed」+한국어 사유다. 예전에는 결과와 무관하게 200 을 돌려줘 실행되지 않은 호출도 실행된 것으로 보였다.
   */
  /**
   * 외부 호출자(토큰·웹훅 주소만 가진 바깥 시스템)에게 돌려줄 건너뜀 사유(WD-169).
   *
   * <p>트리거를 만든 사람의 자격 사유(정지·제거, 실행 권한 없음)는 워크스페이스 안의 인사·권한 상태라 바깥에 그대로 알리지 않는다 — 둘을 같은 일반 문구로 바꾼다.
   * 자세한 사유는 트리거 이벤트 기록과 파이프라인 주인·관리자 알림에 남는다. 그 밖의 사유(파이프라인 비활성·동시 실행·꺼짐)는 그대로다.
   */
  static String externalReason(String reason) {
    if (TriggerService.REASON_EXECUTOR_INACTIVE.equals(reason)
        || TriggerService.REASON_EXECUTOR_NO_EXECUTE_PERMISSION.equals(reason)) {
      return REASON_EXECUTOR_NOT_ELIGIBLE_EXTERNAL;
    }
    return reason;
  }

  /** 트리거를 만든 사람의 자격 때문에 건너뛰었을 때 바깥 호출자에게 보이는 일반 사유(WD-169). */
  static final String REASON_EXECUTOR_NOT_ELIGIBLE_EXTERNAL =
      "트리거를 만든 사용자가 지금 이 파이프라인을 실행할 수 없어 실행하지 않았습니다. 워크스페이스 관리자에게 문의하세요";

  private ResponseEntity<?> toFireResponse(TriggerFireResult result, TriggerResponse trigger) {
    return switch (result.outcome()) {
      case FIRED -> {
        // executionId 는 항상 있지만, null 이 섞여도 Map.of 처럼 NPE 가 나지 않게 순서 보존 맵을 쓴다.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "triggered");
        body.put("pipelineId", trigger.pipelineId());
        body.put("triggerId", trigger.id());
        body.put("executionId", result.executionId());
        yield ResponseEntity.ok(body);
      }
      // 워크스페이스 정지(WD-174)도 같은 409 모양이다 — 사유 「워크스페이스가 정지되어 실행하지 않았습니다」는 externalReason 이 그대로 통과시킨다
      case SKIPPED, DISABLED, TENANT_SUSPENDED ->
          ResponseEntity.status(HttpStatus.CONFLICT)
              .body(Map.of("status", "skipped", "reason", externalReason(result.reason())));
      // NOT_FOUND 는 위에서 트리거를 읽은 직후 삭제된 경합이다 — 실행되지 않았다는 사실이 중요하므로 실패로 알린다.
      case NOT_FOUND, ERROR ->
          ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
              .body(
                  Map.of(
                      "status",
                      "failed",
                      "reason",
                      result.reason() != null
                          ? result.reason()
                          : TriggerService.REASON_FIRE_ERROR));
    };
  }

  /** 웹훅을 찾지 못했을 때의 404 응답. 해석 실패·RLS 비가시·꺼진 웹훅의 서명 실패가 모두 같은 모양이어야 서로 구별되지 않는다. */
  private ResponseEntity<?> webhookNotFound(HttpServletRequest request) {
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

  /**
   * 요청자 IP. X-Forwarded-For 맨 앞 값을 그대로 믿으면 헤더 한 줄로 허용 IP 검사를 우회할 수 있어(WD-402), 신뢰 프록시를 거쳐 온 경우에만
   * 헤더를 오른쪽부터 읽는 {@link ClientIpExtractor} 에 맡긴다.
   */
  private String getClientIp(HttpServletRequest request) {
    return clientIpExtractor.extract(request);
  }
}
