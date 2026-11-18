FROM --platform=$BUILDPLATFORM node:22-alpine AS build
WORKDIR /app
RUN npm install -g pnpm
COPY package.json tsconfig.json ./
# pnpm 10.x strict 모드에서 transitive esbuild 의 빌드 스크립트가 실행되지 않으면
# ERR_PNPM_IGNORED_BUILDS 로 실패. esbuild 는 prebuilt binary 동봉이라 안전하게
# --ignore-scripts 로 우회 가능.
RUN pnpm install --ignore-scripts
COPY src/ ./src/
RUN pnpm build

FROM node:22-alpine
# 런타임에 쓰지 않는 npm·npx·corepack 을 지운다(WD-790 — 번들된 npm 의 취약 의존성이 이미지 검사에 잡힌다).
# 지워도 되는 근거: 런타임은 `node dist/index.js` 하나만 실행하고, 의존성은 빌드 스테이지에서 pnpm 으로 받아 복사한다.
# 지운 뒤 npm·npx·corepack 이 PATH 에 남았는지 확인한다 — 베이스 이미지가 설치 위치를 바꾸면 rm 이 조용히 빗나가므로 빌드를 실패시킨다.
# 확인은 새 셸(sh -c)에서 한다 — 같은 RUN 셸은 앞서 실행한 npm 의 경로를 해시해 두어, 파일을 지운 뒤에도 command -v 가 그 경로를 내기 때문이다.
RUN rm -rf /usr/local/lib/node_modules/npm /usr/local/lib/node_modules/corepack \
           /usr/local/bin/npm /usr/local/bin/npx /usr/local/bin/corepack \
    && sh -c '! command -v npm && ! command -v npx && ! command -v corepack'
WORKDIR /app
COPY --from=build /app/dist ./dist
COPY --from=build /app/node_modules ./node_modules
COPY --from=build /app/package.json ./
EXPOSE 3002
CMD ["node", "dist/index.js"]
