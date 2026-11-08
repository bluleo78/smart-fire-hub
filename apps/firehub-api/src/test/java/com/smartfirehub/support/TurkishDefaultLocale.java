package com.smartfirehub.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Locale;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/**
 * 각 테스트를 터키어 기본 로캘에서 돌리고 끝나면 원래 로캘로 되돌린다 — 인자 없는 대소문자 변환이 결과를 바꾸는(i → İ) 대표 로캘로 로캘 무관 코드를 검증한다.
 *
 * <p>JVM 전역 상태를 바꾸므로 {@link Resources#LOCALE} 잠금을 함께 건다 — JUnit 병렬 실행을 켜도 로캘을 쓰는 다른 테스트와 겹치지 않는다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(TurkishDefaultLocale.Extension.class)
@ResourceLock(Resources.LOCALE)
public @interface TurkishDefaultLocale {

  /** 기본 로캘(전체·DISPLAY·FORMAT 범주)을 저장하고 터키어로 바꾼 뒤, 테스트가 실패해도 범주별로 되돌리는 확장. */
  final class Extension implements BeforeEachCallback, AfterEachCallback {

    private static final ExtensionContext.Namespace NS =
        ExtensionContext.Namespace.create(Extension.class);

    /** 원래 로캘 세 범주를 저장소에 남기고 터키어로 바꾼다. 로캘이 정말 대문자화를 바꾸는지 확인해 공허한 통과를 막는다. */
    @Override
    public void beforeEach(ExtensionContext context) {
      ExtensionContext.Store store = context.getStore(NS);
      store.put("default", Locale.getDefault());
      store.put("display", Locale.getDefault(Locale.Category.DISPLAY));
      store.put("format", Locale.getDefault(Locale.Category.FORMAT));
      Locale.setDefault(Locale.forLanguageTag("tr"));
      if ("i".toUpperCase().equals("I")) {
        throw new IllegalStateException("터키어 로캘에서 인자 없는 대문자화가 바뀌지 않는다 — 로캘 테스트가 의미 없다");
      }
    }

    /** 저장해 둔 로캘을 범주별로 되돌린다(전체를 먼저 두고 범주를 덮어써 원래 조합을 복원). */
    @Override
    public void afterEach(ExtensionContext context) {
      ExtensionContext.Store store = context.getStore(NS);
      Locale.setDefault(store.get("default", Locale.class));
      Locale.setDefault(Locale.Category.DISPLAY, store.get("display", Locale.class));
      Locale.setDefault(Locale.Category.FORMAT, store.get("format", Locale.class));
    }
  }
}
