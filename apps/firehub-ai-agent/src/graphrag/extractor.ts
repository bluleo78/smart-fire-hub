// 청크 텍스트를 LLM에 넘겨 온톨로지 준수 엔티티/관계를 추출한다.
// classification-service.ts와 동일하게 Anthropic messages API를 raw axios로 단발 호출한다(query() 아님).
import axios from 'axios';
import {
  ExtractionResult, EntityType, RelationType, isEntityType, isRelationType, isAllowedTriple, ENTITY_TYPES, RELATION_TYPES,
} from './ontology.js';

export interface ExtractOptions { model: string; apiKey: string; anthropicBaseUrl?: string; }

// LLM에 온톨로지 스키마와 함께 JSON 산출을 지시하는 시스템 프롬프트.
const SYSTEM_PROMPT = `너는 화재조사 보고서에서 지식 그래프를 추출하는 도구다.
아래 온톨로지에 **정확히 일치하는** 엔티티와 관계만 추출한다.
엔티티 타입: ${ENTITY_TYPES.join(', ')}
관계 타입: ${RELATION_TYPES.join(', ')}
반드시 다음 형식의 JSON 코드블록만 출력한다:
\`\`\`json
{"entities":[{"type":"Incident","name":"..."}],"relations":[{"subject":"엔티티명","type":"CAUSED_BY","object":"엔티티명"}]}
\`\`\`
name은 본문에 등장한 표기를 그대로 사용한다.`;

// 응답 텍스트에서 첫 JSON 코드블록을 추출해 파싱한다. 실패 시 null.
function parseJsonBlock(text: string): unknown | null {
  const m = text.match(/```json\s*([\s\S]*?)```/);
  const raw = m ? m[1] : text;
  try { return JSON.parse(raw.trim()); } catch { return null; }
}

export async function extractGraph(text: string, opts: ExtractOptions): Promise<ExtractionResult> {
  const base = opts.anthropicBaseUrl ?? 'https://api.anthropic.com';
  let content = '';
  try {
    const resp = await axios.post(
      `${base}/v1/messages`,
      { model: opts.model, max_tokens: 2048, system: SYSTEM_PROMPT,
        messages: [{ role: 'user', content: text }] },
      { headers: { 'x-api-key': opts.apiKey, 'anthropic-version': '2023-06-01', 'content-type': 'application/json' },
        timeout: 60_000 },
    );
    content = resp.data?.content?.[0]?.text ?? '';
  } catch (err) {
    // API 키 오설정/타임아웃 등을 진단할 수 있도록 경고 로그를 남기고, 배치는 계속 진행한다.
    console.warn('[graphrag] extractGraph LLM 호출 실패, 빈 결과로 계속:', err);
    return { entities: [], relations: [] }; // LLM 호출 실패 → 빈 결과(호출부에서 배치 계속)
  }

  const parsed = parseJsonBlock(content) as { entities?: unknown[]; relations?: unknown[] } | null;
  if (!parsed) return { entities: [], relations: [] };

  // 엔티티: 온톨로지 타입에 없는 것은 폐기. 이름→타입 맵을 만들어 관계 검증에 사용.
  // parsed.entities가 배열이 아닌 경우(형식 오류)에도 배치가 죽지 않도록 방어.
  const entities = (Array.isArray(parsed.entities) ? parsed.entities : [])
    .filter((e: unknown): e is { type: EntityType; name: string } => {
      const rec = e as Record<string, unknown> | null;
      return !!rec && typeof rec.name === 'string' && typeof rec.type === 'string' && isEntityType(rec.type);
    })
    .map((e) => ({ type: e.type, name: e.name }));
  const typeByName = new Map<string, EntityType>(entities.map((e) => [e.name, e.type]));

  // 관계: 관계타입 유효 + 주어·목적어가 추출된 엔티티 + 허용 트리플이어야 함.
  const relations = (Array.isArray(parsed.relations) ? parsed.relations : [])
    .filter((r: unknown): r is { subject: string; type: RelationType; object: string } => {
      const rec = r as Record<string, unknown> | null;
      if (!rec || typeof rec.subject !== 'string' || typeof rec.object !== 'string'
        || typeof rec.type !== 'string' || !isRelationType(rec.type)) return false;
      const subjectType = typeByName.get(rec.subject);
      const objectType = typeByName.get(rec.object);
      return !!subjectType && !!objectType && isAllowedTriple(subjectType, rec.type, objectType);
    })
    .map((r) => ({ subject: r.subject, type: r.type, object: r.object }));

  return { entities, relations };
}
