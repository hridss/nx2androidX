"""The fetch tool: the certificate is checked, a dropped handshake is retried, the failure is in plain words.

No network here: the attempts are faked, so the retry, the backoff and the classification of the
failures are what the test is about.
"""
import io
import json
import os
import socket
import ssl
import subprocess
import sys
import tempfile
import unittest
import urllib.error
from pathlib import Path
from unittest import mock

ROOT = Path(__file__).resolve().parents[1]
TOOLS = ROOT / 'phone_tools'
sys.path.insert(0, str(TOOLS))

import fetch  # noqa: E402


class Classification(unittest.TestCase):
    def test_a_certificate_problem_is_not_retried(self):
        error = ssl.SSLCertVerificationError('self signed certificate')
        kind, message, again = fetch.classify(error)
        self.assertEqual(kind, fetch.CERT)
        self.assertIn('certificate', message)
        self.assertFalse(again)

    def test_a_dropped_handshake_is_retried(self):
        kind, message, again = fetch.classify(urllib.error.URLError(ssl.SSLEOFError('EOF')))
        self.assertEqual(kind, fetch.HANDSHAKE)
        self.assertTrue(again)

    def test_a_timeout_is_retried(self):
        kind, _, again = fetch.classify(urllib.error.URLError(socket.timeout('timed out')))
        self.assertEqual(kind, fetch.TIMEOUT)
        self.assertTrue(again)

    def test_a_refused_proxy_is_told_apart(self):
        kind, _, again = fetch.classify(urllib.error.URLError(ConnectionRefusedError('refused')))
        self.assertEqual(kind, fetch.PROXY)
        self.assertTrue(again)

    def test_an_http_status_is_told_apart(self):
        error = urllib.error.HTTPError('https://example.com/', 404, 'Not Found', {}, io.BytesIO(b''))
        kind, message, again = fetch.classify(error)
        self.assertEqual(kind, fetch.HTTP)
        self.assertIn('404', message)
        self.assertFalse(again)


class Retrying(unittest.TestCase):
    def test_a_dropped_handshake_is_retried_until_it_works(self):
        calls = []

        def attempt(url, method, headers, timeout, ctx):
            calls.append(url)
            if len(calls) < 3:
                raise urllib.error.URLError(ssl.SSLEOFError('EOF occurred in violation of protocol'))
            return 200, {'Content-Type': 'text/plain'}, b'hello'

        with mock.patch.object(fetch, 'attempt', attempt), mock.patch.object(fetch.time, 'sleep', lambda s: None):
            status, headers, body, attempts, seconds = fetch.fetch('https://example.com/', retries=5)
        self.assertEqual(status, 200)
        self.assertEqual(body, b'hello')
        self.assertEqual(attempts, 3)
        self.assertEqual(len(calls), 3)

    def test_a_certificate_problem_gives_up_at_once(self):
        calls = []

        def attempt(url, method, headers, timeout, ctx):
            calls.append(url)
            raise ssl.SSLCertVerificationError('unable to get local issuer certificate')

        with mock.patch.object(fetch, 'attempt', attempt):
            with self.assertRaises(fetch.FetchError) as caught:
                fetch.fetch('https://example.com/', retries=5)
        self.assertEqual(caught.exception.kind, fetch.CERT)
        self.assertEqual(len(calls), 1)   # not retried: retrying would not fix a certificate

    def test_the_failure_says_what_it_was(self):
        def attempt(url, method, headers, timeout, ctx):
            raise urllib.error.URLError(ssl.SSLEOFError('EOF'))

        with mock.patch.object(fetch, 'attempt', attempt), mock.patch.object(fetch.time, 'sleep', lambda s: None):
            with self.assertRaises(fetch.FetchError) as caught:
                fetch.fetch('https://example.com/', retries=2)
        self.assertEqual(caught.exception.kind, fetch.HANDSHAKE)
        self.assertIn('handshake', caught.exception.message)


class TheCommand(unittest.TestCase):
    def test_a_url_that_is_not_a_url_is_an_error(self):
        out = subprocess.run([sys.executable, str(TOOLS / 'fetch.py'), 'not-a-url'],
                             capture_output=True, text=True)
        self.assertEqual(out.returncode, 2)
        self.assertIn('not a URL', out.stderr)
        self.assertNotIn('Traceback', out.stderr)

    def test_the_user_agent_is_not_the_default_one(self):
        self.assertNotIn('python-urllib', fetch.USER_AGENT)
        self.assertTrue(fetch.USER_AGENT)


if __name__ == '__main__':
    unittest.main()
