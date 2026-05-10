package com.smartfirehub.dataimport.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RowConsumerTest {

  @Test
  void lambda_can_implement_accept_and_signal_stop() {
    List<List<String>> captured = new ArrayList<>();
    RowConsumer consumer =
        (idx, cells) -> {
          captured.add(cells);
          return idx < 1; // 0,1 받으면 stop
        };

    boolean cont0 = consumer.accept(0, List.of("a"));
    boolean cont1 = consumer.accept(1, List.of("b"));

    assertThat(cont0).isTrue();
    assertThat(cont1).isFalse();
    assertThat(captured).hasSize(2);
  }
}
