package com.smartfirehub.global.platformdb.mybatis;

import com.smartfirehub.global.platformdb.PlatformDbType;
import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.ConfigurationCondition;

/**
 * MyBatis 플랫폼 배선을 켜는 조건(WD-706) — {@code firehub.platform-db.mybatis.enabled=true}(테스트 프로필) 또는
 * {@code firehub.platform-db.type=tibero}(Tibero 설치본).
 *
 * <p>왜: Tibero 설치본은 MyBatis 구현이 유일한 구현이라 별도 설정 없이 켜져야 하고, PG 운영은 둘 다 없어 MyBatis 빈이 하나도 생기지 않는다.
 */
public class MyBatisPlatformCondition extends AnyNestedCondition {

  MyBatisPlatformCondition() {
    super(ConfigurationCondition.ConfigurationPhase.PARSE_CONFIGURATION);
  }

  /** 명시적으로 켠 경우(테스트 프로필). */
  @ConditionalOnProperty(name = MyBatisPlatformConfig.ENABLED_PROPERTY, havingValue = "true")
  static class Enabled {}

  /** 플랫폼 DB 가 Tibero 인 경우. */
  @ConditionalOnProperty(name = PlatformDbType.PROPERTY, havingValue = "tibero")
  static class Tibero {}
}
