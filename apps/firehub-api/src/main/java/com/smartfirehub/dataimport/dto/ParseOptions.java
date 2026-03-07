package com.smartfirehub.dataimport.dto;

import java.io.Serializable;

public record ParseOptions(
    String delimiter, // ",", "\t", ";", "|", or custom single char. Default ","
    String encoding, // "AUTO", "UTF-8", "EUC-KR", "CP949". Default "AUTO"
    boolean hasHeader, // Default true
    int skipRows // Rows to skip before header. Default 0
    ) implements Serializable {

  private static final java.util.Set<String> ALLOWED_ENCODINGS =
      java.util.Set.of("AUTO", "UTF-8", "EUC-KR", "CP949");

  public ParseOptions {
    if (delimiter == null || delimiter.isEmpty()) delimiter = ",";
    if (encoding == null || encoding.isEmpty()) encoding = "AUTO";
    if (!ALLOWED_ENCODINGS.contains(encoding)) {
      throw new IllegalArgumentException(
          "Unsupported encoding: " + encoding + ". Allowed: " + ALLOWED_ENCODINGS);
    }
  }

  public static ParseOptions defaults() {
    return new ParseOptions(",", "AUTO", true, 0);
  }

  /** Detect encoding from file bytes: UTF-8 BOM → UTF-8, valid UTF-8 → UTF-8, else EUC-KR. */
  public static String detectEncoding(byte[] data) {
    if (data.length >= 3
        && (data[0] & 0xFF) == 0xEF
        && (data[1] & 0xFF) == 0xBB
        && (data[2] & 0xFF) == 0xBF) {
      return "UTF-8";
    }
    if (isValidUtf8(data)) {
      return "UTF-8";
    }
    return "EUC-KR";
  }

  private static boolean isValidUtf8(byte[] data) {
    int i = 0;
    boolean hasHighBytes = false;
    while (i < data.length) {
      int b = data[i] & 0xFF;
      if (b <= 0x7F) {
        i++;
        continue;
      }
      hasHighBytes = true;
      int seqLen;
      if ((b & 0xE0) == 0xC0) seqLen = 2;
      else if ((b & 0xF0) == 0xE0) seqLen = 3;
      else if ((b & 0xF8) == 0xF0) seqLen = 4;
      else return false;
      if (i + seqLen > data.length) return false;
      for (int j = 1; j < seqLen; j++) {
        if ((data[i + j] & 0xC0) != 0x80) return false;
      }
      i += seqLen;
    }
    return hasHighBytes;
  }
}
