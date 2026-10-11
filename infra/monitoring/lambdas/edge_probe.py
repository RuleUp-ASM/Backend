"""외부 공개 점검 — 노션 「알람 카탈로그」 §4 EDGE-01/02·TLS-01. 도쿄·버지니아에 같은 코드를 하나씩 둔다(Scheduler 1분).

사용자와 같은 길(DNS → Cloudflare → ALB → 태스크)로 무인증 /health/public 을 부르고 DNS → TLS 검증 → HTTP → 본문 단계를
모두 통과해야 성공이다. timeout 5초, 성공은 200 + 고정 본문 토큰. 캐시 응답을 성공으로 오인하지 않게 no-cache 로 묻는다.

결과는 중앙 감시 리전(버지니아)의 RuleUp/Edge 로 직접 보낸다:
  ProbeFailure(Env, Probe=public, Origin) 0/1 · CertificateDaysLeft(Env, Origin) · RuleUp/Observe CollectorHeartbeat(Component=edge-<origin>)
노션 설계의 「점검 릴레이」 함수는 두지 않았다 — 결과 검증은 이 함수가 하고, 리전 간 PutMetricData 로 충분해서다.
마지막 실행이 오래되면(하트비트 누락) 그건 실패가 아니라 관측 누락으로 OBS-01 이 본다.
"""
import os
import socket
import ssl
import time
from datetime import datetime, timezone

import boto3

import cwio

TIMEOUT = 5.0


def probe(host, path, token, origin):
    """(성공 여부, 단계, 인증서 남은 일수). 단계는 실패 지점(dns·tls·http·body) — 로그로 원인을 가른다."""
    days_left = None
    try:
        addr = socket.getaddrinfo(host, 443, proto=socket.IPPROTO_TCP)[0][4]
    except OSError:
        return False, 'dns', None
    ctx = ssl.create_default_context()           # 체인·호스트 이름 검증(엄격 TLS)
    try:
        with socket.create_connection(addr[:2], timeout=TIMEOUT) as sock, \
                ctx.wrap_socket(sock, server_hostname=host) as tls:
            cert = tls.getpeercert()
            if cert and cert.get('notAfter'):
                expires = datetime.fromtimestamp(ssl.cert_time_to_seconds(cert['notAfter']), timezone.utc)
                days_left = (expires - datetime.now(timezone.utc)).total_seconds() / 86400
            request = (f'GET {path} HTTP/1.1\r\nHost: {host}\r\nUser-Agent: ruleup-edge-probe/{origin}\r\n'
                       f'Cache-Control: no-cache\r\nX-RuleUp-Synthetic: edge-{origin}\r\nConnection: close\r\n\r\n')
            tls.sendall(request.encode())
            data = _read_all(tls)
    except ssl.SSLError:
        return False, 'tls', days_left
    except OSError:
        return False, 'http', days_left
    head, _, body = data.partition(b'\r\n\r\n')
    status_line = head.split(b'\r\n', 1)[0].decode(errors='replace')
    if ' 200 ' not in f'{status_line} ':
        return False, f'http:{status_line[:40]}', days_left
    if token.encode() not in body:
        return False, 'body', days_left
    return True, 'ok', days_left


def _read_all(sock):
    deadline = time.monotonic() + TIMEOUT
    chunks = []
    while time.monotonic() < deadline:
        chunk = sock.recv(4096)
        if not chunk:
            break
        chunks.append(chunk)
        if sum(len(c) for c in chunks) > 65536:
            break
    return b''.join(chunks)


def handler(event, context):
    reg = cwio.registry()
    env = reg['env']
    origin = os.environ.get('PROBE_ORIGIN') or _origin_of(reg)
    now = cwio.event_time(event)
    stamp = now.replace(second=0, microsecond=0)
    ok, stage, days = probe(reg['public_domain'], reg['public_health_path'], reg['public_health_token'], origin)
    cwio.log('edge_probe', env=env, origin=origin, ok=ok, stage=stage, cert_days_left=days)
    cw = boto3.client('cloudwatch', region_name=reg['monitor_region'])
    data = [('ProbeFailure', {'Env': env, 'Probe': 'public', 'Origin': origin}, 0 if ok else 1, stamp, 'None')]
    if days is not None:
        data.append(('CertificateDaysLeft', {'Env': env, 'Origin': origin}, days, stamp, 'None'))
    cwio.put(cw, 'RuleUp/Edge', data)
    cwio.put(cw, 'RuleUp/Observe', [('CollectorHeartbeat', {'Env': env, 'Component': f'edge-{origin}'}, 1, stamp, 'Count')])
    return {'ok': ok, 'stage': stage}


def _origin_of(reg):
    region = os.environ.get('AWS_REGION', '')
    return next((name for name, r in reg['probe_origins'].items() if r == region), region or 'unknown')
