package com.smartfirehub.settings.service;

import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.event.Level;

/**
 * 메일(SMTP) 코드가 로그를 남기는 유일한 창구(WD-693).
 *
 * <p><b>왜.</b> 메일 예외는 서버 응답 원문(AUTH 단계에서 되울린 비밀번호·AUTH PLAIN 자격 증명)을 담을 수 있다. 발송 지점은 실패를 가린
 * 실패({@link SmtpFailureMessages.CredentialMasker#callMasked})로 바꿔 던지지만, 새 코드가 {@code log.warn("…",
 * e)} 로 다른 경로의 원래 예외를 넘기면 원문이 남는다. 그래서 메일 코드(메일 API 에 의존하는 클래스)는 {@link Logger} 를 직접 부르지 않고 이 도우미만
 * 부른다 — 아키텍처 검사({@code SmtpFailureLoggingArchitectureTest})가 바이트코드로 막는다.
 *
 * <ul>
 *   <li>예외를 붙이는 판({@link #warn(Logger, SmtpFailureMessages.CredentialMasker, Throwable, String,
 *       Object...)} 등): 원래 예외 대신 늘 가린 사본({@link SmtpFailureMessages.CredentialMasker#maskedCopy})을
 *       스택으로 붙이고 그 사본을 돌려준다 — 결과 cause·다시 던질 예외의 cause 로 그대로 써서 같은 실패에 사본을 두 번 만들지 않게.
 *   <li>예외 없는 판({@link #info(Logger, String, Object...)} 등): 인자에 예외가 섞여 오면 클래스 이름으로 바꿔 스택·메시지가 로그에
 *       실리지 않게 한다.
 * </ul>
 */
public final class SmtpFailureLog {

  private SmtpFailureLog() {}

  /**
   * warn 으로 남긴다 — {@code format}·{@code args} 는 SLF4J 형식 그대로이고, 가린 사본을 마지막 인자(스택)로 붙인다.
   *
   * @param masker 이 SMTP 설정의 마스커
   * @param error 원래 예외(이미 가린 실패여도 된다) — 원래 예외는 로그에 실리지 않는다
   * @return 가린 사본(error 가 null 이면 null)
   */
  public static SmtpFailureMessages.MaskedSmtpException warn(
      Logger log,
      SmtpFailureMessages.CredentialMasker masker,
      Throwable error,
      String format,
      Object... args) {
    return logMasked(log, Level.WARN, masker, error, format, args);
  }

  /**
   * error 로 남긴다 — {@link #warn(Logger, SmtpFailureMessages.CredentialMasker, Throwable, String,
   * Object...)} 과 같은 규칙.
   */
  public static SmtpFailureMessages.MaskedSmtpException error(
      Logger log,
      SmtpFailureMessages.CredentialMasker masker,
      Throwable error,
      String format,
      Object... args) {
    return logMasked(log, Level.ERROR, masker, error, format, args);
  }

  /** 예외 없이 info 로 남긴다 — 인자의 예외는 클래스 이름으로 바꾼다(WD-693). */
  public static void info(Logger log, String format, Object... args) {
    emit(log, Level.INFO, format, withoutThrowables(args));
  }

  /** 예외 없이 warn 으로 남긴다 — 인자의 예외는 클래스 이름으로 바꾼다(WD-693). */
  public static void warn(Logger log, String format, Object... args) {
    emit(log, Level.WARN, format, withoutThrowables(args));
  }

  /** 예외 없이 error 로 남긴다 — 인자의 예외는 클래스 이름으로 바꾼다(WD-693). */
  public static void error(Logger log, String format, Object... args) {
    emit(log, Level.ERROR, format, withoutThrowables(args));
  }

  /** 가린 사본을 만들어 마지막 인자(스택)로 붙여 남기고 사본을 돌려준다 — warn·error 판이 함께 쓴다(WD-693). */
  private static SmtpFailureMessages.MaskedSmtpException logMasked(
      Logger log,
      Level level,
      SmtpFailureMessages.CredentialMasker masker,
      Throwable error,
      String format,
      Object[] args) {
    SmtpFailureMessages.MaskedSmtpException masked = masker.maskedCopy(error);
    emit(log, level, format, withThrowable(withoutThrowables(args), masked));
    return masked;
  }

  /** 수준에 맞는 Logger 메서드를 부른다 — 이 도우미 밖에서는 메일 코드가 Logger 를 부르지 않는다. */
  private static void emit(Logger log, Level level, String format, Object[] args) {
    switch (level) {
      case ERROR -> log.error(format, args);
      case WARN -> log.warn(format, args);
      default -> log.info(format, args);
    }
  }

  /** 형식 인자 가운데 예외를 클래스 이름으로 바꾼 사본 — 가리지 않은 예외가 형식 인자로 몰래 실리지 않게(WD-693). */
  private static Object[] withoutThrowables(Object[] args) {
    if (args == null) return new Object[0];
    Object[] out = args.clone();
    for (int i = 0; i < out.length; i++) {
      if (out[i] instanceof Throwable t) out[i] = t.getClass().getName();
    }
    return out;
  }

  /** 형식 인자 뒤에 사본을 붙인다 — SLF4J 는 마지막 인자가 Throwable 이고 자리표시자보다 많으면 스택으로 찍는다. */
  private static Object[] withThrowable(Object[] args, Throwable masked) {
    if (masked == null) return args;
    Object[] out = Arrays.copyOf(args, args.length + 1);
    out[args.length] = masked;
    return out;
  }
}
