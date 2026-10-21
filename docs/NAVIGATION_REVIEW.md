# TACResQ mobile navigation review

Date: 2026-10-21

## Decision

Keep five bottom-navigation positions on compact Android screens, with GPS Map as the emphasized center destination. Do not add a sixth tab. Android guidance recommends 3–5 primary destinations; top app-bar controls are better for global actions. References:
- Android Developers, Layouts and navigation patterns: https://developer.android.com/design/ui/mobile/guides/layout-and-content/layout-and-nav-patterns
- Material Design, Bottom navigation: https://m1.material.io/components/bottom-navigation.html

## Current role-specific destinations

| Role | Home/primary | Second destination | Center | Fourth | Fifth |
|---|---|---|---|---|---|
| Requester | Home | Requests | GPS Map | Safety | Profile |
| Responder | Current call | History | GPS Map | Safety | Profile |
| Driver | Current call | History | GPS Map | Safety | Profile |
| Dispatcher | Queue | Units | GPS Map | Safety | Profile |

The labels are role-specific but the destinations remain stable. Dispatcher Profile previously displayed an Activity label while rendering account details; the label and title now both say Profile to match the content. Do not label a screen "Activity log" until it actually shows real activity/audit events.

## Placement recommendations

- Notifications: top-app-bar bell/action with unread badge and a dedicated notification-center screen opened from the action. Do not allocate a bottom-navigation slot just for notifications.
- Profile: keep the existing destination until a separate top-right profile action is implemented and the navigation is deliberately rebalanced. Avoid showing duplicate profile entry points without a clear reason.
- History: keep it in the second destination for responders/drivers. Requesters' Requests should show active and past requests with filters or clear sections rather than creating a duplicate History tab.
- Dispatcher activity: if built, make it a real, permission-protected timeline sourced from persisted incident/status audit events; do not disguise the profile screen as activity.
- Create request: a prominent contextual action on requester Home, not a bottom-navigation destination.
- Incident details: open from request/history rows, map markers, and relevant notifications; it is a child screen, not a primary tab.
- Safety: retain only if its content remains distinct and useful. If it remains a static informational placeholder after the main screen audit, replace it with a genuinely useful role-specific destination rather than adding more tabs.

## Implementation order

1. Validate and polish existing role screens, navigation state, empty/loading/error states, and full-screen GPS map behavior.
2. Audit data semantics and access controls for request history and dispatcher unit listings.
3. Build persisted in-app notifications backed by real server events; then add Firebase Cloud Messaging (FCM), device-token registration/rotation/removal, Android notification permission handling, deep links, and delivery-failure observability. Android 13+ requires runtime notification permission: https://firebase.google.com/docs/cloud-messaging/android/get-started
4. Build a real activity timeline only after confirming an authorized persisted event source.
5. Test physical devices, network failures, role isolation, notification opt-in, and GPS-sharing consent.

## Verification caveat

Passing CI is not proof of live Supabase end-to-end behavior, physical GPS reliability, or push-notification delivery. Use synthetic incidents and test locations only. TACResQ remains an educational prototype, not a real emergency dispatch service.
