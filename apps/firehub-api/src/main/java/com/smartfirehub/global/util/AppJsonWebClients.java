package com.smartfirehub.global.util;

import com.smartfirehub.global.config.JacksonCompatConfig;
import org.springframework.http.codec.ClientCodecConfigurer;
import org.springframework.http.codec.json.JacksonJsonDecoder;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * 직접 만드는 WebClient 의 JSON 코덱을 앱 매퍼(Boot 가 HTTP 응답·주입에 쓰는 매퍼)와 같은 설정으로 맞추는 공유 팩토리다(WD-793).
 *
 * <p>왜 필요한가: {@code WebClient.builder()}·{@code ExchangeStrategies.builder()} 로 직접 만든 클라이언트는 Boot
 * 자동 구성을 거치지 않아 Spring 기본 Jackson 3 매퍼({@code FAIL_ON_NULL_FOR_PRIMITIVES} 켜짐, {@code
 * java.sql.Date/Timestamp} 를 UTC 시각 문자열로 씀 등)를 쓴다. 그러면 ai-agent 로 보내는 행의 DATE 값이 하루 어긋나고(코드 리뷰 지적
 * 1), 응답의 원시 타입 자리 null 이 502 가 된다(지적 2). 호출처마다 문자열로 우회하면 다음에 추가되는 곳이 빠지므로(지적 4) 매퍼 설정은 {@link
 * JacksonCompatConfig#appJsonMapperBuilder()} 한 곳에서 가져오고, 직접 빌더는 모두 여기를 거친다.
 *
 * <p>사용: 직접 빌더는 {@link #webClientBuilder()}, 코덱 상한을 함께 거는 교환 전략은 {@link #exchangeStrategies(int)}.
 * 다른 곳에서 {@code WebClient.builder()}·{@code ExchangeStrategies.builder()} 를 부르지 않는다 — {@code
 * AppJsonWebClientsArchitectureTest} 가 막는다. 주입받는 자동 구성 {@code WebClient.Builder} 는 이미 앱 매퍼를 쓰므로 대상이
 * 아니다(단 거기에 교환 전략을 새로 끼우면 코덱이 기본값으로 바뀌므로 그때도 {@link #exchangeStrategies(int)} 를 쓴다).
 */
public final class AppJsonWebClients {

  /**
   * 모든 직접 클라이언트가 함께 쓰는 매퍼 — 앱 매퍼와 같은 설정이다. 요청·페이지마다 클라이언트를 만드는 곳(API 호출 스텝·연결 점검·opencode 점검)이 있어,
   * 부를 때마다 매퍼를 새로 만들면(모듈 탐색 포함) CPU·메모리를 헛쓴다(지적 3). 매퍼는 만든 뒤 스레드 안전하다.
   */
  private static final JsonMapper SHARED_MAPPER =
      JacksonCompatConfig.appJsonMapperBuilder().build();

  private AppJsonWebClients() {}

  /** 공유 매퍼 — 시험에서 앱 매퍼와 같은지 대조하는 데 쓴다. */
  public static JsonMapper sharedMapper() {
    return SHARED_MAPPER;
  }

  /** JSON 코덱을 앱 매퍼 설정으로 맞춘 새 {@code WebClient.Builder} — {@code WebClient.builder()} 대신 쓴다. */
  public static WebClient.Builder webClientBuilder() {
    return WebClient.builder().codecs(AppJsonWebClients::apply);
  }

  /**
   * JSON 코덱을 앱 매퍼 설정으로 맞추고 메모리에 모으는 응답 크기 상한을 건 교환 전략. 상한은 디코더를 끼운 <b>뒤에</b> 걸어야 그 디코더에 적용되므로 순서를
   * 여기 한 곳에 묶는다.
   *
   * @param maxInMemorySize 응답 본문을 메모리에 모을 수 있는 최대 바이트 수(넘으면 {@code DataBufferLimitException})
   */
  public static ExchangeStrategies exchangeStrategies(int maxInMemorySize) {
    return ExchangeStrategies.builder()
        .codecs(AppJsonWebClients::apply)
        .codecs(c -> c.defaultCodecs().maxInMemorySize(maxInMemorySize))
        .build();
  }

  /**
   * 기본 코덱의 JSON 인코더·디코더를 공유 매퍼로 바꾼다. 다른 코덱(문자열·바이트·SSE 등)은 그대로 둔다. 인코더·디코더 객체는 클라이언트마다 새로 만든다 —
   * {@code maxInMemorySize} 같은 설정이 디코더 객체 자체에 걸리기 때문이다. 매퍼만 함께 쓴다.
   */
  private static void apply(ClientCodecConfigurer configurer) {
    configurer.defaultCodecs().jacksonJsonDecoder(new JacksonJsonDecoder(SHARED_MAPPER));
    configurer.defaultCodecs().jacksonJsonEncoder(new JacksonJsonEncoder(SHARED_MAPPER));
  }
}
