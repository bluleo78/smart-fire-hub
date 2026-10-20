package com.smartfirehub.dataset.storage;

/**
 * 데이터셋 행 저장소의 배치 크기 상수(WD-74).
 *
 * <p>삽입·upsert 를 몇 행 단위로 끊어 {@code executeBatch} 할지를 백엔드가 같은 값으로 쓰도록 한 곳에 둔다. PostgreSQL 구현은 기존
 * 리터럴(500)을 그대로 두고 있으며 값은 같다.
 */
public final class DataStoreBatching {

  /** 한 청크(배치)의 행 수. */
  public static final int CHUNK = 500;

  private DataStoreBatching() {}
}
