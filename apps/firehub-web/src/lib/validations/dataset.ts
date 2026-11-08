import { z } from 'zod';

import { DATASET_DESCRIPTION_TOO_LONG, MAX_DESCRIPTION_LENGTH } from './description';

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

// 시스템 예약 칼럼명 — 백엔드 DataTableStore.SYSTEM_COLUMN_NAMES(앱 예약어, 물리 테이블이 직접 쓰는 이름)와 같아야 한다.
// 화면은 백엔드와 무관한 앱 예약어만 미리 검사한다. DB 별 예약어(PG 시스템 컬럼 xmin·ctid 등, WD-564)는 화면이 테넌트 저장소
// 백엔드를 모르므로 여기 넣지 않는다 — 서버가 400 과 예약어 안내로 거부한다.
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

/**
 * 저장소 기능 조회 전·실패 시 쓰는 VARCHAR 길이 상한 — PostgreSQL 상한(서버 ColumnPhysicalType.MAX_VARCHAR_LENGTH)과 같다(WD-636).
 * 실제 저장소가 더 작으면(Tibero 4000) 서버가 같은 문구 계열의 400 으로 최종 판정한다.
 */
export const DEFAULT_MAX_VARCHAR_LENGTH = 10000;

/**
 * VARCHAR 길이 입력 규칙(WD-636) — 상한은 저장소마다 달라(PG 10000, Tibero 4000) GET /data-store/capabilities 의
 * maxVarcharLength 를 받는다. 예전에는 10000 고정이라 Tibero 에서 4001~10000 이 폼을 통과해 서버 400 토스트로만 알 수 있었다.
 */
function varcharMaxLengthSchema(maxVarcharLength: number) {
  return z.number().int().min(1, '최소 1').max(maxVarcharLength, `최대 ${maxVarcharLength}`).optional().nullable();
}

/** 칼럼 정의의 공통 필드(WD-636) — 추가·생성(기본 키 필수)과 수정(기본 키 선택)이 같은 정의를 쓴다. */
function columnObject(maxVarcharLength: number) {
  return z.object({
    columnName: z.string()
      .min(1, '칼럼명을 입력하세요')
      .max(MAX_COLUMN_NAME_LENGTH, COLUMN_NAME_MAX_MESSAGE)
      .regex(columnNameRegex, '영문 소문자, 숫자, 밑줄만 사용 가능합니다 (소문자로 시작)'),
    displayName: z.string().max(100, '표시 이름은 100자 이하여야 합니다').optional().or(z.literal('')),
    dataType: z.enum(['TEXT', 'VARCHAR', 'INTEGER', 'DECIMAL', 'BOOLEAN', 'DATE', 'TIMESTAMP', 'GEOMETRY'], {
      message: '데이터 타입을 선택하세요',
    }),
    maxLength: varcharMaxLengthSchema(maxVarcharLength),
    isNullable: z.boolean(),
    isIndexed: z.boolean(),
    isPrimaryKey: z.boolean(),
    description: z.string().max(255, '설명은 255자 이하여야 합니다').optional().or(z.literal('')),
  });
}

/** 칼럼 간 규칙(VARCHAR 길이 필수·기본 키는 NOT NULL)이 보는 필드. */
interface ColumnRuleFields {
  dataType: string;
  maxLength?: number | null;
  isNullable: boolean;
  isPrimaryKey?: boolean;
}

/** 칼럼 간 규칙을 덧붙인다(WD-636) — 추가·생성·수정 스키마가 같은 규칙·문구를 쓰도록 한 곳에 둔다. */
function withColumnRules<T extends z.ZodType<ColumnRuleFields>>(schema: T) {
  return schema
    .refine((data) => data.dataType !== 'VARCHAR' || (data.maxLength != null && data.maxLength > 0), {
      message: 'VARCHAR 타입은 길이를 입력해야 합니다',
      path: ['maxLength'],
    })
    .refine((data) => !data.isPrimaryKey || !data.isNullable, {
      message: '기본 키 칼럼은 NULL을 허용할 수 없습니다',
      path: ['isNullable'],
    });
}

/** 칼럼 정의 스키마를 VARCHAR 길이 상한과 함께 만든다(WD-636) — 생성 폼·필드 추가 대화상자가 저장소 상한으로 쓴다. */
export function makeDatasetColumnSchema(maxVarcharLength: number = DEFAULT_MAX_VARCHAR_LENGTH) {
  return withColumnRules(columnObject(maxVarcharLength));
}

/** 기본 상한(PG 10000)의 칼럼 스키마 — 저장소와 무관한 호출부·타입 추론용. */
export const datasetColumnSchema = makeDatasetColumnSchema();

/** 데이터셋 설명 — 서버와 같은 2,000자 상한(WD-520). 생성·수정·복제 폼이 같이 쓴다. */
export const datasetDescriptionSchema = z
  .string()
  .max(MAX_DESCRIPTION_LENGTH, DATASET_DESCRIPTION_TOO_LONG)
  .optional()
  .or(z.literal(''));

/** 생성 폼 스키마를 VARCHAR 길이 상한과 함께 만든다(WD-636) — 칼럼 길이 상한만 저장소 값을 따른다. */
export function makeCreateDatasetSchema(maxVarcharLength: number = DEFAULT_MAX_VARCHAR_LENGTH) {
  return z.object({
    // 공백만 넣은 이름이 길이 검사를 통과해 서버 @NotBlank 400(당시 영어 토스트, WD-570 이후 한국어)까지 가지 않도록 trim 뒤 검사한다 (WD-502).
    name: z.string().trim().min(1, '데이터셋 이름을 입력하세요').max(100, '이름은 100자 이하여야 합니다'),
    tableName: z.string()
      .min(1, '테이블명을 입력하세요')
      .max(MAX_TABLE_NAME_LENGTH, TABLE_NAME_MAX_MESSAGE)
      .regex(tableNameRegex, '영문 소문자, 숫자, 밑줄만 사용 가능합니다 (소문자로 시작)'),
    description: datasetDescriptionSchema,
    categoryId: z.number().optional(),
    storageType: z.enum(['TABLE', 'DOCUMENT', 'FILE'], { message: '저장 방식을 선택하세요' }),
    originType: z.enum(['SOURCE', 'DERIVED', 'TEMP'], { message: '출처를 선택하세요' }),
    columns: z.array(makeDatasetColumnSchema(maxVarcharLength)),
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
}

/** 기본 상한(PG 10000)의 생성 폼 스키마 — 테이블명 실시간 판정(shape.tableName)·타입 추론용. */
export const createDatasetSchema = makeCreateDatasetSchema();

export const updateDatasetSchema = z.object({
  // 공백만 넣은 이름이 길이 검사를 통과해 서버 @NotBlank 400(당시 영어 토스트, WD-570 이후 한국어)까지 가지 않도록 trim 뒤 검사한다 (WD-502).
  name: z.string().trim().min(1, '데이터셋 이름을 입력하세요').max(100, '이름은 100자 이하여야 합니다'),
  description: datasetDescriptionSchema,
  categoryId: z.number().optional(),
});

export const addColumnSchema = datasetColumnSchema;

/** 필드 수정 스키마를 VARCHAR 길이 상한과 함께 만든다(WD-636) — 칼럼 정의와 같고 기본 키만 선택 항목이다. */
export function makeUpdateColumnSchema(maxVarcharLength: number = DEFAULT_MAX_VARCHAR_LENGTH) {
  return withColumnRules(columnObject(maxVarcharLength).extend({ isPrimaryKey: z.boolean().optional() }));
}

/** 기본 상한(PG 10000)의 필드 수정 스키마 — 타입 추론용. */
export const updateColumnSchema = makeUpdateColumnSchema();

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
