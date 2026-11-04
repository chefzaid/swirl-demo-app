FROM docker.io/library/node:24.19.0-alpine@sha256:2a49bdf71e9fd965a58c1703fd9ddd205b34e5782b692a72dd1d248abb0beb43 AS build
WORKDIR /workspace
COPY package.json package-lock.json ./
RUN npm install --global npm@12.0.2
RUN npm ci --ignore-scripts
COPY angular.json tsconfig.json tsconfig.app.json tsconfig.spec.json .browserslistrc ./
COPY src ./src
RUN npm run build-prod

FROM docker.io/nginxinc/nginx-unprivileged:1.31.5-alpine@sha256:19c132c9ab02d3b783f478743dafc7a7f42e27aa7d2bdcbec1bb1128ca8f2a07
USER root
RUN apk upgrade --no-cache
USER 101:101
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build --chown=101:101 /workspace/dist/swirlapp-web/browser /usr/share/nginx/html
EXPOSE 8080
USER 101:101
