# PostgreSQL 16 (Alpine) with the pgvector extension compiled in.
#
# Same base image as before, on purpose: the data volume keeps its libc (musl), so
# text collation and existing indexes are untouched. Switching to a Debian-based
# image on an existing volume would silently change sort order.
FROM postgres:16-alpine

ARG PGVECTOR_VERSION=0.8.0

RUN apk add --no-cache --virtual .build-deps git build-base \
    && git clone --branch "v${PGVECTOR_VERSION}" --depth 1 https://github.com/pgvector/pgvector.git /tmp/pgvector \
    && cd /tmp/pgvector \
    && make OPTFLAGS="" with_llvm=no \
    && make install with_llvm=no \
    && rm -rf /tmp/pgvector \
    && apk del .build-deps
