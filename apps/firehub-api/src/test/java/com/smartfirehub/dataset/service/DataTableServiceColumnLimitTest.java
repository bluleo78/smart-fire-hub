package com.smartfirehub.dataset.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.exception.ColumnModificationException;
import com.smartfirehub.support.ReservedNameResolvers;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * WD-603 — 필드 수 상한(백엔드 테이블 컬럼 한도 − 앱 시스템 컬럼 수) 검사를 DB 없이 고정한다.
 *
 * <p>PG 저장소의 실제 한도(1600)를 쓰는 리졸버로 경계를 본다. DB 가 삭제한 컬럼까지 세어 DDL 을 거부(SQLState 54011)할 때의 400 변환은 PG
 * 저장소의 일이라 실제 DB 로 {@code DatasetColumnLimitTest} 가 확인한다.
 */
class DataTableServiceColumnLimitTest {

  private DataTableService service;

  @BeforeEach
  void setUp() {
    // PG 한도 1600 − 시스템 컬럼 4 = 사용자 필드 한도 1596.
    service = new DataTableService(ReservedNameResolvers.postgres());
  }

  /** c1..cN 의 TEXT 필드 N 개. */
  private static List<DatasetColumnRequest> columns(int n) {
    return IntStream.rangeClosed(1, n)
        .mapToObj(i -> new DatasetColumnRequest("c" + i, null, "TEXT", null, true, false, null))
        .toList();
  }

  @Test
  void 사용자_필드_한도는_백엔드_한도에서_시스템_컬럼_수를_뺀다() {
    assertThat(service.maxUserColumns()).isEqualTo(1596);
  }

  @Test
  void 새_컬럼_목록이_한도면_통과하고_한도를_넘으면_400_문구() {
    assertThatCode(() -> service.validateNewColumns(columns(1596))).doesNotThrowAnyException();
    assertThatThrownBy(() -> service.validateNewColumns(columns(1597)))
        .isInstanceOf(ColumnModificationException.class)
        .hasMessage("필드는 최대 1596개까지 만들 수 있습니다(요청 1597개)");
  }

  @Test
  void 빈_목록은_통과한다() {
    assertThatCode(() -> service.validateNewColumns(List.of())).doesNotThrowAnyException();
  }

  @Test
  void 필드_추가_상한_검사는_지금_개수가_한도면_400() {
    assertThatCode(() -> service.validateColumnCountForAdd(1595)).doesNotThrowAnyException();
    assertThatThrownBy(() -> service.validateColumnCountForAdd(1596))
        .isInstanceOf(ColumnModificationException.class)
        .hasMessage("필드는 최대 1596개까지 만들 수 있습니다(지금 1596개)");
  }
}
