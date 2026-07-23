// 평가 질문 셋 로더 — questions.json 을 읽어 검증한다(고유 id, 유효 class).
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { EvalQuestion, QuestionClass } from './types.js';

const CLASSES: QuestionClass[] = ['multihop', 'relationship', 'lookup', 'attribute'];

export function loadQuestions(): EvalQuestion[] {
  const dir = dirname(fileURLToPath(import.meta.url));
  const raw = JSON.parse(readFileSync(join(dir, 'questions.json'), 'utf-8')) as EvalQuestion[];
  const ids = new Set<string>();
  for (const q of raw) {
    if (!q.id || ids.has(q.id)) throw new Error(`중복/누락 질문 id: ${q.id}`);
    ids.add(q.id);
    if (!CLASSES.includes(q.class)) throw new Error(`유효하지 않은 class: ${q.class}`);
  }
  return raw;
}
