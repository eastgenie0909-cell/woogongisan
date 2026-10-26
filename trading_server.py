"""Private local service: python trading_server.py --demo (no real orders).

For Meritz use --meritz and configure environment variables; starts in observation.
Only bind to loopback. Remote access requires an authenticated HTTPS reverse proxy.
"""
import argparse
import hmac
import json
import os
from pathlib import Path
import secrets
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit
from trading.broker import Meritz, PaperBroker
from trading.service import ROOT, Service, dump


def handler(service, token):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass  # Never log bearer tokens, request bodies or broker payloads.

        def reply(self, status, obj):
            raw = dump(obj).encode()
            self.send_response(status)
            self.send_header('Content-Type', 'application/json; charset=utf-8')
            self.send_header('Content-Length', str(len(raw)))
            self.send_header('Cache-Control', 'no-store')
            self.send_header('X-Content-Type-Options', 'nosniff')
            self.end_headers()
            self.wfile.write(raw)

        def do_GET(self):
            self.route()

        def do_POST(self):
            self.route()

        def route(self):
            path = urlsplit(self.path).path
            if self.command == 'GET' and path in ('/', '/trading.html', '/trading.mjs', '/timing.mjs', '/style.css'):
                file = ROOT / 'site' / ('trading.html' if path == '/' else path[1:])
                raw = file.read_bytes()
                self.send_response(200)
                self.send_header('Content-Type', 'text/html; charset=utf-8' if file.suffix == '.html' else 'text/javascript; charset=utf-8' if file.suffix == '.mjs' else 'text/css; charset=utf-8')
                self.send_header('Cache-Control', 'no-store')
                self.send_header('Content-Length', str(len(raw)))
                self.send_header('X-Content-Type-Options', 'nosniff')
                self.send_header('Content-Security-Policy', "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; connect-src 'self' https: http://127.0.0.1:* http://localhost:*; frame-ancestors 'none'; base-uri 'none'")
                self.end_headers()
                self.wfile.write(raw)
                return
            if not hmac.compare_digest(self.headers.get('Authorization', ''), 'Bearer ' + token):
                self.reply(401, {'error': '개인 서버 연결 토큰을 확인하세요'})
                return
            # CORS intentionally absent: open the console on the private server origin.
            try:
                if self.command == 'GET' and path == '/api/status':
                    self.reply(200, service.status())
                    return
                length = int(self.headers.get('Content-Length', '0'))
                if self.command != 'POST' or not 0 < length <= 16384 or self.headers.get('Content-Type', '').split(';')[0] != 'application/json':
                    self.reply(400, {'error': 'JSON 요청 형식을 확인하세요'})
                    return
                body = json.loads(self.rfile.read(length))
                if path == '/api/sync':
                    service.sync()
                elif path == '/api/plan':
                    service.create_plan()
                elif path == '/api/mode':
                    service.mode(body['mode'])
                elif path == '/api/pause':
                    if not isinstance(body.get('paused'), bool):
                        raise ValueError('중지 설정 형식 오류')
                    service.pause(body['paused'])
                elif path == '/api/approve':
                    service.execute(body['plan_id'])
                elif path == '/api/simulate':
                    service.simulate(body['plan_id'])
                elif path == '/api/resolve':
                    service.resolve(body['order_id'], body['broker_id'], body['broker_date'])
                else:
                    self.reply(404, {'error': '지원하지 않는 기능'})
                    return
                self.reply(200, service.status())
            except (ValueError, KeyError, TypeError) as e:
                self.reply(409, {'error': str(e)})
            except Exception:
                self.reply(503, {'error': '서버 처리 실패. 이전 기록은 보존됩니다'})
    return Handler


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument('--demo', action='store_true')
    group.add_argument('--meritz', action='store_true')
    parser.add_argument('--port', type=int, default=8769)
    args = parser.parse_args()
    runtime = ROOT / 'runtime'
    runtime.mkdir(exist_ok=True)
    token_file = runtime / 'trading-access-token.txt'
    token = os.environ.get('WOOGONG_ACCESS_TOKEN')
    if not token:
        if not token_file.exists():
            token_file.write_text(secrets.token_urlsafe(32), encoding='utf-8')
            try:
                token_file.chmod(0o600)
            except OSError:
                pass
        token = token_file.read_text(encoding='utf-8').strip()
    if len(token) < 32:
        raise ValueError('서버 연결 토큰은 32자 이상이어야 합니다')
    service = Service(PaperBroker() if args.demo else Meritz(), runtime / ('trading-demo.sqlite' if args.demo else 'trading-live.sqlite'))
    service.mode('observe')  # A server restart never resumes autonomous orders.
    def worker():
        while not stop.wait(300):
            try:
                service.tick()
            except Exception:
                pass  # status retains connection error; no order retry.
    stop = threading.Event()
    threading.Thread(target=worker, daemon=True).start()
    server = ThreadingHTTPServer(('127.0.0.1', args.port), handler(service, token))
    print(f'우공이산 운용 센터 http://127.0.0.1:{args.port}/ · ' + ('예시 계좌' if args.demo else '메리츠 조회 연결'))
    print('연결 토큰: WOOGONG_ACCESS_TOKEN 환경변수 또는 runtime/trading-access-token.txt 파일에서 확인')
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        stop.set()
        server.server_close()


if __name__ == '__main__':
    main()

