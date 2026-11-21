package com.smartfirehub.embedding;

import com.smartfirehub.pipeline.service.executor.ApiCallErrorMessages;
import com.smartfirehub.pipeline.service.executor.UrlAuthority;
import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.regex.Pattern;
import javax.net.ssl.SSLException;
import org.springframework.core.codec.DecodingException;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

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

  /** 원문에 섞인 Bearer 토큰 — 헤더 검증 오류 등이 헤더 값을 그대로 실을 수 있어 지운다(/code-review). */
  private static final Pattern BEARER_TOKEN = Pattern.compile("(?i)(bearer\\s+)\\S+");

  /** 원문에 섞인 OpenAI 형식 API 키(sk-…) — 같은 이유로 지운다. */
  private static final Pattern SK_KEY = Pattern.compile("sk-[A-Za-z0-9_-]+");

  private EmbeddingFailureMessages() {}

  /**
   * 호출 예외를 사용자 문장으로 바꾼다. {@code block()}·WebClient 가 원인을 여러 겹 감싸므로 cause 사슬을 한 번만 훑어 분류별 첫 원인을 모은 뒤
   * 우선순위대로 고른다.
   *
   * <p>순서: HTTP 상태·응답 과대 → 응답 해석 실패 → 시간 초과 → 호스트 없음/연결 거부·실패 → TLS 실패 → 그 밖 입출력 실패 → 그 밖. 시간 초과를
   * 연결 오류보다 먼저 보는 이유는 netty 의 {@code ConnectTimeoutException} 이 {@link ConnectException} 을 상속해, 순서가
   * 바뀌면 시간 초과가 「거부」로 보이기 때문이다.
   */
  public static String describe(Throwable error) {
    // 사슬을 한 번 훑어 분류별 첫 원인을 모은다(자기 자신을 cause 로 갖는 순환은 next 가 끊는다).
    Throwable status = null; // HTTP 상태 또는 응답 과대 중 사슬에서 먼저 나온 것
    Throwable decoding = null;
    Throwable timeout = null;
    Throwable hostOrConnect = null; // 호스트 없음 또는 연결 예외 중 먼저 나온 것
    Throwable tls = null;
    Throwable io = null; // 입출력 실패(연결 끊김 등) — TLS 를 먼저 보므로 SSLException 이어도 된다
    String host = null;
    Throwable root = error;
    for (Throwable c = error; c != null; c = next(c)) {
      root = c;
      if (status == null
          && (c instanceof WebClientResponseException || c instanceof DataBufferLimitException)) {
        status = c;
      }
      if (decoding == null && c instanceof DecodingException) {
        decoding = c;
      }
      if (timeout == null && ApiCallErrorMessages.isTimeout(c)) {
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
    // 7) 분류하지 못한 오류 — 근본 원인 메시지를 쓰되 URL 의 쿼리·userinfo 와 토큰·키 모양은 지운다.
    String raw = root.getMessage() != null ? root.getMessage() : root.getClass().getSimpleName();
    return "임베딩 호출 중 오류가 발생했습니다: " + redactSecrets(ApiCallErrorMessages.redactUrlsInText(raw));
  }

  /** 원문에서 Bearer 토큰·sk- 키 모양을 가린다. */
  private static String redactSecrets(String text) {
    String t = BEARER_TOKEN.matcher(text).replaceAll("$1***");
    return SK_KEY.matcher(t).replaceAll("sk-***");
  }

  /** 거부가 아닌 연결 실패(연결 끊김·경로 없음 등). */
  private static String connectFailed() {
    return "임베딩 서버에 연결하지 못했습니다 — Base URL 과 네트워크 상태를 확인하세요";
  }

  /** HTTP 오류 상태별 안내. 응답 본문은 담지 않는다. */
  private static String httpStatus(int status) {
    return switch (status) {
      case 401 -> "임베딩 서버 인증에 실패했습니다(HTTP 401) — 임베딩 API 키를 확인하세요";
      case 403 -> "임베딩 서버가 접근을 거부했습니다(HTTP 403) — 임베딩 API 키 권한을 확인하세요";
      case 404 -> "임베딩 서버에서 요청한 주소나 모델을 찾을 수 없습니다(HTTP 404) — Base URL 과 모델 이름을 확인하세요";
      case 429 -> "임베딩 서버가 요청이 너무 많아 거부했습니다(HTTP 429) — 잠시 후 다시 시도하거나 사용 한도를 확인하세요";
      default ->
          status >= 500
              ? "임베딩 서버 오류(HTTP " + status + ") — 서버 상태를 확인하세요"
              : "임베딩 서버가 요청을 거부했습니다(HTTP " + status + ") — 모델 이름과 입력 길이를 확인하세요";
    };
  }

  /** 자기 자신을 cause 로 갖는 순환을 끊는다. */
  private static Throwable next(Throwable c) {
    Throwable cause = c.getCause();
    return cause == c ? null : cause;
  }
}
