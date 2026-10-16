package com.smartfirehub.settings.controller;

import com.smartfirehub.settings.model.AiCredentialSlot;
import com.smartfirehub.settings.service.AiCredentialService;
import com.smartfirehub.settings.service.OpencodeProbeService;
import com.smartfirehub.settings.service.OpencodeProbeService.ProbeResult;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/**
 * opencode 자격증명 PUT 검증 — 채팅({@code AiCredentialController})과 분류 ({@code
 * AiClassifyCredentialController}) 두 컨트롤러가 공유한다(#707).
 *
 * <p><b>왜 추출했나.</b> 이 순서(추론 강도 → 필수 필드 → 모델·공급자 정합성 → SSRF 가드 → 프로브 → 모델 소속)는 보안 리뷰 Fix1 이 고정한 것이다.
 * 컨트롤러마다 사본을 두면 한쪽에만 새 가드가 들어가는 드리프트가 생긴다. 채팅 경로의 동작은 추출 전과 같다({@code AiCredentialControllerTest}).
 * 단 하나의 예외는 userinfo 거부({@link #MSG_BASE_URL_USERINFO})로, 추출과 함께 두 경로 모두에 더해졌다.
 *
 * <p>차이는 인자로만 표현한다: {@code model} 은 채팅이면 저장된 {@code ai.model}, 분류면 요청의 모델이고, {@code slot} 은 프로브를 어느
 * 자격증명 슬롯의 행위로 부를지다(채팅/분류).
 */
@Component
@RequiredArgsConstructor
public class OpencodePutValidator {

  /**
   * baseURL 에 userinfo 가 있을 때의 400 문구(#707).
   *
   * <p><b>왜 막나.</b> {@code https://user:pass@gw/v1} 같은 값은 비밀을 URL 에 싣는다 — 그대로 저장되면 payload(평문, GET
   * 응답에 노출)·캐시 판별자·로그에 비밀이 남는다. 프로브는 URI 를 조립할 때 userinfo 를 버리므로 SSRF 가드만으로는 걸러지지 않는다. 비밀은 {@code
   * apiKey}(암호화 저장)로만 받는다.
   */
  public static final String MSG_BASE_URL_USERINFO =
      "baseURL 에 사용자 정보(user:pass@)를 넣을 수 없습니다. API 키는 apiKey 로 입력하세요.";

  private final OpencodeProbeService opencodeProbeService;

  /**
   * {@link #validate} 의 결과 — 저장을 막아야 하면 {@code block} 에 그 응답을, 막지 않되 사용자에게 알려야 할 사정이 있으면
   * {@code warning} 에 문구를 담는다(WD-50). 저장을 통과시킨다는 점에서 "차단 아님"은 같지만, 과거 반환 타입({@code
   * Optional<ResponseEntity<...>>} 의 빈 값)은 그 둘을 구분할 통로가 없었다.
   */
  public record ValidationOutcome(
      Optional<ResponseEntity<Map<String, Object>>> block, Optional<String> warning) {

    private static final ValidationOutcome PASS = new ValidationOutcome(Optional.empty(), Optional.empty());

    /** 막을 것도 경고할 것도 없다 — 조용히 저장 진행. */
    public static ValidationOutcome pass() {
      return PASS;
    }

    /** 저장은 통과시키되, 호출부가 응답에 실어 사용자에게 보여줘야 하는 경고. */
    public static ValidationOutcome passWithWarning(String warning) {
      return new ValidationOutcome(Optional.empty(), Optional.of(warning));
    }

    /** 저장을 막는다 — 호출부는 이 응답을 그대로 돌려준다. */
    public static ValidationOutcome block(ResponseEntity<Map<String, Object>> response) {
      return new ValidationOutcome(Optional.of(response), Optional.empty());
    }
  }

  /**
   * opencode PUT 검증. 순서(비싼 것을 뒤로 미룬다):
   *
   * <ol>
   *   <li>reasoningEffort 정적 enum(네트워크 없음)
   *   <li>필수 필드(providerId/baseURL)가 하나라도 비어 있으면 <b>여기서 더 검증하지 않고 통과</b> — {@link
   *       AiCredentialService#save} 자신의 {@code requireNonBlank} 가 400 을 던진다(같은 검증을 두 곳에 두지 않는다).
   *   <li>{@code model} 과 요청 {@code providerId} 정합성(네트워크 없음)
   *   <li>baseURL 에 userinfo({@code user:pass@}) 가 있으면 400(네트워크 없음, #707 — 아래 상수 참고)
   *   <li><b>baseURL SSRF 가드(스킴/포트/DNS 해석·사설대역, 네트워크 없음) — apiKey 유무와 무관하게 항상 돈다</b>(보안 리뷰 Fix1,
   *       {@link OpencodeProbeService#validateTargetOnly}).
   *   <li>{@code apiKey} 가 생략됐으면 <b>여기서 멈춘다</b> — (네트워크가 필요한) 프로브·모델 소속 검사만 건너뛰고 그대로 저장으로 넘어간다(아래
   *       문단).
   *   <li>프로브(최대 10초 — 설계서 "동시성" 절이 이 창을 인지하고 있다)
   *   <li>프로브가 비어 있지 않은 목록을 주면 모델 소속 검사
   * </ol>
   *
   * <p><b>{@code apiKey} 생략 = 프로브(모델 검증)만 건너뛴다 — 저장 자체도, baseURL 가드도 막지 않는다.</b> 스펙의 "PUT 의 비밀 의미:
   * 필드를 생략하면 현재 값 유지"를 그대로 따르고, 막아도 실제로 얻는 것이 없다 — 저장된 키는 이 PUT 의 승인 여부와 무관하게 런타임에 새 baseURL 로 그대로
   * 나간다(막았다고 그 조합이 안전해지지 않는다). <b>다만 baseURL 자체의 안전성(SSRF 가드)은 apiKey 유무와 별개다</b> — 예전에는 apiKey 생략이
   * 프로브 호출 자체를 건너뛰면서 그 안에 있던 SSRF 가드까지 함께 건너뛰어, {@code apiKey} 없이 169.254.169.254 같은 내부 주소를 무검증으로
   * 저장할 수 있었다 (Ruling #27/#29 가 "apiKey 를 요구하지 말라"고 판정한 것이지 "URL 검사를 건너뛰라"는 아니었는데 그 구분이 코드에 반영되지
   * 않았던 것 — 보안 리뷰 Fix1). <b>{@code POST /probe} 는 이 완화를 적용받지 않는다</b> — 거기서 하는 "생략하면 저장된 값 재사용"
   * 판정({@link OpencodeProbeService#probe} 의 baseURL 불일치 검사 포함)은 그대로 남는다. 프로브는 요청 즉시 실제로 외부에 접속하는
   * 별개의 행위이고, 그 접속을 "지금 이 값으로"가 아니라 "몰래 다른 값으로" 트리거할 수 없게 막는 것이 그 가드의 존재 이유이기 때문이다 — 저장(PUT)은 그 접속
   * 자체를 일으키지 않는다.
   *
   * @param payload 요청 payload(비공개 필드 제외)
   * @param secret 요청 secret(생략 = 저장값 유지)
   * @param model 공급자 정합성·소속 검사에 쓸 모델 — 채팅은 저장된 {@code ai.model}, 분류는 요청 모델
   * @param slot 프로브를 부를 자격증명 슬롯(채팅/분류). {@code apiKey} 가 실려 있을 때만 프로브까지 오므로 여기서 이 슬롯의 저장 키를 대신 읽는
   *     폴백 분기는 돌지 않는다 — 슬롯은 프로브 호출을 어느 자격증명의 행위로 기록·해석할지만 정한다
   * @return 막아야 하면 {@code block} 에 그 응답을 담은 {@link ValidationOutcome}, 통과(필요하면 경고와 함께)하면 {@code
   *     block} 이 비어 있는 {@link ValidationOutcome}
   */
  public ValidationOutcome validate(
      Map<String, Object> payload,
      Map<String, String> secret,
      String model,
      AiCredentialSlot slot) {
    Optional<OpencodeCredentialValidation.Problem> effortProblem =
        OpencodeCredentialValidation.checkReasoningEffort(payload);
    if (effortProblem.isPresent()) {
      return ValidationOutcome.block(OpencodeCredentialValidation.problemResponse(effortProblem.get()));
    }

    String providerId = OpencodeCredentialValidation.strOrEmpty(payload.get("providerId"));
    String baseURL = OpencodeCredentialValidation.strOrEmpty(payload.get("baseURL"));
    if (providerId.isBlank() || baseURL.isBlank()) {
      return ValidationOutcome.pass();
    }

    Optional<OpencodeCredentialValidation.Problem> consistency =
        OpencodeCredentialValidation.checkProviderConsistency(providerId, model);
    if (consistency.isPresent()) {
      return ValidationOutcome.block(OpencodeCredentialValidation.problemResponse(consistency.get()));
    }

    // userinfo 거부(#707) — SSRF 가드보다 먼저, 네트워크 없이 끊는다. 가드는 userinfo 를 버린 URI 로
    // 검사하므로 이 값을 통과시킨다(MSG_BASE_URL_USERINFO javadoc 참고).
    if (hasUserInfo(baseURL)) {
      return ValidationOutcome.block(
          ResponseEntity.badRequest().body(Map.of("message", MSG_BASE_URL_USERINFO)));
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
      return ValidationOutcome.block(
          ResponseEntity.status(OpencodeCredentialValidation.statusFor(failure.reason()))
              .body(Map.of("message", failure.message())));
    }

    String apiKey = secret.get("apiKey");
    if (apiKey == null || apiKey.isBlank()) {
      // 재사용할 apiKey 가 없다 — 네트워크 프로브(모델 목록 조회·소속 검사)만 건너뛰고 통과시킨다.
      // baseURL 자체의 안전성 검사는 위에서 이미 끝났다. save() 가 "생략=유지"를 스스로 지키므로
      // 저장 자체는 정상 진행된다(위 javadoc).
      return ValidationOutcome.pass();
    }

    ProbeResult result;
    try {
      result = opencodeProbeService.probe(slot, targetCheck, apiKey);
    } catch (IllegalArgumentException e) {
      // 방어적 catch — apiKey 를 이미 명시적으로 받았고(그래서 "저장된 키 재사용" 판정 자체가
      // 돌지 않는다) baseURL 형식 검사는 위 targetCheck 가 이미 끝냈으므로, 지금 이 자리에서
      // 실제로 던져질 수 있는 것은 없다(OpencodeProbeService#probe 의 @throws 문서 참고).
      // 그래도 남겨 둔다 — 그 두 전제 중 하나가 바뀌면 500 이 아니라 400 으로 떨어져야 한다.
      return ValidationOutcome.block(ResponseEntity.badRequest().body(Map.of("message", e.getMessage())));
    }
    if (!result.ok()) {
      // WD-50: "/models 엔드포인트 자체가 없다"(404)는 "공급자가 진짜로 거부했다"(401/403/5xx 등)와
      // 다르다 — 전자는 채팅(chat/completions)엔 영향이 없는 모델 목록 API 하나의 부재일 뿐이라,
      // 저장을 막는 대신 경고로 낮춘다. 그 외 PROVIDER_REJECTED(401/403/5xx)나 TIMEOUT 등 다른
      // Reason 은 기존대로 차단한다 — status 문자열 파싱이 아니라 httpStatus 구조화 필드로 구분한다.
      if (result.reason() == ProbeResult.Reason.PROVIDER_REJECTED
          && Integer.valueOf(404).equals(result.httpStatus())) {
        return ValidationOutcome.passWithWarning(
            "이 게이트웨이는 모델 목록 조회(/models)를 지원하지 않습니다 — 자격증명은 저장됐지만 모델 선택 화면에서"
                + " 목록을 불러올 수 없습니다. 채팅 자체는 정상 동작할 수 있습니다.");
      }
      return ValidationOutcome.block(
          ResponseEntity.status(OpencodeCredentialValidation.statusFor(result.reason()))
              .body(Map.of("message", result.message())));
    }

    Optional<OpencodeCredentialValidation.Problem> membership =
        OpencodeCredentialValidation.checkModelMembership(model, result.models());
    return membership
        .map(p -> ValidationOutcome.block(OpencodeCredentialValidation.problemResponse(p)))
        .orElseGet(ValidationOutcome::pass);
  }

  /**
   * baseURL 의 authority 에 userinfo 가 있는지. 파싱이 안 되는 값은 여기서 판정하지 않는다 — 바로 뒤 SSRF 가드가 형식 오류(400)로 거른다.
   * {@code getUserInfo()} 는 authority 가 서버 형식으로 파싱될 때만 채워지므로, 원문 authority 의 {@code @} 도 함께 본다.
   */
  private static boolean hasUserInfo(String baseURL) {
    try {
      URI uri = new URI(baseURL);
      String rawAuthority = uri.getRawAuthority();
      return uri.getRawUserInfo() != null || (rawAuthority != null && rawAuthority.contains("@"));
    } catch (URISyntaxException e) {
      return false;
    }
  }
}
