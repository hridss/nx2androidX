#!/usr/bin/env python3
"""Search the distilled upstream cases and the knowledge documents: the offline half of tools/upstream_pr.py.

    python3 kb_search.py "black screen"
    python3 kb_search.py --symptom crash --layer vk
    python3 kb_search.py --docs "shared font glyph"
    python3 kb_search.py --docs --section "The shared fonts"
    python3 kb_search.py --source droidplay --symptom crash
    python3 kb_search.py --game "Pokémon" --json
    python3 kb_search.py --local "黑屏"          # the player's own cases

upstream_pr.py asks GitHub whether a fix exists there. This one asks what the corpus already
knows, and it answers when the phone's network cannot reach GitHub at all (which is most of
the time in China). It reads the case index that ships with the knowledge base, so it needs
no network and no tool tree.

The index holds the cases of this fork's upstream, `shirai91/nx2android`. Every row has a
`source` column, so the search can say which upstream a case came from, and a second
upstream's cases would land in the same index and be found by the same search.

The words are matched against the case's title, its summary, its game and its
symptom. With --symptom / --layer / --area / --kind the search is by field instead, which
is the sharper way to ask "what did the Vulkan backend break, and is the fix in this fork".

--docs searches the other half of the corpus: the hand-written knowledge documents. They
are searched by section, so a query returns the section that holds the answer and where it
is - never the whole document. --section prints one section as it is, for the case where the
snippet is not enough.

--local searches the other corpus: the player's own case files (gamespec/<title ID>/spec.md).
Those are the cases this phone already worked through - the same symptom on another game is
often already solved there, and the answer is one grep away. They are the player's data, so
they survive every app update.
"""
import argparse
import json
import math
import os
import re
import sys
from collections import Counter
from pathlib import Path

# The columns a search may look at.
TEXT_FIELDS = ('title', 'summary', 'game', 'symptom', 'layer', 'area', 'ref')

# The fields of a local case that a search may look at.
LOCAL_FIELDS = ('game', 'title', 'status', 'symptom', 'cause', 'fix')

# How much one answer may cost. Every output is capped: the corpus grows, the answer does not.
DEFAULT_TOP = 12          # hits listed
SUMMARY_CHARS = 220       # characters of a case summary
DOC_TOP = 5               # document sections listed
SNIPPET_LINES = 6         # lines of a document section
SNIPPET_CHARS = 400       # characters of a document section snippet

# The knowledge documents, in the order a reader should meet them. Every one of them is
# hand-written and static; they ship with the app and the assistant reads them by query.
DOC_FILES = ('runtime-symptoms.md', 'device-findings.md', 'build-failures.md',
             'shader-split.md', 'porting-method.md')

# Query expansion: the word a query uses and the words the documents use for the same thing.
# Hand-written and small - it is the one thing a keyword index cannot do by itself, and it
# grows with the corpus rather than with the traffic.
ALIASES = {
    'audio': ('sound',), 'sound': ('audio',),
    'frozen': ('freeze', 'hang', 'stuck'), 'freeze': ('frozen', 'hang'),
    'hang': ('frozen', 'stuck'), 'stuck': ('stuck', 'stall'), 'stall': ('stuck',),
    'garbled': ('garbage', 'noise'), 'garbage': ('garbled',),
    'input': ('pad', 'touch', 'controller', 'joycon'), 'pad': ('input', 'touch'),
    'controller': ('pad', 'joycon', 'input'), 'touch': ('input', 'pad'),
    'vulkan': ('vk',), 'vk': ('vulkan',),
    'movie': ('video',), 'video': ('movie',),
    'font': ('glyph', 'typeface'), 'glyph': ('font',),
    'boot': ('start', 'launch'), 'launch': ('boot', 'start'),
    'killed': ('kill',), 'kill': ('killed',),
    'fps': ('frame', 'rate'), 'frame': ('fps',),
    'stutter': ('jank',), 'jank': ('stutter',),
    'crash': ('crashes', 'crashed', 'sigsegv', 'sigill'),
    'driver': ('turnip',), 'turnip': ('driver',),
    'dlc': ('aoc', 'addon'), 'aoc': ('dlc', 'addon'),
    'shader': ('shaders', 'spirv'), 'compile': ('build',),
    'memory': ('lmk',), 'lmk': ('memory',),
    'install': ('apk',), 'apk': ('install',),
    'black': ('screen',),
    'question': ('marks', 'glyph', 'font'), 'marks': ('question',),
    'icons': ('icon', 'button'), 'draws': ('draw', 'drawn'), 'drawn': ('draws',),
}

# Words that carry no meaning in a query.
STOP = set(('the a an and or of in is are to at on for with as by be it its this that when then from not no you your we '
            'but if can will do does did has have had was were been they them their i me my our us').split())

# A token: a word, a number, an identifier. The inner punctuation stays, so "0x25a1" and
# "main+0x7caa0" are one token each - the corpus is full of such names. A trailing dot is
# dropped ("crash." is "crash") and a hyphen splits ("low-memory" is "low" and "memory").
TOKEN = re.compile(r'[a-z0-9_][a-z0-9_+.#]*')

# The symptom filter of the index, and the section titles of the documents that answer it.
DOC_SYMPTOM_TITLES = {
    'crash': ('crash',), 'black': ('black',), 'garble': ('garbled', 'garbage'),
    'freeze': ('frozen', 'freeze'), 'missing': ('missing',), 'build': ('build',),
    'memory': ('memory',), 'shader': ('shader',), 'perf': ('perf', 'frame', 'frozen'),
    'audio': ('audio',), 'movie': ('movie',), 'feature': ('feature',),
}


def index_path(explicit=''):
    """Where the case index is: the path given, or the one beside this script."""
    if explicit:
        return Path(explicit)
    here = Path(__file__).resolve().parent
    for candidate in (here.parent / 'references/case-index.tsv',
                      here.parent.parent / 'references/case-index.tsv'):
        if candidate.is_file():
            return candidate
    return here.parent / 'references/case-index.tsv'


def docs_dir(explicit=''):
    """Where the knowledge documents are: the folder beside this script that holds them."""
    if explicit:
        return Path(explicit)
    here = Path(__file__).resolve().parent
    for candidate in (here.parent / 'references', here.parent.parent / 'references'):
        if candidate.is_dir():
            return candidate
    return here.parent / 'references'


def read_index(path):
    """The case index as a list of dicts (the header names the columns)."""
    if not path.is_file():
        return []
    lines = path.read_text(encoding='utf-8').splitlines()
    if not lines:
        return []
    header = lines[0].split('\t')
    rows = []
    for line in lines[1:]:
        if not line.strip():
            continue
        cells = line.split('\t')
        cells += [''] * (len(header) - len(cells))
        rows.append(dict(zip(header, cells)))
    return rows


def matches(row, words):
    """Does the row hold every word? The words are matched as plain text, case aside."""
    blob = '\n'.join(row.get(field, '') for field in TEXT_FIELDS).lower()
    return all(word.lower() in blob for word in words)


def fields_match(row, filters):
    """The field filters: an exact value (a field may hold several, comma separated)."""
    for field, wanted in filters.items():
        if not wanted:
            continue
        held = [part.strip() for part in row.get(field, '').split(',')]
        if wanted not in held:
            return False
    return True


def cap_rows(rows, top):
    """The rows to show and how many there were in all."""
    return rows[:top], len(rows)


def text_of(rows, words, top=DEFAULT_TOP):
    """What a reader sees: one line per hit, then what it means."""
    shown, total = cap_rows(rows, top)
    lines = []
    if words:
        lines.append('searched the distilled cases for: ' + ' '.join(words))
        lines.append('')
    for row in shown:
        source = row.get('source', '')
        reference = row.get('ref', '')
        # a case of this fork's upstream is a pull request; another upstream's would be a
        # document and an anchor in it
        label = f"#{reference}" if source == 'shirai91' else reference
        state = row.get('state', '')
        if state:
            state = ' (' + state + ')'
        lines.append(f"{source} {label}{state} [{row.get('symptom', '')}/"
                     f"{row.get('layer', '')}] in this fork: {row.get('fork', '?')}")
        lines.append(f"  {row.get('title', '')}")
        if row.get('summary'):
            lines.append(f"  {row['summary'][:SUMMARY_CHARS]}")
        if row.get('url'):
            lines.append(f"  {row['url']}")
    if total > len(shown):
        lines.append('')
        lines.append(f'({total} cases match, {len(shown)} shown; narrow it down with --symptom / --layer / --fork)')
    if not rows:
        lines.append('(nothing found)')
    return '\n'.join(lines)


# ---------------------------------------------------------------- the knowledge documents

def tokenize(text):
    """The words of a text, case aside, without the ones that carry no meaning."""
    return [t for t in (raw.rstrip('.') for raw in TOKEN.findall(text.lower()))
            if len(t) > 1 and t not in STOP]


def parse_sections(path):
    """The sections of a knowledge document: a '## ' heading opens one, '###' belongs to it.

    The documents are hand-written and stable, so the heading is the anchor: the agent reads
    the section, not the document. Returns the sections with their line range."""
    lines = path.read_text(encoding='utf-8').splitlines()
    sections = []
    title, start = None, None
    for i, line in enumerate(lines):
        if line.startswith('## '):
            if title is not None:
                sections.append(make_section(path.name, title, start, i, lines[start:i]))
            title, start = line[3:].strip(), i
    if title is not None:
        sections.append(make_section(path.name, title, start, len(lines), lines[start:len(lines)]))
    return sections


def make_section(doc, title, start, end, lines):
    """One section: its place in the document, its words, and its lines."""
    text = '\n'.join(lines)
    tokens = tokenize(text)
    return {'doc': doc, 'title': title, 'start': start, 'end': end, 'lines': lines,
            'tokens': tokens, 'token_set': set(tokens), 'title_tokens': set(tokenize(title)),
            'tf': Counter(tokens)}


def read_sections(folder):
    """Every section of every knowledge document."""
    sections = []
    for name in DOC_FILES:
        path = folder / name
        if path.is_file():
            sections.extend(parse_sections(path))
    return sections


def expand(words):
    """The query words and the words the documents use for the same thing."""
    out = set(words)
    for word in words:
        out.update(ALIASES.get(word, ()))
    return out


def score_sections(sections, words, symptom=''):
    """The sections a query lands in, best first.

    A section that holds every word of the query comes first (strict); a section that holds
    some of them comes after it and is marked loose. The score is BM25's: rarer words count
    more, a word repeated in a long section counts less, and a word in the section's title
    counts more - the titles are what the documents are organised by."""
    terms = set(words)
    expanded = expand(words)
    keep = DOC_SYMPTOM_TITLES.get(symptom, (symptom,) if symptom else ())
    n = len(sections) or 1
    df = Counter()
    for s in sections:
        for t in set(s['tokens']):
            df[t] += 1
    avgdl = sum(len(s['tokens']) for s in sections) / n or 1
    k1, b = 1.2, 0.6
    strict, loose = [], []
    for s in sections:
        if keep and not any(k in s['title'].lower() for k in keep):
            continue
        # a word counts as present when it is there, or when the word the documents use for it
        # is there: "question" and "glyph" are the same thing here
        present = [w for w in terms if w in s['token_set']
                   or any(a in s['token_set'] for a in ALIASES.get(w, ()))]
        if not present:
            continue
        score = 0.0
        for t in expanded:
            tf = s['tf'].get(t, 0)
            if not tf:
                continue
            idf = math.log(1 + n / df[t])
            dl = len(s['tokens'])
            score += idf * (tf * (k1 + 1)) / (tf + k1 * (1 - b + b * dl / avgdl))
            if t in s['title_tokens']:
                score += 1.5 * idf
        hit = {'section': s, 'score': score, 'matched': len(present), 'terms': len(terms),
               'loose': len(present) < len(terms)}
        (loose if hit['loose'] else strict).append(hit)
    strict.sort(key=lambda h: -h['score'])
    loose.sort(key=lambda h: -h['score'])
    return strict + loose


def snippet_of(section, terms):
    """The lines of a section that answer the query: the first line that holds a word of it,
    and what follows it. A table row can be a thousand characters long, so the snippet is cut
    at a cell boundary - a half row is readable, a half word is not."""
    wanted = expand(terms)
    body = section['lines'][1:]     # the heading itself is the title, not the answer
    start = 0
    for i, line in enumerate(body):
        if wanted & set(tokenize(line)):
            start = i
            break
    lines = body[start:start + SNIPPET_LINES]
    out = []
    used = 0
    for line in lines:
        room = SNIPPET_CHARS - used
        if room <= 20:
            break
        if len(line) > room:
            cut = line[:room]
            bar = cut.rfind('|')
            line = (cut[:bar] + '| …' if bar > 20 else cut + ' …')
        used += len(line) + 1
        out.append(line)
    return '\n'.join(out)


def docs_text_of(hits, words, sections, top=DOC_TOP):
    """What a reader sees: the sections a query lands in, and where they are."""
    shown = hits[:top]
    lines = []
    if words:
        lines.append('searched the knowledge documents for: ' + ' '.join(words))
    strict = sum(1 for h in shown if not h['loose'])
    lines.append(f'({len(hits)} sections match, {strict} of them hold every word, '
                 f'{len(shown)} shown)')
    lines.append('')
    for hit in shown:
        s = hit['section']
        mark = '' if not hit['loose'] else f" [loose, {hit['matched']}/{hit['terms']} words]"
        lines.append(f"{s['doc']} · {s['title']} (lines {s['start'] + 1}-{s['end']}){mark}")
        for line in snippet_of(s, words).splitlines():
            lines.append('  | ' + line)
        lines.append('')
    if len(hits) > len(shown):
        lines.append(f'({len(hits)} sections match, {len(shown)} shown; read one of them with '
                     f'--docs --section "<title>", or narrow the query with --symptom)')
    if not hits:
        lines.append('(nothing found) - the documents may call it something else:')
        for hint in alias_hints(words):
            lines.append(f'  - {hint}')
        lines.append('  - a symptom filter: ' + ', '.join(sorted(DOC_SYMPTOM_TITLES)))
        lines.append('  - the upstream cases may have it: kb_search.py "your words" (the index '
                     'holds the pull request titles and summaries)')
    return '\n'.join(lines)


def alias_hints(words, count=4):
    """When nothing matched: the words the documents use for the words the query used."""
    hints = []
    for word in words:
        for alias in ALIASES.get(word, ()):
            hints.append(f'"{word}" is written "{alias}"')
    return hints[:count]


def section_text_of(sections, title, top=SNIPPET_LINES * 8):
    """One section as it is written, so the reader can have the whole of it."""
    want = title.strip().lower()
    found = [s for s in sections if want in s['title'].lower()]
    if not found:
        return f'(no section titled "{title}")'
    lines = []
    for s in found:
        lines.append(f"{s['doc']} ## {s['title']} (lines {s['start'] + 1}-{s['end']})")
        lines.append('')
        for line in s['lines'][:top]:
            lines.append(line)
        if len(s['lines']) > top:
            lines.append(f'... ({len(s["lines"]) - top} more lines)')
        lines.append('')
    return '\n'.join(lines)


# ---------------------------------------------------------------- the player's own cases

# A problem entry in a case file: "### 2026-11-23 the crash (status: fixed)".
ENTRY = re.compile(r'^###\s+(?P<title>.+?)\s*\((?P<status>status:[^)]*)\)\s*$')

# The fields of an entry. The case file is English: the player reads the answer, not the file.
FIELDS = {
    'symptom': ('Symptom:',),
    'cause': ('Cause:',),
    'fix': ('Proposed fix:', 'Fix:'),
    'evidence': ('Evidence:',),
}


def cases_dir(explicit=''):
    """Where the player's case files are: the gamespec folder of their nx2android home."""
    if explicit:
        return Path(explicit)
    home = os.environ.get('NX2A_HOME', '')
    if home:
        return Path(home) / 'gamespec'
    # the script lives in <nx2a>/agent/.agents/skills/port-failures/scripts/
    here = Path(__file__).resolve()
    for depth in range(2, len(here.parents)):
        candidate = here.parents[depth] / 'gamespec'
        if candidate.is_dir():
            return candidate
    return here.parents[5] / 'gamespec'


def parse_case(spec):
    """One case file as a list of problems, plus the game's name and its timeline."""
    game = ''
    title_id = ''
    for line in spec.read_text(encoding='utf-8', errors='replace').splitlines():
        if line.startswith('# ') and not game:
            head = line[2:].strip()
            match = re.match(r'^(?P<name>.+?)\s*\((?P<id>[0-9A-Fa-f]{16})\)\s*case file', head)
            if match:
                game = match.group('name').strip()
                title_id = match.group('id')
            else:
                game = head
            break

    problems = []
    current = None
    timeline = []
    in_timeline = False
    holding = None   # the field the continuation lines belong to
    for raw in spec.read_text(encoding='utf-8', errors='replace').splitlines():
        line = raw.strip()
        if line.startswith('## '):
            in_timeline = line.lower().startswith('## timeline')
            current = None
            holding = None
            continue
        if in_timeline:
            if line.startswith('- '):
                timeline.append(line[2:].strip())
            continue
        entry = ENTRY.match(line)
        if entry:
            current = {'game': game, 'title_id': title_id,
                       'title': entry.group('title').strip(),
                       'status': entry.group('status').replace('status:', '').strip()}
            problems.append(current)
            holding = None
            continue
        if current is None:
            continue
        matched = None
        for field, prefixes in FIELDS.items():
            for prefix in prefixes:
                if line.startswith(prefix):
                    matched = field
                    value = line[len(prefix):].strip()
                    if not current.get(field):
                        current[field] = value
                    break
            if matched:
                break
        if matched:
            holding = matched
            continue
        # 续行：上一个字段的下一段（字段常常是一整段文字）
        if holding and line:
            current[holding] = (current.get(holding, '') + ' ' + line).strip()
    return problems, timeline


def read_local(root):
    """Every problem of every case file under the gamespec folder."""
    rows = []
    if not root.is_dir():
        return rows
    for spec in sorted(root.glob('*/spec.md')):
        try:
            problems, _ = parse_case(spec)
        except OSError:
            continue
        rows.extend(problems)
    return rows


def local_matches(row, words):
    """Does the case hold every word? The player's language lines count too."""
    blob = '\n'.join(row.get(field, '') or '' for field in LOCAL_FIELDS).lower()
    blob += '\n' + (row.get('player_cause', '') or '').lower()
    blob += '\n' + (row.get('player_fix', '') or '').lower()
    blob += '\n' + (row.get('player_headline', '') or '').lower()
    return all(word.lower() in blob for word in words)


def local_text_of(rows, words, top=DEFAULT_TOP):
    """What a reader sees: the cases this phone already worked through."""
    shown, total = cap_rows(rows, top)
    lines = []
    if words:
        lines.append('searched this phone\'s own cases for: ' + ' '.join(words))
        lines.append('')
    for row in shown:
        lines.append(f"{row.get('game', '?')} ({row.get('title_id', '?')}) "
                     f"[{row.get('status', '')}]")
        lines.append(f"  {row.get('title', '')}")
        cause = row.get('player_cause') or row.get('cause')
        fix = row.get('player_fix') or row.get('fix')
        if cause:
            lines.append(f"  cause: {cause[:SUMMARY_CHARS]}")
        if fix:
            lines.append(f"  fix: {fix[:SUMMARY_CHARS]}")
        if row.get('evidence'):
            lines.append(f"  evidence: {row['evidence'][:SUMMARY_CHARS]}")
    if total > len(shown):
        lines.append('')
        lines.append(f'({total} cases match, {len(shown)} shown)')
    return '\n'.join(lines)


def main():
    parser = argparse.ArgumentParser(description="Search the distilled upstream cases and the knowledge documents")
    parser.add_argument('words', nargs='*', help='the words to look for')
    parser.add_argument('--symptom', default='', help='only cases with this symptom')
    parser.add_argument('--layer', default='', help='only cases in this layer')
    parser.add_argument('--area', default='', help='only cases in this area')
    parser.add_argument('--kind', default='', help='only cases of this kind (core, profile, app)')
    parser.add_argument('--game', default='', help='only cases about this game')
    parser.add_argument('--source', default='', help='only cases from this upstream')
    parser.add_argument('--fork', default='', help="only cases whose fix is in this fork (yes, no, ?)")
    parser.add_argument('--index', default='', help='the case index (found beside this script)')
    parser.add_argument('--docs', action='store_true',
                        help='search the knowledge documents (by section) instead of the case index')
    parser.add_argument('--section', default='', help='print the section with this title as it is written')
    parser.add_argument('--docs-dir', default='', help='where the knowledge documents are (found beside this script)')
    parser.add_argument('--top', type=int, default=0,
                        help='how many hits to show (the default is per corpus: 12 cases, 5 document sections)')
    parser.add_argument('--local', action='store_true',
                        help="search this phone's own case files (gamespec/<title ID>/spec.md)")
    parser.add_argument('--cases', default='', help='where the case files are (found by themselves)')
    parser.add_argument('--json', action='store_true', help='print the result as JSON')
    args = parser.parse_args()

    # Every word of every argument counts: "black lakes" and "black" "lakes" are the same search.
    words = [word for argument in args.words for word in argument.split()]
    if args.game:
        words.extend(args.game.split())

    # How many hits to show. The default is per corpus: a case is a few lines, a document
    # section is a table - the answer must stay small enough to read in one go.
    top = args.top or DEFAULT_TOP
    doc_top = args.top or DOC_TOP

    if args.docs or args.section:
        folder = docs_dir(args.docs_dir)
        sections = read_sections(folder)
        if not sections:
            print(f'error: no knowledge documents under {folder}', file=sys.stderr)
            return 1
        if args.section:
            if args.json:
                found = [s for s in sections if args.section.strip().lower() in s['title'].lower()]
                print(json.dumps({'ok': True, 'corpus': 'docs', 'section': args.section,
                                  'count': len(found),
                                  'results': [{'doc': s['doc'], 'title': s['title'],
                                               'start_line': s['start'] + 1, 'end_line': s['end'],
                                               'text': '\n'.join(s['lines'])} for s in found]},
                             indent=2, ensure_ascii=False, sort_keys=True))
            else:
                print(section_text_of(sections, args.section))
            return 0
        terms = tokenize(' '.join(words))
        if not terms:
            print('error: no words to look for', file=sys.stderr)
            return 1
        hits = score_sections(sections, terms, args.symptom)
        if args.json:
            shown = hits[:doc_top]
            print(json.dumps({'ok': True, 'corpus': 'docs', 'words': words, 'filters': {'symptom': args.symptom},
                              'count': len(hits), 'shown': len(shown),
                              'results': [{'doc': h['section']['doc'], 'title': h['section']['title'],
                                           'start_line': h['section']['start'] + 1,
                                           'end_line': h['section']['end'], 'score': round(h['score'], 3),
                                           'matched': h['matched'], 'terms': h['terms'], 'loose': h['loose'],
                                           'snippet': snippet_of(h['section'], terms)} for h in shown]},
                             indent=2, ensure_ascii=False, sort_keys=True))
        else:
            print(docs_text_of(hits, words, sections, doc_top))
        return 0

    if args.local:
        root = cases_dir(args.cases)
        rows = read_local(root)
        if not rows:
            print(f'error: no case files under {root}', file=sys.stderr)
            return 1
        hits = [row for row in rows if local_matches(row, words)]
        if args.json:
            shown, total = cap_rows(hits, top)
            trimmed = [{**row, 'cause': (row.get('cause') or '')[:SUMMARY_CHARS],
                        'fix': (row.get('fix') or '')[:SUMMARY_CHARS],
                        'evidence': (row.get('evidence') or '')[:SUMMARY_CHARS]} for row in shown]
            print(json.dumps({'ok': True, 'corpus': 'local', 'root': str(root), 'words': words,
                              'count': len(hits), 'shown': len(trimmed), 'results': trimmed},
                             indent=2, ensure_ascii=False, sort_keys=True))
        else:
            print(local_text_of(hits, words, top))
        return 0

    path = index_path(args.index)
    rows = read_index(path)
    if not rows:
        print(f'error: the case index is not at {path}', file=sys.stderr)
        return 1

    filters = {'symptom': args.symptom, 'layer': args.layer, 'area': args.area,
               'kind': args.kind, 'fork': args.fork, 'source': args.source}
    hits = [row for row in rows if fields_match(row, filters) and matches(row, words)]

    if args.json:
        shown, total = cap_rows(hits, top)
        trimmed = [{**row, 'summary': (row.get('summary') or '')[:SUMMARY_CHARS]} for row in shown]
        print(json.dumps({'ok': True, 'words': words, 'filters': filters,
                          'count': len(hits), 'shown': len(trimmed), 'results': trimmed},
                         indent=2, ensure_ascii=False, sort_keys=True))
    else:
        print(text_of(hits, words, top))
    return 0


if __name__ == '__main__':
    sys.exit(main())
