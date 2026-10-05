"""Traceability and link checker for the AsciiDoc concept (requirements, specifications, roadmap,
implementation watch).

Usage: python3 trace.py [DOC_ROOT]   (default: <git toplevel>/doc)

Reports broken links/anchors (Asciidoctor auto-id rules), requirement <-> specification
bidirectionality, the Specification.adoc index, roadmap coverage, and watch-item counts, gaps and
backlinks. Run it once before editing (baseline) and after every edit batch; compare the two outputs:
only findings that are new against the baseline are caused by the edits.

A large specification may be split into parts: `specification/<spec>.adoc` stays the index (header,
Overview, Traceability, Status, `== Parts`) and `specification/<spec>/NN-<topic>.adoc` hold the
sections. A part belongs to its spec `specification/<spec>.adoc` for every check: a link to a part
counts as a link to the spec, and the Traceability section is read from the index only.
"""
import re, os, sys, collections, subprocess
if len(sys.argv) > 1:
    ROOT = os.path.abspath(sys.argv[1])
else:
    top = subprocess.run(['git', 'rev-parse', '--show-toplevel'], capture_output=True, text=True).stdout.strip()
    ROOT = os.path.join(top or os.getcwd(), 'doc')
files = {}
for dp, _, fns in os.walk(ROOT):
    for fn in fns:
        if fn.endswith('.adoc'):
            p = os.path.relpath(os.path.join(dp, fn), ROOT)
            files[p] = open(os.path.join(dp, fn), encoding='utf-8').read()

# a link to specification/<spec>.adoc or to one of its parts specification/<spec>/<part>.adoc
SPEC_REF = re.compile(r'specification/([a-z-]+)(?:/[0-9a-z-]+)?\.adoc')
def spec_refs(text):
    return {m + '.adoc' for m in SPEC_REF.findall(text)}
def is_spec_index(p):
    return p.startswith('specification/') and p.count('/') == 1
def spec_of(p):
    """specification/<spec>/<part>.adoc -> specification/<spec>.adoc; other paths unchanged"""
    m = re.match(r'^specification/([a-z-]+)/[^/]+\.adoc$', p)
    return f'specification/{m.group(1)}.adoc' if m else p

def autoid(title):
    # Asciidoctor order: special characters become entities first, then inline quotes become tags;
    # the id generator then strips tags and entities (so `<x>` keeps the text x).
    t = title.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
    t = re.sub(r'`([^`]+)`', r'<code>\1</code>', t)
    t = re.sub(r'\*([^*]+)\*', r'<strong>\1</strong>', t)
    t = t.lower()
    t = re.sub(r'<[^>]+>|&(?:[a-z][a-z]+\d{0,2}|#\d{2,5}|#x[\da-f]{2,4});', '', t)
    t = re.sub(r'[^ \w\-.]+', '', t)
    t = re.sub(r'[ .\-]+', '_', t)
    t = re.sub(r'_+', '_', t).strip('_')
    return '_' + t

anchors = collections.defaultdict(set)
section_of = {}  # (file, anchor) -> line
for p, txt in files.items():
    lines = txt.split('\n')
    pending = None
    for i, l in enumerate(lines):
        for m in re.finditer(r'\[#([^\],.]+)', l):
            anchors[p].add(m.group(1)); pending = m.group(1)
        for m in re.finditer(r'\[\[([^\],]+)', l):
            anchors[p].add(m.group(1))
        m = re.match(r'^(=+)\s+(.*)$', l)
        if m:
            anchors[p].add(autoid(m.group(2)))

# includes: Requirements.adoc includes requirements/*; anchors in included files resolvable via Requirements.adoc
for p, txt in list(files.items()):
    for m in re.finditer(r'^include::([^\[]+)\[', txt, re.M):
        inc = os.path.normpath(os.path.join(os.path.dirname(p), m.group(1)))
        anchors[p] |= anchors.get(inc, set())

broken = []
linkre = re.compile(r'(?:link:|xref:)([^\[\s>]*?)(?:#([^\[\s,>]+))?\[|<<([\w#/.:{][^,>\s]*)(?:,|>>)')
for p, txt in files.items():
    if p.startswith('discussions/'): continue
    for ln, l in enumerate(txt.split('\n'), 1):
        for m in linkre.finditer(l):
            if m.group(3):
                g = m.group(3)
                if '#' in g: target, anc = g.split('#', 1)
                elif g.endswith('.adoc'): target, anc = g, None
                else: target, anc = '', g
            else:
                target, anc = m.group(1), m.group(2)
            if target.startswith('http') or target.startswith('file:'):
                continue
            if target == '' :
                tp = p
            else:
                tp = os.path.normpath(os.path.join('' if p.startswith('requirements/') else os.path.dirname(p), target))
            if not tp.endswith('.adoc'):
                if not os.path.exists(os.path.join(ROOT, tp)):
                    broken.append((p, ln, 'missing-file', target))
                continue
            if tp not in files:
                broken.append((p, ln, 'missing-file', target)); continue
            if anc and anc not in anchors[tp]:
                broken.append((p, ln, 'missing-anchor', f'{target}#{anc}'))
print('BROKEN LINKS', len(broken))
for b in broken: print('  ', b)

# split specifications: every part is listed in its index, every listed part exists
for p in sorted(files):
    sp = spec_of(p)
    if sp != p:
        if sp not in files: print('PART WITHOUT INDEX', p)
        elif f'link:{os.path.relpath(p, os.path.dirname(sp))}[' not in files[sp]: print('PART NOT LISTED IN INDEX', p)
for p in sorted(files):
    if is_spec_index(p):
        for m in re.finditer(r'^\* link:([a-z-]+/[^\[]+\.adoc)\[', files[p], re.M):
            if os.path.normpath(os.path.join('specification', m.group(1))) not in files: print('INDEX LISTS MISSING PART', p, m.group(1))

# requirement ids and titles
reqs = {}
for p, txt in files.items():
    if p.startswith('requirements/'):
        for m in re.finditer(r'\[#(PM-[A-Z]+-\d+)\]\n=== (PM-[A-Z]+-\d+): (.*)', txt):
            if m.group(1) != m.group(2): print('ID MISMATCH', p, m.group(1), m.group(2))
            reqs[m.group(1)] = (p, m.group(3).strip())
print('REQS', len(reqs))

# requirement body -> spec links
def req_body(rid):
    p, _ = reqs[rid]; txt = files[p]
    s = txt.index(f'[#{rid}]'); e = txt.find('\n[#PM-', s+5)
    return txt[s: e if e > 0 else len(txt)]
req2spec = {r: spec_refs(req_body(r)) for r in reqs}
for r, s in req2spec.items():
    if not s: print('REQ WITHOUT SPEC LINK', r)

# spec traceability section
spec2req = {}
for p, txt in files.items():
    if p.startswith('specification/'):
        if is_spec_index(p):  # parts carry no Traceability section of their own
            m = re.search(r'== Traceability\n(.*?)\n== ', txt, re.S)
            spec2req[os.path.basename(p)] = set(re.findall(r'#(PM-[A-Z]+-\d+)\[', m.group(1))) if m else set()
        # title mismatches in traceability
        for mm in re.finditer(r'#(PM-[A-Z]+-\d+)\[PM-[A-Z]+-\d+: ([^\]]+)\]', txt):
            rid, t = mm.group(1), mm.group(2).strip()
            if rid in reqs and reqs[rid][1] != t:
                print('TITLE MISMATCH spec', p, rid, repr(t), '!=', repr(reqs[rid][1]))
            if rid not in reqs: print('UNKNOWN REQ', p, rid)
for p in ['roadmap.adoc']:
    for mm in re.finditer(r'#(PM-[A-Z]+-\d+)\[PM-[A-Z]+-\d+: ([^\]]+)\]', files[p]):
        rid, t = mm.group(1), mm.group(2).strip()
        if rid in reqs and reqs[rid][1] != t:
            print('TITLE MISMATCH roadmap', rid, repr(t), '!=', repr(reqs[rid][1]))

# index table in Specification.adoc (absent once the last specification is implemented and removed)
idx = {}
for m in re.finditer(r'\|link:specification/([a-z-]+\.adoc)\[[^\]]+\]\n\|`[A-Z ]+`\n\|(.*?)\n', files.get('Specification.adoc', '')):
    idx[m.group(1)] = set(re.findall(r'#(PM-[A-Z]+-\d+)\[', m.group(2)))
print('\n== Spec traceability vs Specification.adoc index')
for s in sorted(spec2req):
    a, b = spec2req[s], idx.get(s, set())
    if a != b: print(s, 'only-in-spec:', sorted(a-b), 'only-in-index:', sorted(b-a))

print('\n== Req->spec vs spec->req (bidirectional)')
for s in sorted(spec2req):
    fwd = {r for r, ss in req2spec.items() if s in ss}
    back = spec2req[s]
    if fwd != back:
        print(s)
        print('   req links spec but spec traceability lacks:', sorted(fwd-back))
        print('   spec traceability lists but req does not link spec:', sorted(back-fwd))

# prefix taxonomy table in Requirements.adoc
print('\n== Taxonomy table vs actual req->spec union')
# reference specifications (status REFERENCE) record evidence and are not primary specifications of a prefix
reference_specs = {os.path.basename(p) for p, t in files.items()
                   if is_spec_index(p) and re.search(r'^== Status: REFERENCE', t, re.M)}
for m in re.finditer(r'\|`(PM-[A-Z]+)`\n\|[^\n]*\n\|[^\n]*\n\|([^\n]*)', files['Requirements.adoc']):
    pre = m.group(1); tab = spec_refs(m.group(2))
    act = set().union(*[req2spec[r] for r in reqs if r.startswith(pre + '-')]) - reference_specs
    act_back = {s for s, rr in spec2req.items() if any(r.startswith(pre+'-') for r in rr)} - reference_specs
    if tab != act or tab != act_back:
        print(pre, 'table-only:', sorted(tab-act), 'req-links-only:', sorted(act-tab), '| vs spec-backlinks table-only:', sorted(tab-act_back), 'back-only:', sorted(act_back-tab))

# roadmap coverage
rm = set(re.findall(r'Requirements\.adoc#(PM-[A-Z]+-\d+)', files['roadmap.adoc']))
print('\nREQS NOT IN ROADMAP', sorted(set(reqs)-rm))

# watch items
watch = {}
for p, txt in files.items():
    if p.startswith('implementation-watch/'):
        ids = re.findall(r'^\[#(PM-WATCH-[A-Z]+-\d+)\]', txt, re.M)
        watch[os.path.basename(p)] = ids
        # contiguous numbering / duplicates
        nums = [int(i.rsplit('-',1)[1]) for i in ids]
        if len(set(nums)) != len(nums): print('DUP WATCH', p)
        if sorted(nums) != list(range(1, len(nums)+1)): print('GAPS WATCH', p, sorted(set(range(1, max(nums)+1)) - set(nums)))
print('\n== Watch counts')
counts_idx = dict(re.findall(r'\|link:implementation-watch/([a-z-]+\.adoc)\[[^\]]+\]\n\|`[A-Z]+`\n\|(\d+)', files.get('ImplementationWatch.adoc', '')))
counts_spec = dict(re.findall(r'link:implementation-watch/([a-z-]+\.adoc)\[[^\]]+\] \((\d+) items?\)', files.get('Specification.adoc', '')))
for w, ids in sorted(watch.items()):
    print(w, len(ids), 'IW-index', counts_idx.get(w), 'Spec-index', counts_spec.get(w))

# watch anchor back-links: for each item, each Anchor link target must have an "Implementation watch" line mentioning the item id
print('\n== Watch anchor backlink check')
allwatch = {i for ids in watch.values() for i in ids}
for p, txt in files.items():
    if not p.startswith('implementation-watch/'): continue
    for m in re.finditer(r'^\[#(PM-WATCH-[A-Z]+-\d+)\]\n(.*?)(?=^\[#PM-WATCH|\Z)', txt, re.S | re.M):
        wid, body = m.group(1), m.group(2)
        am = re.search(r'^Anchor::(.*?)(?=^[A-Z][a-z]+::)', body, re.S | re.M)
        if not am: print('NO ANCHOR', wid); continue
        for lm in re.finditer(r'link:([^\[#]+)(?:#([^\[]+))?\[', am.group(1)):
            tp = os.path.normpath(os.path.join(os.path.dirname(p), lm.group(1)))
            anc = lm.group(2)
            if tp not in files: print('ANCHOR FILE MISSING', wid, tp); continue
            t = files[tp]
            if anc:
                # locate section block
                if tp.startswith('requirements/') or tp == 'Requirements.adoc':
                    if tp == 'Requirements.adoc' and anc in reqs:
                        tp2 = reqs[anc][0]; t = files[tp2]; blk = req_body(anc)
                    else:
                        blk = None
                else:
                    blk = None
                if blk is None:
                    # find heading with that auto id or explicit anchor
                    lines = t.split('\n'); start = None; level = None
                    for i, l in enumerate(lines):
                        if f'[#{anc}]' in l or f'[[{anc}]]' in l:
                            start = i
                            for j in range(i, min(i+3, len(lines))):
                                hm = re.match(r'^(=+)\s', lines[j])
                                if hm: level = len(hm.group(1)); start = j; break
                            break
                        hm = re.match(r'^(=+)\s+(.*)$', l)
                        if hm and autoid(hm.group(2)) == anc:
                            start = i; level = len(hm.group(1)); break
                    if start is None: print('ANCHOR TARGET NOT FOUND', wid, tp, anc); continue
                    end = len(lines)
                    for j in range(start+1, len(lines)):
                        hm = re.match(r'^(=+)\s', lines[j])
                        if hm and level and len(hm.group(1)) <= level: end = j; break
                    # only header region: until first subsection
                    blk = '\n'.join(lines[start:end])
                if wid not in blk:
                    print('NO BACKLINK', wid, '->', tp + '#' + anc)
            else:
                if wid not in t: print('NO BACKLINK (file)', wid, '->', tp)

# referenced watch ids that do not exist
refs = set()
for p, txt in files.items():
    for m in re.finditer(r'(PM-WATCH-[A-Z]+-\d+)', txt): refs.add(m.group(1))
print('\nREFERENCED BUT UNDEFINED WATCH IDS', sorted(refs - allwatch))
# watch items not referenced from any req/spec
refd_outside = set()
for p, txt in files.items():
    if p.startswith('implementation-watch/') or p == 'ImplementationWatch.adoc': continue
    refd_outside |= set(re.findall(r'(PM-WATCH-[A-Z]+-\d+)', txt))
print('WATCH ITEMS NOT REFERENCED FROM REQ/SPEC', sorted(allwatch - refd_outside))
# referenced reqs undefined
allreqrefs = set()
for p, txt in files.items():
    allreqrefs |= set(re.findall(r'\bPM-(?:ARCH|WF|SKILL|TOOL|SVC|EXEC|CRED|SEC|TECH|DIST|MIG|TEST|CLIENT|IMPL|EXT|WORK)-\d+\b', txt))
print('UNDEFINED REQ IDS REFERENCED', sorted(allreqrefs - set(reqs)))
