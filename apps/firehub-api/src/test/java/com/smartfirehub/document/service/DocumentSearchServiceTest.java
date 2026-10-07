package com.smartfirehub.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartfirehub.document.dto.DocumentSearchHit;
import com.smartfirehub.document.dto.DocumentSearchRequest;
import com.smartfirehub.document.repository.DocumentChunkRepository;
import com.smartfirehub.embedding.EmbeddingDimension;
import com.smartfirehub.embedding.EmbeddingNotConfiguredException;
import com.smartfirehub.embedding.EmbeddingProvider;
import com.smartfirehub.embedding.EmbeddingProviderFactory;
import com.smartfirehub.embedding.EmbeddingSpace;
import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.DatasetAccessGuard;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class DocumentSearchServiceTest {

  // 가시성 SQL 은 가드(목)가 만든 고정 문자열 — 서비스가 이 값을 모든 검색 분기에 그대로 전달하는지 eq(VIS) 로 고정한다.
  private static final String VIS = "VIS_SQL";
  private static final Clearance CLEARANCE = Clearance.none(1L, 1L);

  private DatasetAccessGuard guard() {
    var guard = Mockito.mock(DatasetAccessGuard.class);
    when(guard.visibleSql(CLEARANCE, "d")).thenReturn(VIS);
    return guard;
  }

  private EmbeddingProvider fakeProvider(int dim) {
    return new EmbeddingProvider() {
      public List<float[]> embed(List<String> texts) {
        return texts.stream().map(t -> new float[dim]).toList();
      }

      public String modelId() {
        return "fake";
      }

      public int dimension() {
        return dim;
      }
    };
  }

  @Test
  void searchEmbedsQueryAndDelegatesToRepository() {
    var factory = Mockito.mock(EmbeddingProviderFactory.class);
    var repo = Mockito.mock(DocumentChunkRepository.class);
    when(factory.current()).thenReturn(fakeProvider(1024));
    var hit = new DocumentSearchHit(1L, 2L, 3L, "f.txt", 0, "내용", 0.9);
    // 검색 공간은 provider 의 (차원, 모델)이어야 한다 — 다른 공간이면 차원 테이블·모델 필터가 어긋난다.
    when(repo.searchByCosine(
            Mockito.eq(new EmbeddingSpace(EmbeddingDimension.D1024, "fake")),
            Mockito.any(),
            Mockito.eq(List.of(3L)),
            Mockito.eq(5),
            Mockito.eq(VIS)))
        .thenReturn(List.of(hit));

    var service = new DocumentSearchService(factory, repo, guard());
    // SEMANTIC 모드: 쿼리를 임베딩해 코사인 검색만 위임한다(topK 그대로 전달).
    // (3-인자 생성자는 이제 HYBRID 기본이므로 의미검색 경로는 모드를 명시한다.)
    var result =
        service.search(
            new DocumentSearchRequest(
                "질의", List.of(3L), 5, com.smartfirehub.document.dto.SearchMode.SEMANTIC),
            CLEARANCE);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).content()).isEqualTo("내용");
  }

  @Test
  void searchRejectsBlankQuery() {
    var service =
        new DocumentSearchService(
            Mockito.mock(EmbeddingProviderFactory.class),
            Mockito.mock(DocumentChunkRepository.class),
            guard());
    assertThatThrownBy(() -> service.search(new DocumentSearchRequest("  ", null, 5), CLEARANCE))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void keywordModeUsesTrigramAndSkipsEmbedding() {
    var factory = Mockito.mock(EmbeddingProviderFactory.class);
    var repo = Mockito.mock(DocumentChunkRepository.class);
    var hit = new DocumentSearchHit(1L, 2L, 3L, "k.txt", 0, "키워드", 0.7);
    when(repo.searchByTrigram(
            Mockito.eq("질의"), Mockito.eq(List.of(3L)), Mockito.eq(5), Mockito.eq(VIS)))
        .thenReturn(List.of(hit));

    var service = new DocumentSearchService(factory, repo, guard());
    var result =
        service.search(
            new DocumentSearchRequest(
                "질의", List.of(3L), 5, com.smartfirehub.document.dto.SearchMode.KEYWORD),
            CLEARANCE);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).content()).isEqualTo("키워드");
    // KEYWORD 모드는 임베딩 provider 를 호출하지 않아야 한다(회복탄력성).
    verify(factory, never()).current();
  }

  @Test
  void hybridModeFusesSemanticAndKeywordWithRrf() {
    var factory = Mockito.mock(EmbeddingProviderFactory.class);
    var repo = Mockito.mock(DocumentChunkRepository.class);
    when(factory.current()).thenReturn(fakeProvider(1024));

    // 시맨틱: A(rank0), B(rank1) / 키워드: B(rank0), C(rank1)
    var a = new DocumentSearchHit(10L, 1L, 3L, "f", 0, "A", 0.9);
    var b = new DocumentSearchHit(11L, 1L, 3L, "f", 1, "B", 0.8);
    var c = new DocumentSearchHit(12L, 1L, 3L, "f", 2, "C", 0.7);
    when(repo.searchByCosine(
            Mockito.any(), Mockito.any(), Mockito.eq(List.of(3L)), Mockito.eq(50), Mockito.eq(VIS)))
        .thenReturn(List.of(a, b));
    when(repo.searchByTrigram(
            Mockito.eq("질의"), Mockito.eq(List.of(3L)), Mockito.eq(50), Mockito.eq(VIS)))
        .thenReturn(List.of(b, c));

    var service = new DocumentSearchService(factory, repo, guard());
    var result =
        service.search(
            new DocumentSearchRequest(
                "질의", List.of(3L), 10, com.smartfirehub.document.dto.SearchMode.HYBRID),
            CLEARANCE);

    // B 는 양쪽에 등장 → RRF 점수 최고 → 1위. A, C 는 한쪽씩.
    assertThat(result).hasSize(3);
    assertThat(result.get(0).chunkId()).isEqualTo(11L); // B
    // chunkId 중복 없이 병합되어야 한다.
    assertThat(result.stream().map(DocumentSearchHit::chunkId).distinct().count()).isEqualTo(3L);
  }

  @Test
  void defaultModeIsHybrid() {
    var factory = Mockito.mock(EmbeddingProviderFactory.class);
    var repo = Mockito.mock(DocumentChunkRepository.class);
    when(factory.current()).thenReturn(fakeProvider(1024));
    when(repo.searchByCosine(
            Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyInt(), Mockito.eq(VIS)))
        .thenReturn(List.of());
    when(repo.searchByTrigram(Mockito.any(), Mockito.any(), Mockito.anyInt(), Mockito.eq(VIS)))
        .thenReturn(List.of());

    var service = new DocumentSearchService(factory, repo, guard());
    // 3-인자 생성자 → mode 미지정 → HYBRID → 양쪽 repo 호출돼야 함.
    service.search(new DocumentSearchRequest("질의", List.of(3L), 5), CLEARANCE);

    verify(repo)
        .searchByCosine(
            Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyInt(), Mockito.eq(VIS));
    verify(repo).searchByTrigram(Mockito.any(), Mockito.any(), Mockito.anyInt(), Mockito.eq(VIS));
  }

  @Test
  void semanticSearchWhenNotConfiguredThrowsNotConfigured() {
    var factory = Mockito.mock(EmbeddingProviderFactory.class);
    var repo = Mockito.mock(DocumentChunkRepository.class);
    when(factory.current()).thenThrow(new EmbeddingNotConfiguredException());
    var service = new DocumentSearchService(factory, repo, guard());
    assertThatThrownBy(
            () ->
                service.search(
                    new DocumentSearchRequest(
                        "화재", List.of(3L), 5, com.smartfirehub.document.dto.SearchMode.SEMANTIC),
                    CLEARANCE))
        .isInstanceOf(EmbeddingNotConfiguredException.class);
  }
}
