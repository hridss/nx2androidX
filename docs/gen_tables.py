#!/usr/bin/env python3
"""The parts of the READMEs and docs/PORT_CHECKLIST.md that come from the game profiles, made from them (pull requests that add a game edit only its profile):
  README.md / README.zh.md, the games gallery: the table of games, one cell for each profile whose android.tsv has readme_* rows (readme_image, readme_tag,
                    readme_note; readme_name when the table names the game otherwise than game.tsv), in name order, two to a row. A cell shows the name and
                    its facts; the picture is not shown (the screenshot still decides which games the gallery lists).
  README.md / README.zh.md, the counts: the numbers in the table at the top of the page (the ports and their states, the games in the gallery). Each one is a slot
                    (<!-- count:ported --> … <!-- /count -->) in the page's own table; the words around it are the page's own, the numbers are made here.
  docs/PORT_CHECKLIST.md: the list of the games the porter knows: a row for every profile in the tree, checked when the profile has an android.tsv, and the count with it.
                    The list was written by hand and drifted from the tree (the profiles grew: upstream's synced in bulk, ports added), so it is made from the profiles now.
    python docs/gen_tables.py           write the files
    python docs/gen_tables.py --check   exit 1 when a file is not what the profiles make (tests/test_docs_tables.py runs it)
"""
import json, re, subprocess, sys, textwrap
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO / 'nxnative/dm/android/tools'))
import android_profile   # noqa: E402

BEGIN = '<!-- games table: made by docs/gen_tables.py from the readme_* rows of each android.tsv; edit those, not this table -->'
END = '<!-- end of the games table -->'

# The counts in the READMEs' own tables: the number is made from the profiles, the words around it are the page's own.
COUNT_SLOT = re.compile(r'(<!-- count:([a-z_]+) -->)(.*?)(<!-- /count -->)', re.S)

GH_REPO = 'zFitness/nx2androidX'
CREDITS_CACHE = REPO / '.cache' / 'gen_tables_credits.json'
HANDLE = re.compile(r'^[A-Za-z0-9][\w-]*$')

# The games with an official Android release of their own: there is nothing to port here. The tree holds a few of them (upstream brought the profile up on the Mac),
# and the list says so, so a player does not read "no port" as "the game is missing on Android".
ON_ANDROID = (
    'Unpacking', 'Balatro', 'Raging Loop', 'Baba Is You', 'BALL x PIT', 'Blasphemous',
    'FINAL FANTASY CRYSTAL CHRONICLES Remastered Edition', 'Ghost Trick: Phantom Detective',
    'Sea of Stars', 'Stardew Valley', 'Absolum',
)
ON_ANDROID_LOWER = {name.strip().lower() for name in ON_ANDROID}


def cells():
    """The gallery's cells: one per profile that has a screenshot in the repository, in name order. The cell shows
    the name and its facts only — the page carries no game pictures; the screenshot still decides who is listed."""
    out = []
    for g, p in android_profile.all_profiles():
        if not p or not p.get('readme_tag'): continue
        img = p.get('readme_image')
        if not img or not (REPO / img).is_file(): continue   # membership: the gallery lists the games a screenshot exists for, though the page shows none
        name = p.get('readme_name') or g.get('name', g.id)
        out.append((name, f"{name}<br><sub>`{p.get('readme_tag')}` · {p.get('readme_note')}</sub>"))
    out.sort(key=lambda c: c[0].casefold())
    return [c for _, c in out]


def readme_table():
    cs = cells(); rows = ['| | |', '|:---:|:---:|']
    for i in range(0, len(cs), 2):
        pair = cs[i:i + 2]
        rows.append('| ' + ' | '.join(pair) + (' |' if len(pair) == 2 else ' | |'))
    return '\n'.join(rows)


def counts():
    """The numbers the READMEs show: the ports and their states, and the games with a screenshot."""
    rs = rows()
    ported = [r for r in rs if r['status']]
    states = {}
    for r in ported:
        states[r['status']] = states.get(r['status'], 0) + 1
    out = {'ported': f"{len(ported)} / {len(rs)}", 'gallery': str(len(cells()))}
    out.update({s: str(n) for s, n in sorted(states.items())})
    return out


def fill_counts(text, values):
    """The numbers in the slots: the ones the profiles make, the rest left as they are."""
    return COUNT_SLOT.sub(lambda m: m.group(1) + values.get(m.group(2), m.group(3)) + m.group(4), text)


def readme_gaps(text, values):
    """What a README still lacks: the gallery's markers and a slot for every number."""
    gaps = []
    if BEGIN not in text or END not in text: gaps.append('the games table markers')
    names = set(re.findall(r'<!-- count:([a-z_]+) -->', text))
    for name in values:
        if name not in names: gaps.append(f'the count:{name} slot')
    return gaps


def readme(text, resolve=False):
    """The README's own generated parts: the games gallery and the counts."""
    out = text
    if BEGIN in out and END in out:
        a = out.index(BEGIN) + len(BEGIN); b = out.index(END)
        out = out[:a] + '\n' + readme_table() + '\n' + out[b:]
    return fill_counts(out, counts())


# ---- docs/PORT_CHECKLIST.md: the list of the games, made from the profiles ----


def version_of(g):
    """The version the profile was made for: the newest build row's own version.

    A profile may name several builds (the game was brought up on more than one update): the rows are in version order, and the last one is the newest.
    """
    rows = g.values('build')
    if not rows: return ''
    row = rows[-1]
    return row[1] if len(row) > 1 and '=' not in row[1] else ''


def rows():
    """One row per profile of the tree: the game's name, its version, and what its Android profile says."""
    out = []
    for g, p in android_profile.all_profiles():
        name = (p.get('readme_name') if p else '') or g.get('name', g.id)
        out.append({
            'id': g.id,
            'name': name,
            'version': version_of(g),
            'fps60': g.get('fps_target', '') == '60',
            'status': p.status if p else '',
            'note': p.get('readme_note') if p else '',
            'tested': p.tested if p else '',
        })
    out.sort(key=lambda r: (r['name'].casefold(), r['id']))
    return out


def credits(text):
    """The credits the list carries, by title ID: the file is where they are kept."""
    out = {}
    for line in text.splitlines():
        m = re.match(r'- \[[ x]\] .*?`([0-9A-F]{16})`', line)
        c = re.search(r'ported by (@[\w-]+)', line)
        if m and c: out[m.group(1)] = c.group(1)
    return out


def credit_of(title_id):
    """The GitHub account of the commit that added the profile's android.tsv: who brought the port up.

    GitHub knows the account of the commit when the author's email is linked to one. A commit made from a machine that never linked its email has no account on the
    commit, but its author name is the handle the author writes commits under (DPR, rospec14, C1oudust) — that one is taken as it is, when it looks like a handle.
    """
    sha = subprocess.run(['git', '-C', str(REPO), 'log', '--diff-filter=A', '--format=%H', '--',
                          f'nxnative/dm/games/{title_id}/android.tsv'], capture_output=True, text=True)
    if sha.returncode != 0: return ''
    commits = [l.strip() for l in sha.stdout.splitlines() if l.strip()]
    if not commits: return ''
    sha = commits[-1]
    cache = {}
    if CREDITS_CACHE.is_file():
        try: cache = json.loads(CREDITS_CACHE.read_text())
        except ValueError: cache = {}
    if sha in cache: return cache[sha]
    done = subprocess.run(['gh', 'api', f'repos/{GH_REPO}/commits/{sha}', '--jq',
                           '.author.login + "\\t" + .commit.author.name'], capture_output=True, text=True)
    login, _, name = (done.stdout.strip() if done.returncode == 0 else '').partition('\t')
    out = login or (name.strip() if HANDLE.fullmatch(name.strip()) else '')
    cache[sha] = out
    try:
        CREDITS_CACHE.parent.mkdir(parents=True, exist_ok=True)
        CREDITS_CACHE.write_text(json.dumps(cache, indent=2, sort_keys=True) + '\n')
    except OSError: pass
    return out


def paragraph(text):
    return textwrap.fill(text, width=96)


def render(rs, known):
    """The list itself: the preamble, the count, and a row per profile."""
    ported = [r for r in rs if r['status']]
    official = sorted((r for r in rs if r['name'].strip().lower() in ON_ANDROID_LOWER), key=lambda r: r['name'].casefold())
    out = [
        '# Port checklist',
        '',
        paragraph(
            "The games the porter knows: every profile folder under `nxnative/dm/games/` is a row. A checked row has the game's own `android.tsv` — the game is ported to "
            "Android (the keys are in [ARCHITECTURE.md](ARCHITECTURE.md#game-profiles-on-android)). An unchecked row has the profile only: `porter build <folder> "
            "--experimental` copies it into the build folder, writes an experimental `android.tsv` and tries the build; the build may fail and the log says why "
            "([UPSTREAM_PROFILES.md](UPSTREAM_PROFILES.md))."),
        '',
    ]
    nowhere = [r['name'] for r in official if not r['status']]
    anyway = [r['name'] for r in official if r['status']]
    if nowhere:
        out += [paragraph(
            'Some games in the tree have an official Android release of their own, so there is nothing to port here: ' + ', '.join(nowhere) + '. Their rows are the '
            'profiles upstream brought them up with — the game is not missing on Android.'), '']
    if anyway:
        out += [paragraph('These have an official Android release too, and are ported here anyway: ' + ', '.join(anyway) + '.'), '']
    out += [
        f'**{len(ported)} / {len(rs)} ported**',
        '',
        paragraph(
            "This file is generated: `python3 docs/gen_tables.py` reads the profiles and writes it. The facts in a row are the profile's own — the name and the version "
            "from `game.tsv`, the status, the note and the device the port was tested on from `android.tsv`."),
        '',
        paragraph(
            '**Credit.** A ported game names the person who brought it up (`ported by @handle`), taken from the commit that added its profile. The ports from before '
            'this was written down are credited to the author of the commit that introduced them.'),
        '',
    ]
    for r in rs:
        parts = [r['name'], f"`{r['id']}`"]
        if r['status']:
            parts.append(r['status'])
            parts.append(r['note'] or r['tested'])
            if known.get(r['id']): parts.append(f"ported by {known[r['id']]}")
        else:
            if r['version']: parts.append(r['version'])
            if r['fps60']: parts.append('60 fps')
        out.append('- [' + ('x' if r['status'] else ' ') + '] ' + ' · '.join(p for p in parts if p))
    return '\n'.join(out) + '\n'


def checklist(text, resolve=False):
    """The whole list: the rows are the profiles, and the credits the list carries are kept (a ported game without one gets it from its commit)."""
    known = credits(text); rs = rows()
    if resolve:
        for r in rs:
            if r['status'] and r['id'] not in known:
                c = credit_of(r['id'])
                if c: known[r['id']] = '@' + c
    return render(rs, known)


def main(argv):
    check = '--check' in argv; bad = []
    values = counts()
    for path, make in ((REPO / 'README.md', readme), (REPO / 'README.zh.md', readme), (REPO / 'docs/PORT_CHECKLIST.md', checklist)):
        old = path.read_text(encoding='utf-8'); new = make(old, not check)
        if path.name.startswith('README'):
            gaps = readme_gaps(old, values)
            if gaps: bad.append(f"{path.relative_to(REPO)} (missing {', '.join(gaps)})")
        if new is None or new == old: continue
        if check: bad.append(str(path.relative_to(REPO)))
        else: path.write_text(new, encoding='utf-8', newline='\n'); print(f'wrote {path.relative_to(REPO)}')
    if bad: print('not what the profiles make: ' + '; '.join(bad) + ' (run python docs/gen_tables.py)'); return 1
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
