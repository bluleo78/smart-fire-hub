package com.smartfirehub.dataset.repository.mybatis;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.smartfirehub.global.platformdb.mybatis.TiberoTextLimits;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * MyBatisSearchIndexStateRepository 가 오류 메시지를 자른 뒤 매퍼에 넘기는지 DB 없이 고정한다(WD-707 리뷰). 자르기 규칙 자체는
 * {@code TiberoTextLimitsTest} 가 한 번 고정한다(WD-712 공용 도우미로 옮김).
 */
class MyBatisSearchIndexStateRepositoryTruncateTest {

  @Test
  @DisplayName("markFailed·markWaiting 은 자른 메시지를 매퍼에 넘긴다")
  void markFailedAndWaiting_passTruncatedMessage() {
    SearchIndexStateMapper mapper = mock(SearchIndexStateMapper.class);
    var repository = new MyBatisSearchIndexStateRepository(mapper);
    String huge = "가".repeat(25_000);
    String expected = huge.substring(0, TiberoTextLimits.MAX_ERROR_CHARS);

    repository.markFailed(1L, huge, OffsetDateTime.now());
    repository.markWaiting(2L, huge, OffsetDateTime.now());

    verify(mapper).markFailed(eq(1L), eq(expected), any());
    verify(mapper).markWaiting(eq(2L), eq(expected), any());
  }
}
