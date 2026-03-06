package com.smartfirehub.dataimport.service.export;

import java.io.IOException;
import java.util.List;

public interface ExportWriter extends AutoCloseable {

  void writeHeader(List<String> displayNames) throws IOException;

  void writeRow(String[] values) throws IOException;

  @Override
  void close() throws IOException;
}
