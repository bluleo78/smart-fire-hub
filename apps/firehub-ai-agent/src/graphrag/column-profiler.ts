// 표 데이터셋 컬럼을 결정적으로 프로파일링한다(LLM·네트워크 없음).
// 매핑 추론기(mapping-inference.ts)가 이 프로파일을 LLM 프롬프트 입력으로 사용한다.

// 프로파일러가 필요로 하는 최소 컬럼 메타(백엔드 DatasetColumnResponse의 부분집합).
export interface ProfilerColumn {
  columnName: string;
  dataType: string;
  isPrimaryKey: boolean;
}

// 컬럼 1개의 프로파일 결과.
export interface ColumnProfile {
  columnName: string;
  dataType: string;                              // 원본 컬럼 타입(대문자)
  ontologyDataType: 'text' | 'number' | 'date' | null; // collapse 결과. GEOMETRY 등 매핑 불가 → null
  distinctCount: number;                         // 표본 내 비어있지 않은 값의 고유 개수
  nullRatio: number;                             // 표본 내 빈값 비율 [0,1]
  cardinalityRatio: number;                      // distinctCount / 비어있지않은표본수 [0,1]
  sampleValues: string[];                        // 대표값(중복 제거, 순서 유지) 최대 5개
  isPrimaryKey: boolean;
}

// 컬럼 dataType(백엔드 8종)을 온톨로지 속성 dataType(3종)으로 접는다.
// GEOMETRY(공간값)는 노드 속성으로 만들 의미가 없어 null(제외)로 표시한다.
function collapseDataType(dataType: string): 'text' | 'number' | 'date' | null {
  switch (dataType.toUpperCase()) {
    case 'INTEGER':
    case 'DECIMAL':
      return 'number';
    case 'DATE':
    case 'TIMESTAMP':
      return 'date';
    case 'GEOMETRY':
      return null;
    // BOOLEAN, TEXT, VARCHAR 및 미지 타입은 text로 취급(보수적 기본값).
    default:
      return 'text';
  }
}

// 값이 비어있는지(null/undefined/빈문자열/공백만) 판정한다.
function isEmpty(v: unknown): boolean {
  if (v === null || v === undefined) return true;
  if (typeof v === 'string') return v.trim() === '';
  return false;
}

// 컬럼 목록과 행 표본으로 컬럼별 프로파일을 만든다.
export function profileColumns(
  columns: ProfilerColumn[],
  sampleRows: Record<string, unknown>[],
): ColumnProfile[] {
  const total = sampleRows.length;
  return columns.map((col) => {
    // 이 컬럼의 비어있지 않은 값들을 문자열화해 모은다.
    const nonEmpty: string[] = [];
    for (const row of sampleRows) {
      const v = row[col.columnName];
      if (!isEmpty(v)) nonEmpty.push(String(v));
    }
    const distinct = Array.from(new Set(nonEmpty)); // 순서 유지 고유값
    const nonEmptyCount = nonEmpty.length;
    return {
      columnName: col.columnName,
      dataType: col.dataType.toUpperCase(),
      ontologyDataType: collapseDataType(col.dataType),
      distinctCount: distinct.length,
      nullRatio: total === 0 ? 0 : (total - nonEmptyCount) / total,
      cardinalityRatio: nonEmptyCount === 0 ? 0 : distinct.length / nonEmptyCount,
      sampleValues: distinct.slice(0, 5),
      isPrimaryKey: col.isPrimaryKey,
    };
  });
}
