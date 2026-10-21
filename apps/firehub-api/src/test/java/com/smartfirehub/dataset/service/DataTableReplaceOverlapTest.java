package com.smartfirehub.dataset.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.exception.InvalidTableNameException;
import com.smartfirehub.dataset.storage.DataTableNames;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * WD-151 — 같은 출력 테이블에 REPLACE 적재(임시 테이블 생성 → 적재 → 맞바꿈, 실패 시 정리)가 겹쳐도 서로를 깨지 않는다. 실제 DB 로 확인한다.
 *
 * <p>예전에는 임시 테이블 이름이 출력마다 고정({@code rtmp_<md5>})이라, 겹친 실행이 같은 이름을 동시에 만들다 {@code
 * pg_type_typname_nsp_index} 중복으로 깨지거나, 뒤 실행이 앞 실행의 임시 테이블을 지우고 다시 만들었고, 실패한 실행의 정리가 적재 중인 다른 실행의
 * 임시 테이블을 지웠다. 이제 {@link DataTableService#createTempTable} 이 실행마다 다른 이름을 돌려주므로 생성·적재·정리가 서로 닿지 않고,
 * 맞바꿈은 원본의 ACCESS EXCLUSIVE 잠금으로 차례로 서서 둘 다 성공하고 나중에 끝난 실행이 이긴다.
 *
 * <p>클래스 레벨 {@code @Transactional} 은 쓰지 않는다 — 동시 실행 테스트는 다른 스레드·커넥션이 커밋된 테이블을 봐야 한다.
 */
class DataTableReplaceOverlapTest extends IntegrationTestBase {

  @Autowired private DataTableService dataTableService;
  @Autowired private DataTableRowService dataTableRowService;
  @Autowired private DSLContext dsl;

  /**
   * 긴 컬럼명 — {@code LIKE ... INCLUDING ALL} 이 짓는 인덱스 이름({@code <tmp>_<col>_idx})이 63바이트를 넘어 잘리게 한다.
   * 잘려도 두 실행의 인덱스 이름이 달라야 동시 생성이 중복 오류로 깨지지 않는다(난수가 이름 앞쪽에 있는 이유).
   */
  private static final String LONG_COLUMN = "customer_reference_code_x";

  private String table;

  @BeforeEach
  void createOutput() {
    table = "wd151_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    dataTableService.createTable(
        table,
        List.of(
            new DatasetColumnRequest("name", "name", "TEXT", null, true, false, null, false),
            new DatasetColumnRequest(LONG_COLUMN, "ref", "TEXT", null, true, true, null, false)));
    insert(table, "old");
  }

  @AfterEach
  void dropOutput() {
    for (String tmp : tempTablesOf(table)) {
      dsl.execute("DROP TABLE IF EXISTS " + DataSchema.qualify(tmp));
    }
    dataTableService.dropTable(table);
  }

  private void insert(String target, String value) {
    dataTableRowService.insertBatch(
        target, List.of("name", LONG_COLUMN), List.of(Map.of("name", value, LONG_COLUMN, value)));
  }

  private List<String> names(String target) {
    return dsl.fetch("SELECT name FROM " + DataSchema.qualify(target) + " ORDER BY name")
        .getValues(0, String.class);
  }

  /** 이 출력의 REPLACE 임시 테이블(옛 고정 이름 포함) 중 지금 남아 있는 것. */
  private List<String> tempTablesOf(String output) {
    return dsl.fetch(
            "SELECT c.relname::text FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
                + " WHERE n.nspname = ? AND c.relkind = 'r' AND c.relname LIKE ?",
            DataSchema.current(),
            DataTableService.TEMP_TABLE_PREFIX + "%" + DataTableNames.md5Hex(output) + "%")
        .getValues(0, String.class);
  }

  /** 테이블에 걸린 인덱스 이름. */
  private List<String> indexNames(String target) {
    return dsl.fetch(
            "SELECT indexname::text FROM pg_indexes WHERE schemaname = ? AND tablename = ?",
            DataSchema.current(),
            target)
        .getValues(0, String.class);
  }

  /**
   * 겹친 두 스테이징에서 뒤 실행(B)이 실패해 자기 임시 테이블을 지워도 앞 실행(A)의 임시 테이블은 남고, A 는 그대로 맞바꾼다. 예전(고정 이름)에는 B 의 생성이
   * A 의 임시 테이블을 지우고 다시 만들었고, B 의 정리가 A 의 적재분을 지워 A 의 맞바꿈이 실패했다.
   */
  @Test
  void 뒤_실행이_실패해_정리해도_앞_실행의_임시테이블은_남고_앞_실행이_맞바꾼다() {
    String tmpA = dataTableService.createTempTable(table);
    insert(tmpA, "a");
    String tmpB = dataTableService.createTempTable(table);

    assertThat(tmpB).as("실행마다 다른 임시 테이블").isNotEqualTo(tmpA);
    assertThat(names(tmpA)).as("B 의 생성이 A 의 임시 테이블을 건드리지 않는다").containsExactly("a");
    // 긴 컬럼의 인덱스 이름(<tmp>_<col>_idx)이 63바이트에서 잘려도 실행마다 다른 난수 부분은 남아야 한다 — 잘려 나가면 두 실행의 인덱스 기본
    // 이름이 같아지고, 동시에 만들 때(서로의 미커밋 인덱스를 못 봐 PostgreSQL 이 번호를 붙여 피하지 못한다) 중복 오류가 난다. 순차 생성에서는
    // 번호(_idx1)가 붙어 이름이 달라 보이므로 "다르다"만으로는 증명되지 않는다 — 각 인덱스 이름에 자기 난수가 온전히 들어 있는지 본다.
    String md5 = DataTableNames.md5Hex(table);
    for (String tmp : List.of(tmpA, tmpB)) {
      String random =
          tmp.substring(DataTableService.TEMP_TABLE_PREFIX.length())
              .replace(md5, "")
              .replace("_", "");
      assertThat(random).hasSize(12);
      assertThat(indexNames(tmp))
          .as("%s 의 인덱스 이름", tmp)
          .isNotEmpty()
          .allMatch(n -> n.contains(random));
    }

    // B 실패 → 자기 임시 테이블만 정리
    insert(tmpB, "b");
    dataTableService.dropTempTable(table, tmpB);

    assertThat(tempTablesOf(table)).containsExactly(tmpA);
    assertThat(names(tmpA)).containsExactly("a");
    assertThat(names(table)).as("원본은 그대로").containsExactly("old");

    dataTableService.finishReplace(table, tmpA, 1);

    assertThat(names(table)).containsExactly("a");
    assertThat(tempTablesOf(table)).as("rtmp_ 잔재가 없다").isEmpty();
  }

  /** 겹친 두 스테이징이 둘 다 맞바꾸면 나중에 맞바꾼 실행이 이기고, 임시 테이블은 남지 않으며, 그 뒤 삽입(시퀀스)도 정상이다. */
  @Test
  void 겹친_두_스테이징이_모두_맞바꾸면_나중_실행이_이기고_잔재가_없다() {
    String tmpA = dataTableService.createTempTable(table);
    String tmpB = dataTableService.createTempTable(table);
    insert(tmpA, "a");
    insert(tmpB, "b");

    dataTableService.finishReplace(table, tmpA, 1);
    assertThat(names(table)).containsExactly("a");
    // A 의 맞바꿈 뒤에도 B 의 임시 테이블은 그대로다 — B 는 이어서 맞바꿀 수 있다.
    assertThat(names(tmpB)).containsExactly("b");
    dataTableService.finishReplace(table, tmpB, 1);

    assertThat(names(table)).containsExactly("b");
    assertThat(tempTablesOf(table)).isEmpty();
    insert(table, "c");
    assertThat(names(table)).containsExactly("b", "c");
  }

  /**
   * 실제로 동시에 도는 REPLACE 적재 두 줄기가 같은 출력을 반복해 교체해도 어느 쪽도 실패하지 않는다(생성 충돌·남의 임시 테이블 정리·맞바꿈 의존성 오류 없음).
   * 끝나면 출력은 두 줄기 중 하나의 마지막 값 한 행이고 임시 테이블이 남지 않는다.
   */
  @Test
  void 동시에_도는_두_REPLACE_적재가_반복해도_모두_성공한다() throws Exception {
    int rounds = 15;
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<Integer>> runs = new ArrayList<>();
      for (String who : List.of("p", "q")) {
        runs.add(
            pool.submit(
                () ->
                    TenantContext.runScopedGet(
                        DEFAULT_TEST_TENANT_ID,
                        () -> {
                          try {
                            start.await(10, TimeUnit.SECONDS);
                          } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(e);
                          }
                          for (int i = 0; i < rounds; i++) {
                            String tmp = dataTableService.createTempTable(table);
                            try {
                              insert(tmp, who + i);
                              dataTableService.finishReplace(table, tmp, 1);
                            } catch (RuntimeException e) {
                              dataTableService.dropTempTable(table, tmp);
                              throw e;
                            }
                          }
                          return rounds;
                        })));
      }
      start.countDown();
      for (Future<Integer> run : runs) {
        assertThat(run.get(120, TimeUnit.SECONDS)).isEqualTo(rounds);
      }
    } finally {
      pool.shutdownNow();
    }

    assertThat(names(table)).hasSize(1).first().isIn("p" + (rounds - 1), "q" + (rounds - 1));
    assertThat(tempTablesOf(table)).isEmpty();
  }

  /**
   * 맞바꿈·정리는 받은 이름을 그대로 DROP/RENAME 하므로, 그 테이블의 임시 테이블 모양이 아닌 이름은 거부한다 — 인자가 뒤바뀌거나 잘못 넘어와도 실제 데이터셋이나
   * 다른 출력의 임시 테이블을 지우지 않는다.
   */
  @Test
  void 그_테이블의_임시테이블이_아닌_이름은_맞바꿈과_정리가_거부한다() {
    String other = "wd151_other_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    dataTableService.createTable(
        other,
        List.of(new DatasetColumnRequest("name", "name", "TEXT", null, true, false, null, false)));
    try {
      insert(table, "keep");
      String otherTmp = DataTableService.newTempTableName(other);
      List<String> wrongNames =
          List.of(
              other, // 실제 데이터셋 테이블
              table, // 원본 자기 자신
              otherTmp, // 다른 출력의 임시 테이블
              DataTableService.legacyTempTableName(table), // 옛 고정 이름
              DataTableService.newTempTableName(table) + "x"); // 모양이 어긋난 이름
      for (String wrong : wrongNames) {
        assertThatThrownBy(() -> dataTableService.dropTempTable(table, wrong))
            .as("dropTempTable(%s)", wrong)
            .isInstanceOf(InvalidTableNameException.class);
        assertThatThrownBy(() -> dataTableService.swapTable(table, wrong))
            .as("swapTable(%s)", wrong)
            .isInstanceOf(InvalidTableNameException.class);
        assertThatThrownBy(() -> dataTableService.finishReplace(table, wrong, 1))
            .as("finishReplace(%s)", wrong)
            .isInstanceOf(InvalidTableNameException.class);
      }
      // 아무것도 지워지거나 바뀌지 않았다.
      assertThat(names(table)).containsExactly("keep", "old");
      assertThat(names(other)).isEmpty();
    } finally {
      dataTableService.dropTable(other);
    }
  }

  /** WD-151 이전 버전이 남긴 고정 이름 임시 테이블은 다음 생성 때 지워진다(새 코드는 그 이름을 쓰지 않으므로 살아 있는 실행의 것일 수 없다). */
  @Test
  void 옛_고정_이름_잔재는_다음_생성이_지운다() {
    String legacy = DataTableService.legacyTempTableName(table);
    dsl.execute(
        "CREATE TABLE "
            + DataSchema.qualify(legacy)
            + " (LIKE "
            + DataSchema.qualify(table)
            + " INCLUDING ALL)");
    assertThat(tempTablesOf(table)).containsExactly(legacy);

    String tmp = dataTableService.createTempTable(table);

    assertThat(tempTablesOf(table)).containsExactly(tmp);
    dataTableService.dropTempTable(table, tmp);
  }
}
