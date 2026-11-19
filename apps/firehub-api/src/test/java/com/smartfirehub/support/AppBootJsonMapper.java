package com.smartfirehub.support;

import com.smartfirehub.global.config.JacksonCompatConfig;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;

/**
 * 앱이 HTTP 변환기·주입에 쓰는 것과 같은 Jackson 3 매퍼를 시험에서 얻는 도우미(WD-793).
 *
 * <p>왜: 전체 스프링 컨텍스트 없이 매퍼만 필요한 시험(골든·DTO 필드 시험)이 같은 구성을 저마다 적으면, {@code application.yml} 의 {@code
 * spring.jackson.*} 가 바뀔 때 한쪽만 고쳐져 운영과 다른 매퍼로 시험하게 된다. 실제 {@code application.yml} 을 읽어 Boot 의
 * Jackson 자동 구성과 {@link JacksonCompatConfig} 를 그대로 걸어 만든다.
 */
public final class AppBootJsonMapper {

  private AppBootJsonMapper() {}

  /** 실제 application.yml 의 spring.jackson.* 와 JacksonCompatConfig 를 건 Boot 매퍼를 새로 만든다. */
  public static JsonMapper create() {
    AtomicReference<JsonMapper> mapper = new AtomicReference<>();
    new ApplicationContextRunner()
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
        .withUserConfiguration(JacksonCompatConfig.class)
        .run(ctx -> mapper.set(ctx.getBean(JsonMapper.class)));
    return mapper.get();
  }
}
