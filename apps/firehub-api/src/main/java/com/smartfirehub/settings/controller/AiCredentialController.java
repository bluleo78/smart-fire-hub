package com.smartfirehub.settings.controller;

import com.smartfirehub.global.security.RequirePermission;
import com.smartfirehub.settings.dto.AiCredentialUpsertRequest;
import com.smartfirehub.settings.dto.OpencodeProbeRequest;
import com.smartfirehub.settings.service.AiCredentialService;
import com.smartfirehub.settings.service.AiCredentialService.AiCredentialUpsert;
import com.smartfirehub.settings.service.AiCredentialService.AiCredentialView;
import com.smartfirehub.settings.service.OpencodeProbeService;
import com.smartfirehub.settings.service.OpencodeProbeService.ProbeResult;
import com.smartfirehub.settings.service.SettingsService;
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
 * 테넌트 화면의 AI 자격증명 전용 엔드포인트.
 *
 * <p>{@code ai.credential} 은 하위 필드(secret)에 비밀이 있는 JSON 문서라 범용
 * {@code /api/v1/settings}(문자열 키·값 전제) 경로에 얹을 수 없다 — {@link AiCredentialService}
 * 클래스 javadoc "왜 SettingsService 가 아닌가" 절 참고. 그래서 이 키 하나만을 위한 별도
 * 컨트롤러가 존재한다.
 *
 * <p><b>권한은 네 라우트 전부 {@code ai:settings} 다.</b> {@code SettingsController} 가 이미 세운
 * 규칙(조회·저장·연결 테스트가 같은 권한)을 그대로 잇는다 — 프로브도 예외가 아니다. 프로브가
 * 인증된 외부 호출(임의 baseURL 에 Bearer 전송)을 만드는 것은 맞지만, 이미 그 호출을 일으킬 수
 * 있는 사람(=자격증명을 저장할 수 있는 사람)에게 프로브가 새로운 능력을 주는 게 아니다. 다만
 * "쓰기 권한이 필요하다"(설계서 「권한」 절, Ruling #9)는 요구는 이 권한 하나로 이미 충족된다.
 * ({@code ai:read} 라는 권한 코드 자체는 존재한다 — AI 세션 조회용(V12)이고, 이 컨트롤러가 다루는
 * {@code ai.credential} 과는 무관한 리소스다. "테넌트 평면엔 이 네 라우트를 가를 더 약한 권한이
 * 없다"는 결론은 그대로지만, 근거를 {@code ai:read} 부재가 아니라 "네 라우트가 원래
 * {@code ai:settings} 하나로 묶여 있었고 그것을 쪼갤 이유가 없었다"는 쪽에 둔다 — 그 구분이
 * 실제로 갈리는 곳은 플랫폼 평면이다({@code platform:settings:read} vs
 * {@code platform:settings:write}).
 */
@RestController
@RequestMapping("/api/v1/settings/ai-credential")
@RequiredArgsConstructor
public class AiCredentialController {

  private final AiCredentialService aiCredentialService;
  private final OpencodeProbeService opencodeProbeService;
  private final SettingsService settingsService;

  /** 화면용 조회. {@link AiCredentialService#read} 를 그대로 노출한다 — {@code tenantOwned} 포함. */
  @GetMapping
  @RequirePermission("ai:settings")
  public ResponseEntity<AiCredentialView> get() {
    return ResponseEntity.ok(aiCredentialService.read());
  }

  /**
   * 저장. opencode 는 서비스에 넘기기 전에 이 컨트롤러가 몇 가지를 더 검증한다(설계서 "저장 시
   * 검증" 절 — 유형별 필수 필드 자체는 {@link AiCredentialService#save} 가 여전히 지킨다).
   */
  @PutMapping
  @RequirePermission("ai:settings")
  public ResponseEntity<?> put(
      Authentication authentication, @RequestBody AiCredentialUpsertRequest request) {
    if ("opencode".equals(request.agentType())) {
      Optional<ResponseEntity<Map<String, Object>>> rejected = validateOpencode(request);
      if (rejected.isPresent()) return rejected.get();
    }
    Long userId = (Long) authentication.getPrincipal();
    aiCredentialService.save(toUpsert(request), userId, false);
    return ResponseEntity.noContent().build();
  }

  /** 테넌트 오버라이드 삭제 → 이후 조회는 플랫폼 값으로 되돌아간다({@code tenantOwned=false}). */
  @DeleteMapping
  @RequirePermission("ai:settings")
  public ResponseEntity<Void> delete() {
    aiCredentialService.clearTenantOverride();
    return ResponseEntity.noContent().build();
  }

  /**
   * opencode 모델 목록 조회. 요청 값을 검증 없이 그대로 {@link OpencodeProbeService#probe} 에
   * 전달한다.
   *
   * <p><b>여기서 apiKey 를 직접 해석하지 않는다.</b> {@code apiKey} 생략 시 "저장된 값 재사용"
   * 판정(평면 교차 폴백 금지 포함)은 이미 {@code OpencodeProbeService}(테넌트 행만 본다,
   * {@code AiCredentialService#tenantOpencodeCredential} 참고)가 구현하고 있다. 이 메서드가
   * 대신 {@code settingsService.getValue(...)} 같은 두 평면 해석기로 apiKey 를 채워 넘기면, 테넌트가
   * 재정의하지 않은 상태에서 <b>플랫폼의 apiKey 가 테넌트가 지정한 임의 baseURL 로 전송</b>된다 —
   * 이미 막혀 있는 유출을 이 계층에서 다시 여는 셈이라 절대 하지 않는다.
   */
  @PostMapping("/probe")
  @RequirePermission("ai:settings")
  public ResponseEntity<Map<String, Object>> probe(@RequestBody OpencodeProbeRequest request) {
    ProbeResult result = opencodeProbeService.probe(request.baseURL(), request.apiKey());
    return ResponseEntity.ok(OpencodeCredentialValidation.probeBody(result));
  }

  /**
   * opencode PUT 검증. 순서(비싼 것을 뒤로 미룬다):
   *
   * <ol>
   *   <li>reasoningEffort 정적 enum(네트워크 없음)
   *   <li>필수 필드(providerId/baseURL)가 하나라도 비어 있으면 <b>여기서 더 검증하지 않고 통과</b>
   *       — {@link AiCredentialService#save} 자신의 {@code requireNonBlank} 가 400 을 던진다(같은
   *       검증을 두 곳에 두지 않는다).
   *   <li>{@code ai.model} 과 요청 {@code providerId} 정합성(네트워크 없음)
   *   <li><b>baseURL SSRF 가드(스킴/포트/DNS 해석·사설대역, 네트워크 없음) — apiKey 유무와
   *       무관하게 항상 돈다</b>(보안 리뷰 Fix1, {@link OpencodeProbeService#validateTargetOnly}).
   *   <li>{@code apiKey} 가 생략됐으면 <b>여기서 멈춘다</b> — (네트워크가 필요한) 프로브·모델
   *       소속 검사만 건너뛰고 그대로 저장으로 넘어간다(아래 문단).
   *   <li>프로브(최대 10초 — 설계서 "동시성" 절이 이 창을 인지하고 있다)
   *   <li>프로브가 비어 있지 않은 목록을 주면 모델 소속 검사
   * </ol>
   *
   * <p><b>{@code apiKey} 생략 = 프로브(모델 검증)만 건너뛴다 — 저장 자체도, baseURL 가드도 막지
   * 않는다.</b> 스펙의 "PUT 의 비밀 의미: 필드를 생략하면 현재 값 유지"는 평면을 가리지 않고(플랫폼
   * 컨트롤러의 {@code validateOpencode} javadoc과 같은 근거), 막아도 실제로 얻는 것이 없다 —
   * 저장된 키는 이 PUT 의 승인 여부와 무관하게 런타임에 새 baseURL 로 그대로 나간다(막았다고 그
   * 조합이 안전해지지 않는다). <b>다만 baseURL 자체의 안전성(SSRF 가드)은 apiKey 유무와 별개다</b>
   * — 예전에는 apiKey 생략이 프로브 호출 자체를 건너뛰면서 그 안에 있던 SSRF 가드까지 함께
   * 건너뛰어, {@code apiKey} 없이 169.254.169.254 같은 내부 주소를 무검증으로 저장할 수 있었다
   * (Ruling #27/#29 가 "apiKey 를 요구하지 말라"고 판정한 것이지 "URL 검사를 건너뛰라"는 아니었는데
   * 그 구분이 코드에 반영되지 않았던 것 — 보안 리뷰 Fix1). <b>{@code POST /probe} 는 이 완화를
   * 적용받지 않는다</b> — 거기서 하는 "생략하면 저장된 값 재사용" 판정({@link
   * OpencodeProbeService#probe} 의 baseURL 불일치 검사 포함)은 그대로 남는다. 프로브는 요청 즉시
   * 실제로 외부에 접속하는 별개의 행위이고, 그 접속을 "지금 이 값으로"가 아니라 "몰래 다른 값으로"
   * 트리거할 수 없게 막는 것이 그 가드의 존재 이유이기 때문이다 — 저장(PUT)은 그 접속 자체를
   * 일으키지 않는다.
   *
   * @return 막아야 하면 그 응답, 통과하면 {@link Optional#empty()}
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

    String storedAiModel = settingsService.getValue("ai.model").orElse(null);
    Optional<OpencodeCredentialValidation.Problem> consistency =
        OpencodeCredentialValidation.checkProviderConsistency(providerId, storedAiModel);
    if (consistency.isPresent()) {
      return Optional.of(OpencodeCredentialValidation.problemResponse(consistency.get()));
    }

    // 보안 리뷰 Fix1: baseURL 의 SSRF 가드(스킴/포트/DNS 해석·사설대역)는 apiKey 유무와
    // 무관하게 항상 돈다. 예전에는 아래 apiKey 생략 분기가 프로브 호출 자체를 건너뛰면서 이
    // 가드까지 함께 건너뛰어, apiKey 없이 저장하는 opencode 자격증명의 baseURL 이 전혀 검증되지
    // 않았다 — 169.254.169.254(클라우드 메타데이터) 같은 내부 주소를 apiKey 없이 저장하고
    // ai.model 형식만 맞추면 다음 AI_CLASSIFY 가 그 주소로 실제 요청을 보냈다.
    //
    // 이 가드는 공짜가 아니다 — 안에 실제 DNS 질의(InetAddress.getAllByName)가 들어 있다(예전
    // 이 자리의 주석은 "네트워크 없는 로컬 검사"라고 적었지만 사실이 아니었다). 그래서 통과한
    // 결과(TargetCheck: 검증된 /models URI)를 아래 probe() 에 그대로 넘겨 같은 호스트를 두 번
    // 해석하지 않는다 — probe(TargetCheck, ...) 는 가드를 다시 돌지 않는 대신, 가드를 통과한
    // 인스턴스로만 부를 수 있다(그 타입의 생성자가 private 이라 서비스 밖에서 만들 수 없다).
    OpencodeProbeService.TargetCheck targetCheck = opencodeProbeService.validateTargetOnly(baseURL);
    if (!targetCheck.ok()) {
      ProbeResult failure = targetCheck.failure();
      return Optional.of(
          ResponseEntity.status(OpencodeCredentialValidation.statusFor(failure.reason()))
              .body(Map.of("message", failure.message())));
    }

    String apiKey = request.secret().get("apiKey");
    if (apiKey == null || apiKey.isBlank()) {
      // 재사용할 apiKey 가 없다 — 네트워크 프로브(모델 목록 조회·소속 검사)만 건너뛰고 통과시킨다.
      // baseURL 자체의 안전성 검사는 위에서 이미 끝났다. save() 가 "생략=유지"를 스스로 지키므로
      // 저장 자체는 정상 진행된다(위 javadoc).
      return Optional.empty();
    }

    ProbeResult result;
    try {
      result = opencodeProbeService.probe(targetCheck, apiKey);
    } catch (IllegalArgumentException e) {
      // 방어적 catch — apiKey 를 이미 명시적으로 받았고(그래서 "저장된 키 재사용" 판정 자체가
      // 돌지 않는다) baseURL 형식 검사는 위 targetCheck 가 이미 끝냈으므로, 지금 이 자리에서
      // 실제로 던져질 수 있는 것은 없다(OpencodeProbeService#probe 의 @throws 문서 참고).
      // 그래도 남겨 둔다 — 그 두 전제 중 하나가 바뀌면 500 이 아니라 400 으로 떨어져야 한다.
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




  private static AiCredentialUpsert toUpsert(AiCredentialUpsertRequest request) {
    return new AiCredentialUpsert(request.agentType(), request.payload(), request.secret());
  }
}
