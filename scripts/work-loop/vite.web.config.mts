// work-loop 격리 스택 전용 Vite 설정 래퍼(firehub-web).
// .mts 인 이유: scripts/ 에는 "type":"module" package.json 이 없어 .ts 면 Vite 가 CJS 로 번들해 ESM 전용 플러그인 로드가 실패한다.
// 앱의 vite.config.ts 는 /api 프록시 대상을 개발 api(localhost:5010)로 고정하고 있어, 격리 포트로 띄워도
// 그대로 쓰면 다른 세션의 개발 api 로 요청이 새어 나간다. 앱 설정을 그대로 가져와 프록시 대상만
// FIREHUB_API_PROXY_TARGET 으로 바꾼다(앱 코드는 건드리지 않아 기본 dev 동작은 무변경).
import base from '../../apps/firehub-web/vite.config'

const target = process.env.FIREHUB_API_PROXY_TARGET
if (!target) throw new Error('FIREHUB_API_PROXY_TARGET 가 비어 있다 — work-loop-stack.sh 로 기동할 것')
const proxy = (base.server?.proxy ?? {}) as Record<string, { target: string }>
proxy['/api'].target = target

export default base
