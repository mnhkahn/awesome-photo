"""Upload Android ONNX models to a public ModelScope model repository.

Requires MODELSCOPE_API_KEY. MODELSCOPE_MODEL_REPO defaults to
mnhkahn/awesome-photo-models. Update the APK catalog only after all public
HTTPS downloads pass size and SHA-256 verification. No photos are uploaded.
"""
import hashlib
import json
import os
import re
import shutil
import urllib.request
from urllib.parse import urlparse, urlencode

from package_models import ASSETS, ROOT, package

ENDPOINT = 'https://modelscope.cn'
DEFAULT_REPO = 'mnhkahn/awesome-photo-models'


def configuration(env):
    if not env.get('MODELSCOPE_API_KEY'):
        raise ValueError('Missing MODELSCOPE_API_KEY')
    repo = env.get('MODELSCOPE_MODEL_REPO') or DEFAULT_REPO
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repo):
        raise ValueError('MODELSCOPE_MODEL_REPO must be owner/name')
    return repo


def download_url(repo, filename):
    # Official Hub SDK legacy file download endpoint; works anonymously for public repositories.
    return f'{ENDPOINT}/api/v1/models/{repo}/repo?' + urlencode({'Revision': 'master', 'FilePath': filename})


def verify_download(model):
    digest = hashlib.sha256()
    size = 0
    with urllib.request.urlopen(model['url'], timeout=60) as response:
        if response.status != 200 or urlparse(response.url).scheme != 'https':
            raise ValueError('Public HTTPS download failed: ' + model['id'])
        while chunk := response.read(1024 * 1024):
            size += len(chunk)
            if size > model['bytes']:
                raise ValueError('Public model is larger than expected: ' + model['id'])
            digest.update(chunk)
    if size != model['bytes'] or digest.hexdigest() != model['sha256']:
        raise ValueError('Public model verification failed: ' + model['id'])



def main():
    repo = configuration(os.environ)
    from modelscope_hub import HubApi
    from modelscope_hub.errors import NotExistError
    api = HubApi(endpoint=ENDPOINT, token=os.environ['MODELSCOPE_API_KEY'])
    try:
        info = api.get_repo(repo, 'model')
    except NotExistError:
        info = api.create_repo(repo, 'model', visibility='public', license='other',
                              chinese_name='壁纸照片分析器离线模型')
    if str(info.visibility).lower() not in ('public', '5', 'visibility.public'):
        raise ValueError('Model repository must be public for anonymous app downloads')
    output = ROOT / 'build/model-downloads'
    catalog = json.loads(package(f'{ENDPOINT}/models/{repo}', output))
    from prepare_release_models import validate_calibrations
    validate_calibrations(catalog, json.loads((ROOT / 'tools/aesthetic-calibration-report.json').read_text()))
    names = []
    for model in catalog['models']:
        filename = f"{model['id']}-{model['sha256']}.onnx"
        model['url'] = download_url(repo, filename)
        names.append(filename)
    data = json.dumps(catalog, ensure_ascii=False, indent=2) + '\n'
    (output / 'models.json').write_text(data)
    for license_file in ASSETS.glob('*-LICENSE.txt'):
        shutil.copyfile(license_file, output / license_file.name)
        names.append(license_file.name)
    shutil.copyfile(ROOT / 'tools/modelscope-model-card.md', output / 'README.md')
    (output / 'configuration.json').write_text('{}\n')
    names += ['README.md', 'configuration.json', 'models.json']
    api.upload_folder(repo, 'model', str(output), allow_patterns=names,
                      revision='master', max_workers=2, disable_tqdm=True,
                      commit_message='Publish verified Android ONNX model assets',
                      sync_remote_repo=False)
    for model in catalog['models']:
        verify_download(model)
        print('Verified anonymous download:', model['id'], flush=True)
    temporary = ASSETS / 'models.json.tmp'
    temporary.write_text(data)
    temporary.replace(ASSETS / 'models.json')
    print(f'Model repository: {ENDPOINT}/models/{repo}')
    print('App model catalog updated after download verification.')


if __name__ == '__main__':
    main()
