import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.apache.lucene.analysis.*;
import org.apache.lucene.analysis.core.LowerCaseFilter;
import org.apache.lucene.analysis.ko.*;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;

/**
 * WD-716 PoC — 한 줄에 글 하나를 읽어 Nori 토큰을 공백으로 이어 한 줄씩 쓴다.
 * ADR 0002 결정 8 의 "앱에서 Nori 로 sparse 벡터 생성" 을 JVM 안 Lucene Nori 그대로 재현한다.
 * 인자: 복합어 분해 모드(NONE|DISCARD|MIXED).
 */
public class Tok {
  public static void main(String[] a) throws Exception {
    KoreanTokenizer.DecompoundMode mode = KoreanTokenizer.DecompoundMode.valueOf(a[0]);
    Analyzer an = new Analyzer() {
      @Override protected TokenStreamComponents createComponents(String f) {
        Tokenizer t = new KoreanTokenizer(TokenStream.DEFAULT_TOKEN_ATTRIBUTE_FACTORY, null, mode, false);
        // 기본 품사 필터(조사·어미 등 제거) → 읽기 형태 → 소문자
        TokenStream s = new KoreanPartOfSpeechStopFilter(t);
        s = new KoreanReadingFormFilter(s);
        s = new LowerCaseFilter(s);
        return new TokenStreamComponents(t, s);
      }
    };
    BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    PrintWriter out = new PrintWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8));
    String line;
    while ((line = in.readLine()) != null) {
      StringBuilder sb = new StringBuilder();
      try (TokenStream ts = an.tokenStream("f", line)) {
        CharTermAttribute term = ts.addAttribute(CharTermAttribute.class);
        ts.reset();
        while (ts.incrementToken()) { if (sb.length() > 0) sb.append(' '); sb.append(term); }
        ts.end();
      }
      out.println(sb);
    }
    out.flush();
  }
}
