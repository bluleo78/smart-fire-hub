package com.smartfirehub.dataset.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.exception.InvalidTableNameException;
import com.smartfirehub.dataset.repository.TenantDataStoreRepository;
import com.smartfirehub.dataset.storage.DataStoreResolver;
import com.smartfirehub.dataset.storage.ReservedColumnNames;
import com.smartfirehub.global.tenant.MissingTenantScopeException;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.support.ReservedNameResolvers;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 파사드는 테넌트를 해석하기 전에 이름부터 검증한다(WD-72, 기존 동작 유지).
 *
 * <p>잘못된 이름이 테넌트 컨텍스트 없이 들어와도 {@code MissingTenantScopeException} 이 아니라 {@link
 * InvalidTableNameException} 이 나와야 한다. Spring 없이 저장소 구현이 없는 리졸버로 확인하므로, 이름 검증이 먼저 일어나지 않으면 다른 예외가
 * 난다.
 */
class DataTableFacadeNameValidationTest {

  private final DataStoreResolver resolver =
      new DataStoreResolver(List.of(), List.of(), mock(TenantDataStoreRepository.class));
  private final DataTableService tableService = new DataTableService(resolver);
  private final DataTableRowService rowService = new DataTableRowService(resolver);

  @Test
  void 테넌트_컨텍스트_없이_DDL_파사드에_잘못된_이름을_주면_InvalidTableNameException() {
    TenantContext.clear();
    assertThatThrownBy(() -> tableService.dropTable("Bad-Name"))
        .isInstanceOf(InvalidTableNameException.class);
  }

  @Test
  void 테넌트_컨텍스트_없이_행_파사드에_잘못된_이름을_주면_InvalidTableNameException() {
    TenantContext.clear();
    assertThatThrownBy(() -> rowService.truncateTable("Bad-Name"))
        .isInstanceOf(InvalidTableNameException.class);
    assertThatThrownBy(() -> rowService.insertBatch("ok_table", List.of("Bad-Col"), List.of()))
        .isInstanceOf(InvalidTableNameException.class);
  }

  @Test
  void 테넌트_컨텍스트_없이_finishReplace에_잘못된_이름을_주면_InvalidTableNameException() {
    TenantContext.clear();
    assertThatThrownBy(() -> tableService.finishReplace("Bad-Name", "rtmp_x", 1))
        .isInstanceOf(InvalidTableNameException.class);
    assertThatThrownBy(() -> tableService.finishReplace("Bad-Name", "rtmp_x", 0))
        .isInstanceOf(InvalidTableNameException.class);
  }

  /**
   * WD-635 — 새 컬럼명 검증은 형식·길이(테넌트 무관)를 먼저 보고, 백엔드별 예약어(테넌트 저장소)는 그 뒤에 본다. 예전에는 저장소부터 물어 잘못된 형식의 이름도
   * 테넌트 컨텍스트가 없으면 형식 400 대신 테넌트 오류가 났다.
   */
  @Test
  void 테넌트_컨텍스트_없이_형식이_틀린_새_컬럼명은_형식_오류() {
    TenantContext.clear();
    assertThatThrownBy(() -> tableService.validateNewColumnName("Bad-Name"))
        .isInstanceOf(InvalidTableNameException.class)
        .hasMessageContaining("Invalid name: Bad-Name");
    assertThatThrownBy(
            () ->
                tableService.validateNewColumns(
                    List.of(
                        new DatasetColumnRequest(
                            "bad name", "표시", "TEXT", null, true, false, null))))
        .isInstanceOf(InvalidTableNameException.class)
        .hasMessageContaining("Invalid name: bad name");
  }

  /** WD-635 — 길이 상한도 테넌트와 무관한 형식 검사라 저장소 조회보다 먼저 400 이 난다. */
  @Test
  void 테넌트_컨텍스트_없이_너무_긴_새_컬럼명은_길이_오류() {
    TenantContext.clear();
    String tooLong = "a".repeat(DataTableService.MAX_COLUMN_NAME_LENGTH + 1);
    assertThatThrownBy(() -> tableService.validateNewColumnName(tooLong))
        .isInstanceOf(InvalidTableNameException.class)
        .hasMessageContaining("필드명은 최대 " + DataTableService.MAX_COLUMN_NAME_LENGTH + "자");
  }

  /**
   * WD-635 동작 보존 — 형식을 먼저 보도록 순서를 바꿨어도, 예약어의 대소문자·형식 변형(_updated_at·ID·XMIN)은 테넌트 백엔드가 그 이름을 예약하면
   * 예전처럼 「예약어」 안내를, 예약하지 않으면(Tibero 의 XMIN) 예전처럼 형식 안내를 받는다. 형식이 맞는 예약어(xmin)도 백엔드별 판정 그대로다.
   */
  @Test
  void 예약어_변형의_안내는_백엔드별로_예전과_같다() {
    // 예약어 변형의 백엔드별 안내는 테넌트 컨텍스트가 있을 때만 준다(없으면 일반 형식 오류) — 목 리졸버라 값은 아무 테넌트나 된다.
    TenantContext.set(1L);
    try {
      assertReservedVariantMessagesPerBackend();
    } finally {
      TenantContext.clear();
    }
  }

  private static void assertReservedVariantMessagesPerBackend() {
    DataTableService pg = new DataTableService(ReservedNameResolvers.postgres());
    DataTableService tibero = new DataTableService(ReservedNameResolvers.appReservedOnly());
    for (String name :
        List.of("_updated_at", "ID", "Import_Id", "XMIN", "Ctid", "xmin", "tableoid")) {
      assertThatThrownBy(() -> pg.validateNewColumnName(name))
          .as(name)
          .isInstanceOf(InvalidTableNameException.class)
          .hasMessage(ReservedColumnNames.message(name, ReservedColumnNames.POSTGRESQL));
    }
    for (String name : List.of("_updated_at", "ID", "Import_Id", "id", "created_at")) {
      assertThatThrownBy(() -> tibero.validateNewColumnName(name))
          .as(name)
          .isInstanceOf(InvalidTableNameException.class)
          .hasMessage(ReservedColumnNames.message(name, ReservedColumnNames.TIBERO));
    }
    // Tibero 는 PG 시스템 컬럼명을 예약하지 않는다 — 대문자 변형은 형식 오류, 소문자는 통과.
    assertThatThrownBy(() -> tibero.validateNewColumnName("XMIN"))
        .isInstanceOf(InvalidTableNameException.class)
        .hasMessageContaining("Invalid name: XMIN");
    assertThatCode(() -> tibero.validateNewColumnName("xmin")).doesNotThrowAnyException();
    assertThatCode(() -> pg.validateNewColumnName("xmin_value")).doesNotThrowAnyException();
  }

  /** WD-635 — 형식이 맞는 이름의 백엔드별 예약어 판정은 여전히 테넌트 저장소가 정한다(테넌트 없으면 조용한 기본값 없이 실패). */
  @Test
  void 형식이_맞는_이름은_테넌트_저장소에서_예약어를_판정한다() {
    TenantContext.clear();
    assertThatThrownBy(() -> tableService.validateNewColumnName("xmin"))
        .isInstanceOf(MissingTenantScopeException.class);
  }

  /**
   * WD-635 — 형식에 걸리는 예약어 변형(ID·_updated_at)도 테넌트 컨텍스트가 없으면 테넌트 오류가 아니라 일반 형식 400 을 받는다. 백엔드별 「예약어」
   * 안내는 테넌트가 있을 때만 준다({@link #예약어_변형의_안내는_백엔드별로_예전과_같다}).
   */
  @Test
  void 테넌트_컨텍스트_없이_형식이_틀린_예약어_변형은_형식_오류() {
    TenantContext.clear();
    for (String name : List.of("ID", "_updated_at", "XMIN")) {
      assertThatThrownBy(() -> tableService.validateNewColumnName(name))
          .as(name)
          .isInstanceOf(InvalidTableNameException.class)
          .hasMessageContaining("Invalid name: " + name);
    }
  }
}
