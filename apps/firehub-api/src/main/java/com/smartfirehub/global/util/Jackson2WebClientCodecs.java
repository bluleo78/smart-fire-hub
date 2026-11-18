package com.smartfirehub.global.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.codec.ClientCodecConfigurer;
import org.springframework.http.codec.json.Jackson2JsonDecoder;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 직접 만드는 {@code WebClient.builder()} 의 JSON 코덱을 Jackson 2 로 고정한다(WD-792).
 *
 * <p>왜 필요한가: Spring Framework 7 의 기본 코덱({@code BaseDefaultCodecs})은 클래스패스에 Jackson 3({@code
 * tools.jackson})이 있으면 Jackson 2 보다 먼저 Jackson 3 을 고른다. Boot 4 에서는 Flyway 12 와 웹 스타터가 Jackson 3 을
 * 끌어오므로 지울 수 없고, 그러면 자동 구성을 거치지 않는 정적 빌더는 조용히 Jackson 3 으로 바뀐다 — {@code
 * bodyToMono(JsonNode.class)}(com.fasterxml JsonNode)를 디코딩하지 못하고, 날짜·속성 순서·{@code @JsonSerialize}
 * 같은 Jackson 2 전용 설정도 무시된다. Jackson 3 전환(WD-793) 전까지는 Boot 3 때와 같은 코덱({@code new
 * Jackson2JsonDecoder()} = {@code Jackson2ObjectMapperBuilder.json()} 기본값)을 명시한다.
 *
 * <p>자동 구성 {@code WebClient.Builder} 는 {@code spring.http.codecs.preferred-json-mapper: jackson2} 로
 * 따로 맞췄으므로 여기 대상이 아니다. 사용: 직접 빌더는 {@link #webClientBuilder()}, 코덱 상한을 함께 거는 교환 전략은 {@link
 * #exchangeStrategies(int)}. 다른 곳에서 {@code WebClient.builder()}·{@code
 * ExchangeStrategies.builder()} 를 부르지 않는다 — {@code WebClientJackson2CodecsArchitectureTest} 가 막는다.
 * Jackson 3 전환(WD-793) 때 이 클래스와 그 규칙을 함께 지우고 호출처를 원래 빌더로 되돌린다.
 */
@SuppressWarnings("removal")
public final class Jackson2WebClientCodecs {

  /**
   * 모든 클라이언트가 함께 쓰는 ObjectMapper — {@code new Jackson2JsonDecoder()} 의 기본값과 같은 설정이다. 요청마다 클라이언트를
   * 만드는 곳(API 호출 스텝·연결 점검·opencode 점검)이 있어, 부를 때마다 매퍼를 새로 만들면(모듈 탐색 포함) CPU·메모리를 헛쓴다. ObjectMapper
   * 는 설정을 마친 뒤 스레드 안전하다.
   */
  private static final ObjectMapper SHARED_MAPPER = Jackson2ObjectMapperBuilder.json().build();

  private Jackson2WebClientCodecs() {}

  /** JSON 코덱을 Jackson 2 로 고정한 새 {@code WebClient.Builder} — {@code WebClient.builder()} 대신 쓴다. */
  public static WebClient.Builder webClientBuilder() {
    return WebClient.builder().codecs(Jackson2WebClientCodecs::apply);
  }

  /**
   * JSON 코덱을 Jackson 2 로 고정하고 메모리에 모으는 응답 크기 상한을 건 교환 전략. 상한은 Jackson 2 디코더를 끼운 <b>뒤에</b> 걸어야 그
   * 디코더에 적용되므로 순서를 여기 한 곳에 묶는다.
   *
   * @param maxInMemorySize 응답 본문을 메모리에 모을 수 있는 최대 바이트 수(넘으면 {@code DataBufferLimitException})
   */
  public static ExchangeStrategies exchangeStrategies(int maxInMemorySize) {
    return ExchangeStrategies.builder()
        .codecs(Jackson2WebClientCodecs::apply)
        .codecs(c -> c.defaultCodecs().maxInMemorySize(maxInMemorySize))
        .build();
  }

  /**
   * 기본 코덱의 JSON 인코더·디코더를 Jackson 2 로 바꾼다. 다른 코덱(문자열·바이트·SSE 등)은 그대로 둔다. 디코더·인코더 객체는 클라이언트마다 새로 만든다
   * — {@code maxInMemorySize} 같은 설정이 디코더 객체 자체에 걸리기 때문이다. 매퍼만 함께 쓴다.
   */
  private static void apply(ClientCodecConfigurer configurer) {
    configurer.defaultCodecs().jacksonJsonDecoder(new Jackson2JsonDecoder(SHARED_MAPPER));
    configurer.defaultCodecs().jacksonJsonEncoder(new Jackson2JsonEncoder(SHARED_MAPPER));
  }
}
