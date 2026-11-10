package com.smartfirehub.global.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 메일(SMTP)을 다루는 코드는 {@link Logger} 를 직접 부르지 않는다 — 로그는 {@code SmtpFailureLog} 하나로만 남긴다(WD-693).
 *
 * <p><b>왜.</b> 메일 예외는 서버 응답 원문을 담는다 — AUTH 단계에서 되울린 비밀번호·AUTH PLAIN 자격 증명(Base64)이 들어 있을 수 있다. 발송
 * 지점은 실패를 가린 실패로 바꿔 던지고({@code CredentialMasker.callMasked}), 도우미({@code SmtpFailureLog})는 예외를 붙일 때
 * 늘 가린 사본만 붙이며 형식 인자에 섞인 예외는 클래스 이름으로 바꾼다. 이 검사는 메일 코드가 도우미를 건너뛰지 못하게 막는다.
 *
 * <p><b>왜 Logger 호출 전부인가.</b> 바이트코드에서 {@code log.warn("… {}", id, e)} 는 {@code warn(String, Object,
 * Object)}·{@code warn(String, Object...)} 호출이라 마지막 인자가 예외인지 알 수 없다. 예전에는 그 틈을 소스 스캔(정규식)으로 메웠지만 옮겨
 * 담은 변수·다른 이름의 로거 등 빈틈이 많았다. 메일 코드의 로그 호출은 몇 곳뿐이라 Throwable 판만이 아니라 로깅 메서드 전부를 막고 도우미로 돌리는 편이
 * 바이트코드만으로 빈틈없이 지킬 수 있다. 수준 확인({@code isDebugEnabled} 등)·{@code getName} 만 허용한다.
 *
 * <p><b>메일 코드.</b> 메일 타입({@code jakarta.mail..}·{@code org.springframework.mail..}) 또는 SMTP 공용
 * 클래스({@code SmtpFailureMessages}·{@code SmtpMailSenders})에 의존하는 클래스와 그 중첩·익명 클래스 전부(같은 최상위 클래스
 * 단위). 도우미 {@code SmtpFailureLog} 자신은 뺀다. 메일 예외는 대개 {@code catch (Exception e)} 로 잡혀 catch 타입으로는 메일
 * 코드를 가를 수 없어 의존으로 가른다.
 *
 * <p><b>못 잡는 것(남은 위험).</b> 메일 타입에 의존하지 않는 클래스가 메일 예외를 넘겨받아 로깅하는 경우, 예외 메시지 문자열({@code
 * e.getMessage()})을 형식 인자로 넘기는 경우. 이런 형태는 리뷰로 막는다.
 */
class SmtpFailureLoggingArchitectureTest {

  /** 메일 코드 판정용 의존 대상 — 이름이 이것으로 시작하는 클래스에 의존하면 메일 코드다(중첩 클래스 포함). */
  private static final List<String> MAIL_TYPE_PREFIXES =
      List.of(
          "jakarta.mail.",
          "org.springframework.mail.",
          "com.smartfirehub.settings.service.SmtpFailureMessages",
          "com.smartfirehub.settings.service.SmtpMailSenders");

  /** 검사에서 빼는 도우미 — 메일 코드 대신 Logger 를 부르는 유일한 곳(예외는 가린 사본만 붙인다). */
  private static final String HELPER = "com.smartfirehub.settings.service.SmtpFailureLog";

  /** 프로덕션 클래스 그래프 — 다른 아키텍처 규칙과 같은 옵션(테스트·jOOQ 생성 코드 제외, 끝나면 해제). */
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

  // ---------------------------------------------------------------------------------------------
  // 운영 코드 검사
  // ---------------------------------------------------------------------------------------------

  /** 메일 코드가 Logger 의 로깅 메서드를 직접 부르지 않는다(바이트코드) — 도우미 SmtpFailureLog 로만 남긴다. */
  @Test
  void mailCodeDoesNotCallLoggerDirectly() {
    assertThat(bytecodeViolations(PROD)).isEmpty();
  }

  /** 검사가 공허하게 통과하지 않는지 — 아는 메일 코드(리포트 메일·알림 메일)가 실제로 대상에 든다. */
  @Test
  void knownMailClassesAreInScope() {
    Set<String> scope = mailTopLevelNames(PROD);
    assertThat(scope)
        .contains(
            "com.smartfirehub.proactive.service.delivery.EmailDeliveryChannel",
            "com.smartfirehub.notification.channels.EmailChannel",
            "com.smartfirehub.settings.controller.SettingsController")
        .doesNotContain(HELPER);
  }

  // ---------------------------------------------------------------------------------------------
  // 검사 자체 검증 — 위반 예시
  // ---------------------------------------------------------------------------------------------

  /**
   * 바이트코드 검사가 메일 코드의 {@code log.warn("…", e)} 와 가변 인자 끝에 예외를 넣은 {@code log.warn("… {}", id, e)} 를
   * 모두 잡고(WD-693 — 예전 Throwable 판 검사가 놓치던 형태), 수준 확인은 놓아 두며, 메일과 무관한 클래스는 보지 않는다.
   */
  @Test
  void bytecodeCheckReportsLoggerCallsInMailClassOnly() {
    JavaClasses fixture =
        new ClassFileImporter()
            .importClasses(
                SmtpLogMailViolationFixture.class, SmtpLogNotMailFixture.class, Logger.class);

    List<String> violations = bytecodeViolations(fixture);

    assertThat(violations).hasSize(2);
    assertThat(violations)
        .allSatisfy(v -> assertThat(v).contains("SmtpLogMailViolationFixture").contains("warn"))
        .noneSatisfy(v -> assertThat(v).contains("isDebugEnabled"));
  }

  // ---------------------------------------------------------------------------------------------
  // 검사 구현
  // ---------------------------------------------------------------------------------------------

  /** 최상위 클래스 이름(중첩·익명 클래스는 바깥 최상위로 묶는다). */
  private static String topLevelName(JavaClass c) {
    String name = c.getName();
    int dollar = name.indexOf('$');
    return dollar >= 0 ? name.substring(0, dollar) : name;
  }

  /** 메일 타입에 의존하는 클래스의 최상위 이름 — 도우미는 뺀다. */
  private static Set<String> mailTopLevelNames(JavaClasses classes) {
    Set<String> out = new HashSet<>();
    for (JavaClass c : classes) {
      for (Dependency d : c.getDirectDependenciesFromSelf()) {
        String target = d.getTargetClass().getName();
        if (MAIL_TYPE_PREFIXES.stream().anyMatch(target::startsWith)) {
          out.add(topLevelName(c));
          break;
        }
      }
    }
    out.remove(HELPER);
    return out;
  }

  /** 메일 코드가 Logger 를 직접 부르는 곳(설명 문자열) — 허용하는 메서드({@link #isAllowedLoggerMethod})는 뺀다. */
  private static List<String> bytecodeViolations(JavaClasses classes) {
    Set<String> mail = mailTopLevelNames(classes);
    List<String> out = new ArrayList<>();
    for (JavaClass c : classes) {
      if (!mail.contains(topLevelName(c))) continue;
      for (JavaMethodCall call : c.getMethodCallsFromSelf()) {
        var target = call.getTarget();
        if (!target.getOwner().isAssignableTo(Logger.class)) continue;
        if (!isAllowedLoggerMethod(target.getName())) out.add(call.getDescription());
      }
    }
    return out;
  }

  /**
   * 메일 코드가 직접 불러도 되는 Logger 메서드 — 아무것도 남기지 않는 수준 확인과 이름 조회뿐이다. trace~error·{@code atXxx} 플루언트
   * API({@code setCause} 로 예외를 붙일 수 있다) 등 남기는 메서드는 모두 도우미를 거친다.
   */
  private static boolean isAllowedLoggerMethod(String name) {
    return name.equals("getName") || (name.startsWith("is") && name.endsWith("Enabled"));
  }
}

/**
 * 바이트코드 검사 위반 예시(WD-693) — 테스트 소스라 운영 그래프에는 들어가지 않는다. 검사는 최상위 클래스 단위로 메일 코드를 가르므로 테스트 클래스 안에 중첩하지
 * 않고 따로 둔다.
 *
 * <p>위반: 메일 타입에 의존하면서 Logger 를 직접 부른다(Throwable 판과 가변 인자 판 둘 다).
 */
@SuppressWarnings("unused")
final class SmtpLogMailViolationFixture {
  private static final Logger log = LoggerFactory.getLogger(SmtpLogMailViolationFixture.class);

  void send(org.springframework.mail.javamail.JavaMailSender sender, long id) {
    try {
      sender.send(sender.createMimeMessage());
    } catch (org.springframework.mail.MailException e) {
      log.warn("메일 발송 실패", e);
      // 가변 인자 끝의 예외 — warn(String, Object, Object) 호출이라 Throwable 판 검사로는 못 잡던 형태(WD-693)
      log.warn("메일 발송 실패 {}", id, e);
    }
    // 허용: 수준 확인은 아무것도 남기지 않는다
    if (log.isDebugEnabled()) {
      return;
    }
  }
}

/** 허용: 메일과 무관한 클래스의 Throwable 판 로깅은 이 검사의 대상이 아니다. */
@SuppressWarnings("unused")
final class SmtpLogNotMailFixture {
  private static final Logger log = LoggerFactory.getLogger(SmtpLogNotMailFixture.class);

  void run(Runnable r) {
    try {
      r.run();
    } catch (RuntimeException e) {
      log.warn("작업 실패", e);
    }
  }
}
