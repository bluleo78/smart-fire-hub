package com.smartfirehub.dataset.search;

import java.util.ArrayList;
import java.util.List;

/** 데이터셋 메타를 검색용 단일 텍스트로 합치는 빌더. */
public final class DatasetSourceTextBuilder {

  /** 합본 입력. 각 필드는 null/빈값 허용. */
  public record Input(
      String name,
      String description,
      String tableName,
      List<String> columnNames,
      List<String> tagNames,
      String categoryName) {}

  private DatasetSourceTextBuilder() {}

  /** 비어있지 않은 메타들을 줄바꿈으로 이어 붙인다. */
  public static String build(Input in) {
    List<String> parts = new ArrayList<>();
    // 단일 텍스트 필드들을 순서대로 추가 (null/빈값은 내부에서 스킵)
    addIfText(parts, in.name());
    addIfText(parts, in.description());
    addIfText(parts, in.tableName());
    // 컬럼명/태그명은 리스트이므로 null 가드 후 각 원소를 추가
    if (in.columnNames() != null) in.columnNames().forEach(c -> addIfText(parts, c));
    if (in.tagNames() != null) in.tagNames().forEach(t -> addIfText(parts, t));
    addIfText(parts, in.categoryName());
    return String.join("\n", parts);
  }

  /** 값이 null이 아니고 공백만으로 이뤄지지 않은 경우에만 trim 하여 추가한다. */
  private static void addIfText(List<String> parts, String v) {
    if (v != null && !v.isBlank()) parts.add(v.trim());
  }
}
