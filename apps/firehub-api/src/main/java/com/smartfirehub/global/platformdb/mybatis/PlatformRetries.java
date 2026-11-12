package com.smartfirehub.global.platformdb.mybatis;

import java.util.function.Supplier;
import org.springframework.dao.DuplicateKeyException;

/**
 * MyBatis 리포지토리의 「중복 키면 한 번만 다시 시도」 헬퍼(WD-711).
 *
 * <p>왜 있는가: Tibero 에는 ON CONFLICT 가 없어 UPSERT·「없으면 넣기」를 MERGE 로 쓰는데, 같은 키가 동시에 처음 들어오면 두 MERGE 가 함께
 * NOT MATCHED 로 보고 한쪽이 중복 키(-10007)로 실패한다(문장 단위 실패라 트랜잭션은 산다). 한 번 다시 돌면 이미 들어간 행을 만나 MATCHED 쪽으로
 * 가므로 PG 의 ON CONFLICT 와 같은 결과가 된다. 리포지토리마다 복사돼 있던 try/catch 를 한 곳으로 모았다. 두 번째도 실패하면 예외를 그대로
 * 올린다(삼키지 않는다). 중복 키를 「이미 있음·획득 실패」로 해석하는 자리(임대 등)는 이 헬퍼를 쓰지 않는다.
 */
public final class PlatformRetries {

  private PlatformRetries() {}

  /** 문장을 실행하고, 중복 키 예외면 한 번만 다시 실행해 그 결과를 돌려준다. */
  public static <T> T onceOnDuplicateKey(Supplier<T> statement) {
    try {
      return statement.get();
    } catch (DuplicateKeyException e) {
      return statement.get();
    }
  }

  /** 결과 없는 문장판 — 중복 키 예외면 한 번만 다시 실행한다. */
  public static void runOnceOnDuplicateKey(Runnable statement) {
    onceOnDuplicateKey(
        () -> {
          statement.run();
          return null;
        });
  }
}
