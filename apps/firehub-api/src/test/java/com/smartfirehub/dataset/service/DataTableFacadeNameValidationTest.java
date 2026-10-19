package com.smartfirehub.dataset.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.smartfirehub.dataset.exception.InvalidTableNameException;
import com.smartfirehub.dataset.storage.DataStoreResolver;
import com.smartfirehub.dataset.storage.TenantDataStoreRepository;
import com.smartfirehub.global.tenant.TenantContext;
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
    assertThatThrownBy(() -> tableService.finishReplace("Bad-Name", 1))
        .isInstanceOf(InvalidTableNameException.class);
    assertThatThrownBy(() -> tableService.finishReplace("Bad-Name", 0))
        .isInstanceOf(InvalidTableNameException.class);
  }
}
