package com.smartfirehub.embedding;

import com.smartfirehub.pipeline.service.executor.ApiCallErrorMessages;
import com.smartfirehub.pipeline.service.executor.UrlAuthority;
import io.netty.handler.ssl.SslHandshakeTimeoutException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import javax.net.ssl.SSLException;
import org.springframework.core.codec.DecodingException;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 임베딩 provider(Ollama·OpenAI) 호출 실패를 원인별 한국어 안내 문장으로 바꾼다(WD-863).
 *
 * <p><b>왜 필요한가.</b> 예전에는 {@code "Ollama 임베딩 호출 실패: " + e.getMessage()} 라 netty 영어 원문({@code Failed
 * to resolve 'host' [A(1)]} 등)이 그대로 사용자에게 보였고, 연결 테스트가 접두어를 한 번 더 붙여 이중으로 나왔다. 여기서 원인을 분류해 무엇을 확인해야
 * 하는지 알려 준다. 문장에는 접두어를 두지 않는다 — 연결 테스트가 「임베딩 연결 테스트 실패: 」 를 한 번만 붙인다.
 *
 * <p><b>비밀값.</b> 요청 URL 쿼리·userinfo·API 키·응답 본문은 문장에 싣지 않는다. 분류하지 못한 원문은 {@link
 * ApiCallErrorMessages#redactUrlsInText} 로 거른다.
 */
public final class EmbeddingFailureMessages {

  /** 로그에 싣는 조각(예외 메시지·본문 오류 문장) 하나의 최대 길이. */
  private static final int LOG_FRAGMENT_MAX = 500;

  /** 오류 문장 필드를 찾으려 JSON 으로 읽어 볼 본문의 최대 길이 — 큰 HTML·데이터 본문을 통째로 파싱하지 않는다(/simplify). */
  private static final int BODY_PARSE_MAX = 8192;

  /** 로그 줄 위조를 막으려 공백으로 바꿀 제어 문자(줄바꿈 등) — 호출마다 컴파일하지 않게 미리 만든다(/simplify). */
  private static final Pattern CONTROL_CHARS = Pattern.compile("\\p{Cc}");

  /** cause 사슬을 따라가는 최대 단계 수 — 서로를 cause 로 갖는 2노드 순환이 무한 루프를 만들지 못하게 막는다. */
  private static final int MAX_CHAIN = 32;

  /** 응답 본문에서 오류 문장 필드만 읽기 위한 JSON 파서(스레드 안전). */
  private static final ObjectMapper MAPPER = JsonMapper.builderWithJackson2Defaults().build();

  /**
   * 원문에 섞인 Bearer 토큰 — 헤더 검증 오류 등이 헤더 값을 그대로 실을 수 있어 지운다. 값의 모양(소문자뿐·기호 포함 등)과 상관없이 공백 아닌 문자를 모두
   * 가린다(WD-873). 구분자는 공백뿐 아니라 `:`·`=`(예: bearer=abc)도 받는다.
   */
  private static final Pattern BEARER_TOKEN = Pattern.compile("(?i)\\b(bearer[\\s:=]+)\\S+");

  /**
   * 원문에 섞인 Basic 인증 값(base64). "basic validation failed" 같은 일반 영어 단어는 건드리지 않도록 값이 8자 이상이고 소문자만이 아닌
   * 글자(대문자·숫자·기호)가 하나 이상 있을 때만 가린다.
   */
  private static final Pattern BASIC_CREDENTIAL =
      Pattern.compile("(?i)\\b(basic\\s+)(?-i:(?=[a-z]*[0-9A-Z+/=]))[A-Za-z0-9+/]{8,}={0,2}");

  /** 원문에 섞인 OpenAI 형식 API 키(sk-…) — 같은 이유로 지운다. */
  private static final Pattern SK_KEY = Pattern.compile("sk-[A-Za-z0-9_-]+");

  private EmbeddingFailureMessages() {}

  /**
   * provider 호출 실패를 사용자 문장({@link #describe})의 {@link EmbeddingException} 으로 바꾸고, 원인 분석용 가린 한
   * 줄({@link #logDetail})을 warn 로그로 남긴다. 두 provider 가 같은 규칙을 한 곳에서 쓰게 모았다(/simplify).
   *
   * <p>로그 만들기가 실패해도 원래 오류를 가리지 않는다 — 로그는 부가 정보일 뿐이라 예외를 삼키고 만든 예외를 그대로 돌려준다.
   *
   * @param provider 로그에 적을 provider 이름(Ollama·OpenAI)
   */
  public static EmbeddingException failure(String provider, Throwable error) {
    // cause 를 달지 않는다 — 가리지 않은 원문이 상위 스택트레이스 로그로 새지 않게(WD-873).
    EmbeddingException failure = EmbeddingException.redacted(describe(error), error);
    try {
      EmbeddingFailureLog.warnThrottled(provider, failure);
    } catch (RuntimeException ignored) {
      // 로그 만들기 실패가 원래 오류를 가리지 않게 한다.
    }
    return failure;
  }

  /**
   * 호출 예외를 사용자 문장으로 바꾼다. {@code block()}·WebClient 가 원인을 여러 겹 감싸므로 cause 사슬을 한 번만 훑어 분류별 첫 원인을 모은 뒤
   * 우선순위대로 고른다.
   *
   * <p>순서: HTTP 상태·응답 과대 → 응답 해석 실패 → 시간 초과 → 호스트 없음/연결 거부·실패 → TLS 실패 → 그 밖 입출력 실패 → 그 밖. 시간 초과를
   * 연결 오류보다 먼저 보는 이유는 netty 의 {@code ConnectTimeoutException} 이 {@link ConnectException} 을 상속해, 순서가
   * 바뀌면 시간 초과가 「거부」로 보이기 때문이다.
   */
  public static String describe(Throwable error) {
    // 사슬을 한 번 훑어 분류별 첫 원인을 모은다(순환은 chain 이 끊는다).
    // HTTP 오류 상태 또는 응답 과대 중 사슬에서 먼저 나온 것. 비오류 상태(200 등)의 WebClientResponseException 은
    // 본문 읽기 오류를 감싼 껍데기라 상태로 보지 않고 더 구체적인 원인으로 분류한다.
    Throwable status = null;
    Throwable decoding = null;
    Throwable timeout = null;
    Throwable hostOrConnect = null; // 호스트 없음 또는 연결 예외 중 먼저 나온 것
    Throwable tls = null;
    Throwable io = null; // 입출력 실패(연결 끊김 등) — TLS 를 먼저 보므로 SSLException 이어도 된다
    String host = null;
    Throwable root = error;
    // 비오류 상태(200 등)의 WebClientResponseException 을 봤는지 — 아무것도 분류되지 않으면 고정 문장으로 안내한다.
    boolean okResponse = false;
    for (Throwable c : chain(error)) {
      root = c;
      if (c instanceof WebClientResponseException ok && !ok.getStatusCode().isError()) {
        okResponse = true;
      }
      if (status == null
          && ((c instanceof WebClientResponseException wre && wre.getStatusCode().isError())
              || c instanceof DataBufferLimitException)) {
        status = c;
      }
      if (decoding == null && c instanceof DecodingException) {
        decoding = c;
      }
      // SslHandshakeTimeoutException 은 SSLException 하위지만 인증서 문제가 아니라 시간 초과다(시간 초과가 TLS 보다 먼저 평가된다).
      if (timeout == null
          && (ApiCallErrorMessages.isTimeout(c) || c instanceof SslHandshakeTimeoutException)) {
        timeout = c;
      }
      if (hostOrConnect == null
          && (c instanceof UnknownHostException || c instanceof ConnectException)) {
        hostOrConnect = c;
      }
      if (tls == null && c instanceof SSLException) {
        tls = c;
      }
      if (io == null && c instanceof IOException) {
        io = c;
      }
      if (host == null && c instanceof WebClientRequestException wcre && wcre.getUri() != null) {
        // 밑줄 든 이름(도커 서비스 이름)도 SSRF 검사와 같은 파서로 읽는다(/code-review·/simplify).
        host = UrlAuthority.hostOrNull(wcre.getUri());
      }
    }
    // 1) HTTP 상태·응답 과대 — 상태 코드만 쓰고 메시지(쿼리 포함 URL)·본문은 쓰지 않는다.
    if (status instanceof WebClientResponseException wre) {
      return httpStatus(wre.getStatusCode().value());
    }
    if (status != null) {
      return "임베딩 응답이 너무 큽니다";
    }
    // 2) 응답 해석 실패 — 디코더 메시지에 응답 조각이 실릴 수 있어 고정 문장만 쓴다.
    if (decoding != null) {
      return "임베딩 응답 형식을 해석하지 못했습니다 — Base URL 이 임베딩 서버를 가리키는지 확인하세요";
    }
    // 3) 시간 초과(연결 타임아웃 포함).
    if (timeout != null) {
      return "임베딩 서버가 제한 시간 안에 응답하지 않았습니다 — 서버 상태와 Base URL 을 확인하세요";
    }
    // 4) 호스트 없음·연결 거부/실패.
    if (hostOrConnect instanceof UnknownHostException) {
      return "임베딩 Base URL 의 호스트(" + (host != null ? host : "알 수 없음") + ")를 찾을 수 없습니다 — 주소를 확인하세요";
    }
    if (hostOrConnect != null) {
      String msg = hostOrConnect.getMessage();
      return msg != null && msg.toLowerCase(Locale.ROOT).contains("refused")
          ? "임베딩 서버가 연결을 거부했습니다 — Base URL 의 주소·포트와 서버 실행 여부를 확인하세요"
          : connectFailed();
    }
    // 5) TLS 실패(자체 서명 인증서 등) — 네트워크가 아니라 인증서·https 설정이 원인이라 따로 안내한다(/code-review).
    if (tls != null) {
      return "임베딩 서버와 HTTPS(TLS) 연결을 맺지 못했습니다 — 서버 인증서와 Base URL 의 https 설정을 확인하세요";
    }
    // 6) 그 밖 입출력 실패(연결 도중 끊김 등). 입출력 원인이 없는 WebClientRequestException(요청 만들기 오류 등)은
    // 연결 문제가 아니므로 여기서 잡지 않고 아래 원문 안내로 보낸다.
    if (io != null) {
      return connectFailed();
    }
    // 7a) 비오류 상태 응답을 읽다 실패했고 더 구체적인 원인이 없으면 원문 대신 고정 문장(원문에 응답 조각이 실릴 수 있다).
    if (okResponse) {
      return "임베딩 응답을 읽지 못했습니다 — Base URL 이 임베딩 서버를 가리키는지 확인하세요";
    }
    // 7) 분류하지 못한 오류 — 근본 원인 메시지를 쓰되 URL 의 쿼리·userinfo 와 토큰·키 모양은 지운다.
    String raw = root.getMessage() != null ? root.getMessage() : root.getClass().getSimpleName();
    return "임베딩 호출 중 오류가 발생했습니다: " + redactSecrets(ApiCallErrorMessages.redactUrlsInText(raw));
  }

  /**
   * 운영자 로그용 상세 한 줄 — cause 사슬 각 단계를 {@code SimpleName: 가린 메시지} 로 {@code " <- "} 로 잇고, {@link
   * WebClientResponseException} 이면 {@code status=<코드>} 와 본문의 오류 문장({@code body.error=…}, 없으면 {@code
   * body(len=N)})을 붙인다.
   *
   * <p>스택 전체·원본 본문은 일부러 남기지 않는다 — 예외 메시지·본문에 URL 쿼리·토큰·데이터셋 원문이 실릴 수 있어, 가리기 규칙을 거친 이 문자열만 로그에 둔다.
   */
  public static String logDetail(Throwable error) {
    StringBuilder sb = new StringBuilder();
    for (Throwable c : chain(error)) {
      if (sb.length() > 0) {
        sb.append(" <- ");
      }
      String msg = c.getMessage();
      String safe = msg == null ? "" : sanitize(msg);
      if (c instanceof WebClientResponseException wre) {
        // 하위 클래스(Unauthorized 등)는 이름만으로는 출처를 알기 어려워 공통 이름으로 적는다(코드는 status=).
        sb.append("WebClientResponseException: ").append(safe);
        sb.append(" status=").append(wre.getStatusCode().value()).append(' ');
        sb.append(bodyDetail(wre.getResponseBodyAsString()));
      } else {
        sb.append(c.getClass().getSimpleName()).append(": ").append(safe);
      }
    }
    return sb.toString();
  }

  /**
   * 응답 본문을 로그용 조각으로 바꾼다 — JSON 의 오류 문장 필드만 {@code body.error=…} 로 남기고, 그 밖에는 길이만 {@code
   * body(len=N)} 로 남긴다.
   *
   * <p>본문에 데이터셋 원문·알 수 없는 형식의 키가 실릴 수 있어 오류 문장 필드만 남긴다(Ollama {@code {"error":"…"}}, OpenAI {@code
   * {"error":{"message":"…"}}}).
   */
  private static String bodyDetail(String body) {
    String trimmed = body.strip();
    // JSON 객체가 아니거나 너무 크면 파싱하지 않고 길이만 남긴다(HTML 오류 페이지 등에서 예외·할당을 피한다).
    if (trimmed.length() > BODY_PARSE_MAX || !trimmed.startsWith("{")) {
      return "body(len=" + body.length() + ")";
    }
    String error = null;
    try {
      JsonNode err = MAPPER.readTree(trimmed).path("error");
      if (err.isString()) {
        error = err.asString();
      } else if (err.path("message").isString()) {
        error = err.path("message").asString();
      }
    } catch (RuntimeException e) {
      // JSON 이 아니거나 형식이 달라 파싱에 실패하면 길이만 남긴다.
    }
    return error != null ? "body.error=" + sanitize(error) : "body(len=" + body.length() + ")";
  }

  /**
   * 로그 한 조각을 안전하게 만든다 — 먼저 URL 쿼리·토큰·키를 가린 뒤(잘린 비밀이 패턴을 피하지 못하게) 최대 길이로 자르고, 줄바꿈 등 제어 문자는 공백으로 바꿔
   * 로그 줄을 위조하지 못하게 한다.
   */
  private static String sanitize(String text) {
    String t = redactSecrets(ApiCallErrorMessages.redactUrlsInText(text));
    if (t.length() > LOG_FRAGMENT_MAX) {
      t = t.substring(0, LOG_FRAGMENT_MAX);
    }
    return CONTROL_CHARS.matcher(t).replaceAll(" ");
  }

  /** 원문에서 Bearer 토큰·sk- 키 모양을 가린다. */
  private static String redactSecrets(String text) {
    String t = BEARER_TOKEN.matcher(text).replaceAll("$1***");
    t = BASIC_CREDENTIAL.matcher(t).replaceAll("$1***");
    return SK_KEY.matcher(t).replaceAll("sk-***");
  }

  /** 거부가 아닌 연결 실패(연결 끊김·경로 없음 등). */
  private static String connectFailed() {
    return "임베딩 서버에 연결하지 못했습니다 — Base URL 과 네트워크 상태를 확인하세요";
  }

  /** HTTP 오류 상태별 안내. 응답 본문은 담지 않는다. */
  private static String httpStatus(int status) {
    return switch (status) {
      case 401 -> "임베딩 서버 인증에 실패했습니다(HTTP 401) — 인증 정보(API 키·프록시 인증)를 확인하세요";
      case 403 -> "임베딩 서버가 접근을 거부했습니다(HTTP 403) — 인증 정보(API 키·프록시 인증)의 권한을 확인하세요";
      case 404 -> "임베딩 서버에서 요청한 주소나 모델을 찾을 수 없습니다(HTTP 404) — Base URL 과 모델 이름을 확인하세요";
      case 429 -> "임베딩 서버가 요청이 너무 많아 거부했습니다(HTTP 429) — 잠시 후 다시 시도하거나 사용 한도를 확인하세요";
      default ->
          status >= 500
              ? "임베딩 서버 오류(HTTP " + status + ") — 서버 상태를 확인하세요"
              : "임베딩 서버가 요청을 거부했습니다(HTTP " + status + ") — 모델 이름과 입력 길이를 확인하세요";
    };
  }

  /**
   * cause 사슬을 바깥부터 목록으로 편다 — 자기 자신을 cause 로 갖는 순환은 끊고, 서로를 cause 로 갖는 2노드 순환도 최대 {@code MAX_CHAIN}
   * 단계에서 멈춘다. describe·logDetail 이 같은 상한을 쓰게 한 곳에 둔다(/simplify).
   */
  private static List<Throwable> chain(Throwable error) {
    List<Throwable> out = new ArrayList<>();
    for (Throwable c = error; c != null && out.size() < MAX_CHAIN; ) {
      out.add(c);
      Throwable cause = c.getCause();
      c = cause == c ? null : cause;
    }
    return out;
  }
}
