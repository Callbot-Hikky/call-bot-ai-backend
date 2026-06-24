#!/bin/sh
# Runs the app in development mode with automatic recompilation.
#
# - inotifywait watches src/main; on every change it recompiles (mvnw compile),
#   which updates target/classes -> Spring Boot DevTools restarts the app.
# - spring-boot:run runs in the foreground (DevTools enabled).
#
# Used by docker-compose.dev.yml; no need to run it manually.

set -e

# Background watcher: recompile on every source change.
(
  while inotifywait -q -r -e modify,create,delete,move src/main >/dev/null 2>&1; do
    echo "[dev-reload] change detected -> recompiling..."
    ./mvnw -q compile || echo "[dev-reload] compilation failed (fix the error, it will retry)"
  done
) &

# App in the foreground (DevTools restarts when target/classes changes).
exec ./mvnw spring-boot:run
