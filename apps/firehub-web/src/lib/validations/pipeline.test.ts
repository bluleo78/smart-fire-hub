import { describe, expect, it } from 'vitest';

import { createPipelineSchema, editorPipelineSchema, editorStepSchema } from './pipeline';

/**
 * 파이프라인·스텝 이름 길이 한도 메시지 (WD-360).
 * 메시지 없는 max(100) 은 zod 영어 기본 문구(「Too big: expected string to have <=100 characters」)를 그대로 화면에 냈다.
 */
describe('파이프라인 이름 길이 메시지 (WD-360)', () => {
  const step = { name: '가'.repeat(101), scriptType: 'SQL' as const, scriptContent: 'select 1', outputDatasetId: null };

  it('에디터 스텝 이름 100자 초과는 한국어로 안내한다', () => {
    const r = editorStepSchema.safeParse(step);
    expect(r.success).toBe(false);
    expect(r.error?.issues.map((i) => i.message)).toContain('스텝 이름은 100자 이하여야 합니다');
  });

  it('스텝 이름 100자는 통과한다(경계)', () => {
    expect(editorStepSchema.safeParse({ ...step, name: '가'.repeat(100) }).success).toBe(true);
  });

  it('에디터·생성 폼의 파이프라인 이름 100자 초과도 한국어로 안내한다', () => {
    const editor = editorPipelineSchema.safeParse({ name: '가'.repeat(101), steps: [{ ...step, name: 's' }] });
    expect(editor.error?.issues.map((i) => i.message)).toContain('파이프라인 이름은 100자 이하여야 합니다');
    const create = createPipelineSchema.safeParse({ name: '가'.repeat(101), steps: [] });
    expect(create.error?.issues.map((i) => i.message)).toContain('파이프라인 이름은 100자 이하여야 합니다');
  });
});
