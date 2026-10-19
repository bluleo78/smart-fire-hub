import { Prec } from '@codemirror/state';
import { keymap } from '@codemirror/view';

/**
 * SQL 편집기의 실행 단축키(Mod-Enter)와 그 안내 문구 — 분석 쿼리 편집기와 데이터셋 SQL 편집기가 함께 쓴다(WD-87).
 *
 * 왜 한곳에 두나: 두 편집기가 따로 키맵을 짜다 보니 실행 바인딩이 기본 키맵 뒤에 놓여 defaultKeymap 의
 * Mod-Enter(insertBlankLine)가 먼저 처리됐고, 안내("Cmd+Enter로 실행")대로 눌러도 빈 줄만 들어갔다.
 * 안내 문구는 CodeMirror 를 끌어오지 않도록 sql-run-shortcut-label.ts 에 따로 둔다(데이터셋 SQL 편집기는 CodeMirror 를 지연 로드).
 */

/**
 * Mod-Enter 로 실행하는 CodeMirror 확장. 기본 키맵보다 높은 우선순위(Prec.high)로 두어, 키맵 배열 순서가 바뀌어도
 * insertBlankLine 이 먼저 처리되지 않는다. run 은 편집기를 다시 만들지 않도록 ref 로 최신 콜백을 부르는 함수를 넘긴다.
 */
export function sqlRunShortcut(run: () => void) {
  return Prec.high(
    keymap.of([
      {
        key: 'Mod-Enter',
        run: () => {
          run();
          return true;
        },
      },
    ]),
  );
}
