/**
 * 무엇: 디자인 가이드라인 중 "문서가 정답을 딱 하나로 명시한" 규칙들을 정적으로 고정하는 게이트.
 * 왜:  이런 이탈은 한 번 고쳐도 다음 기능 개발에서 조용히 다시 유입된다(#435 에서 실제로
 *      코드베이스 전체에 각 1건씩만 남아 있던 상태였다). 값이 하나로 정해진 규칙이라
 *      리뷰어 눈이 아니라 테스트가 지키는 편이 싸다.
 *
 * 범위: src/ 전체(.ts/.tsx). `components/ui/` 는 shadcn 생성물이라 손대지 않으므로 제외한다.
 */
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { describe, expect, it } from 'vitest';

const SRC_DIR = resolve(dirname(fileURLToPath(import.meta.url)), '..');

/**
 * `components/ui/` 중 **shadcn CLI 가 생성한** 파일들. 이들만 검사에서 제외한다.
 *
 * 왜 디렉터리 통째로 제외하지 않는가 (#448): 같은 디렉터리에 프로젝트가 직접 작성한
 * 컴포넌트가 섞여 있다(한국어 주석 + shadcn 표준 목록에 없는 이름). 통째로 빼면 그 파일들이
 * **영구히 보이지 않는 자리**가 되어, #436 이 25건을 고치는 동안 `searchable-select.tsx` 의
 * `ml-2` 하나만 조용히 살아남는 일이 실제로 벌어졌다.
 *
 * 새 shadcn 컴포넌트를 `npx shadcn` 으로 추가하면 여기에 이름을 넣어야 한다. 넣지 않으면
 * 게이트가 그 파일을 검사하는데, 그건 안전한 방향의 실패다(시끄럽게 알려준다).
 */
const SHADCN_GENERATED = new Set([
  'avatar.tsx', 'badge.tsx', 'button.tsx', 'card.tsx', 'checkbox.tsx', 'collapsible.tsx',
  'command.tsx', 'dropdown-menu.tsx', 'input.tsx', 'label.tsx', 'popover.tsx',
  'radio-group.tsx', 'scroll-area.tsx', 'select.tsx', 'separator.tsx', 'skeleton.tsx',
  'sonner.tsx', 'switch.tsx', 'textarea.tsx', 'tooltip.tsx',
]);

/** src/ 하위의 소스 파일을 모은다(테스트 파일과 shadcn 생성물은 제외). */
function collectSources(dir: string): string[] {
  const inUiDir = dir.endsWith(join('components', 'ui'));
  const out: string[] = [];
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) {
      out.push(...collectSources(full));
      continue;
    }
    if (!/\.tsx?$/.test(entry)) continue;
    if (/\.test\.tsx?$/.test(entry)) continue;
    if (inUiDir && SHADCN_GENERATED.has(entry)) continue;
    out.push(full);
  }
  return out;
}

const FILES = collectSources(SRC_DIR);
const rel = (f: string) => f.slice(SRC_DIR.length + 1);

describe('디자인 가이드라인 게이트', () => {
  /**
   * 03-spacing-layout.md §승인된 Spacing Scale:
   * "space-5 (20px)는 프로젝트에서 사용하지 않는다. 새로 도입하지 말 것."
   *
   * 간격 유틸(p/m/gap/space)만 본다. `h-5`·`w-5` 는 간격이 아니라 아이콘 크기(20px)이고
   * 07-iconography.md 의 크기 스케일에 정식으로 들어 있으므로 걸러지면 안 된다.
   */
  it('20px 간격(-5, 20px 임의값) 을 쓰지 않는다', () => {
    // `space-y-5`·`gap-x-5` 같은 **축 형태**를 반드시 포함해야 한다 (#448).
    // 03 문서가 이름으로 지목한 `space-5` 는 Tailwind 에 존재하지 않는 클래스라,
    // 축 형태를 빼면 게이트가 금지 대상의 실제 유입 경로를 통째로 놓친다.
    // `p-[20px]` 같은 임의값 표기도 같은 20px 이므로 함께 막는다.
    const SPACING_20 = /(?:^|[\s'"`])-?(?:p|m|gap|space)(?:[trblxy]|-[xy])?-(?:5|\[20px\])(?![\w-])/;
    const offenders: string[] = [];
    for (const file of FILES) {
      const src = readFileSync(file, 'utf8');
      // className 값 안만 본다 — 파일 전체 텍스트를 보면 주석·문자열의 `gap-5` 언급까지
      // 위반으로 잡혀(거짓 양성) 게이트를 느슨하게 만들고 싶어진다 (#448).
      for (const tag of findOpeningTags(src, () => true)) {
        if (classNameValuesOf(tag.text).some((v) => SPACING_20.test(v))) {
          offenders.push(`${rel(file)}:${tag.line} <${tag.name}>`);
        }
      }
    }
    expect(
      offenders,
      '03-spacing-layout.md 의 승인 스케일에 20px 은 없다 — 16px(-4) 또는 24px(-6) 을 쓰세요',
    ).toEqual([]);
  });

  /**
   * 07-iconography.md §7.1 의미 일관성 / §7.2 금지 패턴:
   * 같은 개념에는 항상 같은 아이콘을 쓴다. Edit→Pencil, Trash→Trash2, Reload→RefreshCw.
   *
   * `Trash2`·`Edit2` 같은 정식 이름이 걸리지 않도록 import 지정자 하나를 통째로 대조한다
   * (부분 문자열 매칭이면 Trash2 가 Trash 로 잡혀 게이트가 거짓 양성이 된다).
   */
  it('같은 개념에 폐기된 아이콘 별칭(Edit/Trash/Reload)을 쓰지 않는다', () => {
    const FORBIDDEN = new Map([
      ['Edit', 'Pencil'],
      ['Trash', 'Trash2'],
      ['Reload', 'RefreshCw'],
    ]);
    const offenders: string[] = [];
    for (const file of FILES) {
      const src = readFileSync(file, 'utf8');
      for (const m of src.matchAll(/import\s*\{([^}]*)\}\s*from\s*'lucide-react'/g)) {
        for (const raw of m[1].split(',')) {
          // `Edit as PencilIcon` 형태도 원본 이름 기준으로 판정한다.
          const name = raw.trim().split(/\s+as\s+/)[0].trim();
          const replacement = FORBIDDEN.get(name);
          if (replacement) offenders.push(`${rel(file)}: ${name} → ${replacement}`);
        }
      }
    }
    expect(offenders, '07-iconography.md §7.2 가 금지한 아이콘 별칭입니다').toEqual([]);
  });

  /**
   * 09-form-patterns.md §J 접근성:
   * "`<label>` 과 `<input>` 은 반드시 연결되어야 한다 (`htmlFor` + `id` 또는 래핑)."
   *
   * 연결이 없으면 스크린리더가 필드 이름을 읽지 못하고 라벨 클릭으로 포커스도 가지 않는다.
   * #432 시점에 전역 113건이 형제 관계로만 놓여 있었다. 손으로만 고치면 다음 기능에서
   * 다시 유입되므로 테스트가 지킨다.
   *
   * id 는 `useId()` 로 만든다 — 다이얼로그·위저드 스텝은 같은 폼이 여러 번 렌더돼
   * 하드코딩 id 가 실제로 충돌한다. (반복 행은 `${baseId}-${index}-필드` 형태)
   */
  it('<Label> 은 반드시 htmlFor 로 입력 요소와 연결한다', () => {
    const offenders: string[] = [];
    for (const file of FILES) {
      const src = readFileSync(file, 'utf8');
      for (const tag of findLabelOpeningTags(src)) {
        if (/\bhtmlFor\b/.test(tag.text)) continue;
        // 예외 키는 파일 + 라벨 텍스트다(라인번호 아님 — #443). 라인번호로 잡으면
        // 위쪽에 줄이 늘 때마다 무고한 실패가 나고, 반대로 다른 htmlFor 없는 <Label>
        // 이 우연히 그 라인에 오면 **조용히 면제**된다.
        const key = `${rel(file)}#${labelTextOf(src, tag)}`;
        if (LABEL_HTMLFOR_ALLOWLIST.has(key)) continue;
        offenders.push(`${key} (${rel(file)}:${tag.line})`);
      }
    }
    expect(
      offenders,
      '09-form-patterns.md §J — <Label htmlFor={id}> + 입력 요소 id={id} 로 연결하세요 (id 는 useId())',
    ).toEqual([]);
  });

  /**
   * 07-iconography.md §4 아이콘-텍스트 간격:
   * "`gap-*`으로 간격을 제어한다. `margin`이나 `padding`을 아이콘에 직접 적용하지 않는다."
   *
   * 이게 취향이 아니라 실제 시각 결함인 이유: shadcn `Button` 은 기저 클래스에 이미
   * `gap-2`(size="sm" 은 `gap-1.5`)를 갖고 있다. 그 안의 아이콘에 `mr-2` 를 덧붙이면
   * 간격이 8px 이 아니라 **16px** 로 벌어져, 같은 화면에서 margin 이 붙은 버튼과 안 붙은
   * 버튼의 간격이 두 배 차이 난다. #436 시점에 이 형태가 약 20건이었다.
   *
   * 검사 대상은 **Lucide 아이콘 컴포넌트**로 좁힌다. 임의의 대문자 컴포넌트까지 잡으면
   * Badge·FreshnessBar 처럼 아이콘이 아닌 인접 요소의 정당한 margin 까지 걸려서,
   * 게이트를 느슨하게 만들고 싶어지는 압력이 생긴다.
   */
  it('아이콘에 margin 을 직접 걸지 않는다 (컨테이너의 gap-* 으로 제어)', () => {
    // 축(mx/my)까지 포함한다. `mr-`/`ml-` 만 보면 `mx-2` 로 우회된다 (#448).
    // 음수 margin(`-ml-2`)은 컨테이너 패딩 상쇄용이라 간격 규칙과 목적이 다르므로 제외 —
    // 그래서 앞에 `-` 가 붙지 않은 경우만 본다.
    const ICON_MARGIN = /(?:^|[\s'"`])m[rlxy]-[0-9]/;
    const offenders: string[] = [];
    for (const file of FILES) {
      const src = readFileSync(file, 'utf8');
      // 이 파일이 lucide 에서 가져온 아이콘 이름들 (`X as Y` 는 별칭 Y 로 쓰인다)
      const icons = new Set<string>();
      for (const m of src.matchAll(/import\s*\{([^}]*)\}\s*from\s*'lucide-react'/g)) {
        for (const raw of m[1].split(',')) {
          const parts = raw.trim().split(/\s+as\s+/);
          const local = (parts[1] ?? parts[0]).trim();
          if (local) icons.add(local);
        }
      }
      if (icons.size === 0) continue;

      // 줄 단위가 아니라 **태그 단위**로 본다 — prettier 가 prop 이 늘어난 태그를 여러 줄로
      // 쪼개면 태그명과 className 이 다른 줄에 놓여 줄 매칭이 통째로 실패한다 (#448).
      for (const tag of findOpeningTags(src, (name) => icons.has(name))) {
        if (classNameValuesOf(tag.text).some((v) => ICON_MARGIN.test(v))) {
          offenders.push(`${rel(file)}:${tag.line} <${tag.name}>`);
        }
      }
    }
    expect(
      offenders,
      '07-iconography.md §4 — 아이콘의 margin 을 지우고 컨테이너에 gap-* 을 주세요',
    ).toEqual([]);
  });
});

/**
 * htmlFor 없이 쓰이는 것이 정당한 <Label> 의 예외 목록. `상대경로#라벨텍스트` 로 고정한다.
 *
 * **라인번호로 키잉하지 않는 이유 (#443)**: 라인번호는 두 방향으로 다 나쁘다.
 *  - 위쪽에 줄이 하나 늘면 예외가 어긋나 무고한 실패가 난다.
 *  - 반대로 htmlFor 없는 다른 <Label> 이 우연히 그 라인에 오면 **조용히 면제**된다.
 * 라벨 텍스트로 잡으면 양쪽 다 사라진다.
 *
 * 예외 사유는 단 하나 — **대응하는 단일 입력 요소가 없는 그룹/섹션 제목**이다.
 * (래핑 `<Label>…<Input/></Label>` 형태는 이 코드베이스에 0건이라 예외 사유가 아니다.)
 * 존재하지 않는 컨트롤로 htmlFor 를 억지로 걸면 스크린리더가 더 나빠지므로 그대로 둔다.
 */
const LABEL_HTMLFOR_ALLOWLIST = new Set<string>([
  // "속성" 섹션 제목. 아래는 속성 행 목록 + "속성 추가" 버튼이라 짝지을 단일 컨트롤이 없다.
  // 그런데 이 <Label> 은 `tabIndex={-1}` + ref 로 **속성 행 삭제 후 포커스 폴백 대상**이고,
  // e2e/pages/admin/ontology-editor.spec.ts 가 `{ tag: 'LABEL', text: '속성' }` 로
  // 태그 자체를 단언한다. <span> 으로 바꾸면 그 회귀 테스트가 깨지므로 현 형태를 유지한다.
  'pages/admin/components/model-editor/EntityInspector.tsx#속성',
]);

/**
 * `<Label ...>` 여는 태그 바로 뒤의 라벨 텍스트를 뽑는다 — ALLOWLIST 의 키.
 *
 * 닫는 `</Label>` 까지의 내용에서 JSX 표현식(`{...}`)과 중첩 태그를 걷어내고 남은 평문을
 * 공백 정규화해 돌려준다. 평문이 없으면(전부 표현식인 동적 라벨) 빈 문자열이 되는데,
 * 그런 라벨은 안정된 키가 없으므로 애초에 예외로 등록할 수 없다 — 의도된 동작이다.
 */
function labelTextOf(src: string, tag: { text: string; line: number }): string {
  const start = src.indexOf(tag.text);
  if (start === -1) return '';
  const bodyStart = start + tag.text.length;
  const close = src.indexOf('</Label>', bodyStart);
  if (close === -1) return '';
  return src
    .slice(bodyStart, close)
    .replace(/\{[^}]*\}/g, ' ') // JSX 표현식 제거
    .replace(/<[^>]*>/g, ' ') // 중첩 태그 제거
    .replace(/\s+/g, ' ')
    .trim();
}

/**
 * 소스에서 여는 JSX 태그를 전부 찾는다. `accept` 로 태그명을 걸러 쓴다.
 *
 * 왜 정규식 한 줄이 아니라 스캐너인가:
 *  1) prettier 가 prop 이 늘어난 태그를 여러 줄로 쪼개므로 **줄 단위 매칭이면 태그명과
 *     className 이 다른 줄에 놓여 통째로 못 본다** (#448 에서 실증됨). 게이트를 느슨하게
 *     만들고 싶어지는 함정이라 애초에 태그 단위로 본다.
 *  2) `className={cn(a > b ? ...)}` 처럼 prop 값 안의 `>` 를 태그 끝으로 오인하면 안 된다.
 *     중괄호 깊이와 따옴표를 추적해 깊이 0 의 `>` 만 태그 끝으로 본다.
 *  3) 주석·문자열 안의 `<Foo` 는 태그가 아니지만, JSX 파일에서 그 형태가 우연히 나올 확률이
 *     낮고 걸려도 className 이 없어 무해하므로 별도 처리하지 않는다.
 */
function findOpeningTags(
  src: string,
  accept: (name: string) => boolean,
): { name: string; text: string; line: number }[] {
  const found: { name: string; text: string; line: number }[] = [];
  for (let i = 0; i < src.length; i++) {
    if (src[i] !== '<') continue;
    const m = /^<([A-Za-z][A-Za-z0-9_.$]*)/.exec(src.slice(i, i + 64));
    if (!m) continue;
    const name = m[1];
    if (!accept(name)) continue;
    let depth = 0;
    let quote: string | null = null;
    let end = -1;
    for (let j = i + m[0].length; j < src.length; j++) {
      const ch = src[j];
      if (quote) {
        if (ch === '\\') j++;
        else if (ch === quote) quote = null;
        continue;
      }
      if (ch === '"' || ch === "'" || ch === '`') quote = ch;
      else if (ch === '{') depth++;
      else if (ch === '}') depth--;
      else if (ch === '>' && depth === 0) {
        end = j;
        break;
      }
    }
    if (end === -1) continue;
    found.push({ name, text: src.slice(i, end + 1), line: src.slice(0, i).split('\n').length });
    i = end;
  }
  return found;
}

/** `<Label ...>` 여는 태그만 (`<LabelPrimitive`·`<LabelList` 같은 다른 컴포넌트는 제외). */
function findLabelOpeningTags(src: string): { text: string; line: number }[] {
  return findOpeningTags(src, (name) => name === 'Label');
}

/**
 * 여는 태그에서 className 으로 지정된 **문자열 리터럴들**을 뽑는다.
 *
 * `className="a b"` 리터럴뿐 아니라 `className={cn('a', cond && 'b')}` 안의 리터럴까지 본다 —
 * 리터럴만 보면 `cn('mr-2', ...)` 형태로 게이트가 통째로 우회된다 (#448 에서 실증됨).
 * 템플릿 리터럴의 `${...}` 보간부는 정적으로 알 수 없으므로 걷어내고 고정 부분만 검사한다.
 */
function classNameValuesOf(tagText: string): string[] {
  const at = tagText.indexOf('className');
  if (at === -1) return [];
  const rest = tagText.slice(at + 'className'.length).replace(/^\s*=\s*/, '');
  if (rest.startsWith('"') || rest.startsWith("'")) {
    const q = rest[0];
    const close = rest.indexOf(q, 1);
    return close === -1 ? [] : [rest.slice(1, close)];
  }
  if (!rest.startsWith('{')) return [];
  // 표현식 전체에서 문자열 리터럴을 전부 긁는다(cn/clsx/삼항/템플릿 모두 같은 취급).
  const values: string[] = [];
  for (const m of rest.matchAll(/'([^'\\]*)'|"([^"\\]*)"|`([^`]*)`/g)) {
    const v = m[1] ?? m[2] ?? m[3];
    if (v !== undefined) values.push(v.replace(/\$\{[^}]*\}/g, ' '));
  }
  return values;
}
