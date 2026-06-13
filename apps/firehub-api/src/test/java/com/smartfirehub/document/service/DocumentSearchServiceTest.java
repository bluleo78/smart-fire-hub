package com.smartfirehub.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.smartfirehub.document.dto.DocumentSearchHit;
import com.smartfirehub.document.dto.DocumentSearchRequest;
import com.smartfirehub.document.repository.DocumentChunkRepository;
import com.smartfirehub.embedding.EmbeddingProvider;
import com.smartfirehub.embedding.EmbeddingProviderFactory;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class DocumentSearchServiceTest {

  private EmbeddingProvider fakeProvider(int dim) {
    return new EmbeddingProvider() {
      public List<float[]> embed(List<String> texts) {
        return texts.stream().map(t -> new float[dim]).toList();
      }
      public String modelId() { return "fake"; }
      public int dimension() { return dim; }
    };
  }

  @Test
  void searchEmbedsQueryAndDelegatesToRepository() {
    var factory = Mockito.mock(EmbeddingProviderFactory.class);
    var repo = Mockito.mock(DocumentChunkRepository.class);
    when(factory.current()).thenReturn(fakeProvider(1024));
    var hit = new DocumentSearchHit(1L, 2L, 3L, "f.txt", 0, "내용", 0.9);
    when(repo.searchByCosine(Mockito.any(), Mockito.eq(List.of(3L)), Mockito.eq(5)))
        .thenReturn(List.of(hit));

    var service = new DocumentSearchService(factory, repo);
    var result = service.search(new DocumentSearchRequest("질의", List.of(3L), 5));

    assertThat(result).hasSize(1);
    assertThat(result.get(0).content()).isEqualTo("내용");
  }

  @Test
  void searchRejectsBlankQuery() {
    var service = new DocumentSearchService(
        Mockito.mock(EmbeddingProviderFactory.class), Mockito.mock(DocumentChunkRepository.class));
    assertThatThrownBy(() -> service.search(new DocumentSearchRequest("  ", null, 5)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
