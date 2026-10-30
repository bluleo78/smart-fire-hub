package com.smartfirehub.analytics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartfirehub.analytics.dto.CreateSavedQueryRequest;
import com.smartfirehub.analytics.dto.SavedQueryResponse;
import com.smartfirehub.analytics.repository.SavedQueryRepository;
import com.smartfirehub.pipeline.exception.TiberoUnqualifiedTableException;
import com.smartfirehub.pipeline.exception.UnsafeSqlException;
import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.ClearanceResolver;
import com.smartfirehub.securitylevel.access.DatasetAccessGuard;
import com.smartfirehub.securitylevel.access.SqlAccessMode;
import java.util.Optional;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 저장 시점 SQL 판정(WD-409)의 예외 전파 규칙을 가드를 목으로 두고 확인한다 — Tibero 스키마 없는 테이블은 400 으로 전파(저장 안 함), 그 밖의 파싱
 * 불가({@link UnsafeSqlException})는 삼켜 저장한다.
 */
class SavedQueryServiceSaveGateTest {

  private SavedQueryRepository repo;
  private DatasetAccessGuard guard;
  private SavedQueryService service;
  private final Clearance viewer = mock(Clearance.class);

  @BeforeEach
  void setUp() {
    repo = mock(SavedQueryRepository.class);
    guard = mock(DatasetAccessGuard.class);
    ClearanceResolver resolver = mock(ClearanceResolver.class);
    when(resolver.resolve(1L)).thenReturn(viewer);
    when(guard.visibleCondition(viewer)).thenReturn(DSL.trueCondition());
    // 판정과 무관한 협력자는 쓰이지 않으므로 null.
    service =
        new SavedQueryService(
            repo,
            null,
            resolver,
            guard,
            null,
            null,
            null,
            null,
            null,
            null,
            mock(AnalyticsAuditRecorder.class));
  }

  private CreateSavedQueryRequest req(String sql) {
    return new CreateSavedQueryRequest("Q", null, sql, null, null, false);
  }

  @Test
  void create_tiberoUnqualifiedTable_propagatedAndNotInserted() {
    doThrow(new TiberoUnqualifiedTableException("스키마를 붙이세요"))
        .when(guard)
        .requireSql(eq(viewer), any(), eq(SqlAccessMode.INTERACTIVE_SAVE));

    assertThatThrownBy(() -> service.create(req("SELECT * FROM t"), 1L))
        .isInstanceOf(TiberoUnqualifiedTableException.class);
    verify(repo, never()).insert(any(), any());
  }

  @Test
  void create_unsafeSql_swallowedAndInserted() {
    doThrow(new UnsafeSqlException("파싱 불가"))
        .when(guard)
        .requireSql(eq(viewer), any(), eq(SqlAccessMode.INTERACTIVE_SAVE));
    when(repo.insert(any(), eq(1L))).thenReturn(10L);
    SavedQueryResponse saved = mock(SavedQueryResponse.class);
    when(repo.findById(eq(10L), eq(1L), any())).thenReturn(Optional.of(saved));

    assertThat(service.create(req("SELEC x"), 1L)).isSameAs(saved);
    verify(repo).insert(any(), eq(1L));
  }
}
