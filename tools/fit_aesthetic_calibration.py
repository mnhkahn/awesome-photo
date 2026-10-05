"""Fit model-specific score scales against fixed visual teacher labels.

Input: prediction JSON with model metadata and samples containing raw_mean,
teacher_aesthetic, split (train/validation), scene_group and source sha256.
Only train rows fit coefficients. Validation gates are advisory, not proof of
human preference accuracy. This tool never writes Android production code.
"""
import argparse
import hashlib
import json
import math
from pathlib import Path
import numpy as np


def fit(document):
    rows = document['samples']
    if len({r['sha256'] for r in rows}) != len(rows):
        raise ValueError('Duplicate photos must be removed before fitting')
    for row in rows:
        if row['split'] not in ('train', 'validation'):
            raise ValueError('Explicit train/validation split required')
        if not (math.isfinite(row['raw_mean']) and 1 <= row['raw_mean'] <= 10
                and math.isfinite(row['teacher_aesthetic']) and 0 <= row['teacher_aesthetic'] <= 100):
            raise ValueError('Invalid score')
    train = [r for r in rows if r['split'] == 'train']
    validation = [r for r in rows if r['split'] == 'validation']
    if {r['scene_group'] for r in train} & {r['scene_group'] for r in validation}:
        raise ValueError('Related scenes must not cross train and validation')
    if len(train) < 30 or len(validation) < 10:
        raise ValueError('Need at least 30 training and 10 held-out photos')
    x = np.array([r['raw_mean'] for r in train])
    y = np.array([r['teacher_aesthetic'] for r in train])
    if np.ptp(x) < .5:
        raise ValueError('Training model outputs cover too little range')
    slope, intercept = np.polyfit(x, y, 1)
    xx = np.array([r['raw_mean'] for r in validation])
    yy = np.array([r['teacher_aesthetic'] for r in validation])
    mae = float(np.abs(np.clip(slope * xx + intercept, 0, 100) - yy).mean())
    raw_mae = float(np.abs((xx - 1) / 9 * 100 - yy).mean())
    constant_mae = float(np.abs(y.mean() - yy).mean())
    pairs = [(a, b) for i, a in enumerate(validation) for b in validation[i+1:]
             if abs(a['teacher_aesthetic'] - b['teacher_aesthetic']) >= 5]
    concordance = sum(1 if (a['raw_mean']-b['raw_mean'])*(a['teacher_aesthetic']-b['teacher_aesthetic']) > 0
                      else .5 if a['raw_mean'] == b['raw_mean'] else 0 for a, b in pairs) / len(pairs) if pairs else 0
    return dict(modelId=document['model']['id'], modelSha256=document['model']['sha256'],
                slope=float(slope), intercept=float(intercept), trainCount=len(train), validationCount=len(validation),
                trainingMeanRange=[float(x.min()), float(x.max())], validationMeanRange=[float(xx.min()), float(xx.max())],
                rawValidationMae=raw_mae, calibratedValidationMae=mae, constantValidationMae=constant_mae,
                validationPairConcordance=concordance, validationPairCount=len(pairs),
                candidateAccepted=bool(slope > 0 and mae <= 12 and mae < raw_mae and mae < constant_mae and concordance >= .6),
                note='Single-teacher reference, one scene-group holdout; scale calibration preserves ranking and does not establish personal preference accuracy.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('predictions', nargs='+', type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    profiles = []
    for path in args.predictions:
        data = path.read_bytes()
        profile = fit(json.loads(data))
        profile['evidenceSha256'] = hashlib.sha256(data).hexdigest()
        profiles.append(profile)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps({'version': 'teacher-wallpaper-v1', 'profiles': profiles}, indent=2) + '\n')
    print(json.dumps(profiles, indent=2))


if __name__ == '__main__':
    main()
