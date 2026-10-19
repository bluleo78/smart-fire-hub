package com.smartfirehub.dataset.storage;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

/**
 * 외부 저장소 계정 비밀번호 생성기(WD-73).
 *
 * <p>24자, 영문 대소문자+숫자만, 첫 글자는 영문이다. 특수문자를 빼는 이유는 SQL 에서 {@code IDENTIFIED BY "…"} 로 큰따옴표 인용해 넣을 때
 * 이스케이프 문제가 생기지 않게 하기 위함이고, 첫 글자를 영문으로 고정하는 이유는 일부 DB 가 숫자로 시작하는 비밀번호를 거부하기 때문이다.
 */
@Component
public class DataStorePasswordGenerator {

  private static final String LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
  private static final String ALNUM = LETTERS + "0123456789";
  private static final int LENGTH = 24;

  private final SecureRandom random;

  /** 운영용: 암호학적으로 안전한 난수원을 쓴다. */
  public DataStorePasswordGenerator() {
    this(new SecureRandom());
  }

  /** 테스트용: 난수원을 주입한다. */
  DataStorePasswordGenerator(SecureRandom random) {
    this.random = random;
  }

  /** 규칙에 맞는 비밀번호 한 개를 만든다. */
  public String generate() {
    StringBuilder sb = new StringBuilder(LENGTH);
    sb.append(LETTERS.charAt(random.nextInt(LETTERS.length())));
    for (int i = 1; i < LENGTH; i++) {
      sb.append(ALNUM.charAt(random.nextInt(ALNUM.length())));
    }
    return sb.toString();
  }
}
