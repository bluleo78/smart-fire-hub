package com.smartfirehub.settings.controller;

import com.smartfirehub.global.security.RequirePermission;
import com.smartfirehub.settings.dto.AiClassifyCredentialUpsertRequest;
import com.smartfirehub.settings.dto.OpencodeProbeRequest;
import com.smartfirehub.settings.model.AiCredentialSlot;
import com.smartfirehub.settings.service.AiCredentialService;
import com.smartfirehub.settings.service.AiCredentialService.AiClassifyCredentialView;
import com.smartfirehub.settings.service.AiCredentialService.AiCredentialUpsert;
import com.smartfirehub.settings.service.OpencodeProbeService;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 분류(AI_CLASSIFY) 전용 공급자 설정 엔드포인트(#707).
 *
 * <p><b>왜 별도 베이스 경로인가.</b> 기존 {@code /settings/ai-credential} 은 {@code /probe} 가 하위
 * 경로라 {@code /{slot}} 을 끼울 수 없고, 채팅 경로의 계약(DELETE 없음=405)을 바꾸지 않으려고
 * 별도 컨트롤러를 둔다. 네 라우트 모두 {@code ai:settings} — 채팅 자격증명과 같은 등급이다.
 *
 * <p>미설정 = 분류가 AI 에이전트(채팅) 설정을 통째로 쓰는 상태다. DELETE("설정 해제")가 그
 * 상태로 되돌린다.
 */
@RestController
@RequestMapping("/api/v1/settings/ai-classify-credential")
@RequiredArgsConstructor
public class AiClassifyCredentialController {

  private final AiCredentialService aiCredentialService;
  private final OpencodeProbeService opencodeProbeService;
  private final OpencodePutValidator opencodePutValidator;

  /** 화면용 조회 — 미설정이면 {@code configured=false, model=""}. 비밀 값은 싣지 않는다. */
  @GetMapping
  @RequirePermission("ai:settings")
  public ResponseEntity<AiClassifyCredentialView> get() {
    return ResponseEntity.ok(aiCredentialService.readClassify());
  }

  /**
   * 저장. 모델 공백은 opencode 검증보다 먼저 400 으로 끊는다(모델 형식 문구가 먼저 나가면 원인이
   * 흐려진다). opencode 면 채팅과 같은 검증기를 <b>요청 모델</b>과 분류 슬롯으로 돌린다 — SSRF 가드는
   * apiKey 유무와 무관하게 항상 돈다.
   */
  @PutMapping
  @RequirePermission("ai:settings")
  public ResponseEntity<?> put(
      Authentication authentication, @RequestBody AiClassifyCredentialUpsertRequest request) {
    if (request.model() == null || request.model().isBlank()) {
      return ResponseEntity.badRequest()
          .body(Map.of("message", AiCredentialService.MSG_CLASSIFY_MODEL_REQUIRED));
    }
    if ("opencode".equals(request.agentType())) {
      Optional<ResponseEntity<Map<String, Object>>> rejected =
          opencodePutValidator.validate(
              request.payload(), request.secret(), request.model().trim(), AiCredentialSlot.CLASSIFY);
      if (rejected.isPresent()) return rejected.get();
    }
    Long userId = (Long) authentication.getPrincipal();
    aiCredentialService.saveClassify(
        new AiCredentialUpsert(request.agentType(), request.payload(), request.secret()),
        request.model(),
        userId);
    return ResponseEntity.noContent().build();
  }

  /** 설정 해제 — 두 키를 함께 지운다. 이미 미설정이어도 204(멱등). */
  @DeleteMapping
  @RequirePermission("ai:settings")
  public ResponseEntity<Void> delete() {
    aiCredentialService.clearClassify();
    return ResponseEntity.noContent().build();
  }

  /** opencode 모델 목록. 생략된 apiKey 는 <b>분류 슬롯</b>의 저장 키에서만 채운다. */
  @PostMapping("/probe")
  @RequirePermission("ai:settings")
  public ResponseEntity<Map<String, Object>> probe(@RequestBody OpencodeProbeRequest request) {
    return ResponseEntity.ok(
        OpencodeCredentialValidation.probeBody(
            opencodeProbeService.probe(AiCredentialSlot.CLASSIFY, request.baseURL(), request.apiKey())));
  }
}
