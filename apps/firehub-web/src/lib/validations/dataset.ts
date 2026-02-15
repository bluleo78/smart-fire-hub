import { z } from 'zod';

const tableNameRegex = /^[a-z][a-z0-9_]*$/;
const columnNameRegex = /^[a-z][a-z0-9_]*$/;

export const datasetColumnSchema = z.object({
  columnName: z.string()
    .min(1, '칼럼명을 입력하세요')
    .max(100, '칼럼명은 100자 이하여야 합니다')
    .regex(columnNameRegex, '영문 소문자, 숫자, 밑줄만 사용 가능합니다 (소문자로 시작)'),
  displayName: z.string().max(100).optional().or(z.literal('')),
  dataType: z.enum(['TEXT', 'VARCHAR', 'INTEGER', 'DECIMAL', 'BOOLEAN', 'DATE', 'TIMESTAMP'], {
    message: '데이터 타입을 선택하세요',
  }),
  maxLength: z.number().int().min(1, '최소 1').max(10000, '최대 10000').optional().nullable(),
  isNullable: z.boolean(),
  isIndexed: z.boolean(),
  description: z.string().max(255).optional().or(z.literal('')),
}).refine(
  (data) => data.dataType !== 'VARCHAR' || (data.maxLength != null && data.maxLength > 0),
  { message: 'VARCHAR 타입은 길이를 입력해야 합니다', path: ['maxLength'] }
);

export const createDatasetSchema = z.object({
  name: z.string().min(1, '데이터셋 이름을 입력하세요').max(100, '이름은 100자 이하여야 합니다'),
  tableName: z.string()
    .min(1, '테이블명을 입력하세요')
    .max(100, '테이블명은 100자 이하여야 합니다')
    .regex(tableNameRegex, '영문 소문자, 숫자, 밑줄만 사용 가능합니다 (소문자로 시작)'),
  description: z.string().optional().or(z.literal('')),
  categoryId: z.number().optional(),
  datasetType: z.enum(['SOURCE', 'DERIVED'], {
    message: '데이터셋 유형을 선택하세요',
  }),
  columns: z.array(datasetColumnSchema).min(1, '최소 1개의 칼럼을 정의하세요'),
});

export const updateDatasetSchema = z.object({
  name: z.string().min(1, '데이터셋 이름을 입력하세요').max(100),
  description: z.string().optional().or(z.literal('')),
  categoryId: z.number().optional(),
});

export const addColumnSchema = datasetColumnSchema;

export const updateColumnSchema = z.object({
  columnName: z.string()
    .min(1, '칼럼명을 입력하세요')
    .max(100, '칼럼명은 100자 이하여야 합니다')
    .regex(columnNameRegex, '영문 소문자, 숫자, 밑줄만 사용 가능합니다 (소문자로 시작)'),
  displayName: z.string().max(100).optional().or(z.literal('')),
  dataType: z.enum(['TEXT', 'VARCHAR', 'INTEGER', 'DECIMAL', 'BOOLEAN', 'DATE', 'TIMESTAMP'], {
    message: '데이터 타입을 선택하세요',
  }),
  maxLength: z.number().int().min(1, '최소 1').max(10000, '최대 10000').optional().nullable(),
  isNullable: z.boolean(),
  isIndexed: z.boolean(),
  description: z.string().max(255).optional().or(z.literal('')),
}).refine(
  (data) => data.dataType !== 'VARCHAR' || (data.maxLength != null && data.maxLength > 0),
  { message: 'VARCHAR 타입은 길이를 입력해야 합니다', path: ['maxLength'] }
);

export const categorySchema = z.object({
  name: z.string().min(1, '카테고리 이름을 입력하세요').max(50, '이름은 50자 이하여야 합니다'),
  description: z.string().max(255).optional().or(z.literal('')),
});

export type CreateDatasetFormData = z.infer<typeof createDatasetSchema>;
export type UpdateDatasetFormData = z.infer<typeof updateDatasetSchema>;
export type AddColumnFormData = z.infer<typeof addColumnSchema>;
export type UpdateColumnFormData = z.infer<typeof updateColumnSchema>;
export type DatasetColumnFormData = z.infer<typeof datasetColumnSchema>;
export type CategoryFormData = z.infer<typeof categorySchema>;
