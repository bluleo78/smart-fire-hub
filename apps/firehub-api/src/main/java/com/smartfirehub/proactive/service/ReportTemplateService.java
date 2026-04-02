package com.smartfirehub.proactive.service;

import com.smartfirehub.proactive.dto.CreateReportTemplateRequest;
import com.smartfirehub.proactive.dto.ReportTemplateResponse;
import com.smartfirehub.proactive.dto.UpdateReportTemplateRequest;
import com.smartfirehub.proactive.exception.ProactiveJobException;
import com.smartfirehub.proactive.repository.ReportTemplateRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ReportTemplateService {

  private final ReportTemplateRepository reportTemplateRepository;

  @Transactional(readOnly = true)
  public List<ReportTemplateResponse> getTemplates(Long userId) {
    return reportTemplateRepository.findAllForUser(userId);
  }

  @Transactional(readOnly = true)
  public ReportTemplateResponse getTemplate(Long id) {
    return reportTemplateRepository
        .findById(id)
        .orElseThrow(() -> new ProactiveJobException("템플릿을 찾을 수 없습니다: " + id));
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

  @Transactional
  public void updateTemplate(Long id, UpdateReportTemplateRequest request, Long userId) {
    ReportTemplateResponse template = getTemplate(id);
    if (template.builtin()) {
      throw new ProactiveJobException("빌트인 템플릿은 수정할 수 없습니다");
    }
    reportTemplateRepository.update(
        id, userId, request.name(), request.description(), request.sections(), request.style());
  }

  @Transactional
  public void deleteTemplate(Long id, Long userId) {
    ReportTemplateResponse template = getTemplate(id);
    if (template.builtin()) {
      throw new ProactiveJobException("빌트인 템플릿은 삭제할 수 없습니다");
    }
    reportTemplateRepository.delete(id, userId);
  }
}
