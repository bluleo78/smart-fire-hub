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
   * 예외의 원인 사슬을 {@link #RULES} 순서로 분류해 한국어 안내를 만든다 — {@code forCredentials(host, username,
   * password).describe(error)} 와 같다. 설정을 한 번 읽고 여러 번 쓰는 호출부는 {@link #forCredentials} 로 마스커를 한 번만
   * 만든다.
   *
   * @param error 발송·연결 테스트에서 난 예외(null 이면 일반 문구)
   * @param host 설정된 SMTP 호스트(문구용, null·빈 값이면 생략)
   * @param username 설정된 SMTP 사용자 이름 — AUTH PLAIN 자격 증명 덩어리를 지우는 데 쓴다(null 가능)
   * @param password 설정된 SMTP 비밀번호 — 조각에서 지울 값(null 가능)
   */
  public static String describe(Throwable error, String host, String username, String password) {
    return forCredentials(host, username, password).describe(error);
  }

  /**
   * SMTP 설정 값으로 실패 문구·가린 예외 사본을 만드는 마스커(WD-686). 지울 형태(비밀번호 원문·URL 인코딩형·Base64, AUTH PLAIN 덩어리 두
   * 가지)는 여기서 한 번만 계산한다.
   *
   * @param host 문구에 넣을 SMTP 호스트(null·빈 값이면 생략)
   * @param username SMTP 사용자 이름(null 이면 빈 사용자 이름의 AUTH PLAIN 덩어리만 지운다)
   * @param password SMTP 비밀번호(null·빈 값이면 지울 것이 없다)
   */
  public static CredentialMasker forCredentials(String host, String username, String password) {
    return new CredentialMasker(host, username, password);
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
   * SMTP 실패를 비밀 없이 바꾸는 마스커(WD-686, {@link #forCredentials}) — 분류 문구({@link #describe(Throwable)})와
   * 가린 예외 사본({@link #maskedCopy(Throwable)})이 같은 지우기 규칙을 쓴다.
   *
   * <p><b>지우는 것.</b> 비밀번호 원문·URL 인코딩형({@link AuthQueryMasker#scrubberFor}), Base64(AUTH LOGIN 형태),
   * AUTH PLAIN 자격 증명 덩어리 {@code Base64("\0" + user + "\0" + password)}(authzid 없음)·{@code
   * Base64(user + "\0" + user + "\0" + password)} (authzid=user). PLAIN 덩어리는 비밀번호 원문·Base64 가 아니라
   * 앞의 가림에 걸리지 않는데, 디코딩하면 비밀번호가 그대로 나온다. 지운 뒤에도 원문이 남으면(최소 길이 미만이라 지우지 않은 짧은 값) 그 문장을 통째로 버린다 —
   * 비밀번호는 어떤 경우에도 나가지 않는다.
   */
  public static final class CredentialMasker {

    private final String where;
    private final String password;
    private final java.util.function.UnaryOperator<String> plainScrubber;

    /** 긴 덩어리부터 — 「\0user\0」 처럼 앞 조각이 3바이트 경계에 맞으면 비밀번호만의 Base64 가 PLAIN 덩어리의 끝부분과 같다. */
    private final List<String> base64Forms;

    private CredentialMasker(String host, String username, String password) {
      this.where = host == null || host.isBlank() ? "SMTP 서버" : "SMTP 서버(" + host + ")";
      this.password = password == null || password.isEmpty() ? null : password;
      this.plainScrubber = AuthQueryMasker.scrubberFor(this.password, PASSWORD_MASK);
      String user = username == null ? "" : username;
      this.base64Forms =
          this.password == null
              ? List.of()
              : List.of(
                      user + "\0" + user + "\0" + this.password,
                      "\0" + user + "\0" + this.password,
                      this.password)
                  .stream()
                  .map(c -> Base64.getEncoder().encodeToString(c.getBytes(StandardCharsets.UTF_8)))
                  .toList();
    }

    /**
     * 원인 사슬을 {@link #RULES} 순서로 분류해 한국어 안내를 만든다. 맞는 규칙이 없으면 서버 응답 조각(예: {@code 554 relay access
     * denied})을 덧붙인 일반 문구 — 조각은 공백을 접고 {@link #MAX_DETAIL_LENGTH} 자로 자르며 비밀을 지운다.
     */
    public String describe(Throwable error) {
      if (error == null) {
        return generic(null);
      }
      List<Throwable> chain = chain(error);
      for (Rule rule : RULES) {
        if (chain.stream().anyMatch(rule.matches())) {
          return rule.message().apply(where);
        }
      }
      return generic(detail(chain));
    }

    /** 원인 사슬에서 서버 응답에 가장 가까운(가장 깊은) 메시지를 골라 정리한다. 메시지가 없거나 비밀을 다 지우지 못했으면 그 예외의 클래스 이름만 쓴다. */
    private String detail(List<Throwable> chain) {
      Throwable pick = null;
      for (Throwable t : chain) {
        if (t.getMessage() != null && !t.getMessage().isBlank()) pick = t;
      }
      if (pick == null) return chain.get(chain.size() - 1).getClass().getSimpleName();
      String text = scrub(pick.getMessage().replaceAll("\\s+", " ").strip());
      if (text == null) return pick.getClass().getSimpleName();
      if (text.length() > MAX_DETAIL_LENGTH) {
        text = text.substring(0, MAX_DETAIL_LENGTH) + "…";
      }
      return text;
    }

    /** 문장에서 비밀을 지운다. 지운 뒤에도 비밀번호 원문이 남으면 null — 호출부가 문장을 버린다. */
    private String scrub(String text) {
      if (text == null || password == null) return text;
      String out = plainScrubber.apply(text);
      for (String form : base64Forms) {
        out = out.replace(form, PASSWORD_MASK);
      }
      return out.contains(password) ? null : out;
    }

    /**
     * 예외를 비밀을 가린 사본으로 바꾼다(WD-686) — 발송 실패를 다시 던질 때 cause 나 로그 스택에 원래 예외 대신 붙인다.
     *
     * <p><b>왜.</b> 원래 예외는 서버 응답 원문을 메시지에 담는다(AUTH 단계 응답에 비밀번호·자격 증명이 되울려 오기도 한다). 그대로 cause 로 붙이면
     * 호출부가 스택을 찍는 순간 원문이 남고, cause 를 버리면 디버깅 단서가 사라진다.
     *
     * <p><b>사본이 지키는 것.</b> 사슬의 예외마다 원래 클래스 이름(메시지 앞)·비밀을 지운 메시지·원래 스택 위치. 따라가는 연결은 {@link
     * #links}(cause·suppressed·MailSendException 메시지별 예외)로 분류 사슬과 같다 — 사본에서 cause 는 cause, 나머지는
     * suppressed 다. 원래 예외 객체는 사본 어디에도 붙지 않는다. 순환하는 사슬은 동일성으로 끊어 사본도 같은 모양으로 순환한다.
     *
     * @param error 원래 예외(null 이면 null)
     */
    public MaskedSmtpException maskedCopy(Throwable error) {
      return maskedCopy(error, new IdentityHashMap<>());
    }

    private MaskedSmtpException maskedCopy(
        Throwable t, java.util.Map<Throwable, MaskedSmtpException> copies) {
      if (t == null) return null;
      MaskedSmtpException existing = copies.get(t);
      if (existing != null) return existing;
      String message = t.getMessage();
      String masked = scrub(message);
      if (message != null && masked == null) {
        // 지우지 못한 짧은 비밀번호가 남았다 — 메시지를 통째로 뺀다.
        masked = "(비밀번호가 들어 있어 메시지를 뺐습니다)";
      }
      MaskedSmtpException copy = new MaskedSmtpException(t.getClass().getName(), masked);
      copy.setStackTrace(t.getStackTrace());
      // 먼저 등록해야 순환 사슬에서 같은 사본을 다시 쓴다.
      copies.put(t, copy);
      Links links = links(t);
      if (links.cause() != null) {
        copy.initCause(maskedCopy(links.cause(), copies));
      }
      for (Throwable other : links.others()) {
        MaskedSmtpException sc = maskedCopy(other, copies);
        // 자기 자신을 suppressed 로 붙일 수는 없다(순환에서 돌아온 경우).
        if (sc != null && sc != copy) copy.addSuppressed(sc);
      }
      return copy;
    }
  }

  /**
   * 비밀을 가린 예외 사본(WD-686, {@link CredentialMasker#maskedCopy}). 메시지 앞에 원래 클래스 이름을 붙인다({@code 원래클래스:
   * 가린 메시지}) — 로그 스택의 머리줄·「Caused by」는 로깅 라이브러리마다 찍는 방법이 달라(Logback 은 {@code toString()} 이 아니라
   * {@code getClass().getName()} 과 메시지를 찍는다) 메시지에 넣어야 어디서나 원래 예외가 무엇이었는지 보인다. 원래 예외 타입은 아니므로 {@code
   * instanceof} 로 분류하는 코드에는 원래 예외를 넘긴다 — 이 사본은 다시 던지거나 로그에 붙이는 데만 쓴다.
   */
  public static final class MaskedSmtpException extends RuntimeException {

    MaskedSmtpException(String originalClassName, String maskedMessage) {
      super(maskedMessage != null ? originalClassName + ": " + maskedMessage : originalClassName);
    }
  }

  /** 예외에서 이어지는 예외들 — cause 와 그 밖(suppressed·MailSendException 메시지별 예외). */
  private record Links(Throwable cause, List<Throwable> others) {}

  /**
   * 예외의 연결(WD-686) — 분류 사슬({@link #chain})과 가린 사본({@link CredentialMasker#maskedCopy})이 함께 써서, 한쪽만
   * 따라가는 연결이 생기지 않게 한다. Spring {@link MailSendException} 은 {@code send()} 실패 원인을 cause 가 아니라 메시지별
   * 예외 맵에 담는다.
   */
  private static Links links(Throwable t) {
    List<Throwable> others = new ArrayList<>(List.of(t.getSuppressed()));
    if (t instanceof MailSendException mse) {
      others.addAll(List.of(mse.getMessageExceptions()));
    }
    return new Links(t.getCause(), others);
  }

  /** 원인 사슬을 넓게 모은다 — {@link #links} 를 따라간다. 순환은 동일성으로 끊는다. */
  private static List<Throwable> chain(Throwable error) {
    List<Throwable> out = new ArrayList<>();
    Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    Deque<Throwable> todo = new ArrayDeque<>();
    todo.add(error);
    while (!todo.isEmpty()) {
      Throwable t = todo.poll();
      if (t == null || !seen.add(t)) continue;
      out.add(t);
      Links links = links(t);
      if (links.cause() != null) todo.add(links.cause());
      todo.addAll(links.others());
    }
    return out;
  }

  private static boolean messageContains(Throwable t, String needle) {
    String m = t.getMessage();
    return m != null && m.toLowerCase(Locale.ROOT).contains(needle);
  }
}
