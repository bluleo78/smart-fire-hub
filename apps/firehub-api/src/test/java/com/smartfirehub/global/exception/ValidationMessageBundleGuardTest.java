package com.smartfirehub.global.exception;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Size;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedArrayType;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.hibernate.validator.HibernateValidator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;

/**
 * Bean Validation 문구가 영어로 되돌아가지 않게 막는 가드(WD-640, WD-570 후속).
 *
 * <p>① 운영 코드(com.smartfirehub)의 모든 제약 사용처를 훑어, 문구가 번들 키({@code {...}})면 {@code
 * ValidationMessages.properties} 에 한국어 값이 있는지, 직접 적은 문구면 한글인지 확인한다 — 번들에 없는 표준 제약을 새로 붙이거나 영어 문구를
 * 적으면 여기서 실패한다. ② 번들의 「자」 기준 Size 문구는 문자열 전제라, 컬렉션·맵·배열의 @Size 에는 message= 를 명시해야 한다(번들 주석의 약속)를
 * 강제한다. ③ Size 기본 문구의 EL 세 갈래(max 만·min 만·둘 다)를 영어 기본 로케일에서 실제로 렌더해 본다 — 지금까지는 max 만 경로만 검증됐다.
 *
 * <p>Spring 컨텍스트 없이 클래스패스 스캔·리플렉션·순수 Hibernate Validator 로 돈다(DB 불필요).
 */
class ValidationMessageBundleGuardTest {

  private static final Pattern HANGUL = Pattern.compile("[가-힣]");
  private static final Pattern BUNDLE_KEY = Pattern.compile("^\\{([^{}]+)}$");

  private static Properties bundle;
  private static ValidatorFactory factory;
  private static List<Usage> usages;
  private static Validator validator;

  @BeforeAll
  static void setUp() throws Exception {
    bundle = new Properties();
    try (InputStream in =
        ValidationMessageBundleGuardTest.class.getResourceAsStream(
            "/ValidationMessages.properties")) {
      assertThat(in).as("ValidationMessages.properties 가 클래스패스에 있어야 한다").isNotNull();
      bundle.load(new InputStreamReader(in, StandardCharsets.UTF_8));
    }
    // 기본 로케일을 영어로 둬도 기본 번들(접미사 없음)의 한국어가 쓰여야 한다.
    factory =
        Validation.byProvider(HibernateValidator.class)
            .configure()
            .defaultLocale(Locale.ENGLISH)
            .buildValidatorFactory();
    validator = factory.getValidator();
    // 클래스패스 스캔은 무거워 한 번만 하고 테스트들이 나눠 쓴다.
    usages = scanUsages();
  }

  @AfterAll
  static void tearDown() {
    factory.close();
  }

  /** 제약 하나의 사용처 — 위치 설명, 제약, 붙은 요소의 타입. */
  private record Usage(String where, Annotation constraint, Class<?> targetType) {}

  /**
   * com.smartfirehub 아래 운영 클래스(중첩·레코드 포함)를 읽는다. 추상·인터페이스도 후보로 받는다.
   *
   * <p>운영/테스트 구분은 이름이 아니라 출처(클래스가 실린 위치)로 한다 — 운영 클래스(GlobalExceptionHandler)와 같은 출력 폴더·jar 에서 온 것만
   * 남긴다. 이름 규칙(…Test·…Stub)으로 거르면 ConnectionTest 같은 운영 클래스까지 빠진다.
   */
  private static List<Class<?>> scanClasses() throws Exception {
    ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false) {
          @Override
          protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
            return true;
          }
        };
    scanner.addIncludeFilter((reader, factory) -> true);
    URL mainOrigin = origin(GlobalExceptionHandler.class);
    assertThat(mainOrigin).as("운영 클래스 출처").isNotNull();
    List<Class<?>> classes = new ArrayList<>();
    ClassLoader cl = ValidationMessageBundleGuardTest.class.getClassLoader();
    for (BeanDefinition bd : scanner.findCandidateComponents("com.smartfirehub")) {
      String name = bd.getBeanClassName();
      if (name == null) {
        continue;
      }
      try {
        Class<?> c = Class.forName(name, false, cl);
        if (mainOrigin.equals(origin(c))) {
          classes.add(c);
        }
      } catch (Throwable ignored) {
        // 선택 의존성(드라이버 등) 때문에 못 읽는 클래스는 DTO 가 아니다.
      }
    }
    return classes;
  }

  /** 클래스가 실린 위치(출력 폴더 또는 jar). 모르면 null. */
  private static URL origin(Class<?> c) {
    CodeSource source = c.getProtectionDomain().getCodeSource();
    return source == null ? null : source.getLocation();
  }

  /** 요소에 붙은 제약(반복 컨테이너 @X.List 포함)을 모은다. */
  private static void collect(
      List<Usage> out, String where, AnnotatedElement element, Class<?> targetType) {
    for (Annotation a : element.getDeclaredAnnotations()) {
      if (a.annotationType().isAnnotationPresent(Constraint.class)) {
        out.add(new Usage(where, a, targetType));
        continue;
      }
      // @Size.List 처럼 value() 가 제약 배열인 컨테이너.
      try {
        Method value = a.annotationType().getDeclaredMethod("value");
        if (value.getReturnType().isArray()
            && value.getReturnType().getComponentType().isAnnotationPresent(Constraint.class)) {
          for (Annotation inner : (Annotation[]) value.invoke(a)) {
            out.add(new Usage(where, inner, targetType));
          }
        }
      } catch (NoSuchMethodException ignored) {
        // 컨테이너가 아니다.
      } catch (ReflectiveOperationException e) {
        throw new IllegalStateException(e);
      }
    }
  }

  /**
   * 타입 인자·배열 원소에 붙은 제약(예: {@code List<@NotNull Item>})을 모은다 — getDeclaredAnnotations() 로는 보이지 않는다.
   * 레코드 컴포넌트의 타입 애너테이션은 필드 타입으로 전파되므로 필드에서 함께 잡힌다.
   */
  private static void collectTypeUse(List<Usage> out, String where, AnnotatedType type) {
    if (type instanceof AnnotatedParameterizedType pt) {
      for (AnnotatedType arg : pt.getAnnotatedActualTypeArguments()) {
        collect(out, where + "<>", arg, rawClass(arg.getType()));
        collectTypeUse(out, where + "<>", arg);
      }
    } else if (type instanceof AnnotatedArrayType at) {
      AnnotatedType component = at.getAnnotatedGenericComponentType();
      collect(out, where + "[]", component, rawClass(component.getType()));
      collectTypeUse(out, where + "[]", component);
    }
  }

  /** 제네릭 타입의 원시 클래스(모르면 Object). */
  private static Class<?> rawClass(Type t) {
    if (t instanceof Class<?> c) {
      return c;
    }
    if (t instanceof ParameterizedType p && p.getRawType() instanceof Class<?> c) {
      return c;
    }
    return Object.class;
  }

  /** 운영 코드의 모든 제약 사용처(타입·필드·메서드 파라미터, 타입 인자 포함). */
  private static List<Usage> scanUsages() throws Exception {
    List<Usage> out = new ArrayList<>();
    for (Class<?> c : scanClasses()) {
      try {
        collect(out, c.getName(), c, c);
        for (Field f : c.getDeclaredFields()) {
          collect(out, c.getName() + "#" + f.getName(), f, f.getType());
          collectTypeUse(out, c.getName() + "#" + f.getName(), f.getAnnotatedType());
        }
        for (Method m : c.getDeclaredMethods()) {
          for (Parameter p : m.getParameters()) {
            String where = c.getName() + "#" + m.getName() + "(" + p.getName() + ")";
            collect(out, where, p, p.getType());
            collectTypeUse(out, where, p.getAnnotatedType());
          }
        }
      } catch (Throwable ignored) {
        // 리플렉션 중 연결 실패(선택 의존성)는 DTO 가 아니다.
      }
    }
    return out;
  }

  private static String message(Annotation a) throws ReflectiveOperationException {
    return (String) a.annotationType().getMethod("message").invoke(a);
  }

  /** 모든 제약 문구가 한국어로 나간다 — 번들 키면 번들에 한국어 값이, 직접 적은 문구면 한글이 있어야 한다. */
  @Test
  void everyConstraintInMainCode_hasKoreanMessage() throws Exception {
    List<Usage> all = usages;
    // 스캔이 비면 가드가 아무것도 안 지키는 것이다 — 이 코드베이스에는 제약이 수백 개 있다.
    assertThat(all).as("스캔된 제약 사용처").hasSizeGreaterThan(50);
    // 테스트 출처 클래스는 빠졌는지 — 같은 패키지의 테스트 스텁(ExceptionStubController.ValidatedBody)은 운영 DTO 가 아니다.
    assertThat(all).noneMatch(u -> u.where().contains("ExceptionStubController"));
    // 타입 인자 제약도 훑는지 — CreateDatasetRequest.columns 의 List<@NotNull ...> 가 잡혀야 한다.
    assertThat(all)
        .as("타입 인자 제약 스캔")
        .anyMatch(u -> u.where().contains("CreateDatasetRequest#columns<>"));
    List<String> problems = new ArrayList<>();
    for (Usage u : all) {
      String msg = message(u.constraint());
      Matcher key = BUNDLE_KEY.matcher(msg);
      String rendered;
      if (key.matches()) {
        rendered = bundle.getProperty(key.group(1));
        if (rendered == null) {
          problems.add(
              u.where()
                  + " @"
                  + u.constraint().annotationType().getSimpleName()
                  + " → 번들에 키 없음: "
                  + msg);
          continue;
        }
      } else {
        rendered = msg;
      }
      if (!HANGUL.matcher(rendered).find()) {
        problems.add(
            u.where()
                + " @"
                + u.constraint().annotationType().getSimpleName()
                + " → 한국어 아님: "
                + rendered);
      }
    }
    assertThat(problems).as("영어로 나갈 제약 문구").isEmpty();
  }

  /** 컬렉션·맵·배열의 @Size 는 message= 를 명시한다 — 번들 Size 기본 문구는 「N자」라 개수에는 어색하다. */
  @Test
  void sizeOnCollections_hasExplicitMessage() throws Exception {
    List<String> problems = new ArrayList<>();
    for (Usage u : usages) {
      if (!(u.constraint() instanceof Size size)) {
        continue;
      }
      Class<?> t = u.targetType();
      boolean container =
          Collection.class.isAssignableFrom(t) || Map.class.isAssignableFrom(t) || t.isArray();
      if (container && BUNDLE_KEY.matcher(size.message()).matches()) {
        problems.add(u.where());
      }
    }
    assertThat(problems).as("message= 없는 컬렉션 @Size").isEmpty();
  }

  /** Size 기본 문구 렌더 대상 — max 만·min 만·둘 다. */
  static final class SizeSamples {
    @Size(max = 3)
    String maxOnly = "abcd";

    @Size(min = 5)
    String minOnly = "ab";

    @Size(min = 2, max = 4)
    String both = "a";
  }

  /** Size 기본 문구의 세 갈래가 영어 기본 로케일에서도 한국어로 렌더된다(EL 식이 깨져 영어·원문이 새지 않는다). */
  @Test
  void sizeDefaultMessage_rendersKoreanForAllVariants() {
    Set<ConstraintViolation<SizeSamples>> violations = validator.validate(new SizeSamples());
    assertThat(violations).hasSize(3);
    for (ConstraintViolation<SizeSamples> v : violations) {
      String field = v.getPropertyPath().toString();
      switch (field) {
        case "maxOnly" -> assertThat(v.getMessage()).isEqualTo("3자 이하로 입력하세요.");
        case "minOnly" -> assertThat(v.getMessage()).isEqualTo("5자 이상 입력하세요.");
        case "both" -> assertThat(v.getMessage()).isEqualTo("2자 이상 4자 이하로 입력하세요.");
        default -> throw new AssertionError("예상 밖 필드: " + field);
      }
    }
  }
}
