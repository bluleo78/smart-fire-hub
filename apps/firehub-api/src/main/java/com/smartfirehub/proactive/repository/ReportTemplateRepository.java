package com.smartfirehub.proactive.repository;

import com.smartfirehub.global.dto.LockedRowVersion;
import com.smartfirehub.proactive.dto.ReportTemplateResponse;
import com.smartfirehub.proactive.dto.ReportTemplateSummaryResponse;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 리포트 양식 저장소.
 *
 * <p>구현은 {@code JooqReportTemplateRepository}(jOOQ/PostgreSQL) 이다 — WD-485. 다루는 테이블: {@code
 * report_template}.
 */
public interface ReportTemplateRepository {

  /**
   * 목록 조회 전용 요약 조회 (#632).
   *
   * <p>{@code sections}/{@code style} 전체 JSONB를 읽지 않고, 섹션 개수만 DB에서 {@code jsonb_array_length()}로
   * 계산해 반환한다 — 목록 조회 payload가 템플릿 규모/섹션 수에 비례해 커지는 문제를 근본적으로 없앤다(select-then-drop이 아니라 애초에 컬럼을 안
   * 읽음). 상세 구조가 필요하면 {@link #findById(Long)}(단건 조회)을 사용한다.
   *
   * @param page 0-based 페이지 번호
   * @param size 페이지당 개수
   */
  List<ReportTemplateSummaryResponse> findAllForUser(Long userId, int page, int size);

  Optional<ReportTemplateResponse> findById(Long id);

  /**
   * 사용자가 쓸 수 있는 양식만 단건 조회한다 — 기본(빌트인, user_id NULL) 양식이거나 본인 양식일 때만 돌려준다(WD-163).
   *
   * <p>목록({@link #findAllForUser})과 같은 범위다. 상세 조회·작업 연결·작업 실행이 이 범위를 따르지 않으면 양식 번호만으로 남의 비공개 양식(섹션
   * 지시문 전체)을 읽거나 내 작업에 연결해 쓸 수 있었다.
   */
  Optional<ReportTemplateResponse> findVisibleTo(Long id, Long userId);

  Long create(
      String name,
      String description,
      List<Map<String, Object>> sections,
      String style,
      Long userId);

  /**
   * 수정 시각을 행 잠금(FOR UPDATE)과 함께 읽는다 — 편집 충돌(낙관적 잠금) 비교용(WD-364, 파이프라인 WD-353 과 같은 방식).
   *
   * <p>잠금 없이 읽고 비교하면 동시에 들어온 두 저장이 같은 값을 읽어 둘 다 통과한다. 잠금을 잡으면 나중 저장은 앞 저장이 커밋될 때까지 기다렸다가 바뀐 값을 읽어
   * 충돌로 거부된다. 소유자 확인을 마친 뒤에 부른다 — 먼저 부르면 남의 행에 대한 낡은 요청이 404 대신 409 를 받아 존재가 드러난다.
   *
   * <p>행이 없으면 빈 값, 행은 있는데 수정 시각이 비어 있으면(nullable 컬럼, V42) {@code updatedAt} 이 null 인 {@link
   * LockedRowVersion} 이다 — 둘을 구분해야 수정 시각이 빈 옛 행을 "없음"으로 잘못 거부하지 않는다.
   */
  Optional<LockedRowVersion> lockUpdatedAtById(Long id);

  /**
   * 양식을 고치고 새로 저장된 수정 시각을 돌려준다(WD-364).
   *
   * <p>Java 값이 아니라 DB 에 저장된 값(RETURNING)을 돌려준다 — PG timestamp 는 마이크로초로 반올림하므로 넣은 값을 그대로 돌려주면 다음 저장의
   * 기준 버전이 저장값과 어긋나 자기 저장과 충돌한다. 고친 행이 없으면(남의 양식·빌트인) null.
   */
  LocalDateTime update(
      Long id,
      Long userId,
      String name,
      String description,
      List<Map<String, Object>> sections,
      String style);

  /**
   * 사용자 본인 양식을 지운다(빌트인은 조건에서 빠진다).
   *
   * @return 지운 행 수 — 동시 삭제에서 진 쪽은 0 이라 호출자가 404 로 바꾼다(WD-584)
   */
  int delete(Long id, Long userId);
}
