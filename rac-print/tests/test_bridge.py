"""Testes sem Windows e sem impressora física; testa protocolo e autenticação."""
import http.client
import json
import threading
import unittest
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'bridge'))
from rac_print_bridge import PrintService, run_server


class BridgeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.printed = []
        def backend(data, printer, copies):
            cls.printed.append((data, printer, copies))
        cls.service = PrintService({'token': 'a' * 48, 'printer': 'FAKE-BEMATECH'}, backend=backend)
        cls.http = run_server(cls.service, host='127.0.0.1', port=0)
        cls.port = cls.http.server_address[1]
        cls.thread = threading.Thread(target=cls.http.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.http.shutdown()
        cls.http.server_close()

    def req(self, method, path, data=None, token=None, mime=None, copies=1):
        conn = http.client.HTTPConnection('127.0.0.1', self.port, timeout=5)
        headers = {}
        if token is not None: headers['X-RAC-Token'] = token
        if mime: headers['Content-Type'] = mime
        if data is not None: headers['X-Copies'] = str(copies)
        conn.request(method, path, body=data, headers=headers)
        res = conn.getresponse()
        status, body = res.status, json.loads(res.read().decode())
        conn.close()
        return status, body

    def test_auth_required(self):
        self.assertEqual(self.req('GET', '/status')[0], 401)
        self.assertEqual(self.req('POST', '/print', b'%PDF-fake', mime='application/pdf')[0], 401)

    def test_status(self):
        code, value = self.req('GET', '/status', token='a' * 48)
        self.assertEqual(code, 200)
        self.assertEqual(value['printer'], 'FAKE-BEMATECH')

    def test_invalid_pdf(self):
        code, data = self.req('POST', '/print', b'invalid', token='a' * 48, mime='application/pdf')
        self.assertEqual(code, 400)

    def test_print_spool(self):
        fake_pdf = b'%PDF-1.4\n%%EOF'
        code, data = self.req('POST', '/print', fake_pdf, token='a' * 48, mime='application/pdf', copies=2)
        self.assertEqual(code, 202)
        self.service.queue.join()
        code, job = self.req('GET', '/jobs/' + data['id'], token='a' * 48)
        self.assertEqual(code, 200)
        self.assertEqual(job['status'], 'spooled')
        self.assertEqual(self.printed[-1], (fake_pdf, 'FAKE-BEMATECH', 2))

    def test_reject_large_copies(self):
        self.assertEqual(self.req('POST', '/print', b'%PDF-x', token='a'*48,
                                  mime='application/pdf', copies=500)[0], 400)


if __name__ == '__main__':
    unittest.main()
