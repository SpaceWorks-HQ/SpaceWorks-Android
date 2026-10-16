"""Select unchanged implemented operations + transitive schemas from the authoritative snapshot."""
import json
from pathlib import Path
from contract_selection import select_paths, schema_closure
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
phase3_operations |= {'api_v1_integrations_push_devices_create','api_v1_integrations_push_devices_destroy'}
phase3_operations |= {
    'api_v1_member_makerspaces_payments_list',
    'api_v1_member_makerspaces_payments_mobile_intent_create',
    'api_v1_member_makerspaces_payment_documents_retrieve',
    'api_v1_member_makerspaces_payment_documents_download_url_retrieve',
    'api_v1_payment_documents_download_retrieve',
}
phase3_operations |= {
    'api_v1_admin_makerspace_pending_requests_list',
    'api_v1_admin_makerspace_accepted_requests_list',
    'api_v1_admin_makerspace_active_loans_list',
    'api_v1_admin_requests_accept_create',
    'api_v1_admin_requests_reject_create',
    'api_v1_admin_requests_assign_box_create',
    'api_v1_admin_requests_issue_create',
    'api_v1_admin_requests_return_create',
    'api_v1_admin_qr_resolve_create',
    'api_v1_admin_makerspace_direct_loans_list',
    'api_v1_admin_makerspace_direct_loan_members_list',
    'api_v1_admin_makerspace_inventory_list',
    'api_v1_admin_makerspace_containers_list',
    'api_v1_admin_makerspace_direct_loans_create',
    'api_v1_admin_direct_loans_return_create',
    'api_v1_admin_makerspaces_uploads_evidence_url_create',
    'api_v1_admin_makerspace_kit_checklist_preview_create',
    'api_v1_admin_makerspaces_events_retrieve',
    'api_v1_admin_events_check_in_resolve_create',
    'api_v1_admin_event_registrations_mark_attended_create',
    'api_v1_admin_makerspaces_spaces_retrieve',
    'api_v1_admin_spaces_bookings_retrieve',
    'api_v1_admin_bookings_approve_create',
    'api_v1_admin_bookings_reject_create',
}
base_paths=set(selected)
base_operations={op['operationId'] for path in base_paths for op in s['paths'][path].values() if isinstance(op,dict) and 'operationId' in op}
found=set()
for path, item in s['paths'].items():
    operations={op.get('operationId') for op in item.values() if isinstance(op,dict)} & phase3_operations
    if operations:
        selected.append(path);found.update(operations)
assert found == phase3_operations, f'Missing contract operations: {phase3_operations-found}'
# Select exact implemented operations; preserve path metadata and wire schemas.
paths=select_paths(s,base_operations | phase3_operations)
schemas=schema_closure(s,paths)
s['paths']=paths
s['components']['schemas']=schemas
out=root/'app/build/generated/mobile-openapi.json';out.parent.mkdir(parents=True,exist_ok=True)
out.write_text(json.dumps(s),encoding='utf-8')

