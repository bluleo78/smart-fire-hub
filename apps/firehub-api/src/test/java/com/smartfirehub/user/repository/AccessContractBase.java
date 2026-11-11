package com.smartfirehub.user.repository;

import com.smartfirehub.support.IntegrationTestBase;
import java.util.UUID;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * 사용자·역할·권한 리포지토리 계약 테스트의 공통 뼈대(WD-706). 픽스처는 {@link AccessFixtures} seam 으로 모으고, 기본은 PG 구현이다 —
 * Tibero 하위 클래스가 {@link #newFixtures()} 만 바꿔 소유자 계정 구현으로 갈아 낀다.
 */
public abstract class AccessContractBase extends IntegrationTestBase {

  @Autowired private DSLContext dsl;

  /** 정책을 우회하는 PG 소유자 커넥션 — 테넌트와 무관한 실제 행 확인용(픽스처만 쓴다). */
  @Autowired
  @Qualifier("schemaOwnerDataSource")
  private DataSource schemaOwnerDataSource;

  /** 이 시험이 쓰는 픽스처(시험마다 새로 만들고 끝나면 지운다). */
  protected AccessFixtures fx;

  /** 실행마다 고유한 접두 — 공유 DB 의 유일 키(사용자명·권한 코드)가 겹치지 않게 한다. */
  protected String prefix;

  /** 기본 구현(PG). */
  protected AccessFixtures newFixtures() {
    return new PgAccessFixtures(dsl, fixtureTransactionTemplate, schemaOwnerDataSource);
  }

  /**
   * 빈 문자열이 NULL 로 저장되는 DB 인가(controller Ruling) — Tibero 가 그렇다. 값을 변환하지 않으므로 NULL 허용 열은 null 로 돌아오고
   * NOT NULL 열은 거절된다(DataIntegrityViolationException 계열). PG 는 빈 문자열을 그대로 저장·조회한다. 계약 테스트가 DB 별
   * 기대값을 이 훅으로 가른다.
   */
  protected boolean emptyStringIsNull() {
    return fx.emptyStringIsNull();
  }

  @BeforeEach
  void initFixtures() {
    fx = newFixtures();
    prefix = "ac" + UUID.randomUUID().toString().substring(0, 8);
  }

  @AfterEach
  void cleanFixtures() {
    fx.cleanup();
  }
}
