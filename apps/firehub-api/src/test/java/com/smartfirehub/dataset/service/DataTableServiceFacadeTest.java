package com.smartfirehub.dataset.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 파사드({@link DataTableService}) → 저장소 위임(WD-72)이 호출자 트랜잭션 경계를 바꾸지 않음을 고정한다.
 *
 * <p>클래스 레벨 {@code @Transactional} 을 쓰지 않는다 — 테스트 자신이 트랜잭션을 열고 롤백시켜야 DDL 이 호출자 트랜잭션에 묶였는지 알 수 있다.
 */
class DataTableServiceFacadeTest extends IntegrationTestBase {

  @Autowired private DataTableService dataTableService;
  @Autowired private DSLContext dsl;
  @Autowired private PlatformTransactionManager transactionManager;

  @Test
  void 호출자_트랜잭션이_롤백되면_파사드로_만든_테이블도_사라진다() {
    var transactionTemplate = new TransactionTemplate(transactionManager);
    String table = "facade_tx_" + System.nanoTime();
    assertThatThrownBy(
            () ->
                transactionTemplate.executeWithoutResult(
                    s -> {
                      dataTableService.createTable(
                          table,
                          List.of(
                              new DatasetColumnRequest(
                                  "name", "Name", "TEXT", null, true, false, null)));
                      throw new RuntimeException("rollback");
                    }))
        .hasMessage("rollback");
    Integer count =
        dsl.fetchOne(
                "select count(*) from information_schema.tables where table_schema = ? and table_name = ?",
                DataSchema.forTenant(DEFAULT_TEST_TENANT_ID),
                table)
            .get(0, Integer.class);
    assertThat(count).isZero();
  }
}
