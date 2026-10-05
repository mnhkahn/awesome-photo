"""Evaluate the bundled local models against user-labelled folders without uploading photos.

Example:
  python tools/evaluate_wallpaper_preferences.py --liked /photos/liked \
      --neutral /photos/neutral --disliked /photos/disliked --output .cache/preference-audit

Writes derived numeric model inputs, metrics and a report. Never changes source photos.
No score remapping is learned automatically from this small audit.
"""
import argparse
import hashlib
import heapq
import json
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
EXTENSIONS = {'.jpg', '.jpeg', '.png', '.webp', '.bmp', '.tiff', '.tif'}


def summarize(rows):
    groups = {name: [r for r in rows if r['group'] == name] for name in ('liked', 'neutral', 'disliked')}
    result = {'groups': {}, 'pairwise': {}}
    for name, members in groups.items():
        scores = sorted(r['score'] for r in members)
        if not scores:
            continue
        n = len(scores)
        result['groups'][name] = {'count': n, 'mean': sum(scores) / n,
            'median': (scores[(n-1)//2]+scores[n//2])/2, 'min': scores[0], 'max': scores[-1]}
    for better, worse in [('liked','neutral'), ('neutral','disliked'), ('liked','disliked')]:
        pair_count = len(groups[better]) * len(groups[worse])
        if not pair_count:
            continue
        credit = 0.0
        worst = []
        serial = 0
        for a in groups[better]:
            for b in groups[worse]:
                credit += 1 if a['score'] > b['score'] else .5 if a['score'] == b['score'] else 0
                if a['score'] <= b['score']:
                    serial += 1
                    heapq.heappush(worst, (b['score'] - a['score'], serial, a, b))
                    if len(worst) > 10:
                        heapq.heappop(worst)
        failures = [(a,b) for _,_,a,b in sorted(worst, reverse=True)]
        result['pairwise'][better+' > '+worse] = {
            'pairCount': pair_count, 'concordance': credit/pair_count,
            'worstInversions': [{'preferred': a['path'], 'preferredScore': a['score'],
                                 'other': b['path'], 'otherScore': b['score']} for a,b in failures]}
    result['limitation'] = 'Descriptive evaluation on supplied samples, not held-out accuracy. No calibration fitted. Similar photos can bias results.'
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for group in ('liked','neutral','disliked'):
        parser.add_argument('--'+group, type=Path, action='append', default=[])
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--manifest', type=Path, help='JSON rows with path and group; allows selected photos without moving originals')
    args = parser.parse_args()
    rows = []; hashes = {}
    for group in ('liked','neutral','disliked'):
        for directory in getattr(args, group):
            if not directory.is_dir():
                parser.error(f'Not a folder: {directory}')
            for path in sorted(directory.rglob('*')):
                if path.is_symlink() or not path.is_file() or path.suffix.lower() not in EXTENSIONS:
                    continue
                digest = hashlib.sha256(path.read_bytes()).hexdigest()
                if digest in hashes:
                    if hashes[digest] != group:
                        parser.error(f'Identical photo has conflicting labels: {path}')
                    continue
                hashes[digest] = group
                rows.append({'id': str(len(rows)), 'path': str(path.resolve()), 'group': group})
    if args.manifest:
        for entry in json.loads(args.manifest.read_text()):
            group=entry['group']; path=Path(entry['path'])
            if group not in ('liked','neutral','disliked') or not path.is_file():
                parser.error(f'Invalid labelled sample: {path}')
            digest=hashlib.sha256(path.read_bytes()).hexdigest()
            if digest in hashes:
                if hashes[digest] != group:
                    parser.error(f'Identical photo has conflicting labels: {path}')
                continue
            hashes[digest]=group
            rows.append({'id':str(len(rows)), 'path':str(path.resolve()), 'group':group})
    if not rows:
        parser.error('No supported images in the supplied folders')
    # New directory prevents accidentally mixing samples from another run.
    args.output.mkdir(parents=True, exist_ok=False)
    samples = args.output / 'samples'
    sample_report = []
    # Process in modest chunks to avoid OS command-length limits; renumber samples globally.
    for offset in range(0, len(rows), 20):
        chunk = rows[offset:offset+20]
        destination = args.output / f'batch-{offset}'
        subprocess.run([sys.executable, str(ROOT/'tools/benchmark_local_scoring.py'),
                        '--output', str(destination), '--runs', '0',
                        *[r['path'] for r in chunk]], check=True, cwd=ROOT)
        samples.mkdir(exist_ok=True)
        batch_report = json.loads((destination/'report.json').read_text())
        for i, row in enumerate(chunk):
            (destination/f'{i}.bin').replace(samples/f'{row["id"]}.bin')
            metadata = next(item for item in batch_report if item['id'] == i)
            sample_report.append({**metadata, 'id': int(row['id'])})
    (samples/'report.json').write_text(json.dumps(sample_report, ensure_ascii=False, indent=2))
    import os
    env = dict(os.environ, WALLPAPER_TEST_SAMPLES=str(samples.resolve()))
    subprocess.run(['./gradlew', ':app:testDebugUnitTest', '--rerun-tasks', '--console=plain'],
                   check=True, cwd=ROOT, env=env)
    xml = ET.parse(ROOT/'app/build/test-results/testDebugUnitTest/TEST-com.awesomephoto.model.LocalWallpaperSamplesTest.xml')
    output = xml.getroot().findtext('system-out', '')
    scores = {match[0]: float(match[1]) for match in re.findall(r'AUDIT_SCORE (\d+) ([0-9.]+)', output)}
    for row in rows:
        if row['id'] not in scores:
            raise RuntimeError(f'Missing Kotlin score for {row["path"]}')
        row['score'] = scores[row['id']]
    report = summarize(rows)
    (args.output/'scores.json').write_text(json.dumps(rows, ensure_ascii=False, indent=2))
    (args.output/'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2))
    print(json.dumps(report, ensure_ascii=False, indent=2))

if __name__ == '__main__':
    main()
