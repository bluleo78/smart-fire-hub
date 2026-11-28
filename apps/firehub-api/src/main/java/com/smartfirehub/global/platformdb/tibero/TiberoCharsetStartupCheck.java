package com.smartfirehub.global.platformdb.tibero;

import com.smartfirehub.global.platformdb.ConditionalOnTiberoPlatformDb;
import com.smartfirehub.global.platformdb.tibero.TiberoCharsetCheck.Result;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 기동 때 Tibero DB 문자셋이 UTF-8 인지 한 번 확인하고, 아니면 경고만 남긴다(WD-1182).
 *
 * <p>UTF-8 이 아닌 Tibero 는 문자셋 밖 글자를 '?' 로 저장해, 앞뒤 특수 공백·문자셋 밖 글자가 든 이름의 중복 검사가 좁아진다(스마트 작업은 DB 유니크
 * 제약도 없다). 운영은 UTF-8 을 전제하고 비 UTF-8 은 지원 구성이 아니다(PG 의 WD-1162 와 같은 취급).
 *
 * <p>막지 않는 이유: 이미 그 DB 로 돌고 있는 곳이 이 검사 때문에 기동하지 못하면 안 된다는 사람 결정(WD-1182)이다. 그래서 비 UTF-8·판정 불가·조회 실패
 * 모두 WARN 만 남기고 기동은 계속한다. {@link SmartInitializingSingleton} 을 쓰는 이유는 {@code PgEdgeSpaceChars} 와 같다
 * — 웹 서버가 요청을 받기 전, 트랜잭션 밖에서 한 번만 읽기 위해서다.
 */
@Slf4j
@Component
@ConditionalOnTiberoPlatformDb
public class TiberoCharsetStartupCheck implements SmartInitializingSingleton {

  private final JdbcTemplate jdbc;

  /** 마지막 판정 — 시험용. 아직 안 돌았거나 조회가 실패하면 UNKNOWN. */
  private volatile Result lastResult = Result.UNKNOWN;

  /**
   * 플랫폼 DataSource 로 JdbcTemplate 을 만든다.
   *
   * <p>{@link Autowired} 가 필요한 이유: 생성자가 둘(이것과 시험용 패키지 생성자)이라 표시가 없으면 스프링이 기본 생성자를 찾다 Tibero 기동이 멈춘다
   * — WD-724 와 같은 실패의 재발 방지다.
   */
  @Autowired
  public TiberoCharsetStartupCheck(DataSource dataSource) {
    this(new JdbcTemplate(dataSource));
  }

  /** 시험용 — JdbcTemplate 을 직접 받는다. */
  TiberoCharsetStartupCheck(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** 모든 싱글톤을 만든 직후 문자셋을 확인한다. 어떤 경우에도 예외를 던지지 않는다. */
  @Override
  public void afterSingletonsInstantiated() {
    try {
      Result result = TiberoCharsetCheck.probe(jdbc);
      lastResult = result;
      if (result == Result.NOT_UTF8) {
        log.warn(
            "Tibero DB 문자셋이 UTF-8 이 아님 — 지원하지 않는 구성: 문자셋 밖 글자는 '?' 로 저장되고"
                + " 앞뒤 특수 공백·문자셋 밖 글자 이름은 중복 검사가 좁아짐 (WD-1182)");
      } else if (result == Result.UNKNOWN) {
        log.warn("Tibero DB 문자셋 확인 결과를 알 수 없음 (WD-1182)");
      }
    } catch (RuntimeException e) {
      lastResult = Result.UNKNOWN;
      log.warn("Tibero DB 문자셋 확인 실패 — 기동은 계속 (WD-1182)", e);
    }
  }

  /** 테스트용 — 마지막 판정. */
  Result lastResult() {
    return lastResult;
  }
}
