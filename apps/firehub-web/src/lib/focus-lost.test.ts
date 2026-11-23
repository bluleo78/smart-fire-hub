import { afterEach, describe, expect, it } from 'vitest';

import { focusMainContent, isFocusLost, isMainContent } from './focus-lost';

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

/** isMainContent — main#main-content 만 true (body·null·같은 id 의 다른 태그는 false) */
describe('isMainContent', () => {
  afterEach(() => {
    document.body.innerHTML = '';
  });

  it('main#main-content 만 true 다', () => {
    document.body.innerHTML = '<main id="main-content"></main><div id="main-content"></div>';
    expect(isMainContent(document.querySelector('main'))).toBe(true);
    expect(isMainContent(document.querySelector('div'))).toBe(false);
    expect(isMainContent(document.body)).toBe(false);
    expect(isMainContent(null)).toBe(false);
  });
});

/** focusMainContent — tabindex 가 없을 때만 -1 을 붙이고 초점을 준다 (WD-1039) */
describe('focusMainContent', () => {
  afterEach(() => {
    document.body.innerHTML = '';
  });

  it('focusMainContent 는 tabindex 가 없는 main 에 tabindex=-1 을 붙이고 초점을 준다', () => {
    document.body.innerHTML = '<main id="main-content"></main>';
    const main = document.getElementById('main-content')!;
    focusMainContent(main);
    expect(main.getAttribute('tabindex')).toBe('-1');
    expect(document.activeElement).toBe(main);
  });

  it('focusMainContent 는 이미 있는 tabindex 를 바꾸지 않는다', () => {
    document.body.innerHTML = '<main id="main-content" tabindex="-1"></main>';
    const main = document.getElementById('main-content')!;
    focusMainContent(main);
    expect(main.getAttribute('tabindex')).toBe('-1');
    expect(document.activeElement).toBe(main);
  });
});
