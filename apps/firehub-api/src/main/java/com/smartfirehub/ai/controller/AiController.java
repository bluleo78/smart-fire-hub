package com.smartfirehub.ai.controller;

import com.smartfirehub.ai.dto.*;
import com.smartfirehub.ai.service.AiAgentProxyService;
import com.smartfirehub.ai.service.AiSessionService;
import com.smartfirehub.global.security.RequirePermission;
import com.smartfirehub.settings.model.AiCredential;
import com.smartfirehub.settings.service.AiCredentialService;
import jakarta.validation.Valid;
import java.util.List;
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

  /**
   * opencode 자격증명 유형에 대한 인증 상태 응답. opencode 는 Anthropic 인증 개념이 없다 —
   * {@code Opencode.apiKey} 는 OpenAI 호환 키라 {@link AiAgentProxyService#verifyApiKey()}(Anthropic
   * 키 검증 엔드포인트)로 보내면 안 된다. ai-agent 를 아예 부르지 않고 "해당 없음"을 바로
   * 응답한다. web 은 Task 11 에서 opencode 일 때 이 배지/버튼 자체를 숨긴다 — {@code applicable}
   * 필드는 그 화면 분기가 참고할 계약이다.
   */
  private static final String NOT_APPLICABLE_AUTH_STATUS = "{\"valid\":false,\"applicable\":false}";

  private final AiSessionService aiSessionService;
  private final AiAgentProxyService aiAgentProxyService;
  private final AiCredentialService aiCredentialService;

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
    // 테넌트가 자기 AI 를 고를 수 있게 되면서 이 검증은 **호출자의 테넌트 기준**이 됐다.
    // resolve() 는 알 수 없는 agentType 에서 UnknownAgentTypeException 을 던진다(fail-closed) —
    // 여기서 잡지 않는다. 삼키고 빈 자격증명으로 계속하면 그 유형이 실제로는 무엇인지 모르는 채로
    // verifyApiKey()/verifyCliToken() 중 하나를 임의로 골라 부르는 꼴이 된다.
    //
    // 반드시 switch(exhaustive) 로 판정한다 — instanceof 사슬은 마지막 분기가 "그 외 전부"가 되어
    // 새 자격증명 유형이 추가돼도 조용히 컴파일된 채 verifyApiKey()(Anthropic 전용 엔드포인트)로
    // 흘러들 수 있다(리뷰에서 지적됨). sealed interface 에 변형이 늘면 이 switch 가 컴파일
    // 오류로 막는다 — default 를 두지 않는다.
    //
    // 이 switch 가 **이미 쥔** 토큰/키를 프록시에 그대로 넘긴다 — 프록시가 안에서 resolve() 를
    // 또 부르던 예전 모양에서는 요청 1건당 설정 SELECT + AES-GCM 복호화가 두 번 돌았다.
    AiCredential cred = aiCredentialService.resolve();
    String result =
        switch (cred) {
          case AiCredential.Opencode ignored -> NOT_APPLICABLE_AUTH_STATUS;
          case AiCredential.Cli cli -> aiAgentProxyService.verifyCliToken(cli.oauthToken());
          case AiCredential.Sdk sdk ->
              sdk.oauthToken().isBlank()
                  ? aiAgentProxyService.verifyApiKey(sdk.apiKey())
                  : aiAgentProxyService.verifyCliToken(sdk.oauthToken());
          case AiCredential.CliApi cliApi -> aiAgentProxyService.verifyApiKey(cliApi.apiKey());
        };
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
    // #714: 남의 세션 이어쓰기 차단 — ai-agent 는 테넌트만 대조한다
    aiSessionService.verifyNotOthersSession(userId, request.sessionId());
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
