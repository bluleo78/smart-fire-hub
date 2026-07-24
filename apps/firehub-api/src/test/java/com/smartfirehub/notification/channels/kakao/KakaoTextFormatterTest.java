package com.smartfirehub.notification.channels.kakao;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.notification.Payload;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * KakaoTextFormatter 단위 테스트.
 *
 * <p>화이트라벨링 브랜드명이 답장 안내 푸터에 반영되는지 검증한다. @Value 필드는 Spring 컨텍스트 없이 생성 시 null이므로
 * ReflectionTestUtils로 직접 주입한다.
 */
class KakaoTextFormatterTest {

  private Payload payload(String title) {
    return new Payload(
        Payload.PayloadType.STANDARD,
        title,
        null,
        List.of(),
        List.of(),
        List.of(),
        Map.of(),
        Map.of());
  }

  /** 브랜드명이 기본값일 때 푸터에 "Smart Fire Hub"가 포함되어야 한다. */
  @Test
  void render_defaultBrand_appendsDefaultFooter() {
    KakaoTextFormatter formatter = new KakaoTextFormatter();
    ReflectionTestUtils.setField(formatter, "brandName", "Smart Fire Hub");

    String result = formatter.render(payload("제목"));

    assertThat(result).endsWith("\n\n답장은 Smart Fire Hub 웹/Slack에서");
  }

  /** 브랜드명을 재정의하면 푸터에 해당 브랜드명이 반영되어야 한다. */
  @Test
  void render_customBrand_appendsBrandedFooter() {
    KakaoTextFormatter formatter = new KakaoTextFormatter();
    ReflectionTestUtils.setField(formatter, "brandName", "Acme");

    String result = formatter.render(payload("제목"));

    assertThat(result).endsWith("\n\n답장은 Acme 웹/Slack에서");
  }
}
