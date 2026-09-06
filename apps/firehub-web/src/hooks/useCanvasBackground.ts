import { useSyncExternalStore } from 'react';

/**
 * `--background` CSS 변수의 실제 계산값을 Cytoscape 호환 색으로 읽어오는 훅 (#507).
 *
 * 무엇: `getComputedStyle(document.documentElement)`로 현재 테마의 `--background`
 *       (예: `oklch(0.13 0.015 280)`) 값을 읽고, `toCytoscapeColor`로 `rgb()` 문자열로
 *       변환해 반환한다.
 * 왜: Cytoscape는 캔버스(2D context)에 직접 그리므로 스타일시트가 CSS 변수(`var(--background)`)를
 *     읽지 못해 리터럴 색이 필요했다(#377). 리터럴을 고정 상수로 박아 두면 테마 컬러
 *     (indigo/ocean/sunset)마다 다른 실제 `--background` 값과 어긋나, 엣지 라벨 배경 박스가
 *     캔버스 배경과 다른 색의 사각형 얼룩으로 보인다(#507 최초 결함).
 *     **회귀(#507 크로스체크)**: 최초 수정은 `--background`의 원시 문자열(`oklch(...)`)을
 *     그대로 반환했는데, Cytoscape의 색상 파서(`colorname2tuple || hex2tuple || rgb2tuple ||
 *     hsl2tuple`)는 OKLCH 문법을 모른다 — 파싱 실패로 라벨 배경이 다시 (이번엔 검정에 가까운
 *     값으로) 실제 배경과 어긋났다. 그래서 훅이 반환하기 전에 직접 변환한다.
 * 어떻게 변경을 감지하는가: 다크/라이트 전환(next-themes)과 테마 컬러 전환(useThemeColor)은
 *   모두 `document.documentElement`의 class를 바꾸는 방식으로 구현돼 있다. 두 훅(각각 다른
 *   컨텍스트/모듈)에 개별적으로 의존해 재계산 타이밍을 맞추는 대신, `useSyncExternalStore` +
 *   `MutationObserver`로 `class` 속성 변화를 직접 구독한다 — DOM이라는 외부 소스를 구독하는
 *   React 표준 패턴이며, effect 안에서 setState를 직접 호출하지 않아도 되고(react-hooks
 *   set-state-in-effect 회피) React 컴포넌트 트리에서의 effect 실행 순서(부모의 class 갱신이
 *   자식의 읽기보다 먼저 일어난다는 보장)에도 기대지 않는다.
 */
export function useCanvasBackground(): string {
  return useSyncExternalStore(subscribe, readComputedBackground, () => '');
}

// documentElement의 class 속성 변화를 구독한다 — 콜백이 곧 "값이 바뀌었을 수 있다"는 신호다.
function subscribe(onStoreChange: () => void): () => void {
  const observer = new MutationObserver(onStoreChange);
  observer.observe(document.documentElement, { attributes: true, attributeFilter: ['class'] });
  return () => observer.disconnect();
}

// SSR/테스트 환경(window 없음)에서는 빈 문자열을 반환해 호출부가 리터럴 폴백을 쓰도록 한다.
function readComputedBackground(): string {
  if (typeof window === 'undefined') return '';
  const raw = getComputedStyle(document.documentElement).getPropertyValue('--background').trim();
  return toCytoscapeColor(raw);
}

const OKLCH_PATTERN = /^oklch\(\s*([\d.]+)\s+([\d.]+)\s+([\d.]+)\s*\)$/i;

/**
 * OKLCH 문자열을 Cytoscape가 파싱 가능한 `rgb()` 문자열로 변환한다 (#507 회귀 수정).
 *
 * 왜 DOM(`el.style.color` + `getComputedStyle`)이나 canvas(`ctx.fillStyle`) 정규화 대신
 * 직접 계산하는가: 그 방식들은 브라우저의 OKLCH CSS 파서 지원 여부에 기대는데, 이 값은
 * 매 리렌더/테마 전환마다 읽어야 하므로 환경(구형 브라우저·jsdom 단위 테스트)에 따라 달라지면
 * 안 된다. `index.css`가 `--background`를 항상 `oklch(L C H)`(알파 없는 3항) 형식으로만
 * 정의하므로, CSS Color 4 스펙의 OKLab → 선형 sRGB 변환 행렬(Björn Ottosson)을 그대로
 * 구현해 환경 독립적으로 계산한다. oklch가 아닌 값(이미 hex/rgb 등 호환 형식)은 그대로 통과시킨다.
 */
function toCytoscapeColor(raw: string): string {
  const match = raw.match(OKLCH_PATTERN);
  if (!match) return raw;

  const l = Number(match[1]);
  const c = Number(match[2]);
  const hRad = (Number(match[3]) * Math.PI) / 180;
  const a = c * Math.cos(hRad);
  const b = c * Math.sin(hRad);

  // OKLab → LMS (비선형)
  const l_ = l + 0.3963377774 * a + 0.2158037573 * b;
  const m_ = l - 0.1055613458 * a - 0.0638541728 * b;
  const s_ = l - 0.0894841775 * a - 1.2914855480 * b;
  const lc = l_ ** 3;
  const mc = m_ ** 3;
  const sc = s_ ** 3;

  // LMS → 선형 sRGB
  const linearR = 4.0767416621 * lc - 3.3077115913 * mc + 0.2309699292 * sc;
  const linearG = -1.2684380046 * lc + 2.6097574011 * mc - 0.3413193965 * sc;
  const linearB = -0.0041960863 * lc - 0.7034186147 * mc + 1.7076147010 * sc;

  // 선형 sRGB → 감마 보정 sRGB(0~255), 범위 밖 값은 클램프
  const toChannel = (v: number): number => {
    const clamped = Math.min(1, Math.max(0, v));
    const gamma = clamped <= 0.0031308 ? 12.92 * clamped : 1.055 * clamped ** (1 / 2.4) - 0.055;
    return Math.round(Math.min(1, Math.max(0, gamma)) * 255);
  };

  return `rgb(${toChannel(linearR)}, ${toChannel(linearG)}, ${toChannel(linearB)})`;
}
