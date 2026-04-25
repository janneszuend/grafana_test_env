FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json* ./
RUN npm install
COPY src src
RUN npx esbuild src/tracing.js --bundle --outfile=dist/tracing.js --format=iife --global-name=Tracing

FROM nginx:alpine
COPY --from=build /app/dist/tracing.js /usr/share/nginx/html/tracing.js
COPY src/index.html /usr/share/nginx/html/index.html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 3000
