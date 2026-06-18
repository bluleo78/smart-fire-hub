package com.smartfirehub.dataset.search;

/**
 * pgvector 텍스트 리터럴 변환 공용 유틸.
 *
 * <p>float[] 임베딩을 {@code "[v1,v2,...]"} 형태로 직렬화해 {@code ?::vector} 캐스팅으로 바인딩한다.
 * A4 {@code DatasetSearchRepository} 와 본 패키지의 적재 리포지토리가 동일 방식을 공유하도록 한 곳으로 추출했다.
 */
final class VectorLiterals {

  private VectorLiterals() {}

  /** float[] → pgvector 텍스트 리터럴 "[v1,v2,...]". */
  static String toVectorLiteral(float[] v) {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < v.length; i++) {
      if (i > 0) sb.append(',');
      sb.append(v[i]);
    }
    return sb.append(']').toString();
  }
}
