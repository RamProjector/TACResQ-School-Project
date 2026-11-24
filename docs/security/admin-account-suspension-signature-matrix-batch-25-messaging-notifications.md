# Account suspension authorization matrix — batch 25: messaging and notifications

**Evidence:** clean-replay snapshot, callable crosswalk, Storage inventory, and Edge Function review. Service-role paths require explicit checks because client RLS does not constrain service-role access.

| Exact callable / surface | Operation | Branch guard evidence | Remaining proof / decision |
|---|---|---|---|
| `api_my_incident_messages(bigint,integer,bigint)` | Chat history | Read guard migration and active-aware chat authorization helper are documented. | Same-valid-JWT suspended read denial; verify pagination cursor cannot cross incident membership. |
| `api_send_incident_message(bigint,text,uuid)` | Send chat text | Idempotency guard migration checks active-aware profile/chat access before replay lookup. | Test duplicate request, cross-incident membership, suspended sender, and concurrent suspension. |
| `api_send_incident_image_message(bigint,text,text,text,bigint,uuid)` | Finalize image message | Guarded idempotency path and Storage authorization are documented. | Test reservation ownership, object metadata, replay ordering, and suspended finalization. |
| `api_reserve_incident_image_upload` | Reserve image upload | Identified in chat idempotency migration and source contract. | Resolve exact signature from fresh replay; test same-JWT denial before reservation lookup. |
| Storage `incident-chat-attachments` INSERT/SELECT | Upload/download | Active-aware authorization helpers are documented in the object policies. | Execute HTTP-level Storage tests for old-object download and new upload; SQL policy tests alone are not HTTP integration proof. |
| `api_my_notifications(integer)` | Notification list | Read guard migration is documented for active account-aware caller. | Test suspended list denial and no row/count metadata leakage. |
| `api_my_notification(bigint)` | Notification detail | Read guard migration is documented for active account-aware caller. | Test ownership and suspended same-JWT denial. |
| `api_mark_notification_read(bigint)` | Mark read | Included in the notification read/write guard review. | Verify active-state check precedes row mutation and replay; run positive/negative tests. |
| `api_register_fcm_token(text)` | Register device token | Registration wrapper requires active profile lock. | Test suspended token registration denial and duplicate-device behavior. |
| `api_unregister_fcm_token(text)` | Self-cleanup | Narrow suspended-user cleanup exception is documented. | Prove only caller-owned token removal is possible; no arbitrary profile/token deletion. |
| `tacresq-notify` Edge Function | Push dispatch | Source review found service-role profile/recipient reads; a later source contract is described as rejecting suspended actor and filtering active recipients before FCM lookup. | Execute isolated Deno tests with fetch/OAuth/FCM spies; prove denied actor/recipient causes zero provider calls. |
 
## Required test cases

- Suspended sender cannot read/send/replay chat or upload/download attachments with the same unexpired JWT.
- Active incident participants retain normal chat and image behavior.
- Suspended recipient is filtered before token lookup; no Google OAuth or FCM request occurs for a denied event/recipient.
- Unregister remains narrowly self-service and does not become a general suspended-account write bypass.
- Pagination and idempotency responses reveal no protected history to suspended callers.

**Status:** Catalog/source review only. No Deno provider-spy suite or HTTP Storage integration suite is claimed as executed.
