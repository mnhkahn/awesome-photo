"""Fetch the exact, calibrated ONNX artifacts pinned by the committed model catalog.

Release builds must not re-export weights: exporter/platform changes can change
file hashes, causing an exact-hash calibration to stop matching.
"""
import hashlib
import json
import urllib.request
from package_models import ASSETS, ROOT, MODELS


def validate_calibrations(catalog, report):
    profiles = {p['modelId']: p for p in report['profiles'] if p['candidateAccepted']}
    for model in catalog['models']:
        if model['role'] == 'aesthetic':
            profile = profiles.get(model['id'])
            if profile is None or profile['modelSha256'] != model['sha256']:
                raise ValueError('Model has no matching validated calibration: ' + model['id'])


def verified(path, model):
    if not path.is_file() or path.stat().st_size != model['bytes']:
        return False
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest() == model['sha256']


def fetch(model, destination):
    if verified(destination, model):
        return
    temporary = destination.with_suffix('.onnx.part')
    try:
        if not model['url'].startswith('https://'):
            raise ValueError('HTTPS model URL required')
        with urllib.request.urlopen(model['url'], timeout=60) as response, temporary.open('wb') as target:
            if response.status != 200 or not response.url.startswith('https://'):
                raise ValueError('Model download failed')
            size = 0
            while chunk := response.read(1024 * 1024):
                size += len(chunk)
                if size > model['bytes']:
                    raise ValueError('Model is larger than expected')
                target.write(chunk)
        if not verified(temporary, model):
            raise ValueError('Model hash/size verification failed: ' + model['id'])
        temporary.replace(destination)
    finally:
        temporary.unlink(missing_ok=True)


def main():
    catalog = json.loads((ASSETS / 'models.json').read_text())
    report = json.loads((ROOT / 'tools/aesthetic-calibration-report.json').read_text())
    validate_calibrations(catalog, report)
    filenames = {row[0]: row[3] for row in MODELS}
    for model in catalog['models']:
        fetch(model, ASSETS / filenames[model['id']])
        print('Verified pinned release model:', model['id'], flush=True)


if __name__ == '__main__':
    main()
