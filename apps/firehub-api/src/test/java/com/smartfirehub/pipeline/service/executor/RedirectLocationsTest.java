package com.smartfirehub.pipeline.service.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import org.junit.jupiter.api.Test;

/**
 * {@link RedirectLocations} 단위 테스트(WD-676). 정상 해석 결과는 Python 과 함께 쓰는 공용 사례 표({@code
 * ApiCallAuthVectorsTest})가 보고, 여기서는 표에 담기 어려운 실패 문구와 점 구간 제거만 본다.
 */
class RedirectLocationsTest {

  /** 문법 오류 Location(공백)은 재시도하지 않는 ApiCallException·고정 문구 — URL 은 싣지 않는다. */
  @Test
  void resolve_invalidLocation_throwsNonRetryableFixedMessage() {
    assertThatThrownBy(
            () ->
                RedirectLocations.resolve(
                    URI.create("https://api.example.com/v1"), "/next page?serviceKey=SECRET"))
        .isInstanceOf(ApiCallException.class)
        .hasMessage(ApiCallErrorMessages.invalidRedirectLocation())
        .hasMessageNotContaining("SECRET");
  }

  /** RFC 3986 §5.2.4 예시 — 루트 위로 올라가는 「..」 는 버린다. */
  @Test
  void removeDotSegments_rfcExamples() {
    assertThat(RedirectLocations.removeDotSegments("/a/b/c/./../../g")).isEqualTo("/a/g");
    assertThat(RedirectLocations.removeDotSegments("mid/content=5/../6")).isEqualTo("mid/6");
    assertThat(RedirectLocations.removeDotSegments("/../../x")).isEqualTo("/x");
    assertThat(RedirectLocations.removeDotSegments("/a/..")).isEqualTo("/");
  }
}
