package com.smartfirehub.settings.controller;

import com.smartfirehub.settings.service.OpencodeProbeService.ProbeResult;
import com.smartfirehub.settings.service.OpencodeProbeService.ProbeResult.Reason;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * {@code AiCredentialController} 의 opencode PUT/프로브 검증 로직({@link Reason} → HTTP 상태
 * 매핑, ai.model 정합성·모델 소속 검사, 추론 강도 enum).
 *
 * <p><b>왜 별도 클래스인가.</b> 컨트롤러와 분리된 순수 정적 함수라
 * {@code OpencodeCredentialValidationTest} 가 HTTP 없이 매핑 규칙을 고정할 수 있다 — Ruling
 * #25("Reason 을 매핑하지, 문자열을 매칭하지 않는다")가 여기 한 곳에 있다.
 */
public final class OpencodeCredentialValidation {

  private OpencodeCredentialValidation() {}

  /**
   * 설계서 §"저장 시 검증" — 추론 강도는 이 정적 집합으로만 검사한다. {@code /models} 응답으로는
   * 어떤 모델이 어떤 추론 강도를 지원하는지 알 수 없으므로, 실제 지원 여부는 과금되는 completion
   * 호출로 게이트하지 않고 런타임에 드러나게 둔다. {@code ""} 는 "기본값"(미설정)이다.
   */
  public static final Set<String> ALLOWED_REASONING_EFFORTS = Set.of("", "low", "medium", "high");

  /** PUT 검증에서 발견한 문제 하나 — 응답으로 그대로 내보낼 상태/메시지. */
  public record Problem(HttpStatus status, String message) {}

  /**
   * {@link Reason} → HTTP 상태(Ruling #25). <b>exhaustive switch, default 없음</b> — {@code
   * Reason} 에 값이 늘면 이 메서드가 컴파일되지 않는다. "새 실패 종류가 조용히 500(혹은 다른
   * 상태)이 되는" 사고를, 리뷰가 놓쳐도 컴파일러가 막게 하려는 것이다.
   *
   * <p>버킷과 그 근거:
   *
   * <ul>
   *   <li><b>400</b>({@code INVALID_URL}/{@code SCHEME_NOT_ALLOWED}/{@code PORT_NOT_ALLOWED}/
   *       {@code BLOCKED_ADDRESS}) — 넷 다 "이 URL 자체를 받아들일 수 없다"는 <b>요청 형태</b>
   *       문제다. 실제 공급자에 도달하기 전에(BLOCKED_ADDRESS 만 DNS 까지는 하지만 소켓 연결
   *       전에) 판정되며, 요청을 그대로 다시 보내도 절대 성공하지 않는다 — 클라이언트가 값을
   *       바꿔야 하는 부류다.
   *   <li><b>502</b>({@code UNRESOLVED_HOST}/{@code REDIRECT_BLOCKED}/{@code UNREACHABLE}) — 이
   *       호스트에 "도달"하는 것 자체가 실패했다. DNS 실패도 도달 실패의 한 형태로 묶는다 —
   *       호스트명 형식은 멀쩡하되(그래서 400 이 아니다) 그 이름이 가리키는 곳에 갈 수 없었다는
   *       점에서 UNREACHABLE 과 같은 성격이다. REDIRECT_BLOCKED 도 마찬가지로 묶는다 — 우리가
   *       따라가길 거부했을 뿐, 원래 요청한 주소에서 실제 응답을 받는 데는 실패했다.
   *   <li><b>422</b>({@code PROVIDER_REJECTED}/{@code PARSE_ERROR}/{@code TOO_LARGE}) — 도달은
   *       했지만 응답을 쓸 수 없다(인증 거부/파싱 불가/과대). "요청 자체는 문법적으로 멀쩡하지만
   *       지금 이 개체(응답)로는 처리할 수 없다"는 422 원래 의미에 더 가깝다. 스펙이 명시한
   *       "공급자가 거부 / 모델이 목록에 없음" 버킷과 같은 계열로 묶었다.
   *   <li><b>504</b>({@code TIMEOUT}) — 스펙이 명시한 그대로.
   * </ul>
   */
  public static HttpStatus statusFor(Reason reason) {
    return switch (reason) {
      case OK ->
          throw new IllegalStateException(
              "Reason.OK 는 오류가 아니다 — 호출부가 ProbeResult.ok() 를 먼저 확인했어야 한다");
      case INVALID_URL, SCHEME_NOT_ALLOWED, PORT_NOT_ALLOWED, BLOCKED_ADDRESS ->
          HttpStatus.BAD_REQUEST;
      case UNRESOLVED_HOST, REDIRECT_BLOCKED, UNREACHABLE -> HttpStatus.BAD_GATEWAY;
      case PROVIDER_REJECTED, PARSE_ERROR, TOO_LARGE -> HttpStatus.UNPROCESSABLE_ENTITY;
      case TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
    };
  }

  /**
   * reasoningEffort 정적 enum 검사(Ruling #12) — 과금되는 completion 호출로 저장을 게이트하지
   * 않는다. 키가 생략되면 검사 대상이 아니다({@code AiCredentialService.save} 의 병합 규칙이
   * "생략=기존 값 유지"를 이미 보장한다).
   */
  public static Optional<Problem> checkReasoningEffort(Map<String, Object> payload) {
    Object value = payload.get("reasoningEffort");
    if (value == null) return Optional.empty();
    String effort = String.valueOf(value);
    if (!ALLOWED_REASONING_EFFORTS.contains(effort)) {
      return Optional.of(
          new Problem(
              HttpStatus.BAD_REQUEST,
              "reasoningEffort 는 low/medium/high 중 하나이거나 빈 값이어야 한다: " + effort));
    }
    return Optional.empty();
  }

  /**
   * {@code ai.model}(형식 {@code providerId/modelId}) 과 요청 {@code providerId} 의 정합성.
   *
   * <p><b>대조할 수 없으면(비어 있거나 슬래시가 없으면) 통과시킨다 — 400 으로 막지 않는다.</b>
   * 처음 이 메서드를 쓸 때는 "슬래시가 없으면 형식 오류"로 400 을 던졌는데, 그러면 실제 잠금이
   * 생긴다: {@code sdk}(Anthropic, 예 {@code claude-sonnet-4-...} — 슬래시 없음)를 쓰던 테넌트가
   * 처음으로 opencode 자격증명을 저장하려 할 때 {@code ai.model} 이 아직 opencode 형식으로
   * 바뀌지 않았다는 이유만으로 <b>매번</b> 막힌다. 그런데 그 테넌트가 {@code ai.model} 을
   * opencode 형식으로 바꾸려면(화면 흐름상) 먼저 opencode 자격증명을 저장해 모델 목록을 프로브해야
   * 한다 — 순환 잠금이 된다. {@code ai.model} 은 이 클래스의 소관이 아니라 {@code SettingsService}
   * 가 "free-form 키"로 다루는 값이므로(그 클래스의 {@code ai.model 관련} 주석 참고), 이 검사는
   * "대조 가능할 때만 대조한다"로 좁힌다 — 형식 자체를 강제하는 것은 이 엔드포인트의 책임 밖이다.
   */
  public static Optional<Problem> checkProviderConsistency(
      String requestProviderId, String storedAiModel) {
    if (storedAiModel == null || storedAiModel.isBlank()) return Optional.empty();
    int slash = storedAiModel.indexOf('/');
    if (slash < 0) return Optional.empty(); // 슬래시가 없다 = 아직 opencode 형식이 아니다 = 대조 불가
    String storedProviderId = storedAiModel.substring(0, slash);
    if (!storedProviderId.equals(requestProviderId)) {
      return Optional.of(
          new Problem(
              HttpStatus.BAD_REQUEST,
              "ai.model("
                  + storedAiModel
                  + ")의 provider 가 요청의 providerId("
                  + requestProviderId
                  + ") 와 다르다"));
    }
    return Optional.empty();
  }

  /**
   * 프로브가 <b>비어 있지 않은</b> 모델 목록을 줬을 때만 {@code ai.model} 의 modelId 가 그 목록에
   * 있는지 검사한다 — 목록이 비면(공급자가 {@code /models} 를 안 줌) 화면이 자유 입력으로
   * 전환되므로 저장을 막지 않는다(스펙 "저장 시 검증" 절).
   */
  public static Optional<Problem> checkModelMembership(String storedAiModel, List<String> probedModels) {
    if (probedModels.isEmpty()) return Optional.empty();
    if (storedAiModel == null || storedAiModel.isBlank()) return Optional.empty();
    int slash = storedAiModel.indexOf('/');
    // 슬래시가 없으면(아직 opencode 형식이 아닌 ai.model) 대조할 modelId 가 없다 — 400 으로
    // 잡는 게 아니라(checkProviderConsistency 도 이제 이 경우를 통과시킨다, 그 메서드 javadoc
    // 참고) 그냥 검사 대상이 아니라고 본다.
    if (slash < 0) return Optional.empty();
    String modelId = storedAiModel.substring(slash + 1);
    if (!probedModels.contains(modelId)) {
      return Optional.of(
          new Problem(
              HttpStatus.UNPROCESSABLE_ENTITY, "ai.model(" + modelId + ") 이 공급자 모델 목록에 없다"));
    }
    return Optional.empty();
  }

  /**
   * {@link Problem} 을 그대로 응답으로 바꾼다. 응답 모양({@code {message}})은 이 클래스가 만드는
   * {@code Problem} 의 짝이므로 여기 함께 둔다.
   */
  public static ResponseEntity<Map<String, Object>> problemResponse(Problem problem) {
    return ResponseEntity.status(problem.status()).body(Map.of("message", problem.message()));
  }

  /** payload 값(자유형 {@code Object})을 문자열로 — {@code null} 은 빈 문자열이다. */
  public static String strOrEmpty(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  /**
   * {@code POST /probe} 응답 본문.
   *
   * <p>{@code Map.of} 대신 {@link LinkedHashMap} 을 쓴다 — 성공 시 {@code message} 가
   * {@code null} 일 수 있는데({@link ProbeResult} 의 OK 케이스), {@code Map.of} 는 null 값에서
   * 즉시 {@code NullPointerException} 을 던진다.
   */
  public static Map<String, Object> probeBody(ProbeResult result) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("ok", result.ok());
    body.put("models", result.models());
    body.put("message", result.message());
    return body;
  }
}
