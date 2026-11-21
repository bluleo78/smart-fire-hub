package com.smartfirehub.embedding;

/** 임베딩 생성 실패 (서비스 다운, 차원 불일치 등). 조용한 폴백 금지 — 명시적 전파. */
public class EmbeddingException extends RuntimeException {

  /**
   * 원인 분석 로그의 재료가 되는 원 예외(WD-873). cause 로 달지 않고 비공개로만 들고 있다 — cause 로 달면 상위 계층(예: 전역 예외 처리기)이
   * 스택트레이스를 찍을 때 가리지 않은 원문(URL 쿼리·토큰)이 로그에 섞인다. 직렬화 대상이 아니라 transient.
   */
  private final transient Throwable raw;

  /** {@link #logDetail()} 결과 캐시 — 처음 부를 때 한 번만 만든다. */
  private String logDetail;

  /** 이미 로그에 남겼는지 — 호출 지점마다 같은 실패를 다시 남기지 않게 한다. */
  private volatile boolean logged;

  public EmbeddingException(String message) {
    super(message);
    this.raw = null;
  }

  public EmbeddingException(String message, Throwable cause) {
    super(message, cause);
    this.raw = null;
  }

  /** cause 없이 만든다(raw 는 비공개 보관) — 인자 순서를 공개 생성자 (String, Throwable)(cause 를 다는 쪽)와 달리 둔다. */
  private EmbeddingException(Throwable raw, String message) {
    super(message);
    this.raw = raw;
  }

  /**
   * WD-873 용 팩토리 — cause 없이 만들고 원 예외는 비공개로만 보관한다. 다른 패키지(설정·행 검색)도 같은 규칙으로 가려진 예외를 만들 수 있게 public.
   */
  public static EmbeddingException redacted(String message, Throwable raw) {
    return new EmbeddingException(raw, message);
  }

  /** 로그용 상세 한 줄 — 원 예외가 있으면 가려서 처음 한 번만 만들고, 없으면 메시지를 쓴다. */
  public synchronized String logDetail() {
    if (raw == null) {
      return getMessage();
    }
    if (logDetail == null) {
      logDetail = EmbeddingFailureMessages.logDetail(raw);
    }
    return logDetail;
  }

  /** 이미 로그에 남긴 실패인지 — 연결 테스트가 같은 실패를 두 번 남기지 않으려고 본다(WD-873). */
  public boolean isLogged() {
    return logged;
  }

  /** 로그에 남겼다고 표시한다 — EmbeddingFailureLog 가 줄을 쓴 뒤 부른다(WD-873). */
  public void markLogged() {
    this.logged = true;
  }
}
