package com.smartfirehub.platform.controller;

import com.smartfirehub.ai.service.AiAgentProxyService;
import com.smartfirehub.global.security.RequirePermission;
import com.smartfirehub.settings.model.AiCredential;
import com.smartfirehub.settings.service.AiCredentialService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 플랫폼 AI 자격증명의 인증 상태(Ruling #47, 타입형 AI 설정 전환 Task 13 fix round 1).
 *
 * <p><b>테넌트 컨트롤러({@link com.smartfirehub.ai.controller.AiController#getAuthStatus})의
 * 거울이다.</b> 그 메서드는 {@link AiCredentialService#resolve}(두 평면 해석: 테넌트 행 →
 * 없으면 플랫폼 행)를 부르는데, 플랫폼 토큰 인증 경로는 {@code TenantContext} 를 세우지 않으므로
 * (`JwtAuthenticationFilter`), 이 컨트롤러가 같은 {@code resolve()} 를 그대로 불러도 <b>항상
 * 플랫폼 행만</b> 읽는다({@code AiCredentialService#readTenantRaw} 가
 * {@code TenantContext.get() == null} 이면 바로 빈 값을 돌려준다) — 별도 "플랫폼 전용 조회"
 * 메서드를 새로 만들 필요가 없는 이유다.
 *
 * <p><b>exhaustive switch, default 없음.</b> {@link AiCredential} 에 변형이 늘면 이 메서드가
 * 컴파일되지 않는다 — 새 자격증명 유형이 조용히 어느 한쪽(Anthropic 키 검증/OAuth 검증) 분기로
 * 흘러드는 사고를, 리뷰가 놓쳐도 컴파일러가 막게 하려는 것이다(테넌트 쪽 같은 메서드에 대한
 * 이전 리뷰에서 정한 규칙 — Ruling #47 은 그 규칙을 플랫폼 쪽에도 그대로 적용한다).
 */
@RestController
@RequestMapping("/api/platform/ai")
@RequiredArgsConstructor
public class PlatformAiController {

  /**
   * opencode 자격증명에 대한 응답. opencode 는 Anthropic 인증 개념이 없다 —
   * {@code Opencode.apiKey} 는 OpenAI 호환 키라 Anthropic 전용 검증 엔드포인트로 보내면 안
   * 된다. ai-agent 를 아예 부르지 않고 "해당 없음"을 바로 응답한다(테넌트 쪽
   * {@code AiController} 와 문자 그대로 같은 상수 — admin 화면이 이 값을 보고 버튼/배지를
   * 숨긴다).
   */
  private static final String NOT_APPLICABLE_AUTH_STATUS = "{\"valid\":false,\"applicable\":false}";

  private final AiCredentialService aiCredentialService;
  private final AiAgentProxyService aiAgentProxyService;

  // Ruling #54(fix round 2) — 조회(read) 가 아니라 쓰기(write) 권한을 요구한다. 이 엔드포인트는
  // 호출될 때마다 실제로 외부 ai-agent 에 인증된 네트워크 호출을 낸다(verifyCliToken/
  // verifyApiKey) — `POST /settings/ai-credential/probe`(같은 부류의 "저장된 자격증명으로
  // 외부에 나간다" 엔드포인트, `PlatformAiCredentialController`)도 read 가 아니라 write 를
  // 요구한다. 조회 권한만 있는 사용자가 이 호출을 트리거할 수 있으면 "읽기만 하는 권한"의
  // 의미가 깨진다.
  @GetMapping("/auth-status")
  @RequirePermission("platform:settings:write")
  public ResponseEntity<String> getAuthStatus() {
    // resolve() 는 알 수 없는 agentType 에서 UnknownAgentTypeException 을 던진다(fail-closed) —
    // 여기서 잡지 않는다(테넌트 쪽과 같은 이유, AiController.getAuthStatus 참고). 판정에 쓴
    // 토큰/키를 프록시에 그대로 넘긴다 — 프록시가 resolve() 를 또 부르지 않게 하기 위해서다.
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
}
