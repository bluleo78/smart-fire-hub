package com.smartfirehub.global.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaMethodReference;
import com.tngtech.archunit.core.domain.properties.HasName;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.EvaluationResult;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 운영 코드는 기본 로캘을 따르는 {@code String.toUpperCase()}·{@code toLowerCase()}(인자 없는 판)를 호출하지도, 메서드
 * 참조({@code String::toUpperCase})로 쓰지도 않는다(WD-653).
 *
 * <p>왜: 기본 로캘이 터키어면 {@code "int8".toUpperCase()} 가 {@code "İNT8"} 이 되는 등 기계 식별자(타입명·파일 형식·HTTP 메서드
 * 등) 판정이 갈린다. 운영 코드의 대소문자 변환 대상은 모두 기계 식별자라 {@code Locale.ROOT} 를 명시해야 한다. 바이트코드를 보므로 주석·문자열 속 텍스트로
 * 거짓 양성이 나지 않고, {@code Locale} 인자 판은 시그니처가 달라 걸리지 않는다.
 *
 * <p>그래프 가져오기는 다른 아키텍처 규칙과 같은 옵션(테스트·jOOQ 생성 코드 제외, 끝나면 해제)을 쓴다.
 */
class DefaultLocaleCaseConversionArchitectureTest {

  /** 기본 로캘을 따르는 String 메서드 이름 — 인자 없는 판만 금지한다. */
  private static final Set<String> CASE_METHODS = Set.of("toUpperCase", "toLowerCase");

  /** 프로덕션 클래스 그래프 — 클래스 단위로 한 번만 가져오고 끝나면 놓는다(힙 사정은 PlatformDbAccessArchitectureTest 주석 참고). */
  private static JavaClasses PROD;

  @BeforeAll
  static void importProductionClasses() {
    PROD =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .withImportOption(location -> !location.contains("/com/smartfirehub/jooq/"))
            .importPackages("com.smartfirehub");
  }

  @AfterAll
  static void releaseProductionClasses() {
    PROD = null;
  }

  /** 운영 코드에 인자 없는 대소문자 변환 호출·메서드 참조가 없다. */
  @Test
  void productionCodeDoesNotUseDefaultLocaleCaseConversion() {
    rule().check(PROD);
  }

  /** 규칙이 호출과 메서드 참조를 둘 다 잡고, Locale.ROOT 판은 놓아 두는지 — 픽스처 하나로 규칙 자체를 검증한다. */
  @Test
  void ruleReportsCallAndMethodReference_butNotRootLocaleCall() {
    JavaClasses fixture = new ClassFileImporter().importClasses(Fixture.class);

    EvaluationResult result = rule().evaluate(fixture);

    List<String> details = result.getFailureReport().getDetails();
    assertThat(details).hasSize(2);
    assertThat(details)
        .anySatisfy(d -> assertThat(d).contains("calls").contains("toUpperCase()"))
        .anySatisfy(d -> assertThat(d).contains("references").contains("toLowerCase()"))
        .noneSatisfy(d -> assertThat(d).contains("rootLocale"));
  }

  /** {@code com.smartfirehub} 의 모든 클래스가 인자 없는 String 대소문자 변환을 호출·참조하지 않아야 한다는 규칙. */
  private static ArchRule rule() {
    return classes()
        .that()
        .resideInAPackage("com.smartfirehub..")
        .should(notUseDefaultLocaleCaseConversion())
        .because(
            "WD-653: 인자 없는 toUpperCase()/toLowerCase() 는 기본 로캘(예: 터키어)에 따라 결과가 달라진다 — Locale.ROOT 를 명시하라");
  }

  /**
   * 클래스의 메서드 호출과 메서드 참조를 모두 훑어 {@code String} 의 인자 없는 대소문자 변환을 대상으로 하면 위반으로 낸다. ArchUnit 은
   * 호출({@link JavaMethodCall})과 참조({@link JavaMethodReference})를 따로 모으므로 둘 다 본다.
   */
  private static ArchCondition<JavaClass> notUseDefaultLocaleCaseConversion() {
    return new ArchCondition<>(
        "not call or reference String.toUpperCase()/toLowerCase() without Locale") {
      @Override
      public void check(JavaClass javaClass, ConditionEvents events) {
        Stream.<JavaAccess<?>>concat(
                javaClass.getMethodCallsFromSelf().stream(),
                javaClass.getMethodReferencesFromSelf().stream())
            .filter(DefaultLocaleCaseConversionArchitectureTest::isDefaultLocaleCaseConversion)
            .forEach(
                access ->
                    events.add(SimpleConditionEvent.violated(access, access.getDescription())));
      }
    };
  }

  /** 접근 대상이 {@code java.lang.String} 의 매개변수 없는 toUpperCase/toLowerCase 인가. */
  private static boolean isDefaultLocaleCaseConversion(JavaAccess<?> access) {
    if (!(access instanceof JavaMethodCall) && !(access instanceof JavaMethodReference)) {
      return false;
    }
    var target =
        access instanceof JavaMethodCall call
            ? call.getTarget()
            : ((JavaMethodReference) access).getTarget();
    return target.getOwner().isEquivalentTo(String.class)
        && CASE_METHODS.contains(((HasName) target).getName())
        && target.getRawParameterTypes().isEmpty();
  }

  /** 규칙 검증용 픽스처 — 호출 하나·메서드 참조 하나는 위반, Locale.ROOT 호출은 위반이 아니다. 테스트 소스라 운영 그래프에는 들어가지 않는다. */
  @SuppressWarnings("unused")
  static final class Fixture {

    /** 위반: 인자 없는 호출. */
    String defaultLocaleCall(String s) {
      return s.toUpperCase();
    }

    /** 위반: 메서드 참조(인자 없는 판으로 묶인다). */
    List<String> defaultLocaleReference(List<String> names) {
      return names.stream().map(String::toLowerCase).toList();
    }

    /** 허용: 로캘을 명시한 호출. */
    String rootLocale(String s) {
      return s.toUpperCase(Locale.ROOT) + s.toLowerCase(Locale.ROOT);
    }
  }
}
