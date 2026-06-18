package com.smartfirehub.dataset.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.when;

import com.smartfirehub.embedding.EmbeddingProvider;
import com.smartfirehub.embedding.EmbeddingProviderFactory;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

/** DatasetSearchService 단위 테스트: 리포지토리·임베딩을 모킹해 모드 분기와 RRF 융합을 검증한다. */
@ExtendWith(MockitoExtension.class)
class DatasetSearchServiceTest {

  @Mock DatasetSearchRepository repository;
  @Mock EmbeddingProviderFactory embeddingFactory;
  @Mock EmbeddingProvider embeddingProvider;

  private DatasetSearchService service() {
    return new DatasetSearchService(repository, embeddingFactory);
  }

  @Test
  void hybrid_양쪽_등장_데이터셋이_한쪽만_등장보다_상위로_융합되고_RRF_점수가_정확하다() {
    when(embeddingFactory.current()).thenReturn(embeddingProvider);
    when(embeddingProvider.embed(List.of("화재"))).thenReturn(List.of(new float[1024]));

    // 비대칭 시나리오: dsA 는 양쪽 rank0, dsB·dsC 는 각각 한쪽만 rank1.
    // → dsA = 1/(60+0+1) + 1/(60+0+1) = 2/61 (양쪽 누적, merge/Double::sum 검증)
    // → dsB = dsC = 1/(60+1+1) = 1/62 (한쪽만)
    var dsA = hit(1L);
    var dsB = hit(2L);
    var dsC = hit(3L);
    when(repository.searchByCosine(
            ArgumentMatchers.any(), ArgumentMatchers.isNull(), ArgumentMatchers.anyInt()))
        .thenReturn(List.of(dsA, dsC)); // 코사인: dsA(rank0), dsC(rank1)
    when(repository.searchByTrigram(
            ArgumentMatchers.eq("화재"), ArgumentMatchers.isNull(), ArgumentMatchers.anyInt()))
        .thenReturn(List.of(dsA, dsB)); // 트라이그램: dsA(rank0), dsB(rank1)

    var req = new DatasetSearchRequest("화재", 10, DatasetSearchMode.HYBRID, null);
    List<DatasetSearchHit> result = service().search(req);

    double expectedA = 1.0 / 61 + 1.0 / 61; // 2/61
    double expectedSingle = 1.0 / 62; // 1/62

    // 1) 양쪽 등장(dsA)이 1위이며, 한쪽만 등장(dsB/dsC)보다 점수가 크다.
    assertThat(result).hasSize(3);
    assertThat(result.get(0).datasetId()).isEqualTo(1L);
    assertThat(result.get(0).score()).isGreaterThan(result.get(1).score());
    assertThat(result.get(0).score()).isGreaterThan(result.get(2).score());

    // 2) 정확한 RRF 점수 (1/(60+rank+1) 공식, RRF_K=60).
    assertThat(result.get(0).score()).isCloseTo(expectedA, within(1e-9));
    assertThat(result.get(1).score()).isCloseTo(expectedSingle, within(1e-9));
    assertThat(result.get(2).score()).isCloseTo(expectedSingle, within(1e-9));

    // 3) 동점(dsB=1/62, dsC=1/62)은 datasetId 오름차순으로 결정적 정렬 → dsB(id=2)가 dsC(id=3)보다 먼저.
    assertThat(result.get(1).datasetId()).isEqualTo(2L);
    assertThat(result.get(2).datasetId()).isEqualTo(3L);
  }

  @Test
  void keyword_모드는_임베딩을_호출하지_않는다() {
    when(repository.searchByTrigram(
            ArgumentMatchers.eq("x"), ArgumentMatchers.isNull(), ArgumentMatchers.anyInt()))
        .thenReturn(List.of(hit(1L)));
    var req = new DatasetSearchRequest("x", 10, DatasetSearchMode.KEYWORD, null);
    assertThat(service().search(req)).hasSize(1);
    Mockito.verifyNoInteractions(embeddingFactory);
  }

  @Test
  void semantic_모드는_트라이그램을_호출하지_않는다() {
    when(embeddingFactory.current()).thenReturn(embeddingProvider);
    when(embeddingProvider.embed(List.of("화재"))).thenReturn(List.of(new float[1024]));
    when(repository.searchByCosine(
            ArgumentMatchers.any(), ArgumentMatchers.isNull(), ArgumentMatchers.anyInt()))
        .thenReturn(List.of(hit(1L)));
    var req = new DatasetSearchRequest("화재", 10, DatasetSearchMode.SEMANTIC, null);
    assertThat(service().search(req)).hasSize(1);
    Mockito.verify(repository, Mockito.never())
        .searchByTrigram(
            ArgumentMatchers.anyString(), ArgumentMatchers.any(), ArgumentMatchers.anyInt());
  }

  @Test
  void mode가_null이면_HYBRID로_동작한다() {
    when(embeddingFactory.current()).thenReturn(embeddingProvider);
    when(embeddingProvider.embed(List.of("화재"))).thenReturn(List.of(new float[1024]));
    when(repository.searchByCosine(
            ArgumentMatchers.any(), ArgumentMatchers.isNull(), ArgumentMatchers.anyInt()))
        .thenReturn(List.of(hit(1L)));
    when(repository.searchByTrigram(
            ArgumentMatchers.eq("화재"), ArgumentMatchers.isNull(), ArgumentMatchers.anyInt()))
        .thenReturn(List.of(hit(2L)));

    var req = new DatasetSearchRequest("화재", 10, null, null);
    assertThat(service().search(req)).hasSize(2);
  }

  @Test
  void hybrid_후보풀은_CANDIDATE_POOL_크기로_조회된다() {
    when(embeddingFactory.current()).thenReturn(embeddingProvider);
    when(embeddingProvider.embed(List.of("화재"))).thenReturn(List.of(new float[1024]));
    when(repository.searchByCosine(
            ArgumentMatchers.any(), ArgumentMatchers.isNull(), ArgumentMatchers.anyInt()))
        .thenReturn(List.of(hit(1L)));
    when(repository.searchByTrigram(
            ArgumentMatchers.eq("화재"), ArgumentMatchers.isNull(), ArgumentMatchers.anyInt()))
        .thenReturn(List.of(hit(2L)));

    var req = new DatasetSearchRequest("화재", 5, DatasetSearchMode.HYBRID, null);
    service().search(req);

    ArgumentCaptor<Integer> cosineLimit = ArgumentCaptor.forClass(Integer.class);
    ArgumentCaptor<Integer> trigramLimit = ArgumentCaptor.forClass(Integer.class);
    Mockito.verify(repository)
        .searchByCosine(ArgumentMatchers.any(), ArgumentMatchers.isNull(), cosineLimit.capture());
    Mockito.verify(repository)
        .searchByTrigram(
            ArgumentMatchers.eq("화재"), ArgumentMatchers.isNull(), trigramLimit.capture());
    assertThat(cosineLimit.getValue()).isEqualTo(50);
    assertThat(trigramLimit.getValue()).isEqualTo(50);
  }

  @Test
  void topK가_null이면_기본_10으로_정규화된다() {
    when(repository.searchByTrigram(
            ArgumentMatchers.eq("x"), ArgumentMatchers.isNull(), ArgumentMatchers.anyInt()))
        .thenReturn(List.of(hit(1L)));
    var req = new DatasetSearchRequest("x", null, DatasetSearchMode.KEYWORD, null);
    service().search(req);

    ArgumentCaptor<Integer> topK = ArgumentCaptor.forClass(Integer.class);
    Mockito.verify(repository)
        .searchByTrigram(ArgumentMatchers.eq("x"), ArgumentMatchers.isNull(), topK.capture());
    assertThat(topK.getValue()).isEqualTo(10);
  }

  @Test
  void topK가_20초과면_20으로_제한된다() {
    when(repository.searchByTrigram(
            ArgumentMatchers.eq("x"), ArgumentMatchers.isNull(), ArgumentMatchers.anyInt()))
        .thenReturn(List.of(hit(1L)));
    var req = new DatasetSearchRequest("x", 999, DatasetSearchMode.KEYWORD, null);
    service().search(req);

    ArgumentCaptor<Integer> topK = ArgumentCaptor.forClass(Integer.class);
    Mockito.verify(repository)
        .searchByTrigram(ArgumentMatchers.eq("x"), ArgumentMatchers.isNull(), topK.capture());
    assertThat(topK.getValue()).isEqualTo(20);
  }

  @Test
  void storageType_필터가_리포지토리로_전달된다() {
    when(repository.searchByTrigram(
            ArgumentMatchers.eq("x"), ArgumentMatchers.eq("TABLE"), ArgumentMatchers.anyInt()))
        .thenReturn(List.of(hit(1L)));
    var req = new DatasetSearchRequest("x", 10, DatasetSearchMode.KEYWORD, "TABLE");
    assertThat(service().search(req)).hasSize(1);
    Mockito.verify(repository)
        .searchByTrigram(
            ArgumentMatchers.eq("x"), ArgumentMatchers.eq("TABLE"), ArgumentMatchers.anyInt());
  }

  private static DatasetSearchHit hit(long id) {
    return new DatasetSearchHit(id, "n" + id, null, "TABLE", "SOURCE", "t" + id, null, 0.0);
  }
}
