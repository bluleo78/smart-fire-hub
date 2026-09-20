package com.smartfirehub.settings.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.apiconnection.service.UrlUtils;
import com.smartfirehub.pipeline.service.executor.SsrfException;
import com.smartfirehub.pipeline.service.executor.SsrfProtectionService;
import io.netty.channel.ChannelOption;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

/**
 * opencode(OpenAI 호환) 공급자의 {@code GET {baseURL}/models} 를 호출해 모델 목록을 뽑는다.
 *
 * <p><b>이 클래스가 이 기능 전체의 보안 경계다.</b> 인증된 테넌트 관리자가 서버로 하여금 임의
 * URL 에 Bearer 토큰을 실어 보내게 할 수 있는 유일한 지점이다 — 설정 저장(PUT)은 값을 검증만
 * 하지 외부로 나가지 않지만, 프로브는 실제로 나간다. 그래서 가드를 이 서비스 안에서 전부
 * 끝낸다(호출부인 컨트롤러가 빠뜨려도 안전하도록).
 *
 * <p><b>가드 목록</b> (설계서 "프로브(opencode 전용)" 절):
 *
 * <ul>
 *   <li>https 전용, 고정 포트 집합만 허용 — 임의 포트를 허용하면 내부 서비스(Redis/DB 등) 포트
 *       스캔 도구가 된다.
 *   <li>리다이렉트 추적 금지 — 공개 호스트가 302 로 169.254.169.254(클라우드 메타데이터)로
 *       돌려보내는 공격을 막는다.
 *   <li>DNS 해석 결과를 검사한다(호스트명 문자열이 아니라) — 호스트명만 보면 DNS rebinding(검사
 *       시점엔 공인 IP, 접속 시점엔 사설 IP)으로 우회된다.
 *   <li>응답은 모델 ID 배열만 담는다 — upstream 본문·상태 텍스트·헤더를 그대로 흘리지 않는다.
 *   <li>{@code apiKey} 는 로그·응답 어디에도 남기지 않는다.
 *   <li><b>평면 교차 폴백 금지</b> — 요청이 {@code apiKey} 를 생략했을 때 쓰는 "저장된 값"은
 *       {@link AiCredentialService#tenantOpencodeCredential()}(테넌트 행만)이다.
 *       {@link AiCredentialService#resolve()}(두 평면 해석)를 쓰면 테넌트가 재정의하지 않았을 때
 *       플랫폼의 apiKey 가 테넌트가 지정한 임의 baseURL 로 샌다.
 * </ul>
 *
 * <p><b>실패를 구분한다.</b> 모든 실패를 {@code ok=false} 하나로 뭉치면(원인 문구가 전부
 * "실패했습니다" 식이면) 테스트가 "어떤 가드가 실제로 막았는지"를 검증할 수 없고, 화면도 사용자에게
 * 같은 조언만 반복한다. {@code MSG_*} 상수가 원인별로 다른 문구를 낸다.
 *
 * <p><b>400 대 200</b>: 요청 형태 자체가 틀린 두 경우(재사용할 저장된 키가 없음, baseURL 이 저장된
 * 값과 달라 키 없이는 재사용 불가)는 {@link IllegalArgumentException} 을 던진다 —
 * {@code GlobalExceptionHandler} 가 이를 400 으로 매핑하고, {@link AiCredentialService#validate}
 * 가 이미 같은 신호로 쓰는 관례를 그대로 잇는다. 그 외(연결 불가/타임아웃/공급자 거부/응답 해석
 * 불가/가드 차단)는 {@link ProbeResult#ok()}{@code =false} 로 돌려준다 — 설계서가 프로브 엔드포인트
 * 응답을 "항상 200 + {ok,models,message}"(SMTP 테스트 선례)로 못박기 때문이다. 그 200 안에서
 * 422/502/504 를 더 세분화하고 싶으면 {@link ProbeResult#message()}(사람이 읽는 문구)가 아니라
 * {@link ProbeResult.Reason}(분기용 열거형)을 본다 — 문구를 문자열 매칭하면 문구가 바뀔 때마다
 * 그 매핑이 조용히 깨진다. 그 매핑 자체(reason → HTTP 세부 상태)는 이 서비스가 정하지 않는다
 * (저장 시 검증의 400/422/502/504 표는 PUT 경로 것이지 이 프로브의 것이 아니다) — Task 7
 * 컨트롤러가 {@link ProbeResult#reason()} 을 보고 필요하면 세분화한다.
 */
@Slf4j
@Service
public class OpencodeProbeService {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

  // https 기본 포트(443)와, 자체 호스팅 OpenAI 호환 게이트웨이가 흔히 쓰는 대체 포트만 허용한다.
  // 임의 포트를 허용하면 프로브가 내부망 포트 스캐너(예: 6379=Redis, 5432=Postgres, 22=SSH 배너
  // 확인)가 된다 — baseURL 은 테넌트가 자유롭게 입력하는 값이라는 것을 항상 전제한다.
  private static final Set<Integer> ALLOWED_PORTS = Set.of(443, 8443);

  private static final int MAX_RESPONSE_BYTES = 1_000_000; // /models 응답은 목록 하나뿐 — 1MB면 충분하다

  // ---- 실패 사유 메시지 — 각 가드/오류 종류마다 다른 문구를 낸다(테스트가 "어떤 가드가 막았는지"를
  // 구분할 수 있어야 한다는 요구, 아래 클래스 javadoc "실패를 구분한다" 참고). upstream 본문·
  // 상태 텍스트·apiKey 는 어느 문구에도 넣지 않는다.
  static final String MSG_INVALID_URL = "baseUrl 형식이 올바르지 않습니다";
  static final String MSG_SCHEME = "https 만 허용됩니다";
  static final String MSG_PORT = "허용되지 않은 포트입니다";
  static final String MSG_UNRESOLVED_HOST = "호스트를 확인할 수 없습니다";
  static final String MSG_BLOCKED_ADDRESS = "허용되지 않은 대상 주소입니다";
  static final String MSG_REDIRECT = "리다이렉트는 따라가지 않습니다";
  static final String MSG_TIMEOUT = "응답이 시간 초과되었습니다";
  static final String MSG_UNREACHABLE = "공급자에 연결할 수 없습니다";
  static final String MSG_PROVIDER_REJECTED = "공급자가 요청을 거부했습니다";
  static final String MSG_PARSE = "공급자 응답을 해석할 수 없습니다";
  static final String MSG_TOO_LARGE = "공급자 응답이 너무 큽니다";
  static final String MSG_NO_STORED_KEY = "apiKey가 필요합니다 — 재사용할 테넌트 opencode 자격증명이 없습니다";
  static final String MSG_BASE_URL_MISMATCH = "baseUrl이 저장된 값과 다릅니다 — apiKey를 함께 보내야 합니다";

  private final AiCredentialService aiCredentialService;
  private final SsrfProtectionService ssrfProtectionService;
  private final WebClient.Builder webClientBuilder;
  private final Duration timeout;

  @Autowired
  public OpencodeProbeService(
      AiCredentialService aiCredentialService,
      SsrfProtectionService ssrfProtectionService,
      WebClient.Builder webClientBuilder) {
    this(aiCredentialService, ssrfProtectionService, webClientBuilder, DEFAULT_TIMEOUT);
  }

  /**
   * 테스트 전용 — 타임아웃을 짧게 줘서 504 시나리오를 10초 기다리지 않고 재현할 수 있게 한다.
   * 운영 경로는 위 3-인자 생성자(고정 10초)만 쓴다.
   */
  OpencodeProbeService(
      AiCredentialService aiCredentialService,
      SsrfProtectionService ssrfProtectionService,
      WebClient.Builder webClientBuilder,
      Duration timeout) {
    this.aiCredentialService = aiCredentialService;
    this.ssrfProtectionService = ssrfProtectionService;
    this.webClientBuilder = webClientBuilder;
    this.timeout = timeout;
  }

  /**
   * {@code GET {baseUrl}/models} 결과.
   *
   * <p><b>어떤 실패가 여기로 오고, 어떤 실패는 안 오는지.</b> 요청 형태 자체가 틀린 두 경우
   * (재사용할 저장된 키가 없음, baseUrl 이 저장된 값과 달라 키 없이는 재사용 불가)는 이 record 가
   * 아니라 {@link #probe} 가 {@link IllegalArgumentException} 을 던진다(400 신호, 아래
   * {@link #probe} 의 {@code @throws} 참고). <b>그 외 모든 실패</b>(가드 차단/DNS 실패/연결 불가/
   * 타임아웃/리다이렉트/공급자 거부/응답 파싱 불가/응답 과대)는 예외 없이 {@code ok=false} 로
   * 돌아온다 — 설계서가 프로브 엔드포인트 응답을 "항상 200"으로 못박기 때문이다.
   *
   * @param message 사람이 읽는 문구(화면 표시용). 실패 사유별로 다르지만 문자열 자체가 계약은
   *     아니다 — 프로그램이 실패 종류로 분기해야 하면 {@code message} 를 파싱하지 말고
   *     {@link Reason} 을 봐라.
   * @param reason 실패 종류를 식별하는 열거형(성공이면 {@link Reason#OK}). Task 7 컨트롤러가
   *     이 값을 스펙의 "저장 시 검증" 절 400/422/502/504 표에 대응하는 HTTP 상태로 매핑한다 —
   *     한국어 문구를 문자열 매칭해 분기하면 문구가 바뀔 때마다 그 매핑이 조용히 깨진다.
   */
  public record ProbeResult(boolean ok, List<String> models, String message, Reason reason) {

    /**
     * {@code ok() ⇔ reason == Reason.OK} 불변식을 생성 시점에 강제한다.
     *
     * <p><b>왜 필요한가.</b> {@link OpencodeCredentialValidation#statusFor}(Task 7)와 이 클래스의
     * {@code probe()} 호출부는 전부 {@code !result.ok()} 로 먼저 걸러낸 뒤에만
     * {@code statusFor(result.reason())} 를 부른다 — {@code statusFor(Reason.OK)} 는 그 경로가
     * 절대 밟히지 않는다는 가정 아래 일부러 {@code IllegalStateException} 을 던진다(그 메서드
     * javadoc 참고). 이 record 자체는 그 가정("ok=false 인데 reason=OK", 혹은 그 반대)을 아무것도
     * 막지 않았으므로, 나중에 실수로 그런 조합을 만드는 생성자 호출이 생기면 그 가정이 깨지고
     * {@code statusFor(OK)} 가 실제로 호출돼 500 이 된다 — 컴파일도 기존 테스트도 통과한 채로.
     * 그 조합 자체를 생성 시점에 차단해, 사고가 나더라도 이 record 를 만드는 순간 즉시 터지게
     * 한다(런타임 어딘가에서 뒤늦게 500 으로 드러나는 대신).
     */
    public ProbeResult {
      if (ok != (reason == Reason.OK)) {
        throw new IllegalArgumentException(
            "ProbeResult 불변식 위반: ok=" + ok + " 인데 reason=" + reason + " 이다(ok ⇔ reason==OK 여야 한다)");
      }
    }

    /** {@link ProbeResult#reason()} 이 가리키는 실패(또는 성공) 종류. 분기 대상 — 문구가 아니라 이걸 본다. */
    public enum Reason {
      /** 성공. {@code models} 는 비어 있을 수 있다(빈 목록 자체가 유효한 성공, 클래스 하단 참고). */
      OK,
      /** {@code baseUrl} 이 파싱 불가하거나 스킴/호스트가 없다. */
      INVALID_URL,
      /** https 가 아니다. */
      SCHEME_NOT_ALLOWED,
      /** 허용 목록(443/8443)에 없는 포트다. */
      PORT_NOT_ALLOWED,
      /** DNS 해석에 실패했다(호스트가 존재하지 않음 등). */
      UNRESOLVED_HOST,
      /** 해석된 주소가 사설/루프백/링크로컬/멀티캐스트/애니로컬/CGNAT/IPv6 ULA/0.0.0.0/8 중 하나다. */
      BLOCKED_ADDRESS,
      /** 공급자가 3xx 를 돌려줬다 — 따라가지 않고 거부했다. */
      REDIRECT_BLOCKED,
      /** 공급자가 2xx 가 아닌 상태로 응답했다(인증 실패 포함). */
      PROVIDER_REJECTED,
      /** 2xx 응답이지만 몸체를 모델 ID 배열로 해석할 수 없었다. */
      PARSE_ERROR,
      /** 응답 몸체가 크기 상한을 넘었다. */
      TOO_LARGE,
      /** 응답을 기다리다 타임아웃됐다(연결은 됐거나, Mono 타임아웃이 먼저 발동). */
      TIMEOUT,
      /** 연결 자체가 안 됐다(거부/DNS 실패 등, 접속 시점) — 가드 차단과는 다른 사유다. */
      UNREACHABLE,
    }
  }

  /**
   * 모델 목록을 조회한다.
   *
   * @param baseUrl 테넌트가 입력한 opencode 공급자 기본 URL. 신뢰하지 않는다 — 클래스 상단 가드가
   *     전부 이 값에 대해 돈다.
   * @param apiKey 요청에 실린 키. {@code null}/공백이면 테넌트 자신의 저장된 키로 폴백한다(평면
   *     교차 금지, 클래스 javadoc 참고). 그때 {@code baseUrl} 이 저장된 값과 다르면 폴백하지
   *     않고 400 으로 거부한다.
   * @throws IllegalArgumentException 재사용할 저장된 키가 없거나, baseUrl 이 저장된 값과 달라
   *     키 없이는 재사용할 수 없을 때(둘 다 요청 형태 문제 — 400)
   */
  public ProbeResult probe(String baseUrl, String apiKey) {
    String effectiveKey = resolveApiKey(baseUrl, apiKey);

    // 가드 본체는 validateTargetOnly() 하나다 — 이 경로도 그것을 통과해야만 doRequest 에 도달한다.
    // (영어권 표현으로 나누어 복붙하지 않는다 — 두 사본이 갈라지면 한쪽에만 새 가드가 들어간다.)
    TargetCheck check = validateTargetOnly(baseUrl);
    if (!check.ok()) {
      return check.failure();
    }

    return doRequest(check.modelsUri, effectiveKey);
  }

  /**
   * 이미 가드를 통과한 대상({@link #validateTargetOnly} 의 결과)으로 프로브한다 — 같은 baseUrl 에
   * 대해 스킴/포트 검사와 <b>DNS 해석</b>을 다시 하지 않는다.
   *
   * <p><b>왜 있나.</b> 저장(PUT) 경로는 apiKey 유무와 무관하게 가드를 먼저 돌리고(보안 리뷰 Fix1),
   * apiKey 가 있을 때만 이어서 프로브를 부른다. 그 두 단계가 각자 {@code baseUrl} 문자열로 시작하면
   * {@code InetAddress.getAllByName()}(실제 DNS 질의)가 요청 1건당 두 번 돌고, 그 사이에 응답이
   * 바뀌면 "검사한 주소"와 "접속할 주소"가 달라질 여지도 더 넓어진다.
   *
   * <p><b>가드를 건너뛰는 문이 아니다.</b> {@link TargetCheck} 는 생성자가 {@code private} 라 이 클래스
   * 밖에서 만들 수 없고, 유일한 생성 지점이 가드 본체({@link #validateTargetOnly})다. 게다가 실패한
   * 결과를 들고 오면 아래에서 즉시 거부한다.
   *
   * @throws IllegalStateException 가드에 실패한 {@code check} 로 불렸을 때(호출부 버그)
   * @throws IllegalArgumentException {@link #probe(String, String)} 과 같은 조건 — 재사용할 저장된
   *     키가 없거나 baseUrl 이 저장된 값과 달라 키 없이는 재사용할 수 없을 때(400 신호)
   */
  public ProbeResult probe(TargetCheck check, String apiKey) {
    if (!check.ok()) {
      throw new IllegalStateException(
          "가드에 실패한 TargetCheck 로 probe 를 부를 수 없다 — 호출부가 ok() 를 먼저 확인했어야 한다");
    }
    String effectiveKey = resolveApiKey(check.baseUrl, apiKey);
    return doRequest(check.modelsUri, effectiveKey);
  }

  /**
   * baseUrl 이 SSRF 가드(스킴/포트/DNS 해석·사설대역)를 통과하는지만 검사한다 — 실제
   * {@code GET /models} 네트워크 호출은 하지 않는다.
   *
   * <p><b>왜 필요한가(보안 리뷰 Fix1).</b> {@code AiCredentialController}/
   * {@code PlatformAiCredentialController} 의 opencode PUT 검증은 {@code apiKey} 가 생략되면
   * {@link #probe} 를 통째로 건너뛰어 왔다(Ruling #27/#29 — "프로브만 건너뛴다"는 판정 자체는
   * 맞지만, 그 프로브 안에 있던 이 가드까지 함께 건너뛰는 게 문제였다). 그 결과 {@code apiKey}
   * 없이 저장하는 opencode 자격증명은 {@code baseURL} 이 전혀 검증되지 않은 채 저장됐고, 클라우드
   * 메타데이터 주소({@code 169.254.169.254}) 같은 내부 대상을 저장한 뒤 {@code ai.model} 형식만
   * 맞추면 다음 {@code AI_CLASSIFY} 호출에서 서버가 실제로 그 주소에 요청을 보내는 SSRF 가
   * 성립했다. 이 메서드를 apiKey 유무와 무관하게 저장 시마다 호출해 그 구멍을 막는다.
   *
   * @return 가드를 통과하면 {@code ok()==true} 인 {@link TargetCheck}(검증된 {@code /models} URI 를
   *     함께 든다 — {@link #probe(TargetCheck, String)} 가 그대로 받아 재검증·재해석을 건너뛴다),
   *     실패하면 {@code ok()==false} 이고 {@code failure()} 에 실패 사유가 담긴 {@link ProbeResult} 가
   *     들어 있다. {@link #probe} 와 달리 {@code baseUrl} 형식 오류도 예외가 아니라 이 결과
   *     (Reason.INVALID_URL)로 돌아온다 — 호출부(컨트롤러)가 이미 프로브의
   *     {@code IllegalArgumentException}/{@code ProbeResult} 두 갈래를 각자 다르게 다루고 있어,
   *     이 메서드는 후자 하나로 통일해 호출부 분기를 단순하게 유지한다.
   */
  public TargetCheck validateTargetOnly(String baseUrl) {
    URI target;
    try {
      target = buildModelsUri(baseUrl);
    } catch (IllegalArgumentException | URISyntaxException e) {
      return new TargetCheck(
          new ProbeResult(false, List.of(), MSG_INVALID_URL, ProbeResult.Reason.INVALID_URL),
          baseUrl,
          null);
    }
    try {
      validateTarget(target);
    } catch (GuardViolation e) {
      // 어느 가드에 걸렸는지는 예외가 직접 들고 온다(e.reason) — 예전에는 메시지 문자열을 다시
      // 매칭해 Reason 을 복원했는데, 가드를 하나 더하고 그 매핑을 깜빡하면 조용히 깨지는 왕복이었다.
      // e.getMessage() 는 위 MSG_* 상수 중 하나다 — upstream 과 무관하게 이 서비스가 스스로 만든
      // 문구라 그대로 노출해도 안전하다.
      return new TargetCheck(
          new ProbeResult(false, List.of(), e.getMessage(), e.reason), baseUrl, null);
    }
    return new TargetCheck(null, baseUrl, target);
  }

  /**
   * {@link #validateTargetOnly} 가 돌려주는 가드 검사 결과 — 통과했으면 그 대상을, 실패했으면
   * 그대로 응답할 {@link ProbeResult} 를 든다.
   *
   * <p><b>왜 값 객체인가.</b> 전에는 {@code validateTargetOnly} 가 {@link ProbeResult} 만 돌려줘서,
   * 통과했다는 사실 외에 "무엇을 통과시켰는지"(조립된 {@code /models} URI)를 들고 나올 통로가
   * 없었다. 그래서 컨트롤러가 이어서 {@code probe(baseUrl, apiKey)} 를 부르면 같은 호스트에 대해
   * URI 조립과 DNS 해석이 처음부터 다시 돌았다.
   *
   * <p><b>생성자가 {@code private} 인 이유.</b> 이 타입의 존재 자체가 "가드를 통과했다"는 증거라,
   * 밖에서 임의로 만들 수 있으면 {@link #probe(TargetCheck, String)} 가 가드를 건너뛰는 우회로가
   * 된다 — 그게 정확히 보안 리뷰 Fix1 이 고친 버그의 모양이다. {@code record} 가 아니라 일반
   * 클래스인 것도 같은 이유다 — public record 는 정규 생성자가 반드시 public 이라 그 문을 닫을 수 없다.
   */
  public static class TargetCheck {

    /** 가드에 걸렸을 때 그대로 응답할 결과. 통과했으면 {@code null} 이다. */
    private final ProbeResult failure;

    /** 요청이 준 원본 {@code baseUrl} — 저장된 키 재사용 판정(정규화 비교)에 그대로 쓴다. */
    private final String baseUrl;

    /** 가드를 통과한 {@code {baseUrl}/models} URI. 실패했으면 {@code null} 이다. */
    private final URI modelsUri;

    private TargetCheck(ProbeResult failure, String baseUrl, URI modelsUri) {
      this.failure = failure;
      this.baseUrl = baseUrl;
      this.modelsUri = modelsUri;
    }

    /** 가드를 통과했는가. */
    public boolean ok() {
      return failure == null;
    }

    /**
     * 실패 응답. {@link #ok()} 가 {@code true} 면 {@code null} 이다 — 호출부는 항상
     * {@code ok()} 로 먼저 거른다.
     */
    public ProbeResult failure() {
      return failure;
    }
  }

  /**
   * {@link #validateTarget} 이 가드에 걸렸을 때 던지는 사설 예외 — <b>어떤 가드에 걸렸는지를
   * {@link ProbeResult.Reason} 으로 직접 들고 온다.</b>
   *
   * <p><b>왜 사설 예외인가.</b> 예전에는 {@code validateTarget} 이 {@code SsrfException(MSG_*)}
   * 으로 메시지만 던지고, 호출부가 그 고정 문자열 4개를 다시 문자열 매칭해 {@code Reason} 을
   * 복원했다(매칭 실패 시 {@code IllegalStateException} 으로 방어). 가드는 자기가 어느 갈래에서
   * 걸렸는지 이미 정확히 알고 있었으므로 그 왕복은 정보를 한 번 버렸다가 추측으로 되살리는
   * 것이었고, 가드를 하나 더하면서 역매핑을 깜빡하면 런타임에야 드러나는 종류의 결합이었다.
   *
   * <p>{@link SsrfException} 을 상속한다 — {@code validateTarget} 의 공개된 계약("여기서 던지는
   * {@code SsrfException} 의 메시지는 항상 {@code MSG_*} 중 하나")과 그 메서드를 재정의해 쓰는
   * 테스트 확장점을 그대로 유지하기 위해서다. 외부 노출 타입인 {@code SsrfException} 자체의
   * 계약은 건드리지 않는다.
   */
  private static final class GuardViolation extends SsrfException {
    private final transient ProbeResult.Reason reason;

    private GuardViolation(String message, ProbeResult.Reason reason) {
      super(message);
      this.reason = reason;
    }

    private GuardViolation(String message, ProbeResult.Reason reason, Throwable cause) {
      super(message, cause);
      this.reason = reason;
    }
  }

  // -------------------------------------------------------------------------
  // apiKey 해석 — 평면 교차 폴백 금지
  // -------------------------------------------------------------------------

  /**
   * 요청에 {@code apiKey} 가 있으면 그대로 쓴다. 없으면(생략/공백) 테넌트 자신의 저장된 opencode
   * 자격증명으로만 폴백한다 — {@link AiCredentialService#resolve()}(두 평면)를 쓰지 않는다(클래스
   * javadoc "평면 교차 폴백 금지" 참고).
   */
  private String resolveApiKey(String baseUrl, String apiKey) {
    if (apiKey != null && !apiKey.isBlank()) {
      return apiKey;
    }

    Optional<AiCredentialService.StoredOpencodeCredential> stored =
        aiCredentialService.tenantOpencodeCredential();
    if (stored.isEmpty() || stored.get().apiKey().isBlank()) {
      throw new IllegalArgumentException(MSG_NO_STORED_KEY);
    }

    // baseURL 이 저장된 값과 다르면 저장된 키를 재사용하지 않는다 — 그렇지 않으면 관리자가
    // baseURL 만 바꿔 임의 호스트로 테스트해도(apiKey 는 생략) 서버가 알아서 "지금까지 저장된"
    // 키를 실어 보내 버린다. CSRF 로 baseURL 필드만 조작당해도 같은 결과이므로, 키를 재사용하려면
    // 요청이 저장된 것과 같은 baseURL 임을 스스로 증명해야 한다(달라졌으면 apiKey 를 다시 보내라).
    //
    // equals() 의 수신자를 저장된 값(never null — payload().path("baseURL").asText("") 라 항상
    // 문자열이다) 쪽으로 둔다. 요청 baseUrl 은 null 일 수 있고(UrlUtils.normalizeBaseUrl(null) 은
    // null 을 그대로 돌려준다), null 을 수신자로 두면 NPE 가 나 400 대신 500 이 된다 — apiKey 를
    // 생략한 요청이 baseUrl 도 생략했다면 "저장된 값과 다르다"로 자연스럽게 떨어져야 한다.
    if (!UrlUtils.normalizeBaseUrl(stored.get().baseUrl()).equals(UrlUtils.normalizeBaseUrl(baseUrl))) {
      throw new IllegalArgumentException(MSG_BASE_URL_MISMATCH);
    }

    return stored.get().apiKey();
  }

  // -------------------------------------------------------------------------
  // URL 검증 — SSRF 가드
  // -------------------------------------------------------------------------

  /**
   * {@code baseUrl + "/models"} 를 만들되, 원본 문자열을 그대로 이어붙이지 않고 스킴/호스트/포트/
   * 경로만 뽑아 새 {@link URI} 를 조립한다. userinfo({@code user@host})·query·fragment 는 버린다
   * — 파서마다 해석이 갈릴 수 있는 조각을 결과 URI 에 남기지 않기 위해서다(예:
   * {@code https://trusted.example@evil.example/} 같은 입력에서 실제 접속 대상은 항상 host 필드
   * 값이어야 한다).
   */
  private URI buildModelsUri(String baseUrl) throws URISyntaxException {
    if (baseUrl == null || baseUrl.isBlank()) {
      throw new IllegalArgumentException(MSG_INVALID_URL);
    }
    URI base = new URI(baseUrl);
    String scheme = base.getScheme();
    String host = base.getHost();
    if (scheme == null || host == null) {
      throw new IllegalArgumentException(MSG_INVALID_URL);
    }
    String path = base.getPath() == null ? "" : base.getPath();
    if (path.endsWith("/")) {
      path = path.substring(0, path.length() - 1);
    }
    return new URI(scheme, null, host, base.getPort(), path + "/models", null, null);
  }

  /**
   * 대상을 검증한다. 여기서 던지는 것은 {@link SsrfException} 의 하위 타입인 {@code GuardViolation}
   * 이고, 메시지는 항상 위 {@code MSG_*} 상수 중 하나이며 어느 가드에 걸렸는지({@code Reason})를
   * 함께 들고 나간다 — 호출부가 메시지를 되매핑할 필요가 없다.
   *
   * <p><b>protected — 테스트 전용 확장점.</b> WireMock(루프백)을 상대해야 하는 테스트는 이
   * 메서드를 재정의해 통째로 건너뛴다. 가드 자체(사설 대역/스킴/포트/DNS 재바인딩 차단)의 정확성은
   * 이 메서드를 재정의하지 <b>않은</b> 인스턴스로 검증하는 별도 테스트들이 맡는다 — 두 책임을
   * 한 테스트 그룹에 같이 두면(성공 검증 + 루프백 차단을 같은 인스턴스로) 둘 중 하나가 항상
   * 실패하게 된다(WireMock 은 루프백에서 뜬다).
   */
  protected void validateTarget(URI uri) {
    String scheme = uri.getScheme();
    if (scheme == null || !scheme.equalsIgnoreCase("https")) {
      throw new GuardViolation(MSG_SCHEME, ProbeResult.Reason.SCHEME_NOT_ALLOWED);
    }

    int port = uri.getPort() == -1 ? 443 : uri.getPort();
    if (!ALLOWED_PORTS.contains(port)) {
      throw new GuardViolation(MSG_PORT, ProbeResult.Reason.PORT_NOT_ALLOWED);
    }

    InetAddress[] addresses;
    try {
      // 호스트명 문자열이 아니라 실제 DNS 해석 결과를 검사한다 — "사설 대역처럼 보이는 문자열"이
      // 아니라 "지금 이 이름이 가리키는 IP"를 판정해야 DNS rebinding(검사 시점엔 공인 IP를
      // 돌려주고, 접속 시점엔 사설 IP로 바뀌는 공격)을 막을 수 있다. 이 판정 자체는 여전히
      // 검사-시점 값이라 접속 시점까지의 완전한 방어(= IP 고정 접속)는 아니다 — 보고서에 남긴다.
      addresses = resolve(uri.getHost());
    } catch (UnknownHostException e) {
      throw new GuardViolation(MSG_UNRESOLVED_HOST, ProbeResult.Reason.UNRESOLVED_HOST, e);
    }

    for (InetAddress address : addresses) {
      // RFC1918/루프백/링크로컬/멀티캐스트/애니로컬은 기존 SSRF 가드(pipeline 쪽에서 이미 검증된
      // 로직)를 재사용한다 — 같은 판정을 이 클래스에 다시 베끼면 두 곳이 따로 늙는다.
      // 단, 그 가드가 던지는 예외 메시지는 그대로 흘려보내지 않고 MSG_BLOCKED_ADDRESS 로
      // 바꿔치기한다 — 원본 메시지는 실제 해석된 IP(address.getHostAddress())를 영어로 담고
      // 있어(SsrfProtectionService 는 pipeline 쪽 로그/예외용으로 설계됨), 그대로 흘리면 프로브
      // 응답이 테넌트에게 "이 baseURL 이 어떤 내부 IP 로 풀리는지"를 알려주는 정찰 도구가 된다.
      try {
        ssrfProtectionService.validateResolvedAddress(address);
      } catch (SsrfException blocked) {
        throw new GuardViolation(MSG_BLOCKED_ADDRESS, ProbeResult.Reason.BLOCKED_ADDRESS);
      }
      // 기존 가드가 놓치는 대역을 여기서 보강한다: CGNAT(100.64.0.0/10 — 일부 k8s 파드 CIDR가
      // 이 대역을 쓴다), IPv6 ULA(fc00::/7 — 듀얼스택 클러스터의 사설 대역;
      // InetAddress.isSiteLocalAddress() 의 IPv6 판정은 폐기된 fec0::/10 만 본다), 0.0.0.0/8("이
      // 네트워크" 예약 대역 — InetAddress.isAnyLocalAddress() 는 정확히 0.0.0.0 하나만 보고
      // 0.0.0.1 등 나머지 /8 전체는 통과시킨다. 이 세 대역만 보강하고 이 서비스 밖
      // (SsrfProtectionService 자체)으로 넓히지 않는다 — 넓히면 이미 그 클래스를 쓰는 pipeline
      // API_CALL 경로까지 영향권에 들어간다.
      if (isCgnat(address) || isIpv6UniqueLocal(address) || isReservedZeroNet(address)) {
        throw new GuardViolation(MSG_BLOCKED_ADDRESS, ProbeResult.Reason.BLOCKED_ADDRESS);
      }
    }
  }

  /** DNS 해석. protected — DNS rebinding 테스트가 "실제 이름은 그대로 두고 해석 결과만 사설 IP로" 재현하려면 필요하다. */
  protected InetAddress[] resolve(String host) throws UnknownHostException {
    return InetAddress.getAllByName(host);
  }

  private static boolean isCgnat(InetAddress address) {
    byte[] b = address.getAddress();
    if (b.length != 4) return false; // IPv4 전용 대역
    int octet1 = b[0] & 0xFF;
    int octet2 = b[1] & 0xFF;
    return octet1 == 100 && octet2 >= 64 && octet2 <= 127; // 100.64.0.0/10
  }

  private static boolean isIpv6UniqueLocal(InetAddress address) {
    byte[] b = address.getAddress();
    if (b.length != 16) return false; // IPv6 전용 대역
    return (b[0] & 0xFE) == 0xFC; // fc00::/7 (상위 7비트 1111110)
  }

  /**
   * 0.0.0.0/8("이 네트워크" — RFC 791/1122). {@link InetAddress#isAnyLocalAddress()} 는 전부
   * 0인 주소(0.0.0.0) 하나만 참을 반환하고, 0.0.0.1 같은 나머지 대역 전체는 통과시킨다(리뷰에서
   * jshell 로 실측: {@code 0.0.0.1} 은 any/loopback/site 판정 모두 거짓). 커널·라우팅 설정에 따라
   * 실제 도달 가능성은 갈리지만, 스펙이 명시적으로 나열한 차단 대상 대역이라 문서화된 구멍보다는
   * 막힌 상태가 낫다.
   */
  private static boolean isReservedZeroNet(InetAddress address) {
    byte[] b = address.getAddress();
    if (b.length != 4) return false; // IPv4 전용 대역
    return b[0] == 0; // 0.0.0.0/8
  }

  // -------------------------------------------------------------------------
  // HTTP 호출
  // -------------------------------------------------------------------------

  private ProbeResult doRequest(URI uri, String apiKey) {
    ExchangeStrategies strategies =
        ExchangeStrategies.builder()
            .codecs(c -> c.defaultCodecs().maxInMemorySize(MAX_RESPONSE_BYTES))
            .build();

    // followRedirect(false): 리다이렉트를 절대 따라가지 않는다 — 공개 호스트가 302 로
    // 169.254.169.254(클라우드 메타데이터) 같은 내부 주소로 돌려보내는 공격을 애초에 차단한다.
    // ApiCallExecutor 처럼 "리다이렉트를 검증 후 따라간다"가 아니라 "절대 안 따라간다"인 이유는
    // 설계서가 이 프로브에 대해서만 명시적으로 "리다이렉트 추적 금지"라고 못박았기 때문이다.
    //
    // responseTimeout() 을 쓰지 않는다 — 아래 doRequest() 의 Mono.timeout(timeout) 과 같은
    // "응답을 기다리는 구간"을 이중으로 재는 꼴이라, 어느 쪽이 먼저 발동하느냐가 타이밍 경쟁이다.
    // reactor-netty 의 responseTimeout 초과는 java.util.concurrent.TimeoutException 이 아니라
    // io.netty.handler.timeout.ReadTimeoutException 을 내므로, 그게 먼저 발동하면 isTimeout() 이
    // 못 잡고 MSG_UNREACHABLE 로 떨어져 "타임아웃과 연결 불가를 구분한다"는 설계 의도가 깨진다.
    // 대신 연결 자체가 안 되는 상황(사설 대역이 아니면서 방화벽이 조용히 드롭하는 호스트 등)만
    // CONNECT_TIMEOUT_MILLIS 로 별도로 막는다 — 이건 응답 대기가 아니라 접속 자체의 문제라
    // MSG_UNREACHABLE 로 분류되는 것이 맞다.
    HttpClient reactorClient =
        HttpClient.create()
            .followRedirect(false)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) timeout.toMillis());

    WebClient client =
        webClientBuilder
            .clientConnector(new ReactorClientHttpConnector(reactorClient))
            .exchangeStrategies(strategies)
            .build();

    try {
      ProbeResult result =
          client
              .get()
              .uri(uri)
              // Authorization 헤더는 이 요청에만 실린다 — 로그 인터셉터가 없어 액세스 로그에도
              // 남지 않는다(이 클래스는 요청/응답 어디에서도 apiKey 를 log.* 로 찍지 않는다).
              .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
              .exchangeToMono(
                  response -> {
                    HttpStatusCode status = response.statusCode();

                    if (status.is3xxRedirection()) {
                      return response.releaseBody().thenReturn(new ProbeResult(false, List.of(), MSG_REDIRECT, ProbeResult.Reason.REDIRECT_BLOCKED));
                    }
                    if (!status.is2xxSuccessful()) {
                      // 상태 코드 숫자만 남긴다 — 본문(예: "bad key sk-...")과 상태 텍스트(reason
                      // phrase)는 담지 않는다(설계서: "upstream 본문·상태 텍스트·헤더를 그대로
                      // 흘리지 않는다").
                      int code = status.value();
                      return response
                          .releaseBody()
                          .thenReturn(
                              new ProbeResult(
                                      false,
                                      List.of(),
                                      MSG_PROVIDER_REJECTED + " (status=" + code + ")",
                                      ProbeResult.Reason.PROVIDER_REJECTED));
                    }
                    return response.bodyToMono(String.class).map(OpencodeProbeService::parseModels);
                  })
              .timeout(timeout)
              .block();
      return result;
    } catch (DataBufferLimitException e) {
      return new ProbeResult(false, List.of(), MSG_TOO_LARGE, ProbeResult.Reason.TOO_LARGE);
    } catch (Exception e) {
      if (isTimeout(e)) {
        return new ProbeResult(false, List.of(), MSG_TIMEOUT, ProbeResult.Reason.TIMEOUT);
      }
      // 연결 거부/DNS 실패(실제 접속 시점)/기타 네트워크 오류 — 원인 메시지는 로그에만 남기고
      // (아래 log.debug) 응답에는 일반화된 문구만 준다. e.getMessage() 를 그대로 노출하면 내부
      // 네트워크 토폴로지(예: 어떤 주소가 어떻게 실패했는지)가 upstream 없이도 새어 나갈 수 있다.
      log.debug("opencode 프로브 연결 실패: {}", e.toString());
      return new ProbeResult(false, List.of(), MSG_UNREACHABLE, ProbeResult.Reason.UNREACHABLE);
    }
  }

  private static boolean isTimeout(Throwable e) {
    Throwable cur = e;
    while (cur != null) {
      if (cur instanceof TimeoutException) return true;
      cur = cur.getCause();
    }
    return false;
  }

  /** OpenAI 표준 {@code {data:[...]}} 과 배열 직반환을 모두 받는다. 모델 ID 만 뽑는다. */
  private static ProbeResult parseModels(String body) {
    JsonNode root;
    try {
      root = MAPPER.readTree(body);
    } catch (Exception e) {
      return new ProbeResult(false, List.of(), MSG_PARSE, ProbeResult.Reason.PARSE_ERROR);
    }

    JsonNode items = root.isArray() ? root : root.path("data");
    if (!items.isArray()) {
      return new ProbeResult(false, List.of(), MSG_PARSE, ProbeResult.Reason.PARSE_ERROR);
    }

    List<String> ids = new ArrayList<>();
    for (JsonNode item : items) {
      String id = item.path("id").asText("");
      if (!id.isBlank()) {
        ids.add(id);
      }
    }
    return new ProbeResult(true, ids, null, ProbeResult.Reason.OK);
  }
}
