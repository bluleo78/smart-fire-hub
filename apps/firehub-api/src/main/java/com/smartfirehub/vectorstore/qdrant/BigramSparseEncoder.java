package com.smartfirehub.vectorstore.qdrant;

import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 키워드 검색용 음절 바이그램 sparse 벡터 생성기(ADR 0002 결정 8).
 *
 * <p>텍스트 → 소문자 → 낱말({@code [0-9a-z가-힣]+} 덩어리) → 낱말 안의 음절 바이그램(한 음절 낱말은 그대로) → murmur3(32비트, 시드 0,
 * UTF-8) 해시로 정수 id → 문서는 BM25 의 TF 포화 값, 질의는 1. IDF 는 Qdrant 가 sparse 벡터의 {@code modifier: idf} 로
 * 곱한다. 낱말 규칙은 PoC 의 {@code docs/adr/0002-poc/kw.py} {@code bigrams()} 와 같다(해시만 PoC 의 MD5 대신
 * murmur3).
 *
 * <p><b>저장된 데이터에 박히는 규칙이다.</b> 낱말 자르기·해시·BM25 상수를 바꾸면 이미 쓴 sparse 와 질의가 맞지 않는다. 바꿀 때는 {@link
 * #RULE_VERSION} 을 올리고 키워드 표시를 비워 백로그로 다시 쓴다. 단위 테스트가 토큰·id·값을 고정해 실수로 바뀌는 것을 막는다.
 *
 * <p>id 는 Qdrant 의 uint32 다. Java {@code int} 비트를 그대로 넘기면 gRPC 가 부호 없는 값으로 읽는다. 사람이 읽을 때는 {@link
 * Integer#toUnsignedLong} 으로 본다.
 */
public final class BigramSparseEncoder {

  /** 생성 규칙 버전. 규칙을 바꾸면 올린다(컬렉션 스키마 버전과 따로 간다). */
  public static final int RULE_VERSION = 1;

  /** BM25 TF 포화 상수. PoC 와 같은 표준값이다. */
  static final double K1 = 1.2;

  static final double B = 0.75;

  /** 낱말: 숫자·영문 소문자·한글 음절의 연속. 그 밖의 문자(기호·공백·자모 낱자·한자)는 구분자다. */
  private static final Pattern WORD = Pattern.compile("[0-9a-z가-힣]+");

  /**
   * murmur3_x86_32, 시드 0. Guava 의 {@code murmur3_32} 는 비 BMP 문자에서 틀린 값을 내는 옛 구현이라 {@code _fixed} 를
   * 쓴다.
   */
  private static final HashFunction HASH = Hashing.murmur3_32_fixed();

  /**
   * 문서 종류별 평균 길이(바이그램 수). PoC 는 말뭉치에서 평균을 쟀지만, 앱은 문서를 하나씩 쓰므로 평균을 미리 정한 상수로 둔다. 값이 실제 평균과 달라도 같은 종류
   * 안에서는 길이 보정의 기준점만 움직여 순위 영향이 작다. 바꾸면 규칙 변경이다.
   */
  public enum Profile {
    /** 데이터셋 카탈로그 원문(이름·설명·태그·열 이름 등). */
    DATASET(64),
    /** 문서 청크 본문. */
    CHUNK(512),
    /** 행 검색 행 원문. */
    ROW(64);

    private final double avgLength;

    Profile(double avgLength) {
      this.avgLength = avgLength;
    }

    double avgLength() {
      return avgLength;
    }
  }

  private BigramSparseEncoder() {}

  /** 텍스트의 바이그램 토큰(순서·중복 그대로). */
  public static List<String> tokens(String text) {
    List<String> out = new ArrayList<>();
    if (text == null || text.isEmpty()) {
      return out;
    }
    Matcher m = WORD.matcher(text.toLowerCase(Locale.ROOT));
    while (m.find()) {
      String w = m.group();
      if (w.length() == 1) {
        out.add(w);
      } else {
        for (int i = 0; i + 1 < w.length(); i++) {
          out.add(w.substring(i, i + 2));
        }
      }
    }
    return out;
  }

  /** 토큰 하나의 sparse id(uint32 비트). */
  public static int tokenId(String token) {
    return HASH.hashString(token, StandardCharsets.UTF_8).asInt();
  }

  /** 문서 쪽 sparse 벡터 — 값은 BM25 TF 포화 항. 원문이 비면 빈 벡터. */
  public static SparseTerms document(String text, Profile profile) {
    List<String> toks = tokens(text);
    Map<Integer, Integer> tf = countById(toks);
    double dl = Math.max(1, toks.size());
    double norm = K1 * (1 - B + B * dl / profile.avgLength());
    return build(tf, count -> (float) (count * (K1 + 1) / (count + norm)));
  }

  /** 질의 쪽 sparse 벡터 — 서로 다른 id 마다 값 1. */
  public static SparseTerms query(String text) {
    return build(countById(tokens(text)), count -> 1f);
  }

  private static Map<Integer, Integer> countById(List<String> toks) {
    // 부호 없는 순서로 정렬해 같은 입력이면 늘 같은 배열이 나오게 한다(테스트 고정·로그 비교용).
    Map<Integer, Integer> tf = new TreeMap<>(Integer::compareUnsigned);
    for (String t : toks) {
      tf.merge(tokenId(t), 1, Integer::sum);
    }
    return tf;
  }

  private static SparseTerms build(Map<Integer, Integer> tf, CountToValue fn) {
    int[] idx = new int[tf.size()];
    float[] val = new float[tf.size()];
    int i = 0;
    for (Map.Entry<Integer, Integer> e : tf.entrySet()) {
      idx[i] = e.getKey();
      val[i] = fn.apply(e.getValue());
      i++;
    }
    return new SparseTerms(idx, val);
  }

  @FunctionalInterface
  private interface CountToValue {
    float apply(int count);
  }

  /**
   * sparse 벡터(같은 길이의 id·값 배열, id 는 부호 없는 오름차순).
   *
   * @param indices uint32 비트를 담은 id
   * @param values id 별 값
   */
  public record SparseTerms(int[] indices, float[] values) {
    public boolean isEmpty() {
      return indices.length == 0;
    }

    /** gRPC 요청에 넣을 id 목록(부호 없는 값으로 해석된다). */
    public List<Integer> indexList() {
      return Arrays.stream(indices).boxed().toList();
    }

    /** gRPC 요청에 넣을 값 목록. */
    public List<Float> valueList() {
      List<Float> out = new ArrayList<>(values.length);
      for (float v : values) {
        out.add(v);
      }
      return out;
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof SparseTerms s
          && Arrays.equals(indices, s.indices)
          && Arrays.equals(values, s.values);
    }

    @Override
    public int hashCode() {
      return 31 * Arrays.hashCode(indices) + Arrays.hashCode(values);
    }

    @Override
    public String toString() {
      return "SparseTerms" + Arrays.toString(indices) + "=" + Arrays.toString(values);
    }
  }
}
