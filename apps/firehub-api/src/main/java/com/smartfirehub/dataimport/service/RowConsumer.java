package com.smartfirehub.dataimport.service;

import java.util.List;

/**
 * Excel/CSV 스트리밍 파서가 한 행씩 호출하는 콜백.
 *
 * <p>호출자(parseHeaders/parseSampleRows/countRows/parseExcel)가 자신의 누적/조기 종료 정책을 RowConsumer 구현으로
 * 주입한다. 헤더 행도 포함하며 rowIndex는 0-based(첫 행=0).
 */
@FunctionalInterface
public interface RowConsumer {

  /**
   * 한 행을 받아 처리한다.
   *
   * @param rowIndex 0-based 행 인덱스
   * @param cells 컬럼 정렬이 보정된 셀 값 리스트(빈 셀은 빈 문자열)
   * @return false면 즉시 파싱 중단(early-exit)
   */
  boolean accept(int rowIndex, List<String> cells);
}
