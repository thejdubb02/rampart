# One page and its pictures. Nothing is rendered in the browser, so a crawler that does
# not run JavaScript, which is all of the ones that answer questions, sees the whole page
# in the first response.
FROM nginx:alpine

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY index.html robots.txt sitemap.xml /usr/share/nginx/html/
COPY img/ /usr/share/nginx/html/img/

EXPOSE 80
