"""Select unchanged A0 operations + transitive schemas from the authoritative snapshot."""
import json
from pathlib import Path
root=Path(__file__).resolve().parents[1]
s=json.loads((root/'api/openapi.json').read_text(encoding='utf-8-sig'))
paths={k:s['paths'][k] for k in ['/api/v1/health/','/api/v1/auth/device/refresh']}
required=set()
def visit(v):
    if isinstance(v,dict):
        if '$ref' in v and v['$ref'].startswith('#/components/schemas/'):
            n=v['$ref'].split('/')[-1]
            if n not in required:
                required.add(n);visit(s['components']['schemas'][n])
        for x in v.values():visit(x)
    elif isinstance(v,list):
        for x in v:visit(x)
visit(paths)
s['paths']=paths
s['components']['schemas']={k:s['components']['schemas'][k] for k in sorted(required)}
out=root/'app/build/generated/a0-openapi.json';out.parent.mkdir(parents=True,exist_ok=True)
out.write_text(json.dumps(s),encoding='utf-8')
