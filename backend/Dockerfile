FROM postgres:16-alpine
ENV POSTGRES_PASSWORD=backend_dev_pw
ENV POSTGRES_DB=wormgitty_backend
COPY scripts/init.sql /docker-entrypoint-initdb.d/init.sql
EXPOSE 5432
