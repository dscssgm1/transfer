# nginx 容器：托管 Vue 静态页面，并把 /api 反代到宿主机上的 Spring Boot
#
# 前端产物不打进镜像，而是通过 docker-compose 的 bind mount
# 把宿主机目录挂载到 /usr/share/nginx/html，更新前端只需覆盖文件。
FROM nginx:1.27-alpine

RUN rm /etc/nginx/conf.d/default.conf
COPY nginx/default.conf /etc/nginx/conf.d/default.conf

EXPOSE 80
