package com.smartfirehub.pipeline.service.executor;

import com.smartfirehub.apiconnection.service.AuthQueryMasker;
import com.smartfirehub.global.exception.ExternalServiceException;
import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;

/**
 * API_CALL 요청 한 번을 보내고 리다이렉트를 손으로 따라간다(WD-693) — 파이프라인 실행({@link ApiCallExecutor}), 「테스트 호출」
 * 미리보기({@code ApiCallPreviewService}), API 연결 테스트({@code ApiConnectionService})가 같은 경로를 쓴다.
 *
 * <p><b>왜 한 곳인가.</b> 예전에는 실행만 리다이렉트를 따라가고(홉마다 SSRF 검사·인증 떼기), 미리보기는 기본 WebClient(Reactor Netty 기본값:
 * 따라가지 않음)로 3xx 본문을 그대로 미리보기로 보여 같은 설정의 미리보기와 실행 결과가 달랐다. 미리보기에 따로 따라가기를 붙이면 SSRF·인증 규칙이 두 벌이 되므로
 * 실행의 루프를 여기로 옮겨 모두 부른다.
 *
 * <p><b>홉마다 지키는 규칙.</b>
 *
 * <ul>
 *   <li>따라가는 상태는 {@link RedirectLocations#isFollowed}(301·302·303·307·308)만 — 그 밖의 3xx 는 일반 응답으로
 *       본문을 읽는다.
 *   <li>Location 은 {@link RedirectLocations#resolve} 로 절대 주소로 풀고(프래그먼트 제거) 따라가기 전에 SSRF 검사를 한다 — 악성
 *       서버가 내부망(169.254.169.254 등)으로 3xx 해 처음 주소 검사를 우회하지 못하게.
 *   <li>메서드·본문은 {@link RedirectLocations#keepsMethodAndBody} — 한 번 본문을 버리면 그 뒤 홉도 본문·본문 헤더 없음.
 *   <li>인증은 처음 요청과 같은 출처({@link ApiCallAuth#isSameAuthOrigin})로 가는 홉에만 붙이고, 다른 출처면 인증 헤더·자격 증명
 *       헤더·쿼리 인증 키를 뗀다(WD-657). 판단 기준은 언제나 처음 요청의 출처라 A→B→A 면 B 에는 떼고 A 로 돌아올 때는 붙인다.
 *   <li>최대 {@link #MAX_REDIRECTS} 회.
 * </ul>
 *
 * <p><b>오류.</b> {@link Request#failOnErrorStatus} 면 상태 오류를 실행과 같은 분류로 낸다 — 5xx·408·429 는 재시도할
 * {@link RetryableApiCallException}, 그 밖 4xx 는 {@link ApiCallException}. 아니면(API 연결 테스트) 4xx·5xx 도
 * 마지막 응답 그대로 돌려준다. 전송 오류(DNS·연결·시간 초과)는 실패한 홉 안에서 그 홉의 호스트로 사용자 문구를 만들어(인증 비밀 가림) {@link
 * RetryableApiCallException} 으로 낸다 — 호출부는 호스트를 몰라도 되고, 문구가 처음 호스트가 아니라 실패한 홉의 호스트를 말한다. 문구에 요청 URL
 * 은 넣지 않는다(쿼리 인증 키가 들어 있을 수 있다). 응답 크기 초과({@link DataBufferLimitException})만 호출부가 한도 값을 넣어 문구를 만들도록
 * 그대로 낸다.
 */
@Slf4j
public final class RedirectFollower {

  /** 따라가는 리다이렉트 최대 횟수 — 넘으면 {@link ApiCallErrorMessages#tooManyRedirects} 로 실패한다. */
  public static final int MAX_REDIRECTS = 5;

  /**
   * 본문을 설명하는 헤더(소문자, WD-676) — 본문을 버린 리다이렉트 홉부터 사용자 헤더에 있어도 보내지 않는다(규칙: {@link
   * RedirectLocations#keepsMethodAndBody}). Python {@code _BODY_HEADERS} 와 같다.
   */
  private static final Set<String> BODY_HEADERS =
      Set.of("content-type", "content-length", "content-encoding", "transfer-encoding");

  private RedirectFollower() {}

  /**
   * 요청 하나(모든 홉에 걸쳐 바뀌지 않는 값).
   *
   * @param client 자동 리다이렉트를 끄고 접속 시점 주소 검사를 건 클라이언트({@link #withoutAutoRedirect})
   * @param ssrf 홉마다 리다이렉트 대상을 검사할 SSRF 검사기
   * @param method 처음 요청 메서드(대문자, POST 가 아니면 GET 으로 보낸다)
   * @param uri 처음 요청 URI(쿼리 인증 키 포함) — 인증을 붙일지 정하는 출처 기준이기도 하다
   * @param headers 사용자 헤더(null 가능)
   * @param body POST 본문(null·빈 값이면 본문 없음)
   * @param auth 한 번 푼 인증(ApiCallAuth.resolve) — 전송 오류 문구에서 가릴 비밀도 여기서 얻는다
   * @param timeoutMs 응답 시간 제한(ms) — 오류 문구의 초 표기에도 쓴다
   * @param timeoutPerHop true 면 홉마다 timeoutMs(파이프라인 실행 — Python 실행기 httpx 와 같고, 전체 시간은
   *     재시도·maxDurationMs 가 따로 다룬다). false 면 모든 홉을 합쳐 timeoutMs 한 번(사용자가 기다리는 미리보기·연결 테스트 — 홉마다 걸면
   *     (최대 홉 수+1)×timeoutMs 동안 붙잡힌다)
   * @param failOnErrorStatus true 면 4xx·5xx 를 예외로 낸다(실행·미리보기 — 오류 본문은 읽지 않는다). false 면 마지막 응답으로
   *     돌려준다(연결 테스트가 오류 본문·헤더를 보여 준다)
   */
  public record Request(
      WebClient client,
      SsrfProtectionService ssrf,
      String method,
      URI uri,
      Map<String, String> headers,
      String body,
      ApiCallAuth.ResolvedAuth auth,
      int timeoutMs,
      boolean timeoutPerHop,
      boolean failOnErrorStatus) {

    /** 사용자 헤더·본문 없는 GET(WD-693) — API 연결 테스트처럼 헤더·본문을 따로 주지 않는 호출용. */
    public static Request bodilessGet(
        WebClient client,
        SsrfProtectionService ssrf,
        URI uri,
        ApiCallAuth.ResolvedAuth auth,
        int timeoutMs,
        boolean timeoutPerHop,
        boolean failOnErrorStatus) {
      return new Request(
          client, ssrf, "GET", uri, null, null, auth, timeoutMs, timeoutPerHop, failOnErrorStatus);
    }
  }

  /**
   * 받은 빌더를 복제해 자동 리다이렉트를 끈 커넥터를 붙인다 — 홉마다 SSRF 를 검사하려면 Netty 가 스스로 따라가면 안 된다.
   *
   * <p>항상 복제한다(WD-693): 주입받은 공유 빌더를 그대로 바꾸면 응답 크기 제한(exchangeStrategies)이 다른 파이프라인이 동시에 돌 때 서로의 설정을
   * 덮어쓴다. 호출자가 {@code clone()} 을 잊어도 안전하도록 여기서 한다.
   *
   * <p><b>접속 시점 주소 검사(WD-613).</b> 커넥터의 HttpClient 는 반드시 {@link SsrfSafeHttpClientFactory} 에서 받는다 —
   * 그 해석기가 접속할 IP 를 SSRF 정책으로 다시 검사하므로, 첫 요청뿐 아니라 이 클라이언트로 보내는 모든 리다이렉트 홉이 DNS 재바인딩(검사 땐 공개 IP·접속 땐
   * 내부 IP)에 막힌다. 예전처럼 {@code HttpClient.create()} 를 직접 쓰면 홉마다 validateUrl 을 해도 접속 때 다시 해석한 내부 IP 로
   * 나갈 수 있어, 팩토리를 인자로 받아 우회 경로를 없앤다.
   */
  public static WebClient.Builder withoutAutoRedirect(
      WebClient.Builder builder, SsrfSafeHttpClientFactory httpClientFactory) {
    return builder
        .clone()
        .clientConnector(
            new ReactorClientHttpConnector(httpClientFactory.httpClient().followRedirect(false)));
  }

  /**
   * 요청을 보내고 리다이렉트를 따라가 마지막 응답(상태·헤더·본문)을 돌려준다(WD-693). 본문이 없는 응답이면 204 빈 응답이다. 오류 처리는 클래스 설명과
   * {@link Request#failOnErrorStatus} 를 따른다 — 본문만 쓰는 호출부는 {@code getBody()} 를 부른다.
   *
   * <p>홉마다 바뀌는 값(메서드·주소·처음 출처를 벗어난 적·본문을 버린 적)은 루프의 지역 변수다.
   */
  public static ResponseEntity<String> follow(Request req) {
    // 전체 마감(System.nanoTime 기준) — 홉마다 시간 제한이면 없다(null).
    Long deadline =
        req.timeoutPerHop()
            ? null
            : System.nanoTime() + Duration.ofMillis(req.timeoutMs()).toNanos();
    // 처음 출처는 요청마다 한 번만 읽는다(WD-693) — 홉마다 같은 처음 주소를 다시 해석하지 않게. 읽을 수 없으면 모든 홉이 다른 출처다.
    UrlAuthority origin = UrlAuthority.of(req.uri()).orElse(null);

    String method = req.method();
    URI uri = req.uri();
    // 이 홉까지 오는 동안 처음 출처를 벗어난 적이 있는지 — 처음 출처로 돌아올 때 뗐던 쿼리 인증 키를 되붙이는 데 쓴다.
    boolean leftOrigin = false;
    // 앞선 리다이렉트가 본문을 버렸는지(WD-686) — 한 번 true 면 되돌리지 않는다(Python body_dropped).
    boolean bodyDropped = false;

    try {
      for (int redirectCount = 0; ; redirectCount++) {
        if (redirectCount > MAX_REDIRECTS) {
          throw new ApiCallException(ApiCallErrorMessages.tooManyRedirects(MAX_REDIRECTS));
        }
        // 이 홉이 처음 요청과 같은 출처일 때만 인증 헤더를 붙인다(WD-657). 첫 홉은 판정 없이 붙인다 — java.net.URI 가 호스트를 못 읽는
        // 주소(밑줄 든 호스트 이름 등)도 예전처럼 인증해 보내야 한다.
        boolean sendAuth = redirectCount == 0 || sameOrigin(req, origin, uri);
        AtomicReference<Redirect> redirectRef = new AtomicReference<>();
        ResponseEntity<String> response =
            sendHop(req, method, uri, sendAuth, bodyDropped, deadline, redirectRef);

        Redirect redirect = redirectRef.get();
        if (redirect == null) {
          return response != null ? response : ResponseEntity.noContent().build();
        }
        if (redirect.location().isBlank()) {
          // Location 없는 3xx 는 따라갈 곳이 없다(WD-676) — Python(redirect_without_location)과 같은 문구.
          // 재시도하지 않는다.
          throw new ApiCallException(ApiCallErrorMessages.redirectWithoutLocation());
        }
        // Location 은 무엇보다 먼저 지금 요청 기준 절대 주소로 푼다(WD-657·WD-676).
        // 주소 검사·출처 판단·다음 요청이 모두 같은 절대 주소를 본다.
        URI target = RedirectLocations.resolve(uri, redirect.location());
        if (log.isDebugEnabled()) {
          // 주소 가리기(redactUrl)는 디버그 로그를 켰을 때만 한다(WD-693).
          log.debug(
              "Redirect {}/{}: {} -> {}",
              redirectCount + 1,
              MAX_REDIRECTS,
              ApiCallErrorMessages.redactUrl(uri),
              ApiCallErrorMessages.redactUrl(target));
        }

        // 따라가기 전에 대상 주소를 SSRF 검사한다(홉마다)
        req.ssrf().validateUrl(target.toString());

        boolean sameOrigin = sameOrigin(req, origin, target);
        if (req.auth().isQueryApiKey()) {
          target = adjustQueryAuth(target, req.auth(), sameOrigin, leftOrigin);
        }
        // 다음 홉의 메서드·본문(WD-686) — 규칙은 RedirectLocations.keepsMethodAndBody 참고.
        boolean keep = RedirectLocations.keepsMethodAndBody(redirect.status(), sameOrigin);
        method = keep ? method : "GET";
        uri = target;
        leftOrigin |= !sameOrigin;
        bodyDropped |= !keep;
      }
    } catch (ApiCallException
        | SsrfException
        | ExternalServiceException
        | DataBufferLimitException e) {
      // 이미 사용자 문구를 담았거나(이 패키지) 호출부가 한도 값을 넣어 다루는(응답 크기 초과) 예외는 그대로 낸다.
      throw e;
    } catch (RuntimeException e) {
      // 접속 시점 주소 검사(WD-613)에 막힌 홉 — SsrfException 이 WebClientRequestException 등에 감싸여 온다. 꺼내서 사전
      // 검사(validateUrl)와 같은 차단 문구의 SsrfException 으로 낸다: 재시도 대상(RetryableApiCallException)으로 감싸면 같은
      // 차단을
      // N회 반복하고, 호출부(연결 테스트·미리보기)도 사전 검사와 같은 처리(본문·헤더 비움 등)를 하지 못한다.
      SsrfException blocked = SsrfException.findIn(e);
      if (blocked != null) {
        throw new SsrfException(blocked.getMessage(), e);
      }
      // 전송 오류(DNS·연결·시간 초과)를 실패한 홉의 호스트로 분류한다(WD-693) — 문구가 처음 호스트가 아니라 이 홉의 호스트를 말하게. 분류하지
      // 못한 원문에 인증 비밀이 실려 올 수 있어 가린다(WD-177). 실제로 보내는 비밀 전부 — BASIC 은 비밀번호와 Basic 헤더의 Base64
      // 덩어리 둘 다다(WD-681). 재시도 대상이다.
      String message =
          req.auth()
              .sentSecrets()
              .scrubText(
                  ApiCallErrorMessages.describe(e, req.timeoutMs(), UrlAuthority.hostOrNull(uri)));
      throw new RetryableApiCallException(message, e);
    }
  }

  /** 처음 요청과 같은 인증 출처인지 — 미리 읽어 둔 처음 출처({@code origin}, 못 읽었으면 null)로 본다(WD-693). */
  private static boolean sameOrigin(Request req, UrlAuthority origin, URI target) {
    return origin != null && ApiCallAuth.isSameAuthOrigin(req.uri().getScheme(), origin, target);
  }

  /**
   * 홉 하나를 보낸다. 따라가는 3xx 면 {@code redirectRef} 에 상태·Location 을 채우고 null 을 돌려준다(리액티브 콜백이 본문과 함께 돌려줄 수
   * 없다).
   *
   * <p>전송 오류는 그대로 낸다 — {@link #follow} 가 이 홉의 호스트로 사용자 문구를 만든다(WD-693, 클래스 설명).
   *
   * @param deadline 전체 마감(없으면 null) — {@link #hopTimeout}
   */
  private static ResponseEntity<String> sendHop(
      Request req,
      String method,
      URI uri,
      boolean sendAuth,
      boolean bodyDropped,
      Long deadline,
      AtomicReference<Redirect> redirectRef) {
    // Netty 가 못 부르는 호스트(밑줄 등)는 요청 전에 고정 문구로 실패한다(WD-676) — 첫 홉과 리다이렉트 대상 모두 여기서 본다.
    UrlAuthority.requireClientSupportedHost(uri);
    return buildRequestSpec(req, method, uri, sendAuth, bodyDropped)
        .exchangeToMono(
            resp -> {
              HttpStatusCode status = resp.statusCode();

              // 따라가는 상태만(WD-693) — 300·304·305 등 그 밖의 3xx 는 아래 일반 응답으로 본문을 읽는다(Python 과 같다).
              if (RedirectLocations.isFollowed(status.value())) {
                String location = resp.headers().asHttpHeaders().getFirst("Location");
                // Location 이 없으면 빈 문자열로 둔다(WD-676) — 호출부가 「Location 없는 3xx」로 판정한다.
                redirectRef.set(new Redirect(status.value(), location != null ? location : ""));
                return resp.releaseBody().then(Mono.<ResponseEntity<String>>empty());
              }

              if (req.failOnErrorStatus()
                  && (status.is4xxClientError() || status.is5xxServerError())) {
                // 상태별 한국어 안내(WD-150). 5xx·408·429 만 재시도(RetryableApiCallException), 나머지 4xx 는 다시
                // 보내도 같은
                // 답이라 즉시 실패(ApiCallException). 본문은 읽지 않고 버린다 — 큰 오류 본문이 응답 크기 제한에 걸려 「응답 크기
                // 초과」로 잘못 보이지 않게.
                int code = status.value();
                String message = ApiCallErrorMessages.httpStatus(code);
                RuntimeException failure =
                    ApiCallErrorMessages.isRetryableStatus(code)
                        ? new RetryableApiCallException(message)
                        : new ApiCallException(message);
                return resp.releaseBody().then(Mono.<ResponseEntity<String>>error(failure));
              }

              // 2xx 와 따라가지 않는 3xx(failOnErrorStatus 가 아니면 4xx·5xx 도) — 본문을 읽는다.
              // 크기는 클라이언트 코덱 상한이 막는다.
              return resp.toEntity(String.class);
            })
        .timeout(hopTimeout(req.timeoutMs(), deadline))
        .block();
  }

  /**
   * 이 홉에 걸 시간 제한. 전체 마감이 없으면 timeoutMs, 있으면 마감까지 남은 시간이다. 이미 마감이 지났으면 요청을 보내지 않고 시간 초과로 실패한다 —
   * Reactor {@code timeout} 과 같은 {@link TimeoutException} 이라 오류 문구(「응답 시간 초과(n초)」)도 같다.
   */
  private static Duration hopTimeout(int timeoutMs, Long deadline) {
    if (deadline == null) {
      return Duration.ofMillis(timeoutMs);
    }
    long remaining = deadline - System.nanoTime();
    if (remaining <= 0) {
      throw Exceptions.propagate(
          new TimeoutException("redirect deadline of " + timeoutMs + "ms exceeded"));
    }
    return Duration.ofNanos(remaining);
  }

  /**
   * 리다이렉트 대상의 쿼리 인증 키를 출처에 맞춘다(WD-657).
   *
   * <ul>
   *   <li>다른 출처: Location 이 되울려 담아 온 키를 뺀다 — 헤더만 떼고 쿼리 키를 남기면 같은 비밀이 그대로 나간다.
   *   <li>처음 출처로 돌아옴(A→B→A): 앞에서 뗀 키가 빠져 있으면 되붙인다 — 헤더 인증은 같은 출처 홉에 다시 붙으므로 쿼리 인증도 같게 한다.
   *   <li>처음부터 같은 출처 안에서만 옮겨 다니면 Location 을 받은 그대로 쓴다(WD-182).
   * </ul>
   */
  static URI adjustQueryAuth(
      URI target, ApiCallAuth.ResolvedAuth auth, boolean sameOrigin, boolean hasLeftOrigin) {
    String url = target.toString();
    if (!sameOrigin) {
      return URI.create(ApiCallAuth.stripQueryParam(url, auth.paramName()));
    }
    if (hasLeftOrigin && !ApiCallAuth.hasQueryParam(url, auth.paramName())) {
      try {
        // 처음 요청과 같은 공용 빌더(QueryApiKeyEncoding 으로 한 번만 인코딩)로 붙인다.
        return auth.buildUrls(url, Map.of()).callUri();
      } catch (AuthQueryMasker.InvalidRequestUrlException e) {
        throw new ApiCallException(e.getMessage());
      }
    }
    return target;
  }

  /** 받은 3xx 응답(WD-686) — 다음 홉의 메서드·본문을 정할 상태 코드와 Location(없으면 빈 문자열). */
  private record Redirect(int status, String location) {}

  /**
   * 홉 하나의 요청을 만든다 — POST 면 본문(있으면 Content-Type: application/json), 그 밖은 GET.
   *
   * @param sendAuth 인증 헤더를 붙일지 — 처음 요청과 다른 출처로 가는 리다이렉트 홉이면 false(WD-657)
   * @param bodyDropped 리다이렉트가 본문을 버린 홉인지 — 본문 헤더(Content-Type 등)를 뺀다(WD-676)
   */
  private static WebClient.RequestHeadersSpec<?> buildRequestSpec(
      Request req, String method, URI requestUri, boolean sendAuth, boolean bodyDropped) {
    if ("POST".equals(method)) {
      WebClient.RequestBodySpec bodySpec = req.client().post().uri(requestUri);
      applyHeaders(bodySpec, req, sendAuth, false);
      if (req.body() != null && !req.body().isBlank()) {
        return bodySpec.header("Content-Type", "application/json").bodyValue(req.body());
      }
      return bodySpec;
    }
    // GET(기본) — 처음 요청 또는 리다이렉트 홉
    WebClient.RequestHeadersSpec<?> getSpec = req.client().get().uri(requestUri);
    applyHeaders(getSpec, req, sendAuth, bodyDropped);
    return getSpec;
  }

  /**
   * 사용자 헤더와 인증 헤더를 붙인다.
   *
   * <p>{@code sendAuth} 가 false(다른 출처 리다이렉트, WD-657)면 인증 헤더를 붙이지 않고, 사용자 헤더 가운데 자격 증명 헤더도 뺀다 —
   * Authorization·Proxy-Authorization·Cookie 와 인증 헤더와 같은 이름({@link
   * ApiCallAuth.ResolvedAuth#isCredentialHeader}). Python {@code _do_request} 도 같은 규칙이다. 본문을 버린 홉이면
   * 본문 헤더({@link #BODY_HEADERS})도 뺀다.
   */
  private static void applyHeaders(
      WebClient.RequestHeadersSpec<?> spec,
      Request req,
      boolean sendAuth,
      boolean dropBodyHeaders) {
    ApiCallAuth.ResolvedAuth auth = req.auth();
    if (req.headers() != null) {
      req.headers()
          .forEach(
              (name, value) -> {
                if (dropBodyHeaders && BODY_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                  return;
                }
                if (sendAuth || !auth.isCredentialHeader(name)) {
                  spec.header(name, value);
                }
              });
    }
    // 인증 헤더 — 한 번 푼 값(ApiCallAuth.resolve)을 공용 규칙(ResolvedAuth.applyHeaders)으로 붙인다(WD-647). 다른 출처
    // 홉에는 붙이지 않는다.
    if (sendAuth) {
      auth.applyHeaders(spec::header);
    }
  }
}
