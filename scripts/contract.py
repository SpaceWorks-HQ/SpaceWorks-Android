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
phase3_operations={
    'api_v1_bootstrap_retrieve',
    'api_v1_public_requests_create','api_v1_public_requests_status_retrieve',
    'api_v1_member_makerspaces_loans_renew_create','api_v1_public_spaces_availability_retrieve',
    'api_v1_public_spaces_book_create','api_v1_public_my_bookings_cancel_create',
    'api_v1_public_my_bookings_reschedule_create','api_v1_public_events_register_create',
    'api_v1_member_makerspaces_event_registrations_cancel_create',
    'api_v1_member_makerspaces_event_registrations_qr_retrieve',
    'api_v1_member_makerspaces_account_profile_retrieve',
    'api_v1_member_makerspaces_account_profile_partial_update',
    'api_v1_notifications_preferences_retrieve','api_v1_notifications_preferences_partial_update',
}
found=set()
for path, item in s['paths'].items():
    operations={op.get('operationId') for op in item.values() if isinstance(op,dict)} & phase3_operations
    if operations:
        selected.append(path);found.update(operations)
assert found == phase3_operations, f'Missing contract operations: {phase3_operations-found}'
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

