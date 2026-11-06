package com.smartfirehub.support.tibero;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Tibero 실서버 테스트 표시(WD-497).
 *
 * <p>두 조건이 모두 맞을 때만 돈다:
 *
 * <ul>
 *   <li>{@code TIBERO_TEST_HOST} 환경변수 — 접속할 서버가 있다.
 *   <li>시스템 속성 {@value #RUN_PROPERTY}=true — 전용 Gradle 태스크 {@code tiberoTest} 만 켠다.
 * </ul>
 *
 * <p>왜 두 번째 조건인가: 개발자 셸에 {@code TIBERO_TEST_*} 가 남아 있으면 기본 {@code test}(pre-push 전체 회귀 포함)가 공유
 * 서버(세션 20개, DCIM 팀과 공유)에 붙는다. 기본 태스크에서는 건너뛰기로만 보이게 하고(태그 제외로 빼면 {@code --tests} 지정 실행이 "테스트 없음" 으로
 * 실패한다), 실서버 실행은 UTC JVM·단일 포크로 고정한 {@code tiberoTest} 에서만 한다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Tag(TiberoLive.TAG)
@EnabledIfEnvironmentVariable(named = "TIBERO_TEST_HOST", matches = ".+")
@EnabledIfSystemProperty(named = TiberoLive.RUN_PROPERTY, matches = "true")
public @interface TiberoLive {

  /** JUnit 태그 — {@code tiberoTest} 태스크가 이 태그만 고른다. */
  String TAG = "tibero";

  /** {@code tiberoTest} 태스크가 켜는 시스템 속성. */
  String RUN_PROPERTY = "firehub.tibero-live";
}
