package com.smartfirehub.global.validation;

import com.smartfirehub.global.platformdb.ConditionalOnPostgresPlatformDb;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/**
 * PG 이름 비교 SQL({@code lower(trim(translate(...)))})에 바인딩할 공백 문자 집합을 서버 인코딩에 맞춰 고른다(WD-1162).
 *
 * <p>{@link TextRules#EDGE_SPACE_CHARS} 는 U+1680·U+2028 등 비 Latin-1 문자를 바인딩하므로 PG 서버 인코딩이 UTF8 이
 * 아니면 (LATIN1·EUC_KR 등) 바인딩이 실패한다(WD-1145 는 UTF8 을 전제했다). 그래서 기동 때({@link #afterSingletonsInstantiated}) 트랜잭션 밖에서 {@code
 * SHOW server_encoding} 을 한 번만 읽어 쌍을 캐시한다. 요청 경로({@link #translatePair})는 DB 를 절대 조회하지 않는다 — 리포지토리의
 * {@code @Transactional} 안에서 조회가 실패하면 PG 는 트랜잭션을 abort 시켜 이후 쿼리가 500 이 되기 때문이다. 조회가 실패했거나 아직 안 돌았으면
 * 지금 동작(유니코드 집합)을 쓴다.
 *
 * <p>주의: UTF8 이 아닌 인코딩에서는 ASCII 공백만 겹침으로 본다 — 앞뒤 NBSP·U+3000 등은 겹침으로 안 잡혀 중복 판정이 UTF8 보다 좁다. 그런 인코딩은 지원
 * 플랫폼 구성이 아니라 받아들인다.
 */
@Slf4j
@Component
@ConditionalOnPostgresPlatformDb
public class PgEdgeSpaceChars implements SmartInitializingSingleton {

  /** TRANSLATE 의 from·to 한 쌍 — 같은 해석에서 나와 길이가 항상 같다(길이가 다르면 PG 가 남는 문자를 지운다). */
  record TranslatePair(String from, String to) {}

  /** UTF8 서버(및 아직 못 읽었을 때)의 기본 쌍 — 유니코드 공백 전체. */
  private static final TranslatePair UNICODE_PAIR =
      new TranslatePair(TextRules.EDGE_SPACE_CHARS, TextRules.EDGE_SPACE_AS_ASCII);

  private final DSLContext dsl;

  /** 현재 쌍 — afterSingletonsInstantiated 가 비 UTF8 일 때만 ASCII 쌍으로 덮어쓰고 이후엔 읽기만 하므로 volatile 로 충분하다. */
  private volatile TranslatePair pair = UNICODE_PAIR;

  public PgEdgeSpaceChars(DSLContext dsl) {
    this.dsl = dsl;
  }

  /**
   * 모든 싱글톤을 만든 직후(웹 서버가 요청을 받기 전) 서버 인코딩을 트랜잭션 밖에서 한 번 읽는다. 실패하면 경고 한 번만 남기고 유니코드 쌍을 그대로
   * 쓴다.
   *
   * <p>WD-1162 리뷰 후속: 예전엔 ApplicationReadyEvent 에서 읽어, 웹 서버가 연 뒤 그 이벤트 전에 들어온 요청은 아직 유니코드 쌍이라 비 UTF8
   * PG 에서 500 이 날 수 있었다. 내장 웹 서버는 컨텍스트 refresh 마지막(finishRefresh)에 요청을 받기 시작하므로, 그 전에 불리는 이 콜백에서
   * 읽는다. 이 시점엔 트랜잭션이 없다.
   */
  @Override
  public void afterSingletonsInstantiated() {
    try {
      Object encoding = dsl.fetchValue("SHOW server_encoding");
      String value = encoding == null ? null : encoding.toString();
      if (!supportsUnicodeBinding(value)) {
        String ascii = TextRules.ASCII_EDGE_SPACE_CHARS;
        pair = new TranslatePair(ascii, " ".repeat(ascii.length()));
        log.warn("PG 서버 인코딩이 {} 라 유니코드 공백 비교를 ASCII 로 물러섬 (WD-1162)", value);
      }
    } catch (RuntimeException e) {
      log.warn("PG 서버 인코딩 조회 실패 — 유니코드 공백 집합을 그대로 씀 (WD-1162)", e);
    }
  }

  /**
   * 이름 비교 식 {@code lower(trim(translate(name, from, to)))} — 유니코드 공백을 ASCII 공백으로 바꿔 PG 의 TRIM(U+0020 만 걷음)이 걷게 한다.
   * from·to 는 한 번 읽은 쌍에서 함께 나온다. 요청 경로라 DB 를 조회하지 않는다.
   */
  public Field<String> normalize(Field<String> name) {
    TranslatePair p = pair;
    return DSL.lower(DSL.trim(DSL.translate(name, DSL.val(p.from()), DSL.val(p.to()))));
  }

  /** 테스트용 — 현재 쌍. */
  TranslatePair translatePair() {
    return pair;
  }

  /**
   * 이 서버 인코딩이 유니코드 공백 집합 바인딩을 안전하게 받는지. null(알 수 없음)은 지금 동작 유지로 true. 하이픈을 빼고 대소문자를 무시해 UTF8·UTF-8
   * 을 같게 본다. SQL_ASCII 는 false — translate 가 바이트 단위로 돌아 from 문자열(멀티바이트 UTF-8)이 to 문자열보다 길어지고, PG 가
   * 남는 바이트(한글 안에도 0x80·0xA0 이 있다)를 지워 거짓 중복 409 를 낸다.
   */
  static boolean supportsUnicodeBinding(String serverEncoding) {
    return serverEncoding == null || serverEncoding.replace("-", "").equalsIgnoreCase("UTF8");
  }
}
