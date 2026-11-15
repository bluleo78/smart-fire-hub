package com.smartfirehub.dataset.search;

import com.smartfirehub.global.platformdb.mybatis.TiberoTextLimits;
import java.util.ArrayList;
import java.util.List;

/**
 * 데이터셋 메타를 검색용 단일 텍스트로 합치는 빌더.
 *
 * <p>길이 상한({@link #MAX_CHARS}, WD-741): 컬럼·태그 수에 한도가 없어 합본이 수만 자가 될 수 있다. Tibero 플랫폼 DB 의 {@code
 * dataset_embedding.source_text} 는 {@code VARCHAR2(65532)}(바이트)라 넘으면 저장이 실패해 그 데이터셋이 검색에서 빠진다. 저장
 * 때만 자르면 임베딩 경로({@code DatasetEmbeddingService.reindexEmbedding})가 쓰는 원문·해시와 저장된 원문·해시가 어긋나 같은
 * 데이터셋을 끝없이 다시 임베딩하므로, 원문을 만드는 이 한 곳에서 두 DB 공통으로 자른다. 뒤쪽(컬럼·태그·분류)이 잘릴 뿐 이름·설명은 남는다.
 */
public final class DatasetSourceTextBuilder {

  /**
   * 검색 원문 최대 글자 수 — Tibero {@code VARCHAR2(65532)} 에 어떤 글자로도 들어가는 상한({@link
   * TiberoTextLimits#MAX_TRUNCATE_CHARS}, char 하나 최대 3바이트).
   */
  public static final int MAX_CHARS = TiberoTextLimits.MAX_TRUNCATE_CHARS;

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
    // 서로게이트 짝을 깨지 않게 자른다(TiberoTextLimits.truncate 규칙).
    return TiberoTextLimits.truncate(String.join("\n", parts), MAX_CHARS);
  }

  /** 값이 null이 아니고 공백만으로 이뤄지지 않은 경우에만 trim 하여 추가한다. */
  private static void addIfText(List<String> parts, String v) {
    if (v != null && !v.isBlank()) parts.add(v.trim());
  }
}
