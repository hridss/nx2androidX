#!/usr/bin/env python3
"""Fetch a URL from the phone: the certificate is checked, a dropped handshake is retried, the failure is in plain words.

The guest's proxy hands out CONNECT tunnels and drops about one handshake in three, so a failure is
often not a failure: this retries with a growing pause until it works.

    python3 $NX2A_REPO/tools/fetch.py https://api.github.com/repos/shirai91/nx2android
    python3 $NX2A_REPO/tools/fetch.py --output page.html https://example.com/
    python3 $NX2A_REPO/tools/fetch.py --json --method HEAD https://example.com/

The certificate is never waved through: a server whose chain does not lead to a known authority is
an error, and the error says so.
"""
import argparse
import json
import os
import socket
import ssl
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from nx2a_common import fail  # noqa: E402

# A User-Agent that is not the default one: servers (GitHub among them) turn python-urllib away.
USER_AGENT = 'Mozilla/5.0 (Linux; Android 14; nx2androidX) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36'

# How the failures are told apart. The kinds the assistant has to act on differ: a certificate
# problem is a problem to fix, a dropped handshake is a problem to wait out.
CERT = 'certificate'      # the chain does not lead to a known authority
HANDSHAKE = 'handshake'    # the tunnel broke mid-handshake (the proxy drops about one in three)
TIMEOUT = 'timeout'        # the server did not answer in time
PROXY = 'proxy'            # the proxy refused the connection
HTTP = 'http'              # the server answered, with an error status
NETWORK = 'network'        # anything else that went wrong on the way


def classify(exc):
    """(kind, message, worth retrying) for an exception the fetch ran into."""
    if isinstance(exc, urllib.error.HTTPError):
        return HTTP, f'the server answered {exc.code} {exc.reason}', False

    reason = exc.reason if isinstance(exc, urllib.error.URLError) and exc.reason is not None else exc

    if isinstance(reason, ssl.SSLCertVerificationError):
        detail = getattr(reason, 'verify_message', None) or reason
        return CERT, f'certificate check failed: {detail}', False
    if isinstance(reason, ssl.SSLEOFError):
        return HANDSHAKE, 'the TLS handshake was cut short (the proxy drops about one in three)', True
    if isinstance(reason, ssl.SSLError):
        return HANDSHAKE, f'TLS error: {reason}', True
    if isinstance(reason, (socket.timeout, TimeoutError)):
        return TIMEOUT, 'the server did not answer in time', True
    if isinstance(reason, ConnectionRefusedError):
        return PROXY, f'the proxy refused the connection: {reason}', True
    if isinstance(reason, (ConnectionResetError, BrokenPipeError)):
        return HANDSHAKE, 'the connection was reset mid-way', True
    if isinstance(reason, socket.gaierror):
        return NETWORK, f'the host name could not be resolved: {reason}', False
    if isinstance(reason, OSError):
        return NETWORK, f'the connection failed: {reason}', True
    return NETWORK, f'{type(reason).__name__}: {reason}', True


def ca_bundle():
    """Where the CA bundle is.

    The guest is not a chroot: OpenSSL's built-in CA path names the phone's own files, not the
    rootfs, so the bundle has to be named. The app sets SSL_CERT_FILE; without it we look beside
    the tool tree (the rootfs is its sibling on the phone).
    """
    named = os.environ.get('SSL_CERT_FILE')
    if named and Path(named).is_file(): return named
    guess = Path(os.environ.get('NX2A_REPO', '.')) / '..' / 'rootfs' / 'etc' / 'ssl' / 'certs' / 'ca-certificates.crt'
    return str(guess) if guess.is_file() else named


def context():
    """A TLS context that checks the certificate against the bundle the payload carries."""
    bundle = ca_bundle()
    ctx = ssl.create_default_context(cafile=bundle) if bundle and Path(bundle).is_file() else ssl.create_default_context()
    return ctx


def proxy_of():
    """The proxy the guest environment points at (the app sets it), or none."""
    for name in ('https_proxy', 'HTTPS_PROXY', 'http_proxy', 'HTTP_PROXY'):
        value = os.environ.get(name)
        if value: return value
    return None


def attempt(url, method, headers, timeout, ctx):
    """One try: (status, headers, body). Raises what the network raised."""
    request = urllib.request.Request(url, method=method, headers={'User-Agent': USER_AGENT, **headers})
    with urllib.request.urlopen(request, timeout=timeout, context=ctx) as response:
        body = b'' if method == 'HEAD' else response.read()
        return response.status, dict(response.headers.items()), body


def fetch(url, method='GET', headers=None, timeout=30, retries=5, wait=1.0):
    """Fetch a URL, retrying the failures that are worth retrying.

    Returns (status, headers, body, attempts, seconds). Raises the last exception when every
    attempt failed.
    """
    headers = headers or {}
    retries = max(1, retries)
    ctx = context()
    started = time.monotonic()
    last = ('network', 'the fetch did not run', 1)
    for attempt_no in range(1, retries + 1):
        try:
            status, response_headers, body = attempt(url, method, headers, timeout, ctx)
            return status, response_headers, body, attempt_no, time.monotonic() - started
        except (urllib.error.URLError, urllib.error.HTTPError, OSError) as exc:
            kind, message, again = classify(exc)
            last = (kind, message, attempt_no)
            if not again or attempt_no == retries: break
            time.sleep(min(wait * (2 ** (attempt_no - 1)), 15.0))
    raise FetchError(last[0], last[1], last[2])


class FetchError(Exception):
    """A fetch that did not work: the kind says what to do about it."""

    def __init__(self, kind, message, attempts):
        super().__init__(message)
        self.kind = kind
        self.message = message
        self.attempts = attempts


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('url', help='the URL to fetch')
    parser.add_argument('--output', default='', help='write the body to this file instead of the screen')
    parser.add_argument('--header', action='append', default=[], metavar='NAME: VALUE', help='a request header (repeatable)')
    parser.add_argument('--method', default='GET', choices=('GET', 'HEAD'), help='GET (default) or HEAD')
    parser.add_argument('--timeout', type=float, default=30, help='seconds to wait for one attempt (default 30)')
    parser.add_argument('--retries', type=int, default=5, help='how many attempts in all (default 5)')
    parser.add_argument('--retry-wait', type=float, default=1.0, help='the first pause between attempts, in seconds (default 1)')
    parser.add_argument('--json', action='store_true', help='print the result as JSON')
    args = parser.parse_args()

    if not args.url.startswith(('http://', 'https://')):
        fail(f'not a URL: {args.url}')

    headers = {}
    for raw in args.header:
        name, _, value = raw.partition(':')
        if not _:
            fail(f'not a header: {raw} (it should read "Name: value")')
        headers[name.strip()] = value.strip()

    ctx = context()
    try:
        status, response_headers, body, attempts, seconds = fetch(
            args.url, args.method, headers, args.timeout, args.retries, args.retry_wait)
    except FetchError as exc:
        if args.json:
            print(json.dumps({'url': args.url, 'ok': False, 'kind': exc.kind, 'error': exc.message,
                              'attempts': exc.attempts}, indent=2))
        else:
            fail(f'{exc.kind}: {exc.message} (after {exc.attempts} attempt(s))')
        return 1

    written = ''
    if args.output:
        target = Path(args.output)
        try:
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(body)
        except OSError as exc:
            fail(f'cannot write {target}: {exc}')
        written = str(target)

    result = {'url': args.url, 'ok': True, 'status': status, 'headers': response_headers,
              'bytes': len(body), 'attempts': attempts, 'seconds': round(seconds, 3),
              'ca_file': ca_bundle() or '', 'ca_certs': len(ctx.get_ca_certs()), 'output': written}

    if args.json:
        if not written:
            result['body'] = body.decode('utf-8', 'replace')
        print(json.dumps(result, indent=2, ensure_ascii=False, sort_keys=True))
    elif written:
        print(f'{status} {len(body)} bytes -> {written} ({attempts} attempt(s), {seconds:.1f}s)')
    else:
        sys.stdout.flush()
        sys.stdout.buffer.write(body)
        sys.stdout.buffer.flush()
    return 0


if __name__ == '__main__':
    sys.exit(main())
