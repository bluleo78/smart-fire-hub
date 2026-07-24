package com.smartfirehub.graphreview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.graphreview.dto.EntityRelationRef;
import com.smartfirehub.graphreview.dto.ReviewItemRecord;
import com.smartfirehub.graphreview.repository.ReviewItemRepository;
import com.smartfirehub.graphreview.service.GraphMutationClient;
import com.smartfirehub.graphreview.service.ReviewItemService;
import com.smartfirehub.document.repository.DocumentChunkRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class ReviewItemServiceTest {

  private ReviewItemRepository repo;
  private GraphMutationClient mutationClient;
  private DocumentChunkRepository chunkRepository;
  private ReviewItemService service;

  @BeforeEach
  void setUp() {
    repo = Mockito.mock(ReviewItemRepository.class);
    mutationClient = Mockito.mock(GraphMutationClient.class);
    chunkRepository = Mockito.mock(DocumentChunkRepository.class);
    service = new ReviewItemService(repo, mutationClient, chunkRepository, new ObjectMapper());
  }

  @Test
  void recordPendingSynonym_ordersNamesByNormalizedComparison() {
    // "분전반의 누전" > "전기적 요인" (정규화 사전순) → nameA/nameB가 정렬되어 payload/dedupe에 반영.
    service.recordPendingSynonym("Cause", "분전반의 누전", "전기적 요인", 0.7, "동의어", null, null);
    verify(repo).upsertPending(eq("synonym_merge"), any(), eq(null), eq("similarity"), eq(0.7), eq("동의어"),
        argThatContainsBoth());
  }

  @Test
  @DisplayName("동의어 등록 시 datasetId와 sourceChunkIds가 저장되고 evidence가 원문 스니펫을 반환한다")
  void recordPendingSynonym_persistsEvidence() {
    long datasetId = 99L;
    long chunkId = 10L;

    // 실제 서비스가 기록한 payload를 캡처 — 손으로 만든 payload가 아니라 recordPendingSynonym이
    // 진짜로 sourceChunkIds를 직렬화했는지, datasetId를 그대로 전달했는지를 검증한다.
    service.recordPendingSynonym("Cause", "전기적 요인", "분전반의 누전", 0.7, "동의어", datasetId, List.of(chunkId));

    ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
    verify(repo).upsertPending(eq("synonym_merge"), any(), eq(datasetId), eq("similarity"), eq(0.7), eq("동의어"),
        payloadCaptor.capture());

    ReviewItemRecord persisted = new ReviewItemRecord(
        1L, "synonym_merge", "pending", datasetId, "similarity", 0.7, "동의어",
        payloadCaptor.getValue(), null, null, LocalDateTime.now());
    when(repo.findById(1L)).thenReturn(Optional.of(persisted));
    when(chunkRepository.findChunkContentsByDataset(datasetId))
        .thenReturn(List.of(new DocumentChunkRepository.ChunkContent(chunkId, "원문 청크 내용")));

    var evidence = service.evidence(1L);
    assertThat(evidence).isNotEmpty();
    assertThat(evidence.get(0).chunkId()).isEqualTo(chunkId);
  }

  @Test
  @DisplayName("datasetId/sourceChunkIds 없이 등록하면 저장되지만 evidence는 빈 배열(신규-only·하위호환)")
  void recordPendingSynonym_noEvidenceWhenNull() {
    service.recordPendingSynonym("Cause", "누전", "합선", 0.7, "동의어", null, null);
    verify(repo).upsertPending(eq("synonym_merge"), any(), eq(null), eq("similarity"), eq(0.7), eq("동의어"), any());

    ReviewItemRecord persisted = new ReviewItemRecord(
        2L, "synonym_merge", "pending", null, "similarity", 0.7, "동의어", "{}", null, null, LocalDateTime.now());
    when(repo.findById(2L)).thenReturn(Optional.of(persisted));

    assertThat(service.evidence(2L)).isEmpty();
  }

  @Test
  void approve_synonym_callsMergeThenUpdatesStatus() {
    ReviewItemRecord pending = record("synonym_merge",
        "{\"entityType\":\"Cause\",\"nameA\":\"전기적 요인\",\"nameB\":\"분전반의 누전\"}");
    ReviewItemRecord approved = withStatus(pending, "approved");
    when(repo.findById(1L)).thenReturn(Optional.of(pending), Optional.of(approved));

    var res = service.approve(1L, null, 1L);

    verify(mutationClient).mergeEntities("Cause", "전기적 요인", "분전반의 누전");
    verify(repo).updateStatus(1L, "approved", 1L);
    assertThat(res.status()).isEqualTo("approved");
  }

  @Test
  void approve_property_requiresCorrectedValue_thenSetsProperty() {
    ReviewItemRecord pending = record("property_normalization",
        "{\"entityKey\":\"3:화재\",\"propertyName\":\"피해액\",\"dataType\":\"number\"}");
    // 항상 pending 반환 — 두 번 호출(throw 경로 + success 경로)에서 모두 pending 상태여야 한다.
    // (호출 순서별 다른 값을 주면 두 번째 approve가 status≠pending으로 오작동)
    when(repo.findById(2L)).thenReturn(Optional.of(pending));

    // correctedValue 없으면 거부(400성 예외).
    assertThatThrownBy(() -> service.approve(2L, null, 1L)).isInstanceOf(IllegalArgumentException.class);
    verify(mutationClient, never()).setProperty(any(), any(), any(), any());

    service.approve(2L, "30000000", 1L);
    verify(mutationClient).setProperty("3:화재", "피해액", "number", "30000000");
    verify(repo).updateStatus(2L, "approved", 1L);
  }

  @Test
  void reject_updatesStatusWithoutGraphMutation() {
    ReviewItemRecord pending = record("property_normalization", "{\"entityKey\":\"3:화재\"}");
    when(repo.findById(3L)).thenReturn(Optional.of(pending), Optional.of(withStatus(pending, "rejected")));

    service.reject(3L, 1L);

    verify(mutationClient, never()).setProperty(any(), any(), any(), any());
    verify(mutationClient, never()).mergeEntities(any(), any(), any());
    verify(repo).updateStatus(3L, "rejected", 1L);
  }

  @Test
  void approve_alreadyDecided_throws() {
    when(repo.findById(4L)).thenReturn(Optional.of(withStatus(record("synonym_merge", "{}"), "approved")));
    assertThatThrownBy(() -> service.approve(4L, null, 1L)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("엔티티 등록 시 datasetId/sourceChunkIds가 저장되고 evidence가 원문 스니펫을 반환한다")
  void recordPendingEntity_persistsEvidence() {
    long datasetId = 99L, chunkId = 10L;
    service.recordPendingEntity(datasetId, "Cause", "노후배선", null, List.of(chunkId), 0.3, "추론",
        List.of(new EntityRelationRef("CAUSED_BY", "out", "3:과부하")));

    ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
    verify(repo).upsertPending(eq("entity_extraction"), any(), eq(datasetId), eq("low_confidence"), eq(0.3), any(),
        payloadCaptor.capture());
    assertThat(payloadCaptor.getValue()).contains("노후배선").contains("relations").contains("3:과부하");

    ReviewItemRecord persisted = new ReviewItemRecord(1L, "entity_extraction", "pending", datasetId,
        "low_confidence", 0.3, "추론", payloadCaptor.getValue(), null, null, LocalDateTime.now());
    when(repo.findById(1L)).thenReturn(Optional.of(persisted));
    when(chunkRepository.findChunkContentsByDataset(datasetId))
        .thenReturn(List.of(new DocumentChunkRepository.ChunkContent(chunkId, "원문 청크 내용")));
    var evidence = service.evidence(1L);
    assertThat(evidence).isNotEmpty();
    assertThat(evidence.get(0).chunkId()).isEqualTo(chunkId);
  }

  @Test
  @DisplayName("엔티티 승인 시 addEntity를 호출하고(정정 없음) status를 approved로 갱신한다")
  void approve_entity_callsAddEntity() {
    ReviewItemRecord pending = new ReviewItemRecord(3L, "entity_extraction", "pending", 99L, "low_confidence", 0.3, "추론",
        "{\"entityType\":\"Cause\",\"name\":\"노후배선\",\"sourceChunkIds\":[10],"
        + "\"relations\":[{\"relType\":\"CAUSED_BY\",\"direction\":\"out\",\"otherKey\":\"3:과부하\"}]}",
        null, null, LocalDateTime.now());
    when(repo.findById(3L)).thenReturn(Optional.of(pending));

    service.approve(3L, null, 1L); // correctedValue 불필요.

    ArgumentCaptor<List<GraphMutationClient.RelationRef>> relCaptor = ArgumentCaptor.forClass(List.class);
    verify(mutationClient).addEntity(eq("Cause"), eq("노후배선"), any(), eq(List.of(10L)), relCaptor.capture());
    assertThat(relCaptor.getValue()).hasSize(1);
    assertThat(relCaptor.getValue().get(0).otherKey()).isEqualTo("3:과부하");
    verify(repo).updateStatus(3L, "approved", 1L);
  }

  @Test
  @DisplayName("엔티티 lookup은 저장된 결정 상태를 반환한다")
  void lookupEntity_returnsStatus() {
    when(repo.findDecisionStatus("entity_extraction", "Cause|노후배선")).thenReturn(Optional.of("approved"));
    assertThat(service.lookupEntity("Cause", "노후배선")).isEqualTo("approved");
    when(repo.findDecisionStatus("entity_extraction", "Cause|미결")).thenReturn(Optional.empty());
    assertThat(service.lookupEntity("Cause", "미결")).isEqualTo("none");
  }

  // --- helpers ---
  private static ReviewItemRecord record(String itemType, String payloadJson) {
    return new ReviewItemRecord(1L, itemType, "pending", null, null, null, null, payloadJson, null, null, LocalDateTime.now());
  }
  private static ReviewItemRecord withStatus(ReviewItemRecord r, String status) {
    return new ReviewItemRecord(r.id(), r.itemType(), status, r.datasetId(), r.signalType(), r.signalScore(),
        r.reason(), r.payloadJson(), 1L, LocalDateTime.now(), r.createdAt());
  }
  // payload가 두 이름을 모두 포함하는지 확인하는 Mockito matcher.
  private static String argThatContainsBoth() {
    return org.mockito.ArgumentMatchers.argThat(s -> s != null && s.contains("전기적 요인") && s.contains("분전반의 누전"));
  }
}
