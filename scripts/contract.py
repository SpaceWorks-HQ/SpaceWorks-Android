"""Select unchanged implemented operations + transitive schemas from the authoritative snapshot."""
import json
from pathlib import Path
root=Path(__file__).resolve().parents[1]
s=json.loads((root/'api/openapi.json').read_text(encoding='utf-8-sig'))
selected=['/api/v1/health/','/api/v1/config','/api/v1/auth/device/attestation-challenge','/api/v1/auth/device/login','/api/v1/auth/device/refresh','/api/v1/auth/device/logout','/api/v1/auth/me','/api/v1/auth/change-password','/api/v1/auth/forgot-password','/api/v1/auth/reset-password','/api/v1/auth/member-sign-up','/api/v1/auth/email-verification/confirm','/api/v1/auth/email-verification/resend','/api/v1/auth/social/nonce','/api/v1/auth/social/google','/api/v1/auth/mfa/enroll','/api/v1/auth/mfa/confirm','/api/v1/auth/mfa/verify']
selected += [
    '/api/v1/public/{makerspace_slug}/inventory/',
    '/api/v1/public/{makerspace_slug}/inventory/{id}/',
    '/api/v1/public/{makerspace_slug}/inventory/categories/',
    '/api/v1/member/makerspaces/{makerspace_id}/activity',
    '/api/v1/public/{makerspace_slug}/my-bookings/',
    '/api/v1/member/makerspaces/{makerspace_id}/member-card',
    '/api/v1/notifications/inbox/{makerspace_id}',
    '/api/v1/notifications/inbox/{makerspace_id}/{id}/read',
    '/api/v1/notifications/inbox/{makerspace_id}/read-all',
    '/api/v1/public/{makerspace_slug}/events/',
    '/api/v1/public/{makerspace_slug}/events/{public_token}/',
    '/api/v1/public/{makerspace_slug}/spaces/',
]
paths={k:s['paths'][k] for k in selected}
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
out=root/'app/build/generated/mobile-openapi.json';out.parent.mkdir(parents=True,exist_ok=True)
out.write_text(json.dumps(s),encoding='utf-8')

