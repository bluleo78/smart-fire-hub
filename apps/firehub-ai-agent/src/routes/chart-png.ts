import { createCanvas, type SKRSContext2D } from '@napi-rs/canvas';
import { Chart, registerables, type ChartConfiguration, type Plugin } from 'chart.js';

// lib 에 DOM 이 없는(tsconfig lib: ES2022) 이미지 빌드에서도 컴파일되게, DOM 전역 타입
// (CanvasRenderingContext2D·HTMLCanvasElement)을 직접 쓰지 않고 chart.js 시그니처에서 타입을 뽑는다(WD-806).
// 로컬에서는 다른 패키지가 DOM 타입을 끌어와 통과했지만 Docker 빌드의 tsc 에서는 TS2304 로 깨졌다.
type ChartCanvasItem = ConstructorParameters<typeof Chart>[0];

// chart.js 의 모든 컨트롤러·스케일·요소를 모듈 로드 때 한 번만 등록한다.
// (요청마다 등록할 필요가 없고, tree-shaking 이 필요한 번들 환경도 아니다.)
Chart.register(...registerables);

// 기본 글꼴을 한글 지원 계열로 지정한다. ai-agent 의 alpine 이미지에는 font-noto-cjk 만 설치되어
// Chart.js 기본값(Helvetica/Arial)이 없으므로, 지정하지 않으면 한글 제목·라벨이 깨진다.
// 글꼴 파일을 경로로 등록하지 않고 시스템 폰트(fontconfig)를 이름으로 찾게 한다.
Chart.defaults.font.family = "'Noto Sans CJK KR', 'Noto Sans KR', sans-serif";

/**
 * 투명 PNG 가 되지 않도록 차트를 그리기 전에 캔버스 전체를 흰색으로 채우는 플러그인.
 * 이전 구현(chartjs-node-canvas 의 backgroundColour: 'white')과 같은 결과를 내기 위함이다.
 */
const whiteBackgroundPlugin: Plugin = {
  id: 'wd806WhiteBackground',
  beforeDraw: (chart) => {
    // tsconfig 에 DOM lib 이 없어 chart.ctx 타입이 비어 있으므로 실제 컨텍스트 타입으로 단언한다.
    const ctx = chart.ctx as unknown as SKRSContext2D;
    ctx.save();
    ctx.fillStyle = 'white';
    ctx.fillRect(0, 0, chart.width, chart.height);
    ctx.restore();
  },
};

/**
 * chart.js 설정을 PNG 버퍼로 그린다 (WD-806).
 *
 * 왜: 기존 chartjs-node-canvas → node-canvas 는 네이티브 모듈이 alpine(musl) 이미지에서
 * 빌드되지 않아 500 이 났다. @napi-rs/canvas(Skia)는 musl 사전 빌드를 제공해
 * 설치 스크립트·시스템 라이브러리 없이 동작한다.
 *
 * - 요청마다 캔버스를 새로 만들고 끝나면(성공·실패 모두) 차트를 destroy() 한다(캐시 없음, 누수 방지).
 * - 서버 렌더링이므로 반응형·애니메이션을 끄고 devicePixelRatio 를 1 로 고정해
 *   출력 PNG 크기가 요청한 width×height 와 정확히 같게 한다.
 * - 잘못된 차트 type 등은 chart.js 가 던지는 예외를 그대로 전파한다(호출자가 500 처리).
 *
 * @param configuration chart.js 차트 설정(type/data/options)
 * @param width 출력 이미지 너비(px)
 * @param height 출력 이미지 높이(px)
 * @returns PNG 바이너리(Promise)
 */
export async function renderChartPng(
  configuration: { type: string; data: unknown; options?: Record<string, unknown> },
  width: number,
  height: number,
): Promise<Buffer> {
  const canvas = createCanvas(width, height);

  // 생성자가 던져도(예: 잘못된 type) chart.js 는 인스턴스를 Chart.instances 에 이미 등록한 뒤 beforeInit 을 부르고,
  // 컨트롤러 생성(잘못된 type 에서 던지는 지점)은 그 뒤다. 그래서 이번 호출 전용 플러그인으로 인스턴스를 붙잡아
  // 생성자가 던진 경우에도 정확히 이번 인스턴스만 정리한다(전역 스냅샷·canvas 비교에 기대지 않음).
  // 성공하면 생성자 반환값과 같은 인스턴스이므로 이 변수 하나로 정리한다.
  let chart: Chart | undefined;
  const trackPlugin: Plugin = {
    id: 'wd806Track',
    beforeInit: (c) => {
      chart = c;
    },
  };
  try {
    new Chart(canvas as unknown as ChartCanvasItem, {
      ...configuration,
      options: {
        ...configuration.options,
        responsive: false,
        animation: false,
        devicePixelRatio: 1,
      },
      plugins: [whiteBackgroundPlugin, trackPlugin],
    } as unknown as ChartConfiguration);
    // 인코딩은 libuv 스레드풀에서 비동기로 돌려 이벤트 루프를 막지 않는다.
    // destroy 는 인코딩이 끝난 뒤(finally)에 한다.
    return await canvas.encode('png');
  } finally {
    // 생성자가 던졌어도 beforeInit 에서 붙잡은 인스턴스를 정리한다.
    try {
      chart?.destroy();
    } catch (cleanupError: unknown) {
      // 정리 실패가 원래 오류(또는 성공한 PNG)를 덮지 않도록 경고만 남기고 다시 던지지 않는다.
      console.warn('[ChartPng] destroy failed:', cleanupError);
    }
  }
}
