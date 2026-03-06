export type ExportFormat = 'CSV' | 'EXCEL' | 'GEOJSON';

export interface ExportColumnInfo {
  columnName: string;
  displayName: string;
  dataType: string;
  isGeometry: boolean;
}

export interface ExportEstimate {
  rowCount: number;
  async: boolean;
  hasGeometryColumn: boolean;
  columns: ExportColumnInfo[];
}

export interface ExportRequest {
  format: ExportFormat;
  columns?: string[];
  search?: string;
  geometryColumn?: string;
}

export interface QueryResultExportRequest {
  columnNames: string[];
  rows: Record<string, unknown>[];
  format: ExportFormat;
}
