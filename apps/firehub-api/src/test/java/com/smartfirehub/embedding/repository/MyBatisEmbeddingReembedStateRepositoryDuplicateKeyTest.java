package com.smartfirehub.embedding.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.smartfirehub.embedding.repository.mybatis.EmbeddingReembedStateMapper;
import com.smartfirehub.embedding.repository.mybatis.MyBatisEmbeddingReembedStateRepository;
import com.smartfirehub.global.platformdb.mybatis.TiberoTextLimits;
import com.smartfirehub.global.tenant.MissingTenantScopeException;
import com.smartfirehub.global.tenant.TenantContext;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

/**
 * MyBatisEmbeddingReembedStateRepository.tryAcquire 의 Tibero MERGE 중복 키 분기를 DB 없이 고정한다(WD-712). 중복
 * 키는 같은 테넌트의 동시 첫 획득이 이겼다는 뜻이라 「못 잡음」(false)이고 재시도하지 않는다 — 다른 무결성 오류는 그대로 올라간다.
 */
class MyBatisEmbeddingReembedStateRepositoryDuplicateKeyTest {

  private static final Duration LEASE = Duration.ofSeconds(600);

  private final EmbeddingReembedStateMapper mapper = mock(EmbeddingReembedStateMapper.class);
  private final MyBatisEmbeddingReembedStateRepository repository =
      new MyBatisEmbeddingReembedStateRepository(mapper);

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  private boolean acquireInTenant() {
    return TenantContext.runScopedGet(7L, () -> repository.tryAcquire(LEASE));
  }

  @Test
  @DisplayName("중복 키로 실패하면 false 를 돌려주고 다시 시도하지 않는다")
  void duplicateKey_meansNotAcquired_withoutRetry() {
    when(mapper.tryAcquire(anyLong())).thenThrow(new DuplicateKeyException("dup"));

    assertThat(acquireInTenant()).isFalse();

    verify(mapper, times(1)).tryAcquire(600L);
  }

  @Test
  @DisplayName("중복 키가 아닌 무결성 오류는 삼키지 않는다")
  void otherIntegrityErrors_propagate() {
    when(mapper.tryAcquire(anyLong())).thenThrow(new DataIntegrityViolationException("fk"));

    assertThatThrownBy(this::acquireInTenant)
        .isInstanceOf(DataIntegrityViolationException.class)
        .isNotInstanceOf(DuplicateKeyException.class);

    verify(mapper, times(1)).tryAcquire(600L);
  }

  @Test
  @DisplayName("영향 행 1 이면 true, 0 이면 false — 한 번만 부른다")
  void affectedRowCountDecides() {
    when(mapper.tryAcquire(anyLong())).thenReturn(1, 0);

    assertThat(acquireInTenant()).isTrue();
    assertThat(acquireInTenant()).isFalse();

    verify(mapper, times(2)).tryAcquire(600L);
  }

  @Test
  @DisplayName("테넌트 컨텍스트가 없으면 매퍼를 부르기 전에 거절한다")
  void missingTenant_rejectedBeforeMapper() {
    TenantContext.clear();

    assertThatThrownBy(() -> repository.tryAcquire(LEASE))
        .isInstanceOf(MissingTenantScopeException.class);

    verifyNoInteractions(mapper);
  }

  @Test
  @DisplayName("markFailed 는 Tibero 열 한도로 자른 사유를 매퍼에 넘긴다")
  void markFailed_passesTruncatedMessage() {
    String huge = "가".repeat(25_000);

    TenantContext.runScopedGet(
        7L,
        () -> {
          repository.markFailed(huge);
          return null;
        });

    verify(mapper).setStatus("FAILED", huge.substring(0, TiberoTextLimits.MAX_ERROR_CHARS));
  }
}
