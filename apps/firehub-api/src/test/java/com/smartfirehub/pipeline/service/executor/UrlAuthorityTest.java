package com.smartfirehub.pipeline.service.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** {@link UrlAuthority} 단위 테스트(WD-676) — 밑줄 든 호스트 읽기, 모호한 authority 거부, 요청 전 호스트 검사. */
class UrlAuthorityTest {

  @Test
  void of_serverAuthority_usesGetHostAndPort() {
    assertThat(UrlAuthority.of(URI.create("https://Api.Example.com:8443/x")))
        .contains(new UrlAuthority("api.example.com", 8443));
  }

  /** getHost() 가 null 인 밑줄 호스트도 호스트·포트를 읽고 소문자로 바꾼다. */
  @Test
  void of_underscoreHost_parsesRawAuthority() {
    URI uri = URI.create("http://My_API.internal:8080/data");
    assertThat(uri.getHost()).isNull(); // 전제: JDK 가 못 읽는 주소
    assertThat(UrlAuthority.of(uri)).contains(new UrlAuthority("my_api.internal", 8080));
  }

  @Test
  void of_underscoreHostWithUserinfo_dropsUserinfo() {
    assertThat(UrlAuthority.of(URI.create("http://user:pw@my_api.internal/")))
        .contains(new UrlAuthority("my_api.internal", -1));
  }

  /** 두 번째 @·퍼센트·포트 범위 밖은 해석하지 않는다(빈 값 = 호스트 모름). */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "http://a@b@my_api.internal/",
        "http://my_api%2einternal/",
        "http://my_api.x:99999/"
      })
  void of_ambiguousAuthority_isEmpty(String url) {
    assertThat(UrlAuthority.of(URI.create(url))).isEmpty();
  }

  @Test
  void of_noAuthority_isEmpty() {
    assertThat(UrlAuthority.of(URI.create("mailto:a@b.example"))).isEmpty();
    assertThat(UrlAuthority.of(null)).isEmpty();
  }

  @Test
  void effectivePort_defaultsByScheme() {
    UrlAuthority a = new UrlAuthority("h", -1);
    assertThat(a.effectivePort("http")).isEqualTo(80);
    assertThat(a.effectivePort("HTTPS")).isEqualTo(443);
    assertThat(a.effectivePort("ftp")).isEqualTo(-1);
    assertThat(new UrlAuthority("h", 81).effectivePort("http")).isEqualTo(81);
  }

  @Test
  void hostOrNull_returnsNullWhenUnknown() {
    assertThat(UrlAuthority.hostOrNull(URI.create("http://my_api.internal/")))
        .isEqualTo("my_api.internal");
    assertThat(UrlAuthority.hostOrNull(URI.create("http://a@b@c_d/"))).isNull();
  }

  // --- requireClientSupportedHost ---

  /** getHost() 가 null 인데 호스트를 읽을 수 있으면(밑줄) 호스트 이름을 담은 고정 문구로 실패한다. 보통 호스트·문법 오류 문자열은 통과시킨다. */
  @Test
  void requireClientSupportedHost_underscoreHostThrows_othersPass() {
    assertThatThrownBy(
            () -> UrlAuthority.requireClientSupportedHost(URI.create("http://my_api.internal/x")))
        .isInstanceOf(ApiCallException.class)
        .hasMessage(ApiCallErrorMessages.unsupportedHostName("my_api.internal"));
    UrlAuthority.requireClientSupportedHost(URI.create("https://api.example.com/x"));
    UrlAuthority.requireClientSupportedHost("http://exa mple.com/x");
    UrlAuthority.requireClientSupportedHost((String) null);
  }
}
