#!/usr/bin/env python3
"""Copy license notices from a resolved Cargo metadata JSON; no code is executed."""
import argparse
import json
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('metadata', type=Path)
parser.add_argument('--assets', type=Path, default=Path(__file__).resolve().parents[1] / 'app/src/main/assets/licenses')
args = parser.parse_args()
data = json.loads(args.metadata.read_text())
resolved = {item['id'] for item in data['resolve']['nodes']}
out = args.assets / 'rust-0.2.6'
out.mkdir(parents=True, exist_ok=True)
rows = []
for package in data['packages']:
    if package['id'] not in resolved:
        continue
    rows.append(f"{package['name']} {package['version']}: {package.get('license') or 'see upstream LICENSE'}")
    if (package.get('source') or '').startswith('registry+'):
        for notice in Path(package['manifest_path']).parent.iterdir():
            if notice.is_file() and notice.name.upper().startswith(('LICENSE', 'LICENCE', 'COPYING', 'NOTICE')):
                target = out / f"{package['name']}-{package['version']}-{notice.name}"
                target.write_bytes(notice.read_bytes())
(args.assets / 'DEPENDENCIES-0.2.6.txt').write_text(
    'Resolved native build dependencies (Cargo metadata; no runtime tests)\n\n' + '\n'.join(sorted(rows)) + '\n'
)
print(f'{len(rows)} dependency entries; {len(list(out.iterdir()))} registry license notices')
