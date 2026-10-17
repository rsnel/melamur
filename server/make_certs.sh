#!/bin/sh
# Development certificates. In production the server uses a public CA
# certificate for melamur.snel.it (verified by the app via system CAs); only the
# client CA is private.
set -e
cd "$(dirname "$0")"; mkdir -p certs; cd certs
PASS=${P12_PASSWORD:-changeit}
openssl req -x509 -newkey rsa:3072 -nodes -days 3650 -subj "/CN=MelaMur client CA" \
  -keyout client-ca.key -out client-ca.pem
openssl req -newkey rsa:2048 -nodes -subj "/CN=melamur-app" -keyout client.key -out client.csr
openssl x509 -req -in client.csr -CA client-ca.pem -CAkey client-ca.key -CAcreateserial -days 825 -out client.pem
openssl pkcs12 -export -inkey client.key -in client.pem -name melamur -passout pass:$PASS -out client.p12
[ -f server.pem ] || openssl req -x509 -newkey rsa:2048 -nodes -days 365 -subj "/CN=melamur.snel.it" \
  -addext "subjectAltName=DNS:melamur.snel.it" -keyout server.key -out server.pem
cp client.p12 ../../app/src/main/assets/client.p12
echo "client.p12 copied into app assets (git-ignored); build with -PmelamurP12Password=$PASS"
