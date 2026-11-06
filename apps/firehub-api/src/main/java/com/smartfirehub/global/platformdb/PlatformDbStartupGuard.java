package com.smartfirehub.global.platformdb;

import java.time.ZoneId;
import java.time.ZoneOffset;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;

/**
 * 플랫폼 DB 설정을 기동 맨 앞에서 검사한다(WD-496) — 어떤 DB 연결·마이그레이션·작업 서버보다 먼저 실패시키기 위해 빈 팩토리 후처리기로 둔다.
 *
 * <ul>
 *   <li>{@value PlatformDbType#PROPERTY} 값이 {@code postgres}·{@code tibero} 가 아니면 멈춘다. 오타가 나면 어느 쪽
 *       구현 빈도 등록되지 않아 엉뚱한 "빈 없음" 오류로 터지기 때문이다.
 *   <li>Tibero 면 JVM 기본 시간대가 UTC 인지 본다. Tibero 드라이버는 {@code Instant} 를 JVM 시간대의 벽시계로 저장해, KST 에서는
 *       JobRunr 하트비트가 9시간 어긋나 서버가 스스로를 지우고 실패 기록이 0건 갱신으로 사라진다(WD-488 실측, ADR 0001 결정 6).
 * </ul>
 *
 * <p>구성 클래스({@code PlatformDbStartupGuardConfig})의 정적 빈 메서드로 등록한다 — 후처리기는 다른 빈보다 먼저 만들어져야 한다.
 */
public class PlatformDbStartupGuard implements BeanFactoryPostProcessor, EnvironmentAware {

  private Environment environment;

  @Override
  public void setEnvironment(Environment environment) {
    this.environment = environment;
  }

  @Override
  public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
    PlatformDbType type = resolve(environment.getProperty(PlatformDbType.PROPERTY));
    if (type == PlatformDbType.TIBERO) {
      requireUtc(ZoneId.systemDefault());
    }
  }

  /**
   * 설정값을 종류로 바꾼다. 속성이 아예 없을 때만 PostgreSQL 이다.
   *
   * <p>빈 값·앞뒤 공백은 받지 않는다 — 구현 빈을 고르는 {@code @ConditionalOnProperty} 는 "없음" 만 PostgreSQL 로 보고 값을 다듬지
   * 않는다 (대소문자만 무시). 여기서 더 너그럽게 받으면 검사는 통과하고 어느 쪽 빈도 등록되지 않는 어긋남이 생긴다.
   *
   * @throws IllegalStateException 알 수 없는 값(빈 값·공백 포함)
   */
  static PlatformDbType resolve(String raw) {
    if (raw == null) {
      return PlatformDbType.POSTGRES;
    }
    for (PlatformDbType type : PlatformDbType.values()) {
      if (type.name().equalsIgnoreCase(raw)) {
        return type;
      }
    }
    throw new IllegalStateException(
        PlatformDbType.PROPERTY + " 값은 postgres 또는 tibero 여야 합니다(앞뒤 공백 없이): '" + raw + "'");
  }

  /**
   * Tibero 플랫폼 DB 는 UTC JVM 에서만 뜬다. {@code UTC}·{@code Etc/UTC}·{@code GMT}·{@code Z} 처럼 늘 오프셋 0 인
   * 시간대만 받는다(여름 시간이 있는 {@code Europe/London} 은 겨울에만 0 이라 거절).
   *
   * @throws IllegalStateException UTC 가 아님
   */
  static void requireUtc(ZoneId zone) {
    if (!ZoneOffset.UTC.equals(zone.normalized())) {
      throw new IllegalStateException(
          "플랫폼 DB 가 Tibero 면 JVM 시간대가 UTC 여야 합니다(현재 "
              + zone
              + "). JAVA_TOOL_OPTIONS 에 -Duser.timezone=UTC 를 주거나 TZ=UTC 로 실행하세요");
    }
  }
}
