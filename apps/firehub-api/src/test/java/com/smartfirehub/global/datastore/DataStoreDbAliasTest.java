package com.smartfirehub.global.datastore;

import static org.junit.jupiter.api.Assertions.assertSame;

import com.smartfirehub.support.IntegrationTestBase;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 기본 모드(firehub.data-store.postgres.separate 미설정)에서 {@code @DataStoreDb} 빈이 {@code @Primary} 빈과 같은
 * 인스턴스인지 검증한다. 두 번째 풀이 생기면 PG 설치본의 트랜잭션 경계·풀 크기가 바뀌므로 반드시 별칭이어야 한다(WD-494).
 */
class DataStoreDbAliasTest extends IntegrationTestBase {

  @Autowired private DataSource primaryDataSource;
  @Autowired private DSLContext primaryDsl;
  @Autowired @DataStoreDb private DataSource dataStoreDataSource;
  @Autowired @DataStoreDb private DSLContext dataStoreDsl;

  @Test
  void 기본_모드에서_데이터저장소_DataSource는_Primary_빈과_같은_인스턴스다() {
    assertSame(primaryDataSource, dataStoreDataSource);
  }

  @Test
  void 기본_모드에서_데이터저장소_DSLContext는_Primary_빈과_같은_인스턴스다() {
    assertSame(primaryDsl, dataStoreDsl);
  }
}
