import { afterEach, describe, expect, it } from 'vitest';

import { isFocusLost } from './focus-lost';

/** isFocusLost — body 뿐 아니라 건너뛰기 대상 main 으로 돌아온 포커스도 「잃음」으로 본다 (WD-968 리뷰 후속) */
describe('isFocusLost', () => {
  afterEach(() => {
    document.body.innerHTML = '';
  });

  it('activeElement 가 없거나 body 면 잃은 것이다', () => {
    expect(isFocusLost(null)).toBe(true);
    expect(isFocusLost(document.body)).toBe(true);
  });

  it('본문 main#main-content 자체에 있으면 잃은 것이다', () => {
    document.body.innerHTML = '<main id="main-content" tabindex="-1"><button>안</button></main>';
    const main = document.getElementById('main-content')!;
    main.focus();
    expect(document.activeElement).toBe(main);
    expect(isFocusLost()).toBe(true);
  });

  it('main 안의 컨트롤이나 다른 요소에 있으면 잃지 않은 것이다', () => {
    document.body.innerHTML = '<main id="main-content"><button>안</button></main>';
    const btn = document.querySelector('button')!;
    btn.focus();
    expect(isFocusLost()).toBe(false);
    // 같은 id 라도 main 이 아니면 본문 자리가 아니다
    const div = document.createElement('div');
    div.id = 'main-content';
    expect(isFocusLost(div)).toBe(false);
  });
});
