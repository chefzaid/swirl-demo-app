FROM docker.io/library/node:24.22.3-alpine@sha256:116b800c3754b440ee0aa3ed2f20f5bf3d99a554bfd9adeea09ea8017f6e85f5 AS build
WORKDIR /workspace
COPY package.json package-lock.json ./
RUN npm install --global npm@12.2.2
RUN npm ci --ignore-scripts
COPY angular.json tsconfig.json tsconfig.app.json tsconfig.spec.json .browserslistrc ./
COPY src ./src
RUN npm run build-prod

FROM docker.io/nginxinc/nginx-unprivileged:1.31.7-alpine@sha256:b5047b9b76845eac124358373c6852377c52e4b05834a47647e955dd91cb8f1e
USER root
RUN apk upgrade --no-cache
USER 101:101
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build --chown=101:101 /workspace/dist/swirlapp-web/browser /usr/share/nginx/html
EXPOSE 8080
USER 101:101
