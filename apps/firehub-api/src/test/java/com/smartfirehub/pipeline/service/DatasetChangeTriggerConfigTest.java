package com.smartfirehub.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 데이터 변경 트리거 config 정수 해석 규칙 단위 테스트(WD-225) — Spring 컨텍스트 없이 순수 규칙만 본다. */
class DatasetChangeTriggerConfigTest {

  @Test
  void parseWholeNumber_acceptsIntegersWholeDecimalsAndIntegerStrings() {
    assertThat(DatasetChangeTriggerConfig.parseWholeNumber(null)).isNull();
    assertThat(DatasetChangeTriggerConfig.parseWholeNumber(60)).isEqualTo(60L);
    assertThat(DatasetChangeTriggerConfig.parseWholeNumber(60L)).isEqualTo(60L);
    assertThat(DatasetChangeTriggerConfig.parseWholeNumber(BigInteger.valueOf(60))).isEqualTo(60L);
    assertThat(DatasetChangeTriggerConfig.parseWholeNumber(30.0)).isEqualTo(30L);
    assertThat(DatasetChangeTriggerConfig.parseWholeNumber(new BigDecimal("30.00"))).isEqualTo(30L);
    assertThat(DatasetChangeTriggerConfig.parseWholeNumber("60")).isEqualTo(60L);
    assertThat(DatasetChangeTriggerConfig.parseWholeNumber("  60 ")).isEqualTo(60L);
    assertThat(DatasetChangeTriggerConfig.parseWholeNumber("-5")).isEqualTo(-5L);
  }

  @Test
  void parseWholeNumber_rejectsFractionsNonNumbersAndLongOverflow() {
    for (Object bad :
        List.<Object>of(
            30.9,
            new BigDecimal("30.9"),
            Double.NaN,
            Double.POSITIVE_INFINITY,
            "abc",
            "",
            "30.0",
            "1e2",
            "99999999999999999999",
            new BigInteger("99999999999999999999"),
            true,
            List.of(1))) {
      assertThatThrownBy(() -> DatasetChangeTriggerConfig.parseWholeNumber(bad))
          .as("값=%s", bad)
          .isInstanceOf(NumberFormatException.class);
    }
  }

  @Test
  void requirePollingInterval_rangeAndIntOverflowAreRejectedWithKoreanMessage() {
    assertThat(DatasetChangeTriggerConfig.requirePollingInterval(null)).isEqualTo(60);
    assertThat(DatasetChangeTriggerConfig.requirePollingInterval(30)).isEqualTo(30);
    assertThat(DatasetChangeTriggerConfig.requirePollingInterval("3600")).isEqualTo(3600);
    // 2^32+60 — 예전 intValue() 넘침으로 60 이 되어 통과하던 값
    for (Object bad : List.<Object>of(29, 3601, 4294967356L, "abc", 30.9)) {
      assertThatThrownBy(() -> DatasetChangeTriggerConfig.requirePollingInterval(bad))
          .as("값=%s", bad)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("폴링 주기(pollingIntervalSeconds)는 30~3600 사이의 정수(초)여야 합니다.");
    }
  }

  @Test
  void requireDebounce_defaultZeroAndRange() {
    assertThat(DatasetChangeTriggerConfig.requireDebounce(null)).isZero();
    assertThat(DatasetChangeTriggerConfig.requireDebounce("0")).isZero();
    assertThat(DatasetChangeTriggerConfig.requireDebounce(3600)).isEqualTo(3600);
    for (Object bad : List.<Object>of(-1, 3601, 4294967296L, "x", 0.5)) {
      assertThatThrownBy(() -> DatasetChangeTriggerConfig.requireDebounce(bad))
          .as("값=%s", bad)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("디바운스(debounceSeconds)는 0~3600 사이의 정수(초)여야 합니다.");
    }
  }

  @Test
  void normalizedForSave_writesIntegersRemovesNullsAndKeepsAbsentKeysAbsent() {
    Map<String, Object> config = new HashMap<>();
    config.put("datasetIds", List.of("7", 8));
    config.put("pollingIntervalSeconds", "60");
    config.put("debounceSeconds", null);
    config.put("other", "keep");

    Map<String, Object> out = DatasetChangeTriggerConfig.normalizedForSave(config);

    assertThat(out).containsEntry("pollingIntervalSeconds", 60);
    assertThat(out).doesNotContainKey("debounceSeconds");
    assertThat(out).containsEntry("datasetIds", List.of(7L, 8L));
    assertThat(out).containsEntry("other", "keep");
    // 원본은 바꾸지 않는다
    assertThat(config).containsEntry("pollingIntervalSeconds", "60");

    Map<String, Object> minimal =
        DatasetChangeTriggerConfig.normalizedForSave(Map.of("datasetIds", List.of(1)));
    assertThat(minimal).doesNotContainKeys("pollingIntervalSeconds", "debounceSeconds");
  }

  @Test
  void requireDatasetIdElements_rejectsNonIntegerElements() {
    for (List<?> bad :
        List.of(Arrays.asList(1, null), List.of(1, "abc"), List.of(1.5), List.of(List.of(1)))) {
      assertThatThrownBy(() -> DatasetChangeTriggerConfig.requireDatasetIdElements(bad))
          .as("목록=%s", bad)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage(DatasetChangeTriggerConfig.DATASET_ID_FORMAT_MESSAGE);
    }
  }

  /** 폴러는 던지지 않는다 — 읽을 수 없는 옛 값(문자열·int 넘침)은 기본값. */
  @Test
  void secondsOrDefault_isLenientForPoller() {
    assertThat(DatasetChangeTriggerConfig.secondsOrDefault(null, 60)).isEqualTo(60);
    assertThat(DatasetChangeTriggerConfig.secondsOrDefault(120, 60)).isEqualTo(120);
    assertThat(DatasetChangeTriggerConfig.secondsOrDefault("90", 60)).isEqualTo(90);
    assertThat(DatasetChangeTriggerConfig.secondsOrDefault("abc", 60)).isEqualTo(60);
    assertThat(DatasetChangeTriggerConfig.secondsOrDefault(30.9, 60)).isEqualTo(60);
    assertThat(DatasetChangeTriggerConfig.secondsOrDefault(4294967356L, 60)).isEqualTo(60);
  }
}
