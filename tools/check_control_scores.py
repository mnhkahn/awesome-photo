"""Check full Kotlin scores for an original/control benchmark run.
Usage: python tools/check_control_scores.py /tmp/wallpaper-controls/report.json
Run the LocalWallpaperSamplesTest on that same samples folder first.
"""
import argparse
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[1]

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('report',type=Path)
    args=parser.parse_args()
    cases=json.loads(args.report.read_text())
    xml=ET.parse(ROOT/'app/build/test-results/testDebugUnitTest/TEST-com.awesomephoto.model.LocalWallpaperSamplesTest.xml')
    output=xml.getroot().findtext('system-out','')
    scores={int(i):float(s) for i,s in re.findall(r'AUDIT_SCORE (\d+) ([0-9.]+)',output)}
    if set(scores) != {row['id'] for row in cases}:
        raise RuntimeError('Test output does not cover the supplied benchmark run')
    originals={row['file']:scores[row['id']] for row in cases if row['variant']=='original'}
    results=[]
    for row in cases:
        if row['variant']=='original': continue
        original=originals[row['file']]; control=scores[row['id']]
        results.append({'file':row['file'],'variant':row['variant'],
                        'original':original,'control':control,'passed':original>control})
    destination=args.report.with_name('control-check.json')
    destination.write_text(json.dumps(results,ensure_ascii=False,indent=2))
    for row in results: print(json.dumps(row,ensure_ascii=False))
    if not results or not all(row['passed'] for row in results):
        raise SystemExit('Ranking regression detected; inspect control-check.json')
    print(f'{len(results)} control comparisons passed. This does not validate personal aesthetic preference.')

if __name__=='__main__': main()
