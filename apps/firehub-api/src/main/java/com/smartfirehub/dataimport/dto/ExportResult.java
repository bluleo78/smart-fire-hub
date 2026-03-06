package com.smartfirehub.dataimport.dto;

import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

public record ExportResult(
    boolean async,
    String jobId,
    StreamingResponseBody streamingBody,
    String filename,
    String contentType) {

  public static ExportResult sync(StreamingResponseBody body, String filename, String contentType) {
    return new ExportResult(false, null, body, filename, contentType);
  }

  public static ExportResult async(String jobId) {
    return new ExportResult(true, jobId, null, null, null);
  }
}
