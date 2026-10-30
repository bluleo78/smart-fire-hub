package com.smartfirehub.proactive.service;

import com.smartfirehub.global.exception.EditConflictException;
import com.smartfirehub.proactive.dto.CreateReportTemplateRequest;
import com.smartfirehub.proactive.dto.ReportTemplateResponse;
import com.smartfirehub.proactive.dto.ReportTemplateSummaryResponse;
import com.smartfirehub.proactive.dto.UpdateReportTemplateRequest;
import com.smartfirehub.proactive.exception.ProactiveJobException;
import com.smartfirehub.proactive.exception.ProactiveJobNotFoundException;
import com.smartfirehub.proactive.repository.ReportTemplateRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ReportTemplateService {

  private final ReportTemplateRepository reportTemplateRepository;

  /**
   * 리포트 양식 목록을 요약 정보(섹션 전체 JSONB 제외)로 page/size 페이지네이션 조회한다(#632). AI
   * subagent(list_report_templates)와 프론트엔드 목록 탭이 공통으로 사용하며, 상세 구조는 {@link #getTemplate(Long,
   * Long)}로 별도 조회해야 한다.
   */
  @Transactional(readOnly = true)
  public List<ReportTemplateSummaryResponse> getTemplates(Long userId, int page, int size) {
    return reportTemplateRepository.findAllForUser(userId, page, size);
  }

  /** 리포트 양식 단건 조회 — 기본 양식과 본인 양식만 보인다. 남의 양식은 없는 양식과 같은 404 로 답한다(WD-163, 존재 여부도 드러내지 않음). */
  @Transactional(readOnly = true)
  public ReportTemplateResponse getTemplate(Long id, Long userId) {
    return reportTemplateRepository
        .findVisibleTo(id, userId)
        .orElseThrow(() -> new ProactiveJobNotFoundException("템플릿을 찾을 수 없습니다: " + id));
  }

  @Transactional
  public ReportTemplateResponse createTemplate(CreateReportTemplateRequest request, Long userId) {
    Long id =
        reportTemplateRepository.create(
            request.name(), request.description(), request.sections(), request.style(), userId);
    return reportTemplateRepository
        .findById(id)
        .orElseThrow(() -> new ProactiveJobException("템플릿 생성 실패"));
  }

  /** 편집 충돌 안내 문구(WD-364) — 웹은 코드로 충돌 창을 띄우고, 코드를 모르는 호출부(AI 도구 등)는 이 문구를 그대로 보인다. */
  static final String EDIT_CONFLICT_MESSAGE = "이 리포트 양식은 편집을 시작한 뒤 다른 곳에서 변경되었습니다. 다시 불러온 뒤 저장하세요.";

  /**
   * 양식을 고친다 — 섹션은 전체 교체다.
   *
   * @return 새로 저장된 수정 시각 — 웹이 다음 저장의 기준 버전으로 쓴다(WD-364). 돌려주지 않으면 저장 직후 재조회가 오기 전에 다시 고쳐 저장할 때 자기
   *     저장과 충돌한다.
   */
  @Transactional
  public LocalDateTime updateTemplate(Long id, UpdateReportTemplateRequest request, Long userId) {
    ReportTemplateResponse template = getTemplate(id, userId);
    if (template.builtin()) {
      throw new ProactiveJobException("빌트인 템플릿은 수정할 수 없습니다");
    }
    // 낙관적 잠금(WD-364) — 소유자(404)·빌트인 확인 뒤에 한다: 먼저 하면 남의 양식에 대한 낡은 요청이 409 를 받아 존재가 드러난다.
    // 행 잠금을 잡고 수정 시각을 읽어 편집 기준 버전과 비교한다 — 잠금은 트랜잭션 끝까지 유지되어 동시 저장 둘이 같은 버전으로 통과하지 못한다.
    LocalDateTime currentUpdatedAt =
        reportTemplateRepository
            .lockUpdatedAtById(id)
            .orElseThrow(() -> new ProactiveJobNotFoundException("템플릿을 찾을 수 없습니다: " + id))
            .value1();
    EditConflictException.check(
        request.expectedUpdatedAt(), currentUpdatedAt, EDIT_CONFLICT_MESSAGE);
    return reportTemplateRepository.update(
        id, userId, request.name(), request.description(), request.sections(), request.style());
  }

  @Transactional
  public void deleteTemplate(Long id, Long userId) {
    ReportTemplateResponse template = getTemplate(id, userId);
    if (template.builtin()) {
      throw new ProactiveJobException("빌트인 템플릿은 삭제할 수 없습니다");
    }
    reportTemplateRepository.delete(id, userId);
  }
}
