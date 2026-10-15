import os, sys, unittest
from unittest import mock
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..'))
sys.path.insert(0, os.path.dirname(__file__))
import fake_aws
fake_aws.install()
import edge_probe


class FakeTls:
    def __init__(self, response):
        self.response, self.sent = response, b''

    def __enter__(self): return self
    def __exit__(self, *a): return False
    def getpeercert(self): return {'notAfter': 'Dec 31 23:59:59 2099 GMT'}
    def sendall(self, b): self.sent += b

    def recv(self, n):
        r, self.response = self.response, b''
        return r


class ProbeTest(unittest.TestCase):

    def run_probe(self, response):
        tls = FakeTls(response)
        ctx = mock.Mock()
        ctx.wrap_socket.return_value = tls
        with mock.patch('socket.getaddrinfo', return_value=[(None, None, None, None, ('1.2.3.4', 443))]), \
                mock.patch('socket.create_connection', return_value=mock.MagicMock()), \
                mock.patch('ssl.create_default_context', return_value=ctx):
            result = edge_probe.probe('prod.ruleup.co.kr', '/health/public', 'ruleup-ok', 'tokyo')
        return result, tls.sent

    def test_success_needs_200_and_token(self):
        (ok, stage, days), sent = self.run_probe(b'HTTP/1.1 200 OK\r\nCache-Control: no-store\r\n\r\nruleup-ok')
        self.assertTrue(ok)
        self.assertGreater(days, 1000)
        self.assertIn(b'X-RuleUp-Synthetic: edge-tokyo', sent)

    def test_cached_or_wrong_body_fails(self):
        (ok, stage, _), _ = self.run_probe(b'HTTP/1.1 200 OK\r\n\r\n<html>login</html>')
        self.assertEqual((ok, stage), (False, 'body'))

    def test_waf_challenge_is_failure(self):
        (ok, stage, _), _ = self.run_probe(b'HTTP/1.1 403 Forbidden\r\n\r\n')
        self.assertFalse(ok)
        self.assertTrue(stage.startswith('http:'))

    def test_dns_failure(self):
        with mock.patch('socket.getaddrinfo', side_effect=OSError('nx')):
            self.assertEqual(edge_probe.probe('x', '/', 't', 'tokyo'), (False, 'dns', None))



class HandlerTest(unittest.TestCase):
    """한 실행에 두 번 점검 — 둘 다 실패해야 EDGE-01 leaf(ProbeFailure=1), 실패 횟수는 EDGE-02 로."""

    def invoke(self, results):
        put = fake_aws.install()
        os.environ['RULEUP_REGISTRY'] = os.path.join(os.path.dirname(__file__), '..', '..', 'env', 'stg.json')
        os.environ['PROBE_ORIGIN'] = 'oregon'
        with mock.patch.object(edge_probe, 'probe', side_effect=results), mock.patch('time.sleep') as sleep:
            edge_probe.handler({'time': '2026-10-15T10:15:00Z'}, None)
        self.assertEqual(sleep.call_count, 1, '두 점검 사이에 한 번 쉰다')
        return {d['MetricName']: d['Value'] for _, ns, data in put if ns == 'RuleUp/Edge' for d in data}

    def test_two_consecutive_failures(self):
        m = self.invoke([(False, 'http', None), (False, 'http', None)])
        self.assertEqual((m['ProbeFailure'], m['ProbeAttemptFailures']), (1, 2))

    def test_one_failure_is_not_edge01(self):
        m = self.invoke([(False, 'http', None), (True, 'ok', 30)])
        self.assertEqual((m['ProbeFailure'], m['ProbeAttemptFailures']), (0, 1))
        self.assertEqual(m['CertificateDaysLeft'], 30)


if __name__ == '__main__':
    unittest.main()
