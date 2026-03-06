package com.smartfirehub.dataimport.dto;

public enum ExportFormat {
  CSV("csv", "text/csv; charset=UTF-8"),
  EXCEL("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
  GEOJSON("geojson", "application/geo+json");

  private final String extension;
  private final String contentType;

  ExportFormat(String extension, String contentType) {
    this.extension = extension;
    this.contentType = contentType;
  }

  public String getExtension() {
    return extension;
  }

  public String getContentType() {
    return contentType;
  }
}
