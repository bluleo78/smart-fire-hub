package com.smartfirehub.pipeline.service.executor;

import java.math.BigDecimal;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * API 호출(API_CALL 스텝 실행·「테스트 호출」 미리보기) 실패를 분류해 사용자에게 보일 한국어 문구를 만든다(WD-150).
 *
 * <p><b>왜 필요한가.</b> 예전에는 {@code Authentication failed (401): <URL>}·{@code Could not resolve
 * hostname} 같은 영어 원문이 실행 상세에 그대로 저장됐고, 타임아웃·연결 거부는 원인이 cause 에만 남아 {@code API call failed after N
 * retries} 만 보였다. 미리보기는 같은 연결 거부를 {@code Connection refused: …} 로 보여 줘 두 화면이 달랐다. 실행과 미리보기가 이 클래스
 * 하나로 분류·문구를 만들어 같은 상황엔 같은 문장이 나오게 한다.
 *
 * <p><b>비밀값.</b> API_KEY 를 쿼리 문자열로 보내는 인증 방식이 있어, 요청 URL 을 문구에 넣으면 키가 실행 기록(DB)·로그에 남는다. 문구에 넣는 URL
 * 은 항상 {@link #redactUrl} 로 「스킴://호스트[:포트]/경로」까지만 남긴다.
 *
 * <p><b>운영 기본 경로는 Python 실행기다.</b> {@code firehub-executor/app/services/api_call_errors.py} 가 같은
 * 문장을 그대로 쓴다 — 문구를 바꾸면 양쪽을 함께 고칠 것(양쪽 테스트가 같은 문자열을 단언한다).
 */
public final class ApiCallErrorMessages {

  /** 문구 안의 URL 에서 쿼리·프래그먼트를 지우기 위한 패턴 — 분류하지 못한 원문을 그대로 쓸 때의 마지막 방어선. */
  private static final Pattern URL_QUERY_IN_TEXT =
      Pattern.compile("(?i)(https?://[^\\s?#\"'<>]*)[?#][^\\s\"'<>]*");

  /** 문구 안의 URL 에서 userinfo(user:pass@)를 지우기 위한 패턴. */
  private static final Pattern URL_USERINFO_IN_TEXT =
      Pattern.compile("(?i)(https?://)[^\\s/@\"'<>]+@");

  private ApiCallErrorMessages() {}

  // ---------------------------------------------------------------------------
  // HTTP 상태
  // ---------------------------------------------------------------------------

  /**
   * 이 HTTP 상태를 재시도할지. 5xx 와 408(Request Timeout)·429(Too Many Requests)만 재시도한다 — 그 밖의
   * 4xx(400·401·403·404·405 등)는 다시 보내도 같은 답이 오므로 즉시 실패시켜 실패까지 오래 걸리지 않게 한다.
   */
  public static boolean isRetryableStatus(int status) {
    return status >= 500 || status == 408 || status == 429;
  }

  /** HTTP 오류 상태별 짧은 안내. 응답 본문은 담지 않는다(크기·민감 정보 문제로 실행 기록에 남기지 않는다). */
  public static String httpStatus(int status) {
    return switch (status) {
      case 400 -> "요청이 거부되었습니다(HTTP 400) — 요청 파라미터·본문 형식을 확인하세요";
      case 401 -> "인증에 실패했습니다(HTTP 401) — API 키·토큰 설정을 확인하세요";
      case 403 -> "접근이 거부되었습니다(HTTP 403) — API 키 권한이나 허용 IP 설정을 확인하세요";
      case 404 -> "요청한 주소를 찾을 수 없습니다(HTTP 404) — URL 경로를 확인하세요";
      case 405 -> "허용되지 않는 요청 방식입니다(HTTP 405) — GET/POST 설정을 확인하세요";
      case 408 -> "대상 서버가 요청 시간 초과로 응답했습니다(HTTP 408)";
      case 429 -> "요청이 너무 많아 대상 서버가 거부했습니다(HTTP 429) — 호출 빈도를 줄이거나 잠시 후 다시 시도하세요";
      default -> {
        if (status >= 500) {
          yield "대상 서버 오류(HTTP " + status + ")";
        }
        if (status >= 400) {
          yield "요청이 거부되었습니다(HTTP " + status + ")";
        }
        yield "예상하지 못한 응답입니다(HTTP " + status + ")";
      }
    };
  }

  // ---------------------------------------------------------------------------
  // 전송 오류·한도
  // ---------------------------------------------------------------------------

  /** 응답 시간 초과. 초 단위로 보이되 1초 미만·소수(예: 300ms → 0.3초)도 그대로 보이게 한다. */
  public static String timeout(int timeoutMs) {
    return "응답 시간 초과(" + seconds(timeoutMs) + "초) — 대상 서버 상태나 시간 제한을 확인하세요";
  }

  public static String connectionRefused() {
    return "연결이 거부되었습니다 — 주소·포트와 서버 실행 여부를 확인하세요";
  }

  /** 거부가 아닌 연결 실패(연결 끊김·경로 없음 등). */
  public static String connectionFailed() {
    return "대상 서버에 연결하지 못했습니다 — 주소·포트와 네트워크 상태를 확인하세요";
  }

  public static String unknownHost(String host) {
    return "호스트 이름을 찾을 수 없습니다: " + host;
  }

  public static String responseTooLarge(int maxMb) {
    return "응답 크기가 제한(" + maxMb + "MB)을 넘었습니다 — 응답 크기 제한을 늘리거나 페이지 크기를 줄이세요";
  }

  /** 한도 값을 모르는 경로(미리보기 등)용. */
  public static String responseTooLarge() {
    return "응답 크기가 너무 큽니다 — 페이지 크기를 줄여 보세요";
  }

  public static String tooManyRedirects(int max) {
    return "리다이렉트가 너무 많습니다(최대 " + max + "회)";
  }

  public static String notJson() {
    return "응답이 JSON 형식이 아닙니다 — 대상 API 의 응답 형식을 확인하세요";
  }

  /**
   * 응답에 데이터 경로가 없을 때(WD-212). Python 실행기 {@code api_call_errors.data_path_not_found_message} 와 같은
   * 문장이다 — 실행기도 같은 상황을 「완료·0행」이 아니라 이 문구의 실패로 알린다.
   */
  public static String dataPathNotFound(String dataPath) {
    return "응답에서 데이터 경로를 찾을 수 없습니다: " + dataPath;
  }

  /**
   * 데이터 경로가 JSONPath 문법에 맞지 않을 때(WD-212). jayway 의 영어 원문은 싣지 않는다 — Python(jsonpath_ng) 원문과 달라 두 경로
   * 문구가 갈리기 때문이다. Python 실행기 {@code api_call_errors.data_path_invalid_message} 와 같은 문장.
   */
  public static String dataPathInvalid(String dataPath) {
    return "데이터 경로(JSONPath) 형식이 올바르지 않습니다: " + dataPath + " — 예: $.data.items";
  }

  public static String interrupted() {
    return "재시도 대기 중 실행이 중단되었습니다";
  }

  /**
   * 재시도를 다 쓰고도 실패했을 때의 문구. 마지막 실패 원인을 반드시 담는다 — 예전에는 원인이 cause 에만 있어 사용자는 「N retries」만 봤다. 재시도 0회
   * 설정이면 「0회 재시도 후에도」가 어색하므로 「API 호출에 실패했습니다: 원인」으로 쓴다.
   */
  public static String retriesExhausted(int retries, String lastError) {
    if (retries <= 0) {
      return "API 호출에 실패했습니다: " + lastError;
    }
    return "API 호출이 " + retries + "회 재시도 후에도 실패했습니다 — 마지막 오류: " + lastError;
  }

  // ---------------------------------------------------------------------------
  // 예외 분류
  // ---------------------------------------------------------------------------

  /**
   * 예외를 사용자 문구로 바꾼다. {@code block()}·WebClient 가 원인을 여러 겹 감싸므로 원인 사슬을 따라가며 분류한다.
   *
   * <p>타임아웃을 {@link ConnectException} 보다 먼저 본다 — netty 의 연결 타임아웃({@code ConnectTimeoutException})이
   * {@code ConnectException} 을 상속해, 순서가 바뀌면 타임아웃이 「연결이 거부되었습니다」로 보인다.
   *
   * <p>{@link WebClientResponseException} 의 메시지는 {@code 401 Unauthorized from GET
   * http://…?serviceKey=…} 처럼 쿼리 포함 URL 을 담으므로 절대 그대로 쓰지 않고 상태 코드로만 문구를 만든다.
   *
   * @param timeoutMs 이 요청에 건 시간 제한(타임아웃 문구용)
   * @param host 요청 대상 호스트(DNS 실패 문구용, null 이면 「알 수 없음」)
   */
  public static String describe(Throwable error, int timeoutMs, String host) {
    // 1) 이미 사용자 문구를 담은 예외(이 패키지가 만든 것)는 그대로 — 가장 바깥 것을 쓴다.
    for (Throwable c = error; c != null; c = next(c)) {
      if (c instanceof ApiCallException || c instanceof SsrfException) {
        return c.getMessage();
      }
      if (c instanceof WebClientResponseException wre) {
        return httpStatus(wre.getStatusCode().value());
      }
      if (c instanceof DataBufferLimitException) {
        return responseTooLarge();
      }
    }
    // 2) 타임아웃(연결 타임아웃 포함)을 연결 오류보다 먼저 본다.
    for (Throwable c = error; c != null; c = next(c)) {
      if (isTimeout(c)) {
        return timeout(timeoutMs);
      }
    }
    // 3) 연결 오류·DNS 실패.
    for (Throwable c = error; c != null; c = next(c)) {
      if (c instanceof UnknownHostException) {
        return unknownHost(host != null ? host : "알 수 없음");
      }
      if (c instanceof ConnectException) {
        String msg = c.getMessage();
        return msg != null && msg.toLowerCase(Locale.ROOT).contains("refused")
            ? connectionRefused()
            : connectionFailed();
      }
    }
    if (hasCause(error, WebClientRequestException.class)
        || hasCause(error, java.io.IOException.class)) {
      return connectionFailed();
    }
    // 4) 분류하지 못한 오류 — 바깥 예외 대신 근본 원인 메시지를 쓰되 URL 의 쿼리·userinfo 는 지운다.
    Throwable root = error;
    while (next(root) != null) {
      root = next(root);
    }
    String raw = root.getMessage() != null ? root.getMessage() : root.getClass().getSimpleName();
    return "API 호출 중 오류가 발생했습니다: " + redactUrlsInText(raw);
  }

  private static boolean isTimeout(Throwable c) {
    return c instanceof java.util.concurrent.TimeoutException
        || c instanceof SocketTimeoutException
        || c instanceof io.netty.handler.timeout.TimeoutException
        || c instanceof io.netty.channel.ConnectTimeoutException;
  }

  private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
    for (Throwable c = error; c != null; c = next(c)) {
      if (type.isInstance(c)) return true;
    }
    return false;
  }

  /** 자기 자신을 cause 로 갖는 순환을 끊는다. */
  private static Throwable next(Throwable c) {
    Throwable cause = c.getCause();
    return cause == c ? null : cause;
  }

  // ---------------------------------------------------------------------------
  // URL 가리기
  // ---------------------------------------------------------------------------

  /**
   * 문구에 넣을 URL 을 「스킴://호스트[:포트]/경로」로 줄인다 — userinfo·쿼리·프래그먼트를 버린다. 쿼리에 API 키가 실릴 수 있기 때문이다(API_KEY
   * + placement=query). 해석할 수 없는 문자열이면 첫 {@code ?}·{@code #} 앞까지만 남긴다.
   */
  public static String redactUrl(String url) {
    if (url == null) return "";
    try {
      URI uri = new URI(url);
      if (uri.getScheme() != null && uri.getHost() != null) {
        StringBuilder sb = new StringBuilder();
        sb.append(uri.getScheme()).append("://").append(uri.getHost());
        if (uri.getPort() != -1) sb.append(':').append(uri.getPort());
        if (uri.getRawPath() != null) sb.append(uri.getRawPath());
        return sb.toString();
      }
    } catch (Exception ignored) {
      // 아래 문자열 자르기로 처리
    }
    int cut = url.length();
    int q = url.indexOf('?');
    int h = url.indexOf('#');
    if (q >= 0) cut = Math.min(cut, q);
    if (h >= 0) cut = Math.min(cut, h);
    String head = url.substring(0, cut);
    return URL_USERINFO_IN_TEXT.matcher(head).replaceAll("$1");
  }

  public static String redactUrl(URI uri) {
    return uri == null ? "" : redactUrl(uri.toString());
  }

  /** 자유 문장 안에 섞인 URL 의 쿼리·프래그먼트·userinfo 를 지운다. API 연결 「테스트」의 오류 문구도 같은 가리기를 쓰므로 공개한다(WD-177). */
  public static String redactUrlsInText(String text) {
    if (text == null) return "";
    String noQuery = URL_QUERY_IN_TEXT.matcher(text).replaceAll("$1");
    return URL_USERINFO_IN_TEXT.matcher(noQuery).replaceAll("$1");
  }

  /** 밀리초를 「30」「0.3」「1.5」 같은 초 표기로. */
  static String seconds(int ms) {
    return BigDecimal.valueOf(ms, 3).stripTrailingZeros().toPlainString();
  }
}
