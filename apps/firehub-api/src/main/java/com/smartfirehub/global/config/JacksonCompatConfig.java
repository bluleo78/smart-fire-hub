package com.smartfirehub.global.config;

import java.sql.Date;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

/**
 * Jackson 3 전환(WD-793) 뒤에도 앱 JSON 이 Jackson 2(Boot 3) 때와 같게 나가도록 하는 설정의 한 곳이다.
 *
 * <p>두 소비자가 같은 규칙({@link #applyAppJsonDefaults})을 쓴다 — ① Boot 가 만드는 앱 매퍼(HTTP 변환기·주입되는 {@code
 * ObjectMapper}) 는 아래 customizer 로, ② 스프링 밖에서 직접 만드는 WebClient 코덱({@code AppJsonWebClients}) 은
 * {@link #appJsonMapperBuilder()} 로. 규칙을 두 곳에 따로 적으면 한쪽만 고쳐져 직접 빌더의 요청·응답 JSON 이 앱 응답과 조용히 갈라지므로(코드
 * 리뷰 지적 1·2) 여기 한 곳에서만 정한다. {@code AppJsonWebClientsEquivalenceTest} 가 두 매퍼의 바이트가 같은지 지킨다.
 */
@Configuration(proxyBeanMethods = false)
public class JacksonCompatConfig {

  /**
   * 앱 매퍼 규칙을 Boot 매퍼에 건다.
   *
   * <p>가장 먼저(HIGHEST_PRECEDENCE) 돈다 — Boot 의 표준 customizer(순서 0, {@code spring.jackson.*} 속성)가 그 뒤에
   * 돌아, 앞으로 yml 에 적는 개별 속성이 이 기본값보다 우선한다. {@code application.yml} 의 {@code
   * spring.jackson.use-jackson2-defaults: true} 도 Boot 가 같은 기능 묶음을 한 번 더 켜는 것일 뿐이라(같은 값으로 켜고 끄는 일만
   * 한다) 결과가 같다.
   */
  @Bean
  @Order(Ordered.HIGHEST_PRECEDENCE)
  JsonMapperBuilderCustomizer appJsonDefaultsCustomizer() {
    return JacksonCompatConfig::applyAppJsonDefaults;
  }

  /**
   * 스프링 컨텍스트 밖에서 앱 매퍼와 같은 매퍼를 만드는 빌더 — Boot 자동 구성처럼 클래스패스 모듈을 찾아 붙이고({@code
   * spring.jackson.find-and-add-modules} 기본값) 앱 규칙을 건다. 직접 만드는 WebClient 코덱이 쓴다.
   */
  public static JsonMapper.Builder appJsonMapperBuilder() {
    JsonMapper.Builder builder =
        JsonMapper.builder().findAndAddModules(JacksonCompatConfig.class.getClassLoader());
    applyAppJsonDefaults(builder);
    return builder;
  }

  /**
   * 앱 매퍼 규칙: Jackson 2 기본값({@code configureForJackson2()} — 원시 타입 자리 null 허용·UTC 를 +00:00 로 쓰기 등)
   * 위에 Boot 3 자동 구성이 걸던 값(날짜·기간을 문자열로, 모르는 속성 무시, DEFAULT_VIEW_INCLUSION 끔 — Boot 4 표준 customizer 도
   * 늘 거는 값)과 {@link java.sql.Date} 의 {@code yyyy-MM-dd} 직렬화를 더한다. 기능을 켜고 끄기만 하므로 여러 번 걸어도 결과가 같다.
   */
  static void applyAppJsonDefaults(JsonMapper.Builder builder) {
    builder
        .configureForJackson2()
        .disable(
            DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS,
            DateTimeFeature.WRITE_DURATIONS_AS_TIMESTAMPS)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .disable(MapperFeature.DEFAULT_VIEW_INCLUSION)
        .addModule(sqlDateAsPlainDateModule());
  }

  /**
   * {@link java.sql.Date} 를 {@code yyyy-MM-dd} 문자열로 쓰는 모듈.
   *
   * <p>Jackson 2(Boot 3) 기본값 재현 — jOOQ·MyBatis 가 DATE 컬럼을 {@code java.sql.Date} 로 읽어 행 Map·DTO 에
   * 담는데, Jackson 2 는 이를 {@code toString()} 인 날짜 문자열({@code "2024-02-29"})로 썼다. Jackson 3 의 기본 직렬화는
   * 자정 시각을 UTC 로 환산한 {@code "2024-02-29T00:00:00.000Z"} 라 응답이 바뀌고, 저장 TZ 가 UTC 가 아니면 날짜가 하루 어긋나 보일
   * 수도 있다. 웹·ai-agent 가 날짜 문자열로 파싱하므로 예전 모양을 그대로 둔다. 모듈 이름이 같으면 Jackson 이 한 번만 등록한다.
   */
  private static SimpleModule sqlDateAsPlainDateModule() {
    SimpleModule module = new SimpleModule("sql-date-as-plain-date");
    module.addSerializer(Date.class, new SqlDateSerializer());
    return module;
  }

  /**
   * {@code java.sql.Date#toString()} 은 JVM 기본 TZ 의 벽시계 날짜를 {@code yyyy-MM-dd} 로 내보낸다 — Jackson 2 와
   * 같은 값이다.
   */
  private static final class SqlDateSerializer extends ValueSerializer<Date> {
    @Override
    public void serialize(Date value, JsonGenerator gen, SerializationContext context) {
      gen.writeString(value.toString());
    }
  }
}
