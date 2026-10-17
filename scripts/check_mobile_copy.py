"""Verify canonical English catalogs and their generated JVM fallback keys."""
from pathlib import Path
import json
import re
import subprocess
import sys
import xml.etree.ElementTree as ET


def verify(root, manifest):
    root=Path(root).resolve()
    plans=json.loads(Path(manifest).read_text(encoding='utf-8'))
    resource_names=set()
    for plan in plans:
        catalog=root/plan['catalog']
        for element in ET.parse(catalog).getroot():
            if element.tag not in ('string','plurals'):continue
            name=element.attrib['name']
            if name in resource_names:raise ValueError(f'Duplicate catalog resource: {name}')
            resource_names.add(name)
            values=[element] if element.tag=='string' else list(element)
            signatures=[]
            for value in values:
                text=''.join(value.itertext())
                signature=re.findall(r'%(\d+)\$([sdf])',text)
                # Numbered formats let translators reorder arguments safely.
                remaining=re.sub(r'%(\d+)\$[sdf]|%%','',text)
                if '%' in remaining:raise ValueError(f'Unnumbered or invalid format in {name}')
                signatures.append(sorted(signature))
            if element.tag=='plurals' and any(s!=signatures[0] for s in signatures):
                raise ValueError(f'Plural argument mismatch: {name}')
        subprocess.run([sys.executable,str(root/'scripts/generate_mobile_copy.py'),
            '--catalog',str(catalog),'--package',plan['package'],'--object',plan['object'],
            '--output',str(root/plan['output']),'--check'],check=True)
    return len(resource_names)


if __name__=='__main__':
    root=Path(__file__).resolve().parents[1]
    print(f'Verified {verify(root,root/"tokens/mobile-copy.json")} English resource keys.')
