package com.smartfirehub.dataset.repository.mybatis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * MyBatisSearchIndexStateRepository 의 오류 메시지 자르기를 DB 없이 고정한다(WD-707 리뷰). Tibero last_error 는
 * VARCHAR2(65532) 바이트 한도라, 자르지 않으면 긴 메시지의 UPDATE 가 실패해 백오프가 기록되지 않는다.
 */
class MyBatisSearchIndexStateRepositoryTruncateTest {

  private static final int LIMIT = MyBatisSearchIndexStateRepository.MAX_ERROR_CHARS;

  @Test
  @DisplayName("한도 이하·null 메시지는 그대로 둔다")
  void shortOrNull_isUnchanged() {
    assertThat(MyBatisSearchIndexStateRepository.truncateError(null)).isNull();
    assertThat(MyBatisSearchIndexStateRepository.truncateError("boom")).isEqualTo("boom");
    String exact = "가".repeat(LIMIT);
    assertThat(MyBatisSearchIndexStateRepository.truncateError(exact)).isSameAs(exact);
  }

  @Test
  @DisplayName("긴 한글 메시지는 앞부분 16000 자로 잘리고 UTF-8 로 65532 바이트를 넘지 않는다")
  void longKorean_isTruncatedUnderByteLimit() {
    String huge = "가".repeat(25_000);
    String cut = MyBatisSearchIndexStateRepository.truncateError(huge);
    assertThat(cut).hasSize(LIMIT);
    assertThat(huge).startsWith(cut);
    assertThat(cut.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(65_532);
  }

  @Test
  @DisplayName("보충 문자(서로게이트 쌍)를 반으로 가르지 않는다")
  void surrogatePair_isNotSplit() {
    // 한도 바로 앞까지 채우고 그 자리에 이모지(char 두 개)를 걸쳐 둔다.
    String huge = "a".repeat(LIMIT - 1) + "😀" + "b".repeat(10);
    String cut = MyBatisSearchIndexStateRepository.truncateError(huge);
    assertThat(cut).hasSize(LIMIT - 1);
    assertThat(Character.isHighSurrogate(cut.charAt(cut.length() - 1))).isFalse();
  }

  @Test
  @DisplayName("markFailed·markWaiting 은 자른 메시지를 매퍼에 넘긴다")
  void markFailedAndWaiting_passTruncatedMessage() {
    SearchIndexStateMapper mapper = mock(SearchIndexStateMapper.class);
    var repository = new MyBatisSearchIndexStateRepository(mapper);
    String huge = "가".repeat(25_000);
    String expected = huge.substring(0, LIMIT);

    repository.markFailed(1L, huge, OffsetDateTime.now());
    repository.markWaiting(2L, huge, OffsetDateTime.now());

    verify(mapper).markFailed(eq(1L), eq(expected), any());
    verify(mapper).markWaiting(eq(2L), eq(expected), any());
  }
}
