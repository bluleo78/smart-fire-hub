import { z } from 'zod';

// 테이블명 인라인 유효성(입력 중 실시간 판정)에서도 재사용하기 위해 export.
// (#644) react-hook-form이 mode:'onSubmit'이면 formState.errors가 최초 제출 전까지
// 채워지지 않으므로, 입력 중 성공 메시지 노출 조건은 이 정규식을 직접 재검사해야 한다.
export const tableNameRegex = /^[a-z][a-z0-9_]*$/;
const columnNameRegex = /^[a-z][a-z0-9_]*$/;

// 테이블명·칼럼명 길이 상한 — 백엔드 DataTableService.MAX_TABLE_NAME_LENGTH / MAX_COLUMN_NAME_LENGTH 와 같아야 한다(근거는 그 javadoc).
export const MAX_TABLE_NAME_LENGTH = 56;
export const MAX_COLUMN_NAME_LENGTH = 63;
export const TABLE_NAME_MAX_MESSAGE = `테이블명은 ${MAX_TABLE_NAME_LENGTH}자 이하여야 합니다`;
export const COLUMN_NAME_MAX_MESSAGE = `칼럼명은 ${MAX_COLUMN_NAME_LENGTH}자 이하여야 합니다`;

/**
 * 칼럼명 길이 상한 위반이면 메시지를, 아니면 undefined 를 돌려준다.
 * zod 폼이 아닌 즉시 반영 입력(파이프라인 출력 칼럼 등)이 같은 상한·문구로 인라인 오류를 띄우도록 공유한다.
 * maxLength 로 조용히 자르지 않는 이유: 붙여넣은 긴 이름이 잘려 다른 칼럼과 같은 이름이 되는 것을 사용자가 모르게 된다.
 */
export function columnNameLengthError(name: string): string | undefined {
  return name.length > MAX_COLUMN_NAME_LENGTH ? COLUMN_NAME_MAX_MESSAGE : undefined;
}

// 이름 중복 안내 (WD-99) — 문구는 각 화면의 입력 라벨을 따른다: 생성 화면(라벨 「칼럼명」)은 COLUMN_NAME_DUPLICATE_MESSAGE,
// 필드 추가·수정 다이얼로그(라벨 「필드명」)는 FIELD_NAME_DUPLICATE_MESSAGE. 서버도 같은 경우를 409 로 거부하지만,
// 화면이 먼저 막아 일반 무결성 오류까지 가지 않게 한다.
export const COLUMN_NAME_DUPLICATE_MESSAGE = '이미 있는 칼럼명입니다';
export const FIELD_NAME_DUPLICATE_MESSAGE = '이미 있는 필드명입니다';

// 시스템 예약 칼럼명 — 백엔드 DataTableService.SYSTEM_COLUMNS 와 같아야 한다(물리 테이블이 직접 쓰는 이름).
// 예약어 안내가 중복 안내보다 먼저 보이게 하는 데 쓴다(예약어 이름이 기존 필드처럼 보여도 진짜 이유는 예약어다):
// 필드 추가·수정 다이얼로그는 제출 시 이 검사를 중복보다 먼저 하고, 생성 화면은 예약어를 중복으로 세지 않아 서버의 예약어 안내가 뜬다.
export const SYSTEM_COLUMN_NAMES = ['id', 'import_id', 'created_at', '_updated_at'] as const;
export const RESERVED_COLUMN_NAME_MESSAGE = `시스템 예약어라 쓸 수 없는 이름입니다 (예약어: ${SYSTEM_COLUMN_NAMES.join(', ')})`;

/** 시스템 예약 칼럼명이면 안내 문구를, 아니면 undefined 를 돌려준다(서버와 같이 소문자로 비교). */
export function reservedColumnNameError(name: string): string | undefined {
  return (SYSTEM_COLUMN_NAMES as readonly string[]).includes(name.toLowerCase())
    ? RESERVED_COLUMN_NAME_MESSAGE
    : undefined;
}

/**
 * 필드 목록에서 앞서 나온 이름과 같은 칼럼의 인덱스를 돌려준다(첫 번째는 원본으로 보고 제외).
 */
export function findDuplicateColumnIndexes(names: string[]): number[] {
  const seen = new Set<string>();
  const duplicates: number[] = [];
  names.forEach((name, index) => {
    // 빈 이름·예약어는 각자의 안내가 따로 뜨므로 중복으로 세지 않는다(예약어 안내가 중복 안내에 가려지지 않게).
    if (name === '' || reservedColumnNameError(name)) return;
    if (seen.has(name)) duplicates.push(index);
    else seen.add(name);
  });
  return duplicates;
}

export const datasetColumnSchema = z.object({
  columnName: z.string()
    .min(1, '칼럼명을 입력하세요')
    .max(MAX_COLUMN_NAME_LENGTH, COLUMN_NAME_MAX_MESSAGE)
    .regex(columnNameRegex, '영문 소문자, 숫자, 밑줄만 사용 가능합니다 (소문자로 시작)'),
  displayName: z.string().max(100, '표시 이름은 100자 이하여야 합니다').optional().or(z.literal('')),
  dataType: z.enum(['TEXT', 'VARCHAR', 'INTEGER', 'DECIMAL', 'BOOLEAN', 'DATE', 'TIMESTAMP', 'GEOMETRY'], {
    message: '데이터 타입을 선택하세요',
  }),
  maxLength: z.number().int().min(1, '최소 1').max(10000, '최대 10000').optional().nullable(),
  isNullable: z.boolean(),
  isIndexed: z.boolean(),
  isPrimaryKey: z.boolean(),
  description: z.string().max(255, '설명은 255자 이하여야 합니다').optional().or(z.literal('')),
}).refine(
  (data) => data.dataType !== 'VARCHAR' || (data.maxLength != null && data.maxLength > 0),
  { message: 'VARCHAR 타입은 길이를 입력해야 합니다', path: ['maxLength'] }
).refine(
  (data) => !data.isPrimaryKey || !data.isNullable,
  { message: '기본 키 칼럼은 NULL을 허용할 수 없습니다', path: ['isNullable'] }
);

export const createDatasetSchema = z.object({
  name: z.string().min(1, '데이터셋 이름을 입력하세요').max(100, '이름은 100자 이하여야 합니다'),
  tableName: z.string()
    .min(1, '테이블명을 입력하세요')
    .max(MAX_TABLE_NAME_LENGTH, TABLE_NAME_MAX_MESSAGE)
    .regex(tableNameRegex, '영문 소문자, 숫자, 밑줄만 사용 가능합니다 (소문자로 시작)'),
  description: z.string().optional().or(z.literal('')),
  categoryId: z.number().optional(),
  storageType: z.enum(['TABLE', 'DOCUMENT', 'FILE'], { message: '저장 방식을 선택하세요' }),
  originType: z.enum(['SOURCE', 'DERIVED', 'TEMP'], { message: '출처를 선택하세요' }),
  columns: z.array(datasetColumnSchema),
}).superRefine((data, ctx) => {
  // DOCUMENT·FILE 데이터셋은 동적 컬럼이 없으므로 빈 배열 허용. 그 외 유형은 최소 1개 필요.
  if (data.storageType !== 'DOCUMENT' && data.storageType !== 'FILE' && data.columns.length === 0) {
    ctx.addIssue({ code: 'custom', path: ['columns'], message: '최소 1개의 칼럼을 정의하세요' });
  }
  // 같은 이름 칼럼이 둘 이상이면 뒤에 나온 칸에 안내하고 제출을 막는다 (WD-99).
  // 경로를 columns.<i>.columnName 으로 두어 SchemaBuilder 의 칸별 오류 자리에 그대로 뜬다.
  for (const index of findDuplicateColumnIndexes(data.columns.map((c) => c.columnName))) {
    ctx.addIssue({
      code: 'custom',
      path: ['columns', index, 'columnName'],
      message: COLUMN_NAME_DUPLICATE_MESSAGE,
    });
  }
});

export const updateDatasetSchema = z.object({
  name: z.string().min(1, '데이터셋 이름을 입력하세요').max(100, '이름은 100자 이하여야 합니다'),
  description: z.string().optional().or(z.literal('')),
  categoryId: z.number().optional(),
});

export const addColumnSchema = datasetColumnSchema;

export const updateColumnSchema = z.object({
  columnName: z.string()
    .min(1, '칼럼명을 입력하세요')
    .max(MAX_COLUMN_NAME_LENGTH, COLUMN_NAME_MAX_MESSAGE)
    .regex(columnNameRegex, '영문 소문자, 숫자, 밑줄만 사용 가능합니다 (소문자로 시작)'),
  displayName: z.string().max(100, '표시 이름은 100자 이하여야 합니다').optional().or(z.literal('')),
  dataType: z.enum(['TEXT', 'VARCHAR', 'INTEGER', 'DECIMAL', 'BOOLEAN', 'DATE', 'TIMESTAMP', 'GEOMETRY'], {
    message: '데이터 타입을 선택하세요',
  }),
  maxLength: z.number().int().min(1, '최소 1').max(10000, '최대 10000').optional().nullable(),
  isNullable: z.boolean(),
  isIndexed: z.boolean(),
  isPrimaryKey: z.boolean().optional(),
  description: z.string().max(255, '설명은 255자 이하여야 합니다').optional().or(z.literal('')),
}).refine(
  (data) => data.dataType !== 'VARCHAR' || (data.maxLength != null && data.maxLength > 0),
  { message: 'VARCHAR 타입은 길이를 입력해야 합니다', path: ['maxLength'] }
).refine(
  (data) => !data.isPrimaryKey || !data.isNullable,
  { message: '기본 키 칼럼은 NULL을 허용할 수 없습니다', path: ['isNullable'] }
);

export const categorySchema = z.object({
  name: z.string().min(1, '카테고리 이름을 입력하세요').max(50, '이름은 50자 이하여야 합니다'),
  description: z.string().max(255, '설명은 255자 이하여야 합니다').optional().or(z.literal('')),
});

export type CreateDatasetFormData = z.infer<typeof createDatasetSchema>;
export type UpdateDatasetFormData = z.infer<typeof updateDatasetSchema>;
export type AddColumnFormData = z.infer<typeof addColumnSchema>;
export type UpdateColumnFormData = z.infer<typeof updateColumnSchema>;
export type DatasetColumnFormData = z.infer<typeof datasetColumnSchema>;
export type CategoryFormData = z.infer<typeof categorySchema>;
