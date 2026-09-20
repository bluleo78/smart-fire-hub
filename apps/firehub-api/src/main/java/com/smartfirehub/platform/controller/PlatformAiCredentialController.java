package com.smartfirehub.platform.controller;

import com.smartfirehub.global.security.RequirePermission;
import com.smartfirehub.platform.dto.PlatformAiCredentialView;
import com.smartfirehub.settings.controller.OpencodeCredentialValidation;
import com.smartfirehub.settings.dto.AiCredentialUpsertRequest;
import com.smartfirehub.settings.dto.OpencodeProbeRequest;
import com.smartfirehub.settings.repository.SettingsRepository;
import com.smartfirehub.settings.service.AiCredentialService;
import com.smartfirehub.settings.service.AiCredentialService.AiCredentialUpsert;
import com.smartfirehub.settings.service.OpencodeProbeService;
import com.smartfirehub.settings.service.OpencodeProbeService.ProbeResult;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 플랫폼 기본 AI 자격증명. 테넌트 컨트롤러({@code AiCredentialController})의 거울이지만 세 가지가
 * 다르다.
 *
 * <ol>
 *   <li><b>{@code DELETE} 가 없다.</b> 플랫폼은 상위 평면이 없어 "지우고 상속받는다"는 개념 자체가
 *       성립하지 않는다.
 *   <li><b>응답에 {@code tenantOwned} 가 없다.</b> {@link PlatformAiCredentialView} 참고.
 *   <li><b>{@code POST /probe} 는 opencode {@code apiKey} 생략을 허용하지 않는다(설계서
 *       「프로브」 절, Ruling #24).</b> 테넌트 평면의 "생략 시 저장된 값 재사용"은 상위 평면(플랫폼)의
 *       값을 빌려주지 <b>않기 위한</b> 장치였는데(그 폴백을 허용하면 테넌트가 플랫폼 키를 임의
 *       주소로 내보낼 수 있다), 플랫폼 평면 자체가 최상위라 빌려줄 상위가 없다 — "생략하면 무엇을
 *       재사용하나"에 답이 없으므로 애초에 생략을 허용하면 안 된다.
 *       <p>{@link OpencodeProbeService#probe}는 apiKey 생략 시 <b>테넌트</b> 행만 본다
 *       ({@code AiCredentialService#tenantOpencodeCredential} — 이름 그대로 테넌트 전용). 그
 *       메서드를 플랫폼 평면에서 그대로 호출해도 실제 운영 요청에는 {@code TenantContext} 가 없어
 *       우연히 안전하긴 하다(플랫폼 토큰 인증 경로는 {@code TenantContext} 를 세우지 않는다 —
 *       {@code JwtAuthenticationFilter} 참고). 하지만 그 안전성은 "이 컨트롤러 밖의 다른 클래스가
 *       그렇게 짜여 있다"는 사실에 기대는 것이라, <b>이 파일만 읽어서는 보장이 보이지 않는다</b>.
 *       그래서 {@code /probe} 에서는 apiKey 생략을 <b>여기서 직접</b> 막아 프로브를 아예 부르지
 *       않는다.
 *       <p><b>{@code PUT} 은 다르다</b> — apiKey 를 생략하면 "지금 값 유지" 의도로 보고 저장은
 *       그대로 진행하되, 저장 전 모델 검증(프로브)만 건너뛴다. 스펙의 "PUT 의 비밀 의미: 생략하면
 *       유지"가 평면을 가리지 않기 때문이다(자세한 이유는 {@link #validateOpencode} javadoc).
 * </ol>
 */
@RestController
@RequestMapping("/api/platform/settings/ai-credential")
@RequiredArgsConstructor
public class PlatformAiCredentialController {

  private static final String PLATFORM_API_KEY_REQUIRED_MESSAGE =
      "플랫폼 평면은 상위 평면이 없어 apiKey 생략을 허용하지 않는다 — apiKey 를 명시해야 한다";

  private final AiCredentialService aiCredentialService;
  private final OpencodeProbeService opencodeProbeService;
  private final SettingsRepository settingsRepository;

  /**
   * 플랫폼 기본값 조회. {@code tenantOwned} 가 없는 별도 뷰({@link PlatformAiCredentialView})로
   * 감싼다 — {@link AiCredentialService#read} 가 돌려주는 {@code AiCredentialView} 를 그대로
   * 내보내면 {@code tenantOwned} 가 새어 나간다.
   */
  @GetMapping
  @RequirePermission("platform:settings:read")
  public ResponseEntity<PlatformAiCredentialView> get() {
    return ResponseEntity.ok(PlatformAiCredentialView.from(aiCredentialService.read()));
  }

  /** 플랫폼 기본값 저장. opencode 검증은 테넌트 컨트롤러와 같되, apiKey 생략을 막는 점이 다르다. */
  @PutMapping
  @RequirePermission("platform:settings:write")
  public ResponseEntity<?> put(
      Authentication authentication, @RequestBody AiCredentialUpsertRequest request) {
    if ("opencode".equals(request.agentType())) {
      Optional<ResponseEntity<Map<String, Object>>> rejected = validateOpencode(request);
      if (rejected.isPresent()) return rejected.get();
    }
    Long userId = (Long) authentication.getPrincipal();
    aiCredentialService.save(
        new AiCredentialUpsert(request.agentType(), request.payload(), request.secret()),
        userId,
        true);
    return ResponseEntity.noContent().build();
  }

  /**
   * opencode 모델 목록 조회. <b>쓰기 권한을 요구한다</b>(설계서 「권한」 절, Ruling #9) — 인증된
   * 외부 호출이라 조회 권한만으로는 임의 baseURL 에 Bearer 를 실어 보낼 수 없어야 한다. 클래스
   * javadoc 이 설명하는 이유로 apiKey 생략은 여기서 곧바로 거부한다(프로브를 부르지 않는다).
   */
  @PostMapping("/probe")
  @RequirePermission("platform:settings:write")
  public ResponseEntity<Map<String, Object>> probe(@RequestBody OpencodeProbeRequest request) {
    if (request.apiKey() == null || request.apiKey().isBlank()) {
      throw new IllegalArgumentException(PLATFORM_API_KEY_REQUIRED_MESSAGE);
    }
    ProbeResult result = opencodeProbeService.probe(request.baseURL(), request.apiKey());
    return ResponseEntity.ok(OpencodeCredentialValidation.probeBody(result));
  }

  /**
   * PUT 의 opencode 검증. 테넌트 컨트롤러와 순서는 같되, {@code apiKey} 를 생략했을 때의 처리가
   * 다르다.
   *
   * <p><b>apiKey 생략 = 프로브(모델 검증)만 건너뛴다 — 저장 자체도, baseURL SSRF 가드도 막지
   * 않는다(보안 리뷰 Fix1).</b> 스펙의 "PUT 의 비밀 의미: 필드를 생략하면 현재 값 유지"는 평면을
   * 가리지 않는다. 플랫폼 관리자가 baseURL 이나 reasoningEffort 만 고치고 싶을 때 매번 apiKey 를
   * 다시 입력하라고 요구하면 그 계약을 어기는 것이다. 다만 이 컨트롤러에는 저장된 <b>플랫폼</b>
   * opencode apiKey 를 복호화해 재사용할 접근자가
   * 없다({@code AiCredentialService#tenantOpencodeCredential} 은 이름 그대로 테넌트 전용이고,
   * 단건 복호화 접근자를 새로 만드는 것은 스펙이 명시적으로 금지한
   * {@code getDecryptedApiKey()} 류의 부활이다) — 그래서 apiKey 가 없으면 "지금 이 저장에서는
   * 모델 소속을 확인할 수 없다"로 받아들이고 프로브·소속 검사를 건너뛴 채 그대로 저장으로
   * 넘어간다. 실제 저장(`AiCredentialService#save`)은 이미 "생략=유지" 규칙을 스스로 지킨다.
   *
   * <p>반대로 {@code POST /probe} 는 이 완화를 적용하지 않는다(Ruling #24) — 그 엔드포인트의
   * 목적 자체가 "지금 이 값으로 실제 접속해 본다"이므로 생략할 "지금 이 값"이 없으면 요청 자체가
   * 성립하지 않는다. PUT 은 반대로 "값을 안 바꾼다"는 뜻이 있어 사정이 다르다.
   */
  private Optional<ResponseEntity<Map<String, Object>>> validateOpencode(
      AiCredentialUpsertRequest request) {
    Map<String, Object> payload = request.payload();

    Optional<OpencodeCredentialValidation.Problem> effortProblem =
        OpencodeCredentialValidation.checkReasoningEffort(payload);
    if (effortProblem.isPresent()) {
      return Optional.of(OpencodeCredentialValidation.problemResponse(effortProblem.get()));
    }

    String providerId = OpencodeCredentialValidation.strOrEmpty(payload.get("providerId"));
    String baseURL = OpencodeCredentialValidation.strOrEmpty(payload.get("baseURL"));
    if (providerId.isBlank() || baseURL.isBlank()) {
      return Optional.empty();
    }

    // ai.model 은 테넌트 오버라이드를 해석하지 않는다 — SettingsRepository.getValue 는
    // system_settings 행을 직접 읽는 원시 접근자라 TenantContext 유무와 무관하게 항상 플랫폼
    // 자신의 값을 돌려준다(SettingsService.getValue 였다면 호출 스레드에 남은 TenantContext 에
    // 따라 테넌트 오버라이드가 섞여 들어올 수 있었다).
    String storedAiModel = settingsRepository.getValue("ai.model").orElse(null);
    Optional<OpencodeCredentialValidation.Problem> consistency =
        OpencodeCredentialValidation.checkProviderConsistency(providerId, storedAiModel);
    if (consistency.isPresent()) {
      return Optional.of(OpencodeCredentialValidation.problemResponse(consistency.get()));
    }

    // 보안 리뷰 Fix1(테넌트 컨트롤러 javadoc과 같은 근거) — baseURL 의 SSRF 가드는 apiKey 유무와
    // 무관하게 항상 돈다. apiKey 생략이 이 검사까지 건너뛰게 두면 플랫폼 관리자 계정으로도
    // 169.254.169.254 같은 내부 주소를 무검증 저장할 수 있었다.
    //
    // 이 가드 안에는 실제 DNS 질의가 들어 있다 — 통과한 결과(TargetCheck)를 아래 probe() 에
    // 그대로 넘겨 같은 호스트를 두 번 해석하지 않는다(테넌트 컨트롤러와 같은 구조).
    OpencodeProbeService.TargetCheck targetCheck = opencodeProbeService.validateTargetOnly(baseURL);
    if (!targetCheck.ok()) {
      ProbeResult failure = targetCheck.failure();
      return Optional.of(
          ResponseEntity.status(OpencodeCredentialValidation.statusFor(failure.reason()))
              .body(Map.of("message", failure.message())));
    }

    String apiKey = request.secret().get("apiKey");
    if (apiKey == null || apiKey.isBlank()) {
      // 재사용할 수 있는 저장된 키가 없다(위 javadoc) — (네트워크가 필요한) 프로브만 건너뛰고
      // 통과시킨다. baseURL 자체의 안전성 검사는 위에서 이미 끝났다. save() 가 "생략=유지"를
      // 스스로 지키므로 저장 자체는 정상 진행된다.
      return Optional.empty();
    }

    ProbeResult result;
    try {
      result = opencodeProbeService.probe(targetCheck, apiKey);
    } catch (IllegalArgumentException e) {
      // 방어적 catch — 위 targetCheck 가 baseURL 형식 검사를 이미 끝냈고 apiKey 도 명시적으로
      // 받았으므로 지금은 던져질 수 있는 것이 없다(테넌트 컨트롤러의 같은 자리 주석 참고).
      return Optional.of(ResponseEntity.badRequest().body(Map.of("message", e.getMessage())));
    }
    if (!result.ok()) {
      return Optional.of(
          ResponseEntity.status(OpencodeCredentialValidation.statusFor(result.reason()))
              .body(Map.of("message", result.message())));
    }

    Optional<OpencodeCredentialValidation.Problem> membership =
        OpencodeCredentialValidation.checkModelMembership(storedAiModel, result.models());
    return membership.map(OpencodeCredentialValidation::problemResponse);
  }



}
