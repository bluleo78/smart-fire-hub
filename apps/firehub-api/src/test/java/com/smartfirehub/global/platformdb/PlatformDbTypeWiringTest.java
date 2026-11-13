package com.smartfirehub.global.platformdb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.global.platformdb.postgres.PostgresPlatformDbErrors;
import com.smartfirehub.global.platformdb.tibero.TiberoPlatformDbErrors;
import com.smartfirehub.vectorstore.VectorStoreFeatures;
import com.smartfirehub.vectorstore.off.OffVectorStoreFeatures;
import com.smartfirehub.vectorstore.pg.PgVectorStoreFeatures;
import java.time.ZoneId;
import java.util.TimeZone;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 플랫폼 DB 종류 설정({@code firehub.platform-db.type}, WD-496)이 구현 빈을 고르고, 기동 검사가 잘못된 설정을 맨 앞에서 막는지 본다.
 *
 * <p>스프링 슬라이스(ApplicationContextRunner)로 조건만 본다 — 전체 컨텍스트를 Tibero 로 띄우는 것은 리포지토리(WD-491)·테넌트
 * 세션(WD-492)·잠금(WD-493)이 생긴 뒤의 일이다.
 */
class PlatformDbTypeWiringTest {

  private final TimeZone original = TimeZone.getDefault();

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(
              PlatformDbStartupGuardConfig.class,
              PostgresPlatformDbErrors.class,
              PgVectorStoreFeatures.class,
              TiberoPlatformDbErrors.class,
              OffVectorStoreFeatures.class);

  @AfterEach
  void restoreTimeZone() {
    TimeZone.setDefault(original);
  }

  @Test
  void 설정이_없으면_PostgreSQL_구현만_뜬다() {
    runner.run(
        ctx -> {
          assertThat(ctx).hasNotFailed();
          assertThat(ctx.getBean(PlatformDbErrors.class))
              .isInstanceOf(PostgresPlatformDbErrors.class);
          assertThat(ctx.getBean(VectorStoreFeatures.class))
              .isInstanceOf(PgVectorStoreFeatures.class);
          assertThat(ctx).doesNotHaveBean(TiberoPlatformDbErrors.class);
        });
  }

  @Test
  void postgres_를_명시해도_같다() {
    runner
        .withPropertyValues("firehub.platform-db.type=postgres")
        .run(
            ctx ->
                assertThat(ctx.getBean(PlatformDbErrors.class))
                    .isInstanceOf(PostgresPlatformDbErrors.class));
  }

  @Test
  void tibero_면_Tibero_구현만_뜨고_벡터_키워드_검색이_꺼진다() {
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    runner
        .withPropertyValues("firehub.platform-db.type=tibero")
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              assertThat(ctx.getBean(PlatformDbErrors.class))
                  .isInstanceOf(TiberoPlatformDbErrors.class);
              VectorStoreFeatures features = ctx.getBean(VectorStoreFeatures.class);
              assertThat(features.supports(PlatformDbFeature.VECTOR_SEARCH)).isFalse();
              assertThat(features.supports(PlatformDbFeature.TRIGRAM_SEARCH)).isFalse();
              assertThat(ctx).doesNotHaveBean(PostgresPlatformDbErrors.class);
            });
  }

  /** KST JVM 에서 Tibero 로 뜨면 JobRunr 시각이 9시간 어긋난다(WD-488) — DB 에 붙기 전에 멈춰야 한다. */
  @Test
  void tibero_인데_JVM_이_UTC_가_아니면_기동이_멈춘다() {
    TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
    runner
        .withPropertyValues("firehub.platform-db.type=tibero")
        .run(
            ctx ->
                assertThat(ctx)
                    .getFailure()
                    .hasStackTraceContaining("UTC")
                    .hasStackTraceContaining("Asia/Seoul"));
  }

  @Test
  void 알_수_없는_값이면_기동이_멈춘다() {
    runner
        .withPropertyValues("firehub.platform-db.type=oracle")
        .run(ctx -> assertThat(ctx).getFailure().hasStackTraceContaining("postgres 또는 tibero"));
  }

  @Test
  void UTC_판정은_항상_오프셋_0_인_시간대만_받는다() {
    PlatformDbStartupGuard.requireUtc(ZoneId.of("UTC"));
    PlatformDbStartupGuard.requireUtc(ZoneId.of("Etc/UTC"));
    PlatformDbStartupGuard.requireUtc(ZoneId.of("GMT"));
    PlatformDbStartupGuard.requireUtc(ZoneId.of("Z"));
    assertThatThrownBy(() -> PlatformDbStartupGuard.requireUtc(ZoneId.of("Europe/London")))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> PlatformDbStartupGuard.requireUtc(ZoneId.of("Asia/Seoul")))
        .isInstanceOf(IllegalStateException.class);
  }

  /** 대소문자만 무시한다 — 빈 구현을 고르는 @ConditionalOnProperty 와 같은 규칙. */
  @Test
  void 값은_대소문자만_가리지_않는다() {
    assertThat(PlatformDbStartupGuard.resolve("Tibero")).isEqualTo(PlatformDbType.TIBERO);
    assertThat(PlatformDbStartupGuard.resolve("POSTGRES")).isEqualTo(PlatformDbType.POSTGRES);
    assertThat(PlatformDbStartupGuard.resolve(null)).isEqualTo(PlatformDbType.POSTGRES);
  }

  /** 빈 값을 받으면 검사는 통과하고 어느 쪽 빈도 등록되지 않는다 — 검사에서 막는다. */
  @Test
  void 빈_값이면_기동이_멈춘다() {
    runner
        .withPropertyValues("firehub.platform-db.type=")
        .run(ctx -> assertThat(ctx).getFailure().hasStackTraceContaining("postgres 또는 tibero"));
  }

  /**
   * 앞뒤 공백이 붙은 값(환경변수 실수)도 거절한다 — {@code @ConditionalOnProperty} 는 값을 다듬지 않아 어느 쪽 빈도 고르지 않는다. 테스트 속성
   * 도구는 값을 다듬어 넘기므로 판정 함수로 직접 본다.
   */
  @Test
  void 공백이_붙은_값은_거절한다() {
    for (String value : new String[] {"", " tibero", "postgres ", "\ttibero"}) {
      assertThatThrownBy(() -> PlatformDbStartupGuard.resolve(value))
          .as("값 '%s'", value)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("postgres 또는 tibero");
    }
  }
}
