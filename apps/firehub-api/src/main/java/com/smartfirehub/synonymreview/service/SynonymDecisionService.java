package com.smartfirehub.synonymreview.service;

import com.smartfirehub.synonymreview.dto.SynonymDecisionRecord;
import com.smartfirehub.synonymreview.dto.SynonymDecisionResponse;
import com.smartfirehub.synonymreview.repository.SynonymDecisionRepository;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** HITL 근접쌍 검수 대기열 서비스 — 이름쌍 정규화·정렬, 대기열 CRUD, 승인 시 ai-agent 병합 위임. */
@Service
@RequiredArgsConstructor
public class SynonymDecisionService {

  private final SynonymDecisionRepository repo;
  private final SynonymMergeClient mergeClient;

  // resolver.ts normalizeName과 동일 규칙(trim + 연속공백 1칸 + 소문자화) — 정렬 키로만 쓰고
  // 저장은 원본(trim만 적용) 표기를 사용해 UI 가독성을 지킨다.
  private static String normalize(String s) {
    return s.trim().replaceAll("\\s+", " ").toLowerCase();
  }

  /** LLM "같다" 판정 근접쌍을 등록한다. 이름쌍은 정규화 비교로 정렬해 저장한다(순서 무관 조회 보장). */
  public void recordPending(String entityType, String rawNameA, String rawNameB, Double similarity, String rationale) {
    String a = rawNameA.trim();
    String b = rawNameB.trim();
    if (normalize(a).compareTo(normalize(b)) > 0) {
      String tmp = a;
      a = b;
      b = tmp;
    }
    repo.upsertPending(entityType, a, b, similarity, rationale);
  }

  /** 근접쌍의 기존 결정 조회. 없으면 "none". */
  public String lookupDecision(String entityType, String rawNameA, String rawNameB) {
    String a = rawNameA.trim();
    String b = rawNameB.trim();
    if (normalize(a).compareTo(normalize(b)) > 0) {
      String tmp = a;
      a = b;
      b = tmp;
    }
    return repo.findDecision(entityType, a, b).orElse("none");
  }

  public List<SynonymDecisionResponse> listPending() {
    return repo.findPending().stream().map(SynonymDecisionService::toResponse).toList();
  }

  /** 승인 — ai-agent 병합을 먼저 호출하고, 성공해야만 DB status를 approved로 갱신한다(실패 시 예외 전파, pending 유지). */
  public SynonymDecisionResponse approve(long id, long userId) {
    SynonymDecisionRecord row = getPendingOrThrow(id);
    mergeClient.mergeEntities(row.entityType(), row.nameA(), row.nameB());
    repo.updateStatus(id, "approved", userId);
    return toResponse(repo.findById(id).orElseThrow());
  }

  /** 거부 — Neo4j 변경 없이 DB status만 갱신한다. */
  public SynonymDecisionResponse reject(long id, long userId) {
    getPendingOrThrow(id);
    repo.updateStatus(id, "rejected", userId);
    return toResponse(repo.findById(id).orElseThrow());
  }

  private SynonymDecisionRecord getPendingOrThrow(long id) {
    SynonymDecisionRecord row =
        repo.findById(id).orElseThrow(() -> new IllegalArgumentException("대기열 항목을 찾을 수 없습니다: " + id));
    if (!"pending".equals(row.status())) {
      throw new IllegalStateException("이미 처리된 항목입니다(status=" + row.status() + "): " + id);
    }
    return row;
  }

  private static SynonymDecisionResponse toResponse(SynonymDecisionRecord r) {
    return new SynonymDecisionResponse(
        r.id(), r.entityType(), r.nameA(), r.nameB(), r.status(), r.similarity(), r.rationale(),
        r.decidedBy(), r.decidedAt() == null ? null : r.decidedAt().toString(),
        r.createdAt() == null ? null : r.createdAt().toString());
  }
}
