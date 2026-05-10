package com.smartfirehub.dataimport.service;

import java.time.ZoneId;
import java.util.Date;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;

/**
 * 기존 {@code FileParserService.getCellValueAsString} 동작을 SAX/HSSF 이벤트 파서에서 재현하기 위한 DataFormatter.
 *
 * <p>POI 기본 DataFormatter는 Excel의 표시 포맷 문자열(예: "m/d/yyyy")을 그대로 적용하지만, 본 프로젝트는 날짜 셀을 ISO
 * LocalDateTime 형태로(예: "2026-05-10T00:00") 일관되게 출력해 왔다. 외부 호출부(데이터 검증·삽입) 호환을 위해 동일 표현을 유지한다.
 *
 * <p>숫자 셀은 정수(소수부 0)면 long 문자열, 그 외엔 {@code Double.toString} 결과로 변환한다.
 */
public class LegacyExcelDataFormatter extends DataFormatter {

  @Override
  public String formatRawCellContents(double value, int formatIndex, String formatString) {
    return formatRawCellContents(value, formatIndex, formatString, false);
  }

  @Override
  public String formatRawCellContents(
      double value, int formatIndex, String formatString, boolean use1904Windowing) {
    if (DateUtil.isADateFormat(formatIndex, formatString)) {
      Date date = DateUtil.getJavaDate(value, use1904Windowing);
      return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime().toString();
    }
    if (value == Math.floor(value) && !Double.isInfinite(value)) {
      return String.valueOf((long) value);
    }
    return String.valueOf(value);
  }
}
