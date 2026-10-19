package com.smartfirehub.dataset.storage;

import com.smartfirehub.dataset.dto.DatasetColumnResponse;
import java.util.List;
import java.util.Map;

/** 컬럼 타입 힌트({@code columnTypes}) 해석용 백엔드 중립 정적 헬퍼(WD-72). */
public final class DataColumnTypes {

  private DataColumnTypes() {}

  /** 해당 컬럼의 타입 힌트가 GEOMETRY 인지 본다. 힌트 맵이 null 이면 false. */
  public static boolean isGeometry(String colName, Map<String, String> columnTypes) {
    return columnTypes != null && "GEOMETRY".equalsIgnoreCase(columnTypes.get(colName));
  }

  /**
   * GEOMETRY 컬럼 이름 해결. geometryColumn이 null이면 첫 번째 GEOMETRY 컬럼 자동 선택.
   *
   * @throws IllegalArgumentException GEOMETRY 컬럼이 없거나 지정 컬럼이 GEOMETRY가 아닌 경우
   */
  public static String resolveGeometryColumn(
      List<DatasetColumnResponse> columns, Map<String, String> columnTypes, String geometryColumn) {
    if (geometryColumn != null) {
      if (!isGeometry(geometryColumn, columnTypes)) {
        throw new IllegalArgumentException("컬럼 '" + geometryColumn + "'은 GEOMETRY 타입이 아닙니다.");
      }
      return geometryColumn;
    }
    // 첫 번째 GEOMETRY 컬럼 자동 선택
    for (DatasetColumnResponse col : columns) {
      if (isGeometry(col.columnName(), columnTypes)) {
        return col.columnName();
      }
    }
    throw new IllegalArgumentException("데이터셋에 GEOMETRY 컬럼이 없습니다.");
  }
}
