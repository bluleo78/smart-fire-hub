package com.smartfirehub.global.platformdb;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** {@link PlatformDbStartupGuard} 등록(WD-496) — 후처리기라 정적 빈 메서드로 둔다(구성 클래스 프록시보다 먼저 만들어져야 한다). */
@Configuration(proxyBeanMethods = false)
public class PlatformDbStartupGuardConfig {

  @Bean
  static PlatformDbStartupGuard platformDbStartupGuard() {
    return new PlatformDbStartupGuard();
  }
}
