package com.smartfirehub.dataset.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 파사드({@link DataTableRowService}) → 행 스토어 위임(WD-72)이 오버로드 동작과 호출자 트랜잭션 경계를 바꾸지 않음을 고정한다.
 *
 * <p>클래스 레벨 {@code @Transactional} 을 쓰지 않는다 — 롤백 테스트는 테스트 자신이 트랜잭션을 열어야 하므로, 동등성 테스트만 메서드 레벨로
 * 트랜잭션을 건다.
 */
class DataTableRowServiceFacadeTest extends IntegrationTestBase {

  @Autowired private DataTableRowService dataTableRowService;
  @Autowired private DataTableService dataTableService;
  @Autowired private PlatformTransactionManager transactionManager;

  private static final List<DatasetColumnRequest> COLUMNS =
      List.of(new DatasetColumnRequest("name", "Name", "TEXT", null, true, false, null));

  private static List<Map<String, Object>> twoRows() {
    return List.of(Map.of("name", "a"), Map.of("name", "b"));
  }

  @Test
  @Transactional
  void 짧은_오버로드와_긴_오버로드는_같은_결과를_만든다() {
    String shortTable = "facade_rows_s_" + System.nanoTime();
    String longTable = "facade_rows_l_" + System.nanoTime();
    dataTableService.createTable(shortTable, COLUMNS);
    dataTableService.createTable(longTable, COLUMNS);

    dataTableRowService.insertBatch(shortTable, List.of("name"), twoRows());
    dataTableRowService.insertBatch(longTable, List.of("name"), twoRows(), Map.of("name", "TEXT"));

    var shortRows =
        dataTableRowService.queryData(shortTable, List.of("name"), null, 0, 10).stream()
            .map(r -> r.get("name"))
            .toList();
    var longRows =
        dataTableRowService.queryData(longTable, List.of("name"), null, 0, 10).stream()
            .map(r -> r.get("name"))
            .toList();
    assertThat(longRows).isEqualTo(shortRows).containsExactly("a", "b");
    // 같은 두 행을 두 테이블에 넣었으므로 합계는 4 — 각 테이블은 2행
    assertThat(
            dataTableRowService.countRows(shortTable) + dataTableRowService.countRows(longTable))
        .isEqualTo(4);
  }

  @Test
  void 호출자_트랜잭션이_롤백되면_파사드로_넣은_행도_사라진다() {
    String table = "facade_rows_tx_" + System.nanoTime();
    // 테이블은 먼저 커밋해 둔다 — 롤백 대상은 행 삽입뿐이다.
    dataTableService.createTable(table, COLUMNS);
    try {
      var transactionTemplate = new TransactionTemplate(transactionManager);
      assertThatThrownBy(
              () ->
                  transactionTemplate.executeWithoutResult(
                      s -> {
                        dataTableRowService.insertRow(table, List.of("name"), Map.of("name", "x"));
                        throw new RuntimeException("rollback");
                      }))
          .hasMessage("rollback");
      assertThat(dataTableRowService.countRows(table)).isZero();
    } finally {
      dataTableService.dropTable(table);
    }
  }
}
