#!/bin/sh
# Starts the backend on a hosted platform.
#  - The platform supplies PORT; the application reads SERVER_PORT.
#  - A managed database signs its TLS certificate with its own CA. That public certificate is
#    passed as DB_CA_PEM_B64 (base64, one line) and turned into a truststore here, so the JDBC
#    URL can use sslMode=VERIFY_CA. It holds no secret.
set -eu
if [ -n "${DB_CA_PEM_B64:-}" ]; then
  echo "$DB_CA_PEM_B64" | base64 -d > /tmp/db-ca.pem
  rm -f /tmp/db-trust.p12
  keytool -importcert -noprompt -alias dbca -file /tmp/db-ca.pem \
    -keystore /tmp/db-trust.p12 -storetype PKCS12 -storepass changeit >/dev/null
fi
SERVER_PORT="${PORT:-8080}"
export SERVER_PORT
# shellcheck disable=SC2086
exec java $JAVA_OPTS -jar /opt/obfds/app.jar
