package com.smartfirehub.vectorstore.qdrant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.smartfirehub.vectorstore.qdrant.BigramSparseEncoder.Profile;
import com.smartfirehub.vectorstore.qdrant.BigramSparseEncoder.SparseTerms;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 바이그램 sparse 생성 규칙 고정 테스트(ADR 0002 결정 8).
 *
 * <p>기대값은 Java 구현과 독립된 Python 참조 구현(PoC {@code kw.py} 의 {@code bigrams()} + 표준 murmur3_x86_32 +
 * BM25 TF)으로 뽑았다. 이 값이 바뀌면 이미 저장된 sparse 와 질의가 어긋나므로, 규칙을 일부러 바꾼 게 아니라면 테스트가 아니라 코드를 고친다.
 */
class BigramSparseEncoderTest {

  private static final String TEXT = "소방안전관리자 2급, Fire-Safety 점검표 / 점검";

  @Test
  void tokensMatchPocBigramRule() {
    // 소문자화, 기호·공백으로 낱말을 자르고 낱말 안에서만 바이그램, 한 음절 낱말은 그대로
    assertThat(BigramSparseEncoder.tokens(TEXT))
        .containsExactly(
            "소방", "방안", "안전", "전관", "관리", "리자", "2급", "fi", "ir", "re", "sa", "af", "fe", "et",
            "ty", "점검", "검표", "점검");
    assertThat(BigramSparseEncoder.tokens("a 가 b1")).containsExactly("a", "가", "b1");
    assertThat(BigramSparseEncoder.tokens("")).isEmpty();
    assertThat(BigramSparseEncoder.tokens(null)).isEmpty();
    // 자모 낱자·한자·기호는 구분자다
    assertThat(BigramSparseEncoder.tokens("ㄱㄴ消防 !!")).isEmpty();
  }

  @Test
  void tokenIdIsMurmur3x86_32Seed0() {
    assertThat(unsigned("hello")).isEqualTo(613153351L);
    assertThat(unsigned("a")).isEqualTo(1009084850L);
    assertThat(unsigned("소방")).isEqualTo(3486565070L); // 부호 없는 값이 int 범위를 넘는 경우
    assertThat(unsigned("안전")).isEqualTo(973247115L);
    assertThat(unsigned("소방안전관리자")).isEqualTo(399600310L);
  }

  @Test
  void documentValuesAreBm25TfSaturation() {
    SparseTerms doc = BigramSparseEncoder.document(TEXT, Profile.DATASET);

    long[] expectedIds = {
      113140839L, 639386442L, 843816345L, 973247115L, 1063320047L, 1256513470L, 1362080072L,
      1530405257L, 1836344936L, 2265677666L, 2327368754L, 2356028193L, 2760891977L, 3122421377L,
      3486565070L, 4113230164L, 4188867726L
    };
    assertThat(Arrays.stream(doc.indices()).mapToLong(Integer::toUnsignedLong).toArray())
        .containsExactly(expectedIds);
    // 「점검」이 두 번(tf=2), 나머지는 한 번 — 18토큰, 평균 64
    for (int i = 0; i < doc.indices().length; i++) {
      double expected =
          Integer.toUnsignedLong(doc.indices()[i]) == 113140839L ? 1.7233782 : 1.4164990;
      assertThat((double) doc.values()[i]).isCloseTo(expected, within(1e-6));
    }
  }

  @Test
  void queryValuesAreOnePerDistinctId() {
    SparseTerms q = BigramSparseEncoder.query("점검 점검표");
    assertThat(q.indices()).hasSize(2); // 점검·검표 — 중복은 하나로
    assertThat(q.valueList()).containsOnly(1f);
  }

  @Test
  void emptyTextGivesEmptyVector() {
    assertThat(BigramSparseEncoder.document("  ", Profile.CHUNK).isEmpty()).isTrue();
    assertThat(BigramSparseEncoder.query("--").isEmpty()).isTrue();
  }

  @Test
  void spacingVariantSharesBigramsExceptBoundary() {
    // 띄어쓰기 변형이 대부분의 바이그램을 공유하는 것이 PoC 에서 pg_trgm 을 앞선 이유다
    List<String> joined = BigramSparseEncoder.tokens("소방안전");
    List<String> spaced = BigramSparseEncoder.tokens("소방 안전");
    assertThat(joined).containsAll(spaced);
    assertThat(joined).contains("방안");
    assertThat(spaced).doesNotContain("방안");
  }

  private static long unsigned(String token) {
    return Integer.toUnsignedLong(BigramSparseEncoder.tokenId(token));
  }
}
