package com.smartfirehub.dataset.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.dataset.repository.DatasetSort.Field;
import org.junit.jupiter.api.Test;

/** WD-874: 정렬 인자 해석 — 기본값·빈 문자열·허용 밖 값(대소문자 다름 포함) 거절. */
class DatasetSortTest {

  @Test
  void parse_noArgs_isDefault() {
    assertThat(DatasetSort.parse(null, null)).isEqualTo(DatasetSort.DEFAULT);
    assertThat(DatasetSort.parse("", "")).isEqualTo(DatasetSort.DEFAULT);
  }

  @Test
  void parse_valid() {
    assertThat(DatasetSort.parse("createdAt", "desc"))
        .isEqualTo(new DatasetSort(Field.CREATED_AT, true));
    assertThat(DatasetSort.parse("name", null)).isEqualTo(new DatasetSort(Field.NAME, false));
    assertThat(DatasetSort.parse(null, "desc")).isEqualTo(new DatasetSort(Field.ID, true));
  }

  @Test
  void parse_invalid_throws() {
    assertThatThrownBy(() -> DatasetSort.parse("CreatedAt", null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> DatasetSort.parse("created_at", null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> DatasetSort.parse("id", "DESC"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> DatasetSort.parse("id", "up"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> DatasetSort.parse("foo", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("foo");
  }
}
