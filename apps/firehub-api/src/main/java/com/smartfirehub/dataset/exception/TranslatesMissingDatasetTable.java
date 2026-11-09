package com.smartfirehub.dataset.exception;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 이 컨트롤러(또는 메서드)에서 새어 나온 「테이블 없음」 DB 오류(PG 42P01·Tibero ORA-00942/JDBC-8033)를 {@link
 * DatasetNotFoundException}(404 「데이터셋을 찾을 수 없습니다. 이미 삭제되었을 수 있습니다.」)으로 답하게 한다(WD-601).
 *
 * <p><b>왜.</b> 데이터셋 화면 API 는 존재 확인(findById) 뒤 별도 단계에서 경로 데이터셋의 데이터 테이블에 SQL 을 보낸다. 그 사이 삭제가 DROP
 * TABLE 을 커밋하면 SQL 이 「테이블 없음」으로 실패하는데, 예전에는 전역 처리기가 500 「예기치 않은 오류」로 답했다. 읽기 경로(조회·통계·행 수·행 조회·행
 * 검색)는 읽기 전용 트랜잭션이라 쓰기 잠금(KEY SHARE)으로 삭제와 줄 세울 수 없어 번역으로 막고, 쓰기 경로는 잠금으로 이미 줄 서지만 같은 번역이 방어선이 된다.
 *
 * <p><b>왜 엔드포인트를 골라 붙이나.</b> 「테이블 없음」을 어디서나 「데이터셋 없음」으로 바꾸면 사용자 SQL(SQL 편집기·애널리틱스·파이프라인)이 없는 테이블을
 * 참조한 오류까지 잘못 안내된다. 그래서 경로의 데이터셋 테이블만 다루는 컨트롤러에만 붙이고, 그 안의 사용자 SQL 실행 메서드는
 * {@code @TranslatesMissingDatasetTable(false)} 로 뺀다. 메서드 표지가 클래스 표지보다 우선한다.
 *
 * <p>판정은 {@code GlobalExceptionHandler} 의 마지막 처리(전용 처리가 없는 예외)에서 한다 — 전용 처리가 있는 예외(무결성 위반 등)는 그대로다.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface TranslatesMissingDatasetTable {

  /** false 면 번역하지 않는다 — 클래스 표지가 붙은 컨트롤러의 사용자 SQL 실행 메서드를 빼는 데 쓴다. */
  boolean value() default true;
}
