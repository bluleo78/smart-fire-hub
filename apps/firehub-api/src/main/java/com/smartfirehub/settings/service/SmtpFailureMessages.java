package com.smartfirehub.settings.service;

import com.smartfirehub.apiconnection.service.AuthQueryMasker;
import jakarta.mail.AuthenticationFailedException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import javax.net.ssl.SSLException;
import org.springframework.mail.MailSendException;

/**
 * SMTP 실패를 관리자가 고칠 수 있는 한국어 안내로 바꾼다(WD-676). 리포트 메일 발송({@code EmailDeliveryChannel})과 설정 화면의 「연결
 * 테스트」({@code SettingsController#testSmtpSettings})가 같은 문구를 쓴다 — 연결 테스트의 안내가 실제 발송 실패 사유와 같아야 관리자가
 * 고친 뒤 확인할 수 있다.
 *
 * <p><b>인증서 문구.</b> STARTTLS 를 꺼도 서버가 광고하면 기회적 TLS 를 쓰고, 모든 TLS 모드에서 인증서 호스트 이름을 확인한다 ({@link
 * SmtpTlsPolicy}). 그래서 인증서 불일치는 「STARTTLS 를 끄라」가 아니라 호스트 이름이나 인증서를 고치라고 안내한다.
 */
public final class SmtpFailureMessages {

  /** 설정을 고치는 곳 — 웹 관리 › 설정 화면의 이메일 탭. */
  public static final String SETTINGS_PLACE = "관리 › 설정 › 이메일 탭";

  /** 일반 문구에 덧붙이는 서버 응답 조각의 최대 길이 — 긴 스택·HTML 오류 페이지가 토스트를 덮지 않게. */
  static final int MAX_DETAIL_LENGTH = 200;

  /** 비밀번호 자리에 넣는 가림 문자열 — API 키와 달리 끝 4자도 보이지 않는다. */
  private static final String PASSWORD_MASK = "****";

  private SmtpFailureMessages() {}

  /**
   * 분류 규칙 하나 — 원인 사슬의 어느 예외든 {@code matches} 면 {@code message}(호스트를 담은 「SMTP 서버(host)」 → 문구)를 쓴다.
   */
  private record Rule(Predicate<Throwable> matches, Function<String, String> message) {}

  /** 분류 규칙 — 구체적인 것부터 차례로 본다. 앞 규칙이 사슬의 어느 예외에든 맞으면 뒤 규칙은 보지 않는다. */
  private static final List<Rule> RULES =
      List.of(
          // 서버가 STARTTLS 를 지원하지 않는데 STARTTLS 가 켜져 있다.
          new Rule(
              t ->
                  messageContains(t, "starttls is required")
                      || messageContains(t, "does not support starttls"),
              where ->
                  where
                      + "가 STARTTLS 를 지원하지 않습니다 — "
                      + SETTINGS_PLACE
                      + "에서 STARTTLS 를 끄거나, 서버가 지원하면 465 포트(접속 즉시 TLS)를 쓰세요"),
          // 「530 5.7.0 Must issue a STARTTLS command first」 — 서버가 암호화를 요구하는데 STARTTLS 가 꺼져 있다.
          new Rule(
              t ->
                  messageContains(t, "starttls")
                      && (messageContains(t, "530") || messageContains(t, "must issue")),
              where ->
                  where
                      + "가 STARTTLS 암호화를 요구합니다 — "
                      + SETTINGS_PLACE
                      + "에서 STARTTLS 를 켜거나, 서버가 지원하면 465 포트(접속 즉시 TLS)를 쓰세요"),
          // 인증서·호스트 이름 문제: CertificateException(PKIX 실패·이름 불일치)이나 JavaMail 의 호스트 확인 실패 문구가 있을 때만.
          // 그 밖의 핸드셰이크 실패(TLS 버전·암호 방식 불일치, 상대가 끊음)는 다음 TLS 규칙으로 간다.
          new Rule(
              t ->
                  t instanceof CertificateException
                      || messageContains(t, "verify identity")
                      || messageContains(t, "no subject alternative")
                      || messageContains(t, "no name matching"),
              where ->
                  where
                      + "의 TLS 인증서를 확인하지 못했습니다 — 인증서가 호스트 이름과 맞지 않거나 신뢰할 수 없는 인증서입니다. "
                      + SETTINGS_PLACE
                      + "의 호스트를 인증서에 적힌 이름으로 고치거나, 서버 인증서를 신뢰할 수 있는 인증서로 바꾸세요"),
          new Rule(
              t -> t instanceof SSLException,
              where ->
                  where
                      + "와 TLS 연결을 맺지 못했습니다 — 서버가 지원하는 TLS 버전·암호화 방식이 맞지 않거나, 포트와 암호화 방식이 어긋났습니다. "
                      + SETTINGS_PLACE
                      + "에서 포트(465 는 접속 즉시 TLS, 587·25 는 STARTTLS)와 STARTTLS 설정을 확인하세요"),
          new Rule(
              t -> t instanceof AuthenticationFailedException,
              where -> where + " 인증에 실패했습니다 — " + SETTINGS_PLACE + "의 사용자 이름·비밀번호를 확인하세요"),
          new Rule(
              t -> t instanceof NumberFormatException,
              where -> "SMTP 포트가 숫자가 아닙니다 — " + SETTINGS_PLACE + "에서 포트를 고쳐 저장하세요"),
          new Rule(
              t ->
                  t instanceof UnknownHostException
                      || t instanceof ConnectException
                      || t instanceof SocketTimeoutException,
              where -> where + "에 연결하지 못했습니다 — " + SETTINGS_PLACE + "의 호스트·포트와 서버 상태를 확인하세요"));

  /**
   * 예외의 원인 사슬을 {@link #RULES} 순서로 분류해 한국어 안내를 만든다. 맞는 규칙이 없으면 서버 응답 조각(예: {@code 554 relay access
   * denied})을 덧붙인 일반 문구 — 조각은 공백을 접고 {@link #MAX_DETAIL_LENGTH} 자로 자르며 비밀번호(원문·Base64)를 지운다.
   *
   * @param error 발송·연결 테스트에서 난 예외(null 이면 일반 문구)
   * @param host 설정된 SMTP 호스트(문구용, null·빈 값이면 생략)
   * @param password 설정된 SMTP 비밀번호 — 조각에서 지울 값(null 가능)
   */
  public static String describe(Throwable error, String host, String password) {
    if (error == null) {
      return generic(null);
    }
    String where = host == null || host.isBlank() ? "SMTP 서버" : "SMTP 서버(" + host + ")";
    List<Throwable> chain = chain(error);
    for (Rule rule : RULES) {
      if (chain.stream().anyMatch(rule.matches())) {
        return rule.message().apply(where);
      }
    }
    return generic(detail(chain, password));
  }

  /** 분류하지 못한 실패의 일반 문구 — 서버 응답 조각이 있으면 덧붙인다. */
  private static String generic(String detail) {
    String head = "메일 발송에 실패했습니다";
    if (detail != null && !detail.isEmpty()) {
      head += "(" + detail + ")";
    }
    return head + " — " + SETTINGS_PLACE + "의 SMTP 설정과 서버 상태를 확인하세요";
  }

  /**
   * 원인 사슬에서 서버 응답에 가장 가까운(가장 깊은) 메시지를 골라 정리한다. 메시지가 없으면 그 예외의 클래스 이름. 비밀번호는 {@link
   * AuthQueryMasker#scrubSecret}(원문·URL 인코딩형)과 Base64(AUTH LOGIN 형태)로 지운다. 지운 뒤에도 남아 있으면(지우기엔 짧은 값)
   * 조각을 버리고 클래스 이름만 쓴다 — 비밀번호는 어떤 경우에도 문구에 나가지 않는다.
   */
  static String detail(List<Throwable> chain, String password) {
    Throwable pick = null;
    for (Throwable t : chain) {
      if (t.getMessage() != null && !t.getMessage().isBlank()) pick = t;
    }
    if (pick == null) return chain.get(chain.size() - 1).getClass().getSimpleName();
    String text = pick.getMessage().replaceAll("\\s+", " ").strip();
    if (password != null && !password.isEmpty()) {
      text = AuthQueryMasker.scrubSecret(text, password, PASSWORD_MASK);
      String base64 = Base64.getEncoder().encodeToString(password.getBytes(StandardCharsets.UTF_8));
      text = text.replace(base64, PASSWORD_MASK);
      if (text.contains(password)) return pick.getClass().getSimpleName();
    }
    if (text.length() > MAX_DETAIL_LENGTH) {
      text = text.substring(0, MAX_DETAIL_LENGTH) + "…";
    }
    return text;
  }

  /**
   * 원인 사슬을 넓게 모은다 — {@code getCause()} 와 함께 Spring {@link MailSendException} 의 메시지별 예외도 따라간다({@code
   * send()} 실패는 원인을 cause 가 아니라 메시지별 예외 맵에 담는다). 순환은 동일성으로 끊는다.
   */
  private static List<Throwable> chain(Throwable error) {
    List<Throwable> out = new ArrayList<>();
    Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    Deque<Throwable> todo = new ArrayDeque<>();
    todo.add(error);
    while (!todo.isEmpty()) {
      Throwable t = todo.poll();
      if (t == null || !seen.add(t)) continue;
      out.add(t);
      if (t.getCause() != null) todo.add(t.getCause());
      if (t instanceof MailSendException mse) {
        Collections.addAll(todo, mse.getMessageExceptions());
      }
    }
    return out;
  }

  private static boolean messageContains(Throwable t, String needle) {
    String m = t.getMessage();
    return m != null && m.toLowerCase(Locale.ROOT).contains(needle);
  }
}
