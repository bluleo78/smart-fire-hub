package com.smartfirehub.dataset.rowsearch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.smartfirehub.dataset.service.DataTableRowService;
import com.smartfirehub.dataset.storage.DataStoreBackend;
import com.smartfirehub.dataset.storage.DataStoreResolver;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 행 검색 원본 읽기 파사드가 현재 테넌트 백엔드로 구현을 고르는지(WD-723). 한 JVM 에 PG·Tibero 테넌트가 섞이므로 호출마다 골라야 하고, 구현이 없는
 * 백엔드는 조용히 PG 로 새지 않고 실패해야 한다.
 */
class SearchSourceReaderDispatchTest {

  private final SearchSourceStore pg = mock(SearchSourceStore.class);
  private final SearchSourceStore tibero = mock(SearchSourceStore.class);
  private final DataStoreResolver resolver = mock(DataStoreResolver.class);
  private final DataTableRowService rows = mock(DataTableRowService.class);
  private SearchSourceReader reader;

  @BeforeEach
  void setUp() {
    when(pg.backend()).thenReturn(DataStoreBackend.POSTGRESQL);
    when(tibero.backend()).thenReturn(DataStoreBackend.TIBERO);
    reader = new SearchSourceReader(List.of(pg, tibero), resolver, rows);
  }

  @Test
  void tiberoTenantGoesToTiberoStore() {
    when(resolver.currentBackend()).thenReturn(DataStoreBackend.TIBERO);
    OffsetDateTime now = OffsetDateTime.now();
    when(tibero.captureCursorCandidate()).thenReturn(now);
    when(tibero.currentOid("t")).thenReturn(7L);
    when(tibero.existingIds("t", List.of(1L))).thenReturn(Set.of(1L));

    assertThat(reader.captureCursorCandidate()).isEqualTo(now);
    assertThat(reader.currentOid("t")).isEqualTo(7L);
    assertThat(reader.existingIds("t", List.of(1L))).containsExactly(1L);
    RowQueryFilter f = RowQueryFilter.none();
    reader.matchingIds("t", f, 5);
    verify(tibero).matchingIds("t", f, 5);
    verifyNoInteractions(rows);
    // pg 는 backend() 등록 질의 말고는 불리지 않는다
    verify(pg).backend();
    org.mockito.Mockito.verifyNoMoreInteractions(pg);
  }

  @Test
  void postgresTenantGoesToPgStore() {
    when(resolver.currentBackend()).thenReturn(DataStoreBackend.POSTGRESQL);
    reader.fetchChanged("t", List.of("c"), null, 0L, 10);
    verify(pg).fetchChanged("t", List.of("c"), null, 0L, 10);
    verify(tibero).backend();
    org.mockito.Mockito.verifyNoMoreInteractions(tibero);
  }

  @Test
  void backendWithoutStoreFails() {
    when(resolver.currentBackend()).thenReturn(DataStoreBackend.MYSQL);
    assertThatThrownBy(() -> reader.currentOid("t"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("MYSQL");
  }

  /** 행 수는 이미 백엔드별로 위임하는 데이터 테이블 파사드의 정본을 쓴다. */
  @Test
  void countRowsUsesDataTableFacade() {
    when(rows.countRows("t")).thenReturn(3L);
    assertThat(reader.countRows("t")).isEqualTo(3L);
  }
}
