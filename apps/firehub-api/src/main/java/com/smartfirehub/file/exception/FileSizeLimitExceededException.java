package com.smartfirehub.file.exception;

public class FileSizeLimitExceededException extends RuntimeException {
  public FileSizeLimitExceededException(String category, long maxBytes) {
    super(
        String.format(
            "File size exceeds limit for category %s: max %d MB",
            category, maxBytes / (1024 * 1024)));
  }
}
