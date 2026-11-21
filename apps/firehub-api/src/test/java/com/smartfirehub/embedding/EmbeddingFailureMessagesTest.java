package com.smartfirehub.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.channel.ConnectTimeoutException;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;
import org.springframework.core.codec.DecodingException;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/** EmbeddingFailureMessages 분류 단위 테스트(WD-863) — 원인별 문장이 spec 과 글자까지 같은지 고정한다. */
class EmbeddingFailureMessagesTest {

  private static final String TIMEOUT = "임베딩 서버가 제한 시간 안에 응답하지 않았습니다 — 서버 상태와 Base URL 을 확인하세요";

  private static String http(int code) {
    return EmbeddingFailureMessages.describe(
        WebClientResponseException.create(code, "x", new HttpHeaders(), new byte[0], null));
  }

  @Test
  void unknownHostUsesRequestUriHost() {
    Throwable e =
        new WebClientRequestException(
            new UnknownHostException("Failed to resolve 'host.docker.internal' [A(1)]"),
            HttpMethod.POST,
            URI.create("http://host.docker.internal:11434/api/embed"),
            new HttpHeaders());
    assertThat(EmbeddingFailureMessages.describe(e))
        .isEqualTo("임베딩 Base URL 의 호스트(host.docker.internal)를 찾을 수 없습니다 — 주소를 확인하세요");
  }

  @Test
  void unknownHostWithoutUri() {
    assertThat(EmbeddingFailureMessages.describe(new UnknownHostException("x")))
        .isEqualTo("임베딩 Base URL 의 호스트(알 수 없음)를 찾을 수 없습니다 — 주소를 확인하세요");
  }

  @Test
  void timeout() {
    assertThat(EmbeddingFailureMessages.describe(new RuntimeException(new TimeoutException())))
        .isEqualTo(TIMEOUT);
  }

  @Test
  void connectTimeoutIsTimeout() {
    // ConnectTimeoutException 은 ConnectException 하위지만 「거부」가 아니라 시간 초과여야 한다.
    assertThat(
            EmbeddingFailureMessages.describe(new ConnectTimeoutException("connection timed out")))
        .isEqualTo(TIMEOUT);
  }

  @Test
  void refused() {
    assertThat(EmbeddingFailureMessages.describe(new ConnectException("Connection refused")))
        .isEqualTo("임베딩 서버가 연결을 거부했습니다 — Base URL 의 주소·포트와 서버 실행 여부를 확인하세요");
  }

  @Test
  void connectFailed() {
    assertThat(EmbeddingFailureMessages.describe(new ConnectException("Network is unreachable")))
        .isEqualTo("임베딩 서버에 연결하지 못했습니다 — Base URL 과 네트워크 상태를 확인하세요");
  }

  @Test
  void http401() {
    assertThat(http(401)).isEqualTo("임베딩 서버 인증에 실패했습니다(HTTP 401) — 임베딩 API 키를 확인하세요");
  }

  @Test
  void http403() {
    assertThat(http(403)).isEqualTo("임베딩 서버가 접근을 거부했습니다(HTTP 403) — 임베딩 API 키 권한을 확인하세요");
  }

  @Test
  void http404() {
    assertThat(http(404))
        .isEqualTo("임베딩 서버에서 요청한 주소나 모델을 찾을 수 없습니다(HTTP 404) — Base URL 과 모델 이름을 확인하세요");
  }

  @Test
  void http429() {
    assertThat(http(429))
        .isEqualTo("임베딩 서버가 요청이 너무 많아 거부했습니다(HTTP 429) — 잠시 후 다시 시도하거나 사용 한도를 확인하세요");
  }

  @Test
  void http503() {
    assertThat(http(503)).isEqualTo("임베딩 서버 오류(HTTP 503) — 서버 상태를 확인하세요");
  }

  @Test
  void http400() {
    assertThat(http(400)).isEqualTo("임베딩 서버가 요청을 거부했습니다(HTTP 400) — 모델 이름과 입력 길이를 확인하세요");
  }

  @Test
  void tooLarge() {
    assertThat(EmbeddingFailureMessages.describe(new DataBufferLimitException("x")))
        .isEqualTo("임베딩 응답이 너무 큽니다");
  }

  @Test
  void decodingFailure() {
    String msg =
        EmbeddingFailureMessages.describe(
            new RuntimeException(
                new DecodingException(
                    "JSON decoding error: Unexpected character ('<' (code 60))")));
    assertThat(msg).isEqualTo("임베딩 응답 형식을 해석하지 못했습니다 — Base URL 이 임베딩 서버를 가리키는지 확인하세요");
    assertThat(msg).doesNotContain("Unexpected character").doesNotContain("JSON decoding");
  }

  @Test
  void fallbackRedactsQuery() {
    String msg =
        EmbeddingFailureMessages.describe(
            new IllegalStateException("boom http://h/p?api_key=SECRET"));
    assertThat(msg).startsWith("임베딩 호출 중 오류가 발생했습니다: boom http://h/p");
    assertThat(msg).doesNotContain("SECRET");
  }

  @Test
  void unknownHostWithUnderscoreNameUsesAuthority() {
    // java.net.URI 는 밑줄 든 이름의 getHost() 가 null — 도커 서비스 이름도 그대로 보여야 한다(/code-review).
    Throwable e =
        new WebClientRequestException(
            new UnknownHostException("Failed to resolve 'ollama_server'"),
            HttpMethod.POST,
            URI.create("http://user:pw@ollama_server:11434/api/embed"),
            new HttpHeaders());
    assertThat(EmbeddingFailureMessages.describe(e))
        .isEqualTo("임베딩 Base URL 의 호스트(ollama_server)를 찾을 수 없습니다 — 주소를 확인하세요");
  }

  @Test
  void tlsFailureHasOwnSentence() {
    // 자체 서명 인증서 등 TLS 실패는 네트워크가 아니라 인증서·https 설정을 가리켜야 한다(/code-review).
    Throwable e =
        new WebClientRequestException(
            new SSLHandshakeException("PKIX path building failed"),
            HttpMethod.POST,
            URI.create("https://embed.example.com/v1/embeddings"),
            new HttpHeaders());
    assertThat(EmbeddingFailureMessages.describe(e))
        .isEqualTo("임베딩 서버와 HTTPS(TLS) 연결을 맺지 못했습니다 — 서버 인증서와 Base URL 의 https 설정을 확인하세요");
  }

  @Test
  void requestExceptionWithoutIoCauseIsNotConnectFailure() {
    // 요청 만들기 오류(입출력 원인 없음)는 「연결하지 못했습니다」가 아니라 원문 안내로 간다(/code-review).
    Throwable e =
        new WebClientRequestException(
            new IllegalArgumentException("invalid header value"),
            HttpMethod.POST,
            URI.create("https://embed.example.com/v1/embeddings"),
            new HttpHeaders());
    assertThat(EmbeddingFailureMessages.describe(e))
        .isEqualTo("임베딩 호출 중 오류가 발생했습니다: invalid header value");
  }

  @Test
  void fallbackRedactsBearerAndSkKey() {
    String msg =
        EmbeddingFailureMessages.describe(
            new IllegalArgumentException(
                "prohibited character in header Authorization: Bearer sk-abc123XYZ_def\n"));
    assertThat(msg).doesNotContain("abc123XYZ").contains("Bearer ***");
    assertThat(
            EmbeddingFailureMessages.describe(
                new IllegalStateException("key sk-proj-Secret99 bad")))
        .isEqualTo("임베딩 호출 중 오류가 발생했습니다: key sk-*** bad");
  }
}
