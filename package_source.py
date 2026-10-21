#!/usr/bin/env python3
"""Package the candidate and licensed upstream sources without historical releases or keys."""
from pathlib import Path
import os
import shutil
import tarfile
import tempfile

root = Path(__file__).resolve().parent
out = Path(os.environ.get('FREEOFFICE_HANDOFF_DIR', str(root.parent / 'freeoffice-0.2.6-handoff')))
out.mkdir(parents=True, exist_ok=True)
archive = out / 'freeoffice-0.2.6-structured-alpha1-source.tar.gz'
ignore = shutil.ignore_patterns(
    '.git', '.gradle', 'build', 'target', 'dist', '*.log', 'local.properties',
    '*.jks', '*.keystore', '*.class', '*.apk', '__pycache__', '*.orig', '*.rej',
    '*.before-navigation', '.manus*', 'native-metadata.json',
)
staging_parent = os.environ.get('FREEOFFICE_SOURCE_STAGE_DIR')
with tempfile.TemporaryDirectory(prefix='freeoffice-source-', dir=staging_parent) as temp:
    stage = Path(temp) / 'freeoffice-android'
    shutil.copytree(root, stage, ignore=ignore)
    vendor = stage / 'vendor'
    vendor.mkdir(exist_ok=True)
    sources = {
        'wordcraft': root / 'vendor/wordcraft' if (root / 'vendor/wordcraft').exists() else root.parent / 'wordcraft',
        'gridcraft': root / 'vendor/gridcraft' if (root / 'vendor/gridcraft').exists() else root.parent / 'office-upstream/gridcraft',
        'pdfcraft': root / 'vendor/pdfcraft' if (root / 'vendor/pdfcraft').exists() else root.parent / 'office-upstream/pdfcraft',
    }
    for name, path in sources.items():
        if not (vendor / name).exists():
            shutil.copytree(path, vendor / name, ignore=ignore)
    for manifest in stage.rglob('Cargo.toml'):
        text = manifest.read_text()
        for name, path in sources.items():
            text = text.replace(str(path), os.path.relpath(vendor / name, manifest.parent))
        manifest.write_text(text)
    (stage / 'local.properties.example').write_text('sdk.dir=/absolute/path/to/Android/Sdk\n')
    with tarfile.open(archive, 'w:gz') as tar:
        tar.add(stage, arcname='freeoffice-android')
print(archive)
