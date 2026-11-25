#!/usr/bin/env python3
"""Search the upstream for a fix that already exists: PRs and issues on GitHub.

    python3 $NX2A_REPO/tools/upstream_pr.py --query "buffer copy outside the memory pools"
    python3 $NX2A_REPO/tools/upstream_pr.py --game "Fire Emblem: Three Houses" --query "cross chunk copy"
    python3 $NX2A_REPO/tools/upstream_pr.py --query "vk_buf_at" --repo seedds/nxnative --include-issues

The two upstreams this product follows: the product itself (shirai91/nx2android) and the engine
(seedds/nxnative). Both are searched unless --repo says otherwise.

The network here is the phone's: in China GitHub often does not answer at all. That is not a
crash — this says so in one line and stops. The caller carries on with its own diagnosis; a
missing search never blocks the work.

What comes back is a shortlist: the number, the title, the state and the first lines of the
body, so the reader can tell in a glance whether the fix is the one at hand.
"""
import argparse
import json
import sys
import urllib.parse
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from fetch import FetchError, fetch  # noqa: E402
from nx2a_common import add_json, emit, fail  # noqa: E402

# The upstreams this product follows: the product line and the engine it is built on.
DEFAULT_REPOS = ('shirai91/nx2android', 'seedds/nxnative')

SEARCH = 'https://api.github.com/search/issues?q='
# How many results per query. The unauthenticated search API allows ten requests a minute —
# a shortlist is enough, and it keeps the phone's radio quiet.
DEFAULT_LIMIT = 5
# How long one attempt waits for GitHub: the phone's network is slow, and the answer is not
# worth waiting long for.
TIMEOUT = 20


def search_url(repo, query, include_issues):
    """The search URL for one repo and one query."""
    terms = [f'repo:{repo}', 'is:pr' if not include_issues else 'is:issue']
    terms.append(query)
    return SEARCH + urllib.parse.quote(' '.join(terms), safe=':+') + f'&per_page={DEFAULT_LIMIT}'


def ask(url):
    """The search API's answer as a dict (raises FetchError when the network said no)."""
    status, _headers, body, _attempts, _seconds = fetch(
        url, timeout=TIMEOUT, retries=2, wait=1.0)
    if status != 200:
        raise FetchError('http', f'GitHub answered {status}', 1)
    try:
        return json.loads(body.decode('utf-8', 'replace'))
    except (ValueError, UnicodeDecodeError) as exc:
        raise FetchError('network', f'GitHub sent something that is not JSON: {exc}', 1)


def results_of(answer):
    """The items of one search answer, in the shape the caller wants."""
    out = []
    for item in (answer.get('items') or []):
        pull = item.get('pull_request') or {}
        out.append({
            'number': item.get('number'),
            'title': (item.get('title') or '').strip(),
            'state': item.get('state') or '',
            'merged': bool(pull.get('merged_at')),
            'url': item.get('html_url') or '',
            'created': (item.get('created_at') or '')[:10],
            'body': (item.get('body') or '').strip()[:400],
        })
    return out


def collect(repos, query, include_issues):
    """(results, unreachable): what GitHub said, and where it did not answer."""
    results = []
    unreachable = []
    for repo in repos:
        url = search_url(repo, query, include_issues)
        try:
            answer = ask(url)
        except FetchError as exc:
            unreachable.append({'repo': repo, 'kind': exc.kind, 'error': exc.message})
            continue
        for item in results_of(answer):
            item['repo'] = repo
            item['query'] = query
            results.append(item)
    return results, unreachable


def text_of(results, unreachable, query):
    """What a human (or the assistant) reads: one line per hit, then what went wrong."""
    lines = [f'searched GitHub for: {query}', '']
    if results:
        for item in results:
            state = 'merged' if item['merged'] else item['state']
            lines.append(f"{item['repo']} #{item['number']} ({state}) {item['title']}")
            if item['url']:
                lines.append(f"  {item['url']}")
            if item['body']:
                lines.append(f"  {item['body'][:200]}")
    else:
        lines.append('(nothing found)')
    if unreachable:
        lines.append('')
        for miss in unreachable:
            lines.append(f"{miss['repo']}: not reached ({miss['kind']}: {miss['error']})")
    return '\n'.join(lines)


def main():
    parser = argparse.ArgumentParser(description="Search the upstream's PRs and issues on GitHub")
    parser.add_argument('--query', required=True, help='the words to look for (an error signature, a function name)')
    parser.add_argument('--game', default='', help="the game's name, searched as well")
    parser.add_argument('--repo', action='append', default=[], help='a repo to search (repeatable; default: both upstreams)')
    parser.add_argument('--include-issues', action='store_true', help='search issues too, not only pull requests')
    add_json(parser)
    args = parser.parse_args()

    repos = tuple(args.repo) if args.repo else DEFAULT_REPOS
    queries = [args.query]
    if args.game.strip():
        queries.append(f'"{args.game.strip()}"')

    results = []
    unreachable = []
    for query in queries:
        found, missed = collect(repos, query, args.include_issues)
        results.extend(found)
        unreachable.extend(missed)

    payload = {
        'ok': bool(results) or not unreachable,
        'query': args.query,
        'queries': queries,
        'repos': list(repos),
        'results': results,
        'unreachable': unreachable,
    }
    if not results and unreachable:
        # 没有结果而且一个仓库都没连上：网络的问题，一句话说清就停
        kinds = ', '.join(sorted({miss['kind'] for miss in unreachable}))
        if args.json:
            print(json.dumps(payload, indent=2, ensure_ascii=False, sort_keys=True))
        else:
            fail(f'GitHub is not reachable ({kinds}): the search did not run')
        return 1

    emit(args, payload, text_of(results, unreachable, args.query))
    return 0


if __name__ == '__main__':
    sys.exit(main())
