package com.smartfirehub.proactive.dto;

import java.util.List;

public record ProactiveResult(String title, List<Section> sections, Usage usage) {

  public record Section(String key, String label, String content, String type, Object data) {}

  public record Usage(int inputTokens, int outputTokens, int totalTokens) {}
}
