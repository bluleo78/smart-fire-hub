package com.smartfirehub.global.platformdb.mybatis;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * MyBatis 배선 게이트(WD-487) — 속성이 없거나 false 면 {@link MyBatisPlatformConfig} 가 통째로 빠지는지 본다.
 *
 * <p>왜: 운영 컨텍스트에는 PoC 빈(팩토리·템플릿·매퍼·구현 3개)이 하나도 생기면 안 된다. DataSource 없이 도는 가벼운 컨텍스트라, 설정이 조건을 무시하고
 * 켜지면 DataSource 를 못 찾아 기동이 실패하므로 그것도 함께 드러난다.
 */
class MyBatisPlatformConfigGateTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(MyBatisPlatformConfig.class);

  /** 속성이 없으면(운영 기본값) MyBatis 빈이 없다. */
  @Test
  void withoutProperty_noMyBatisBeans() {
    runner.run(
        ctx -> {
          assertThat(ctx).hasNotFailed();
          assertThat(ctx).doesNotHaveBean(MyBatisPlatformConfig.class);
          assertThat(ctx).doesNotHaveBean(SqlSessionFactory.class);
          assertThat(ctx).doesNotHaveBean(SqlSessionTemplate.class);
          assertThat(ctx).doesNotHaveBean("myBatisReviewItemRepository");
        });
  }

  /** false 로 명시해도 꺼진다. */
  @Test
  void propertyFalse_noMyBatisBeans() {
    runner
        .withPropertyValues(MyBatisPlatformConfig.ENABLED_PROPERTY + "=false")
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              assertThat(ctx).doesNotHaveBean(SqlSessionFactory.class);
            });
  }
}
