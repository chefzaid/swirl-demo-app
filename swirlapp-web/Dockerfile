FROM docker.io/library/node:26.13.1-alpine@sha256:6bd9ab43c0ec0190ca7b2d193906cb8b7edf6e7f4afa43ddeccd6452710bffb5 AS build
WORKDIR /workspace
COPY package.json package-lock.json ./
RUN npm install --global npm@12.2.2
RUN npm ci --ignore-scripts
COPY angular.json tsconfig.json tsconfig.app.json tsconfig.spec.json .browserslistrc ./
COPY src ./src
RUN npm run build-prod

FROM docker.io/nginxinc/nginx-unprivileged:1.30.6-alpine@sha256:f5116ed4606f3eb015dd4a8e28cb9b351476dc0ad4bc6e9f93b19cec130cdf03
USER root
RUN apk upgrade --no-cache
USER 101:101
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build --chown=101:101 /workspace/dist/swirlapp-web/browser /usr/share/nginx/html
EXPOSE 8080
USER 101:101
