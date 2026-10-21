FROM nginx:1.27-alpine
# 删除默认配置，替换为自己的
RUN rm /etc/nginx/conf.d/default.conf
COPY default.conf /etc/nginx/conf.d/
# 如果有静态页面，可以复制进去
#COPY html/ /usr/share/nginx/html/