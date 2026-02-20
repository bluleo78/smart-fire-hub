package com.smartfirehub.dataimport.dto;

import java.io.Serializable;

public record ParseOptions(
        String delimiter,   // ",", "\t", ";", "|", or custom single char. Default ","
        String encoding,    // "UTF-8", "EUC-KR", "CP949". Default "UTF-8"
        boolean hasHeader,  // Default true
        int skipRows        // Rows to skip before header. Default 0
) implements Serializable {

    private static final java.util.Set<String> ALLOWED_ENCODINGS = java.util.Set.of("UTF-8", "EUC-KR", "CP949");

    public ParseOptions {
        if (delimiter == null || delimiter.isEmpty()) delimiter = ",";
        if (encoding == null || encoding.isEmpty()) encoding = "UTF-8";
        if (!ALLOWED_ENCODINGS.contains(encoding)) {
            throw new IllegalArgumentException("Unsupported encoding: " + encoding + ". Allowed: " + ALLOWED_ENCODINGS);
        }
    }

    public static ParseOptions defaults() {
        return new ParseOptions(",", "UTF-8", true, 0);
    }
}
