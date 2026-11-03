package com.smartfirehub.support;

import com.smartfirehub.global.platformdb.PlatformDbErrors;
import com.smartfirehub.global.platformdb.postgres.PostgresPlatformDbErrors;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * {@code @WebMvcTest} 슬라이스용 {@link PlatformDbErrors} 공급자(WD-486).
 *
 * <p>왜: {@code GlobalExceptionHandler}({@code @RestControllerAdvice})는 모든 WebMvc 슬라이스에 자동으로 올라오는데,
 * 이제 {@code PlatformDbErrors} 를 생성자로 받는다. 슬라이스는 {@code @Component} 를 스캔하지 않으므로 컨트롤러 테스트 40여 개가 빈
 * 누락으로 깨진다. 테스트 전용 자동 구성({@code META-INF/spring/...AutoConfigureWebMvc.imports})으로 한 번에 채운다. 이미 빈이
 * 있으면 물러난다.
 */
@AutoConfiguration
public class PlatformDbErrorsSliceConfig {

  /** 무상태 PG 구현 — 슬라이스에서도 실제 판정 로직을 그대로 쓴다. */
  @Bean
  @ConditionalOnMissingBean(PlatformDbErrors.class)
  PostgresPlatformDbErrors platformDbErrors() {
    return new PostgresPlatformDbErrors();
  }
}
