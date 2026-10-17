#!/usr/bin/env python3
"""MelaMur aggregator — STUB.

Accepts batches of on-board GPS fixes over mutual TLS: only clients presenting
a certificate signed by the MelaMur client CA (server/certs/client-ca.pem) are
accepted. Fixes are appended to a JSONL file; the fusion step (combining fixes
with Tallinja stops/schedules into live bus positions and better route
geometry) is left as TODO.

  ./make_certs.sh            # dev CA, server cert, client.p12 for the app
  ./aggregator.py 8443       # listens on https://0.0.0.0:8443/aggregator/
"""
import json, ssl, sys, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

STORE = 'fixes.jsonl'


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        if self.path.rstrip('/') != '/aggregator':
            return self.send_error(404)
        n = int(self.headers.get('Content-Length', 0))
        if n > 1 << 20:
            return self.send_error(413)
        try:
            body = json.loads(self.rfile.read(n))
            fixes = body['fixes']
        except Exception:
            return self.send_error(400)
        with open(STORE, 'a') as f:
            for fx in fixes:
                f.write(json.dumps({'trip': body.get('trip'), 'rx': time.time(), **fx}) + '\n')
        # TODO fusion: snap fixes to route shapes, estimate vehicle position and
        # delay per trip, refine stop locations / dwell times, publish live map.
        self.send_response(204)
        self.end_headers()


def main(port=8443):
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.minimum_version = ssl.TLSVersion.TLSv1_2
    ctx.load_cert_chain('certs/server.pem', 'certs/server.key')
    ctx.load_verify_locations('certs/client-ca.pem')
    ctx.verify_mode = ssl.CERT_REQUIRED  # the app must present its client cert
    srv = ThreadingHTTPServer(('0.0.0.0', port), Handler)
    srv.socket = ctx.wrap_socket(srv.socket, server_side=True)
    print(f'aggregator stub on :{port}')
    srv.serve_forever()


if __name__ == '__main__':
    main(int(sys.argv[1]) if len(sys.argv) > 1 else 8443)
