(() => {
  const roles = {
    requester: { label: 'REQUESTER', name: 'Rica Santos', initials: 'RS', title: 'Requester' },
    responder: { label: 'RESPONDER / DRIVER', name: 'Rey Responder', initials: 'RR', title: 'Field unit' },
    dispatcher: { label: 'DISPATCHER', name: 'Mika Torres', initials: 'MT', title: 'Dispatcher' },
  };
  const descriptions = {
    home: ['Requester home', 'A calm, clear emergency request form with a location-sharing explanation and a deliberate SOS confirmation.', ['SOS flow', 'Location consent', 'Duplicate protection']],
    requests: ['My requests', 'Review active and completed reports, current status, timestamps, and cancellation options.', ['History', 'Status timeline']],
    profile: ['Requester profile', 'Account controls, privacy information, location consent, and sign-out entry point.', ['Privacy', 'Account']],
    call: ['Current call', 'Responder duty state, assigned incident, safe navigation handoff, and explicit status progression.', ['Duty status', 'Assignment', 'Status transitions']],
    history: ['Response history', 'Completed assignments and a readable status timeline for each call.', ['History', 'Audit context']],
    queue: ['Dispatch queue', 'Prioritized incidents and availability-aware assignment with an explicit confirmation step.', ['Manual assignment', 'No auto-dispatch']],
    units: ['Field units', 'Duty status and the freshness of unit-location data; location is not invented when unavailable.', ['Availability', 'Freshness']],
    activity: ['Activity log', 'A chronological record of incident creation, assignment, status changes, cancellation, and sync state.', ['Audit trail', 'Sync state']],
    map: ['GPS Map', 'A role-specific full-screen map destination. This schematic map uses sample data, not live GPS.', ['Centered map control', 'Role-specific', 'Schematic']],
    privacy: ['Privacy & safety', 'Review which data is shared, location consent, emergency limitations, and support options.', ['Consent', 'Data controls']],
  };
  const appRoot = document.querySelector('#app-root');
  const modalRoot = document.querySelector('#modal-root');
  const toastRoot = document.querySelector('#toast-root');
  const incidentSeed = {
    id: '1042', type: 'Medical', description: '', status: 'Waiting for dispatch', requester: 'Rica Santos', phone: '0917 555 0182',
    created: 'Today · 2:18 PM', lat: '11.2446', lng: '125.0035', accuracy: 18, assignedTo: null,
    events: [{ title: 'Emergency request received', time: '2:18 PM', role: 'Requester' }],
  };
  const state = {
    role: 'requester', page: 'home', network: 'online', gps: 'ready', consented: false, locationAllowed: false,
    activeIncident: null, completed: [], queuedIncident: null, queuedStatus: null,
    incidentType: 'Medical', description: '', duty: 'Available', toastTimer: null, gpsRetry: 0,
  };
  const escapeHtml = (value) => String(value ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
  const iconPaths = {
    home: '<path d="m3 10 9-7 9 7"/><path d="M5 9v11h14V9M9 20v-6h6v6"/>',
    requests: '<rect x="5" y="4" width="14" height="17" rx="2"/><path d="M9 4V3h6v1M8.5 10h7M8.5 14h7M8.5 18h4"/>',
    mapPin: '<path d="M20 10c0 5.4-8 11-8 11S4 15.4 4 10a8 8 0 1 1 16 0Z"/><circle cx="12" cy="10" r="2.5"/>',
    shield: '<path d="M12 22s8-3.6 8-11V5l-8-3-8 3v6c0 7.4 8 11 8 11Z"/><path d="m9 12 2 2 4-4"/>',
    user: '<circle cx="12" cy="8" r="3.4"/><path d="M5 21a7 7 0 0 1 14 0"/>',
    call: '<path d="M22 16.9v3a2 2 0 0 1-2.2 2A19.8 19.8 0 0 1 3.1 5.2 2 2 0 0 1 5.1 3h3a2 2 0 0 1 2 1.7l.4 2.8a2 2 0 0 1-.6 1.7L8.2 11a16 16 0 0 0 4.8 4.8l1.8-1.7a2 2 0 0 1 1.7-.6l2.8.4a2 2 0 0 1 1.7 2Z"/>',
    clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3.5 2"/>',
    queue: '<path d="M9 6h11M9 12h11M9 18h11"/><circle cx="4" cy="6" r="1"/><circle cx="4" cy="12" r="1"/><circle cx="4" cy="18" r="1"/>',
    units: '<circle cx="9" cy="8" r="3"/><path d="M3 20a6 6 0 0 1 12 0M16 5.2a3 3 0 0 1 0 5.6M18 14a5 5 0 0 1 3 4.5"/>',
    activity: '<path d="M3 12h4l2.2-6 4.1 12 2.2-6H21"/>',
    info: '<circle cx="12" cy="12" r="9"/><path d="M12 11v5M12 8h.01"/>',
    alert: '<path d="M12 3 2.8 20h18.4L12 3Z"/><path d="M12 9v4M12 16.5h.01"/>',
    check: '<path d="m5 12 4 4L19 6"/>',
    assign: '<path d="M4 12h15M13 6l6 6-6 6"/><circle cx="5" cy="6" r="1"/><circle cx="5" cy="18" r="1"/>',
    arrowLeft: '<path d="M19 12H5M12 19l-7-7 7-7"/>',
    plus: '<path d="M12 5v14M5 12h14"/>',
    minus: '<path d="M5 12h14"/>',
    target: '<circle cx="12" cy="12" r="8"/><circle cx="12" cy="12" r="2"/><path d="M12 2v2M12 20v2M2 12h2M20 12h2"/>',
    arrowUpRight: '<path d="M7 17 17 7M8 7h9v9"/>',
    signOut: '<path d="M10 17l5-5-5-5M15 12H3"/><path d="M12 3h6a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-6"/>',
    settings: '<circle cx="12" cy="12" r="3"/><path d="m19.4 15 .1.1a1.8 1.8 0 1 1-2.5 2.5l-.1-.1a1.8 1.8 0 0 0-3 .9v.2a1.8 1.8 0 1 1-3.6 0v-.2a1.8 1.8 0 0 0-3-.9l-.1.1a1.8 1.8 0 1 1-2.5-2.5l.1-.1a1.8 1.8 0 0 0-.9-3h-.2a1.8 1.8 0 1 1 0-3.6h.2a1.8 1.8 0 0 0 .9-3l-.1-.1a1.8 1.8 0 1 1 2.5-2.5l.1.1a1.8 1.8 0 0 0 3-.9v-.2a1.8 1.8 0 1 1 3.6 0v.2a1.8 1.8 0 0 0 3 .9l.1-.1a1.8 1.8 0 1 1 2.5 2.5l-.1.1a1.8 1.8 0 0 0 .9 3h.2a1.8 1.8 0 1 1 0 3.6h-.2a1.8 1.8 0 0 0-.9 3Z"/>',
    refresh: '<path d="M20 7v5h-5M4 17v-5h5"/><path d="M5.6 9a7 7 0 0 1 11.7-2L20 12M4 12l2.7 5a7 7 0 0 0 11.7-2"/>',
  };
  const iconSvg = (name, className = '') => `<svg class="${className}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${iconPaths[name] || iconPaths.info}</svg>`;
  const statusOrder = ['Waiting for dispatch', 'Unit assigned', 'Unit en route', 'Unit on scene', 'Transporting', 'Resolved'];
  const statusIndex = (status) => statusOrder.indexOf(status);
  const currentRole = () => roles[state.role];
  const getActive = () => state.activeIncident;
  const isPendingOrActive = () => Boolean(state.activeIncident && !['Resolved', 'Cancelled'].includes(state.activeIncident.status));
  const gpsDescription = () => {
    if (state.role !== 'requester') return 'Live unit location feed unavailable in this prototype';
    return ({
      ready: state.locationAllowed ? 'Location permission ready · sample accuracy 18 m' : 'Location access not yet requested',
      permission: 'Location permission not granted', poor: 'Low GPS accuracy · about 180 m', stale: 'Last GPS fix is stale', unavailable: 'GPS unavailable',
    })[state.gps];
  };
  const networkDescription = () => ({ online: 'Connected', weak: 'Weak signal', offline: 'Offline' })[state.network];
  const setPage = (page) => { state.page = page; render(); document.querySelector('#phone-screen').scrollTop = 0; };
  const recordEvent = (title, role = currentRole().title) => {
    const target = state.activeIncident;
    if (target) target.events.unshift({ title, time: 'Just now', role });
  };
  const showToast = (text, kind = '') => {
    clearTimeout(state.toastTimer);
    toastRoot.innerHTML = `<div class="toast ${kind}">${escapeHtml(text)}</div>`;
    state.toastTimer = setTimeout(() => { toastRoot.innerHTML = ''; }, 3200);
  };
  const setRole = (role) => {
    state.role = role;
    state.page = ({ requester: 'home', responder: 'call', dispatcher: 'queue' })[role];
    render();
  };
  const roleLinks = () => ({
    requester: [['home', 'home', 'Home'], ['requests', 'requests', 'Requests'], ['map', 'mapPin', 'GPS Map'], ['privacy', 'shield', 'Safety'], ['profile', 'user', 'Profile']],
    responder: [['call', 'call', 'Current call'], ['history', 'clock', 'History'], ['map', 'mapPin', 'GPS Map'], ['privacy', 'shield', 'Safety'], ['profile', 'user', 'Profile']],
    dispatcher: [['queue', 'queue', 'Queue'], ['units', 'units', 'Units'], ['map', 'mapPin', 'GPS Map'], ['activity', 'activity', 'Activity'], ['profile', 'user', 'Profile']],
  })[state.role];
  const navHtml = () => {
    const links = roleLinks();
    return `<nav class="nav-bar" aria-label="${currentRole().label} navigation">${links.map(([page, iconName, label]) => {
      if (page === 'map') return `<div class="map-nav-slot"><button class="nav-item map-nav ${state.page === 'map' ? 'active' : ''}" data-action="page" data-page="map" aria-label="GPS Map" title="GPS Map" ${state.page === 'map' ? 'aria-current="page"' : ''}><span class="nav-symbol">${iconSvg(iconName)}</span></button></div>`;
      return `<button class="nav-item ${state.page === page ? 'active' : ''}" data-action="page" data-page="${page}" aria-label="${escapeHtml(label)}" title="${escapeHtml(label)}" ${state.page === page ? 'aria-current="page"' : ''}><span class="nav-symbol">${iconSvg(iconName)}</span></button>`;
    }).join('')}</nav>`;
  };
  const phoneShell = (content, options = {}) => `<div class="phone-app">
    <header class="phone-header"><div class="phone-logo"><span class="phone-logo-mark"><img src="../brand/tacresq-app-icon.png" alt="" /></span>TACResQ</div>
      <div class="header-actions"><span class="connection-pill ${state.network === 'offline' ? 'offline' : ''}">${state.network === 'offline' ? 'OFFLINE' : state.network === 'weak' ? 'WEAK SIGNAL' : 'CONNECTED'}</span><button class="icon-button" aria-label="Open privacy and safety" data-action="page" data-page="privacy">${iconSvg('info')}</button></div>
    </header><main class="phone-scroll" id="screen-content">${content}</main>${options.noNav ? '' : navHtml()}</div>`;
  const greeting = (subtitle = '') => `<div class="welcome-row"><div><div class="eyebrow-phone">${currentRole().label}</div><h1 class="phone-title">${escapeHtml(subtitle || `Hello, ${currentRole().name.split(' ')[0]}`)}</h1><p class="phone-subtitle">${subtitle ? 'TACResQ response workspace' : `Signed in as ${currentRole().title.toLowerCase()}`}</p></div><span class="user-avatar" aria-label="${escapeHtml(currentRole().name)}">${currentRole().initials}</span></div>`;
  const secTitle = (text, linkText = '', linkAction = '') => `<div class="screen-section-heading"><h2>${escapeHtml(text)}</h2>${linkText ? `<button class="tiny-link" data-action="${linkAction}">${escapeHtml(linkText)}</button>` : ''}</div>`;
  const activeIncidentCard = (expanded = false) => {
    const item = getActive();
    if (!item) return '';
    const ix = statusIndex(item.status);
    const statusClass = item.status === 'Waiting for dispatch' ? 'pending' : item.status === 'Resolved' ? 'good' : '';
    const status = `<div class="status-row"><span class="badge ${statusClass}">${escapeHtml(item.status)}</span><span class="subtle">#${item.id}</span></div>`;
    return `<article class="surface-card"><div class="meta-row"><div><div class="micro-label">INCIDENT #${item.id}</div><h2 style="margin-top:5px">${escapeHtml(item.type)} emergency</h2></div>${status}</div>
      <p class="body-copy">${escapeHtml(item.description || 'No additional description provided.')}</p>
      <div class="location-summary"><div class="micro-label">REPORTED LOCATION · ${item.accuracy == null ? 'ACCURACY UNKNOWN' : `±${item.accuracy} M`}</div><div class="body-copy" style="color:#314e58;font-weight:700">${item.lat}, ${item.lng} <span class="subtle">· sample coordinates</span></div><div class="subtle">${item.created} · ${state.gps === 'stale' ? 'GPS freshness unknown' : 'Initial location fix'}</div></div>
      <div class="steps" aria-label="Incident status progress">${statusOrder.map((_, i) => `<span class="step ${i < ix ? 'done' : i === ix ? 'active' : ''}"></span>`).join('')}</div><div class="steps-labels"><span>Sent</span><span>Assigned</span><span>En route</span><span>On scene</span><span>Closed</span></div>
      ${expanded ? `<div class="divider"></div><div class="micro-label">LATEST UPDATE</div><p class="body-copy">${escapeHtml(item.events[0]?.title || 'Request received')} · ${escapeHtml(item.events[0]?.time || '—')}</p>` : ''}
      <button class="btn secondary full" data-action="page" data-page="map">${iconSvg('mapPin')}<span>View on GPS Map</span></button>
      ${state.role === 'requester' && ['Waiting for dispatch', 'Unit assigned'].includes(item.status) ? `<button class="btn tertiary full" data-action="cancel-request">Cancel request</button>` : ''}
    </article>`;
  };
  const emergencySafetyCard = () => `<div class="inline-alert info-alert"><span aria-hidden="true">${iconSvg('info')}</span><span>This prototype is not connected to live dispatch or emergency services. If someone is in immediate danger, use your locally verified emergency contact route.</span></div>`;
  const requesterHome = () => {
    const item = getActive();
    return `${greeting()}${state.network === 'offline' ? `<div class="inline-alert red-alert"><span aria-hidden="true">${iconSvg('alert')}</span><span>You are offline. A new request cannot reach dispatch. This prototype does not store or sync a real SOS.</span></div>` : ''}
      ${state.queuedIncident ? `<div class="inline-alert"><span aria-hidden="true">${iconSvg('refresh')}</span><span>Request not sent · waiting for connection. Restore connectivity to retry. Do not assume dispatch has received it.</span></div><button class="btn full" data-action="retry-sos">Retry sending request</button>` : ''}
      ${item ? `${secTitle('Current request', 'Open requests', 'page:requests')}${activeIncidentCard(true)}${emergencySafetyCard()}` : `<section class="surface-card"><div class="eyebrow-phone">REQUEST ASSISTANCE</div><h2 style="margin-top:7px">What do you need help with?</h2><p class="body-copy">Your location is shared with dispatch and the assigned unit for this request. It is not continuously tracked in this prototype.</p>
       <label class="field-label">Emergency type</label><div class="incident-types" role="group" aria-label="Emergency type">${['Medical', 'Fire', 'Vehicle', 'Flood / disaster', 'Other'].map(type => `<button class="type-chip ${state.incidentType === type ? 'selected' : ''}" data-action="type" data-type="${type}">${type}</button>`).join('')}</div>
       <label class="field-label" for="request-details">What is happening? <span class="subtle">Optional</span></label><textarea class="text-field" id="request-details" rows="3" maxlength="400" placeholder="Add details that may help responders">${escapeHtml(state.description)}</textarea>
       <div class="location-summary"><div class="status-row"><span class="micro-label">LOCATION FOR THIS REQUEST</span><span class="badge ${state.gps === 'ready' ? 'good' : state.gps === 'poor' ? 'pending' : 'alert'}">${state.gps === 'ready' ? 'Ready' : state.gps === 'poor' ? 'Low accuracy' : 'Needs attention'}</span></div><div class="body-copy">${escapeHtml(gpsDescription())}</div><button class="tiny-link" data-action="location-help">Review location access</button></div>
       <button class="btn danger full" data-action="start-sos" ${state.queuedIncident ? 'disabled' : ''}>Review emergency request</button>
       <p class="phone-footer-note">The next screen lets you check details before sending.</p></section>${emergencySafetyCard()}`}
      ${secTitle('Recent activity', 'See all', 'page:requests')}${item ? '' : `<div class="surface-card compact"><div class="list-row"><span class="list-icon">${iconSvg('check')}</span><div class="list-main"><div class="list-title">No active request</div><div class="list-description">Your request history will appear here.</div></div></div></div>`}`;
  };
  const requesterRequests = () => {
    const cards = [getActive(), ...state.completed].filter(Boolean);
    return `${greeting('My requests')}${cards.length ? cards.map(item => `<article class="surface-card"><div class="meta-row"><div><div class="micro-label">INCIDENT #${item.id}</div><h2 style="margin-top:5px">${escapeHtml(item.type)} emergency</h2></div><span class="badge ${item.status === 'Resolved' ? 'good' : 'pending'}">${escapeHtml(item.status)}</span></div><p class="body-copy">${escapeHtml(item.created)} · ${item.accuracy == null ? 'Location accuracy unavailable' : `Initial fix ±${item.accuracy} m`}</p>${item.status !== 'Cancelled' ? `<button class="btn secondary full" data-action="page" data-page="map">${iconSvg('mapPin')}<span>View map</span></button>` : ''}</article>`).join('') : `<div class="surface-card empty-state"><div class="empty-icon">≋</div><h2>No requests yet</h2><p>When you submit a request, its status and timeline will appear here.</p><button class="btn secondary" data-action="page" data-page="home">Go to Home</button></div>`}<div class="inline-alert info-alert"><span aria-hidden="true">${iconSvg('info')}</span><span>Only the initial SOS location is recorded in this prototype. There is no continuous requester tracking.</span></div>`;
  };
  const requesterProfile = () => `${greeting('Profile')}<article class="surface-card"><div class="list-row"><span class="user-avatar">${currentRole().initials}</span><div class="list-main"><div class="list-title">${currentRole().name}</div><div class="list-description">${currentRole().title} · account details are sample data</div></div></div></article><article class="surface-card"><div class="micro-label">PRIVACY &amp; SAFETY</div><ul class="menu-list"><li><button class="menu-button" data-action="page" data-page="privacy"><span class="list-icon">${iconSvg('info')}</span><span>Privacy and location sharing</span><span class="chevron">›</span></button></li><li><button class="menu-button" data-action="consent-details"><span class="list-icon">${iconSvg('mapPin')}</span><span>Location consent${state.consented ? ' · reviewed' : ' · not yet given'}</span><span class="chevron">›</span></button></li><li><button class="menu-button" data-action="location-help"><span class="list-icon">${iconSvg('settings')}</span><span>Location permission status</span><span class="chevron">›</span></button></li></ul></article><article class="surface-card"><div class="micro-label">ACCOUNT</div><button class="btn tertiary full" data-action="sign-out">Sign out</button></article>`;
  const dutyBadge = () => `<span class="badge ${state.duty === 'Available' ? 'good' : 'neutral'}">${escapeHtml(state.duty)}</span>`;
  const responderCall = () => {
    const item = getActive();
    const assigned = item && item.assignedTo === 'responder';
    return `${greeting('Current call')}<article class="surface-card"><div class="meta-row"><div><div class="micro-label">${assigned ? 'ASSIGNMENT' : 'DUTY STATUS'}</div><h2 style="margin-top:5px">${assigned ? 'Assigned to incident' : escapeHtml(state.duty)}</h2></div><span class="badge ${assigned ? '' : state.duty === 'Available' ? 'good' : 'neutral'}">${assigned ? 'On assignment' : escapeHtml(state.duty)}</span></div><p class="body-copy">${assigned ? 'Your duty state changes as you progress through the active incident. Updates are sent to dispatch when online.' : 'Choose whether you are ready to receive an assignment.'}</p>${!assigned ? `<div class="button-row" style="margin-top:10px"><button class="btn ${state.duty === 'Available' ? '' : 'secondary'}" data-action="duty" data-value="Available">Available</button><button class="btn ${state.duty === 'Off duty' ? '' : 'tertiary'}" data-action="duty" data-value="Off duty">Off duty</button></div>` : ''}</article>
      ${item && assigned ? `<div class="screen-section-heading"><h2>Active response</h2><span class="badge">#${item.id}</span></div><article class="surface-card"><div class="meta-row"><div><div class="micro-label">${escapeHtml(item.type.toUpperCase())} · INCIDENT #${item.id}</div><h2 style="margin-top:6px">${escapeHtml(item.status)}</h2></div><span class="badge">${escapeHtml(item.status)}</span></div><p class="body-copy">${escapeHtml(item.description || 'No extra details supplied.')}</p><div class="location-summary"><div class="micro-label">INCIDENT LOCATION · REQUESTER FIX</div><div class="body-copy" style="font-weight:800;color:#2d4b55">${item.lat}, ${item.lng}</div><div class="subtle">Initial fix ±${item.accuracy} m · sample coordinates · not a live requester position</div></div><button class="btn secondary full" data-action="page" data-page="map">${iconSvg('mapPin')}<span>Open GPS Map</span></button><button class="btn tertiary full" data-action="external-nav">↗ &nbsp; Open turn-by-turn navigation</button><button class="btn full" data-action="advance-status">${nextStatusLabel(item.status)}</button><button class="btn tertiary full" data-action="cannot-respond">Cannot safely respond</button></article>${state.queuedStatus ? `<div class="inline-alert"><span aria-hidden="true">${iconSvg('refresh')}</span><span>Status update queued locally in demo. Dispatch has not received the change yet.</span></div>` : ''}${emergencySafetyCard()}` : `<div class="surface-card empty-state"><div class="empty-icon">${iconSvg('call')}</div><h2>No assigned call</h2><p>New assignments appear here. Keep dispatch contact available according to TACRU procedure.</p><button class="btn secondary" data-action="page" data-page="history">View recent history</button></div>${item && item.status === 'Waiting for dispatch' ? `<div class="inline-alert info-alert"><span aria-hidden="true">${iconSvg('info')}</span><span>Incident #${item.id} is awaiting dispatcher assignment. This unit has not been assigned.</span></div>` : ''}`}`;
  };
  function nextStatusLabel(status) { return ({ 'Unit assigned': 'Confirm en route', 'Unit en route': 'Confirm on scene', 'Unit on scene': 'Start transport', 'Transporting': 'Mark resolved' })[status] || 'Update status'; }
  const responderHistory = () => {
    const items = [getActive(), ...state.completed].filter(item => item && item.assignedTo === 'responder');
    return `${greeting('Response history')}${items.length ? items.map(item => `<article class="surface-card"><div class="meta-row"><div class="micro-label">INCIDENT #${item.id} · ${escapeHtml(item.type.toUpperCase())}</div><span class="badge ${item.status === 'Resolved' ? 'good' : ''}">${escapeHtml(item.status)}</span></div><p class="body-copy">${escapeHtml(item.created)} · ${escapeHtml(item.requester)}</p><div class="timeline-line">${item.events.map(ev => `<div class="timeline-entry">${escapeHtml(ev.title)}<span class="timeline-time">${escapeHtml(ev.time)} · ${escapeHtml(ev.role)}</span></div>`).join('')}</div></article>`).join('') : `<div class="surface-card empty-state"><div class="empty-icon">${iconSvg('clock')}</div><h2>No completed calls</h2><p>Assigned call history will appear here.</p></div>`}`;
  };
  const units = [
    { key: 'responder', name: 'Rey Responder', role: 'Field responder', status: state.activeIncident?.assignedTo === 'responder' && state.activeIncident.status !== 'Resolved' ? 'Assigned' : state.duty === 'Off duty' ? 'Off duty' : 'Available', point: '' },
    { key: 'driver', name: 'Joaquin Driver', role: 'Driver · sample unit', status: 'Off duty', point: '30%,62%' },
    { key: 'unitC', name: 'Unit 03', role: 'Vehicle · sample unit', status: 'Location unavailable', point: '' },
  ];
  const dispatcherQueue = () => {
    const item = getActive();
    const pending = item?.status === 'Waiting for dispatch';
    const unitsAvailable = units.filter(u => u.status === 'Available');
    return `${greeting('Response desk')}<div class="metric-grid"><div class="metric"><span>Awaiting dispatch</span><strong>${pending ? '01' : '00'}</strong></div><div class="metric"><span>Active response</span><strong>${item && !pending && item.status !== 'Resolved' ? '01' : '00'}</strong></div><div class="metric"><span>Units available</span><strong>${unitsAvailable.length.toString().padStart(2, '0')}</strong></div><div class="metric"><span>Resolved today</span><strong>${state.completed.length.toString().padStart(2, '0')}</strong></div></div>
      ${state.network === 'offline' ? `<div class="inline-alert red-alert"><span aria-hidden="true">${iconSvg('alert')}</span><span>Offline. Assignment and status changes cannot reach field units. Queue data may be out of date.</span></div>` : ''}
      <div class="screen-section-heading"><h2>Incident queue</h2><span class="flow-state">${state.network === 'offline' ? 'Last sync unknown' : 'Updated just now · demo'}</span></div>
      ${item ? `<article class="surface-card"><div class="meta-row"><div><div class="micro-label">INCIDENT #${item.id} · ${escapeHtml(item.type.toUpperCase())}</div><h2 style="margin-top:5px">${escapeHtml(item.status)}</h2></div><span class="badge ${pending ? 'pending' : ''}">${escapeHtml(item.status)}</span></div><p class="body-copy">${escapeHtml(item.requester)} · ${escapeHtml(item.description || 'No additional description')}</p><div class="location-summary"><div class="micro-label">REPORTED COORDINATES · INITIAL REQUESTER FIX</div><div class="body-copy" style="font-weight:800;color:#294b55">${item.lat}, ${item.lng} · ±${item.accuracy} m</div><div class="subtle">Coordinates and accuracy are sample data, not live updates.</div></div><button class="btn secondary full" data-action="page" data-page="map">${iconSvg('mapPin')}<span>View incident on map</span></button>${pending ? unitsAvailable.length ? unitsAvailable.map(u => `<button class="btn full" data-action="start-assign" data-unit="${u.key}" ${state.network === 'offline' ? 'disabled' : ''}>Assign to ${escapeHtml(u.name)} · Available</button>`).join('') : `<div class="inline-alert"><span aria-hidden="true">${iconSvg('alert')}</span><span>No available unit can be assigned. Confirm unit availability through the approved dispatch procedure.</span></div>` : `<p class="body-copy">Assigned unit: ${escapeHtml(item.assignedTo === 'responder' ? 'Rey Responder' : item.assignedTo || 'Not assigned')}</p>`}</article>` : `<div class="surface-card empty-state"><div class="empty-icon">${iconSvg('queue')}</div><h2>Queue is clear</h2><p>New incidents from requesters will appear here.</p></div>`}
      ${secTitle('All active incidents', 'Open map', 'page:map')}${item && item.status !== 'Resolved' ? `<div class="surface-card compact"><div class="list-row"><span class="list-icon">${item.id.slice(-2)}</span><div class="list-main"><div class="list-title">#${item.id} · ${escapeHtml(item.type)} · ${escapeHtml(item.status)}</div><div class="list-description">${escapeHtml(item.requester)} · location ${state.gps === 'stale' ? 'freshness unknown' : 'from initial request'}</div></div><button class="tiny-link" data-action="page" data-page="map">Map</button></div></div>` : ''}`;
  };
  const dispatcherUnits = () => `${greeting('Field units')}<div class="inline-alert info-alert"><span aria-hidden="true">${iconSvg('info')}</span><span>Duty state and GPS location are different signals. No unit live-location feed is connected to this prototype.</span></div><article class="surface-card"><div class="micro-label">RESPONDERS &amp; DRIVERS</div>${units.map(unit => `<div class="unit-card"><span class="unit-avatar">${unit.key === 'responder' ? 'RR' : unit.key === 'driver' ? 'JD' : '03'}</span><div class="unit-meta"><div class="unit-name">${escapeHtml(unit.name)}</div><div class="unit-role">${escapeHtml(unit.role)} · ${escapeHtml(unit.status)}</div></div><span class="badge ${unit.status === 'Available' ? 'good' : 'neutral'}">${unit.status === 'Available' ? 'Available' : unit.status === 'Off duty' ? 'Off duty' : 'No GPS'}</span></div>`).join('')}</article><article class="surface-card"><div class="micro-label">LOCATION FRESHNESS</div><p class="body-copy">No responder coordinates have been received. Map view will not show a live unit marker until a timestamped position is available.</p><button class="btn secondary full" data-action="page" data-page="map">${iconSvg('mapPin')}<span>Open units map</span></button></article>`;
  const dispatcherActivity = () => {
    const item = getActive();
    const events = item?.events || [];
    return `${greeting('Activity log')}<p class="phone-subtitle">Chronological prototype events. Production records must be server-generated and immutable.</p>${events.length ? `<article class="surface-card"><div class="micro-label">INCIDENT #${item.id} · ${escapeHtml(item.type.toUpperCase())}</div><div class="timeline-line" style="margin-top:16px">${events.map(ev => `<div class="timeline-entry">${escapeHtml(ev.title)}<span class="timeline-time">${escapeHtml(ev.time)} · ${escapeHtml(ev.role)}</span></div>`).join('')}</div></article>` : `<div class="surface-card empty-state"><div class="empty-icon">${iconSvg('clock')}</div><h2>No events yet</h2><p>Dispatch actions and incident status changes will appear here.</p></div>`}${state.queuedStatus ? `<div class="inline-alert"><span aria-hidden="true">${iconSvg('refresh')}</span><span>An offline status update is waiting for synchronization.</span></div>` : ''}`;
  };
  const profile = () => `${greeting('Profile')}<article class="surface-card"><div class="list-row"><span class="user-avatar">${currentRole().initials}</span><div class="list-main"><div class="list-title">${currentRole().name}</div><div class="list-description">${currentRole().title} · sample account</div></div></div></article>${state.role === 'responder' ? `<article class="surface-card"><div class="micro-label">DUTY STATUS</div><h2 style="margin-top:6px">${escapeHtml(state.duty)}</h2><p class="body-copy">${isPendingOrActive() && getActive()?.assignedTo === 'responder' ? 'Status is controlled by the active incident.' : 'Set yourself available on the Current Call screen.'}</p></article>` : ''}<article class="surface-card"><div class="micro-label">PRIVACY &amp; SAFETY</div><ul class="menu-list"><li><button class="menu-button" data-action="page" data-page="privacy"><span class="list-icon">${iconSvg('info')}</span><span>Privacy and data use</span><span class="chevron">›</span></button></li><li><button class="menu-button" data-action="location-help"><span class="list-icon">${iconSvg('mapPin')}</span><span>GPS permissions and freshness</span><span class="chevron">›</span></button></li><li><button class="menu-button" data-action="support"><span class="list-icon">?</span><span>Support / emergency contact</span><span class="chevron">›</span></button></li></ul></article><button class="btn tertiary full" data-action="sign-out">Sign out</button>`;
  const privacyPage = () => `${greeting('Privacy & safety')}<div class="inline-alert info-alert"><span aria-hidden="true">${iconSvg('info')}</span><span>This design preview does not contact dispatch and stores no real user data. These are proposed production disclosures, not legal terms.</span></div><article class="surface-card"><div class="micro-label">LOCATION SHARING</div><h2 style="margin-top:6px">Only share what is needed to respond</h2><p class="body-copy">A requester should see when location is collected, who can access it, how long it is retained, and how to stop sharing when tracking exists. This prototype records only a sample initial GPS fix.</p>${state.role === 'requester' ? `<button class="btn secondary full" data-action="consent-details">Review location consent</button>` : ''}</article><article class="surface-card"><div class="micro-label">DATA VISIBLE TO AUTHORIZED ROLES</div><div class="switch-row"><span>Incident type and description</span><span class="badge">While handling</span></div><div class="switch-row"><span>Requester contact details</span><span class="badge">Assigned response</span></div><div class="switch-row"><span>Incident coordinates</span><span class="badge">Dispatch + assigned unit</span></div><div class="switch-row"><span>Responder GPS history</span><span class="badge neutral">Not implemented</span></div></article><article class="surface-card"><div class="micro-label">EMERGENCY LIMITATIONS</div><p class="body-copy">TACResQ is a coordination aid, not a substitute for local emergency procedures or emergency services. Do not depend on this prototype for urgent help.</p><button class="btn tertiary full" data-action="support">Support and emergency contact details</button></article>`;
  let leafletMap = null;
  let mapTileLoadTimer = null;
  let activeMapCenter = [11.2446, 125.0035];
  const destroyMap = () => {
    if (mapTileLoadTimer) { clearTimeout(mapTileLoadTimer); mapTileLoadTimer = null; }
    if (leafletMap) { leafletMap.remove(); leafletMap = null; }
  };
  const initializeMap = () => {
    const host = document.querySelector('#actual-map');
    if (!host) return;
    const status = document.querySelector('#map-provider-status');
    const showMapError = (message) => {
      if (!status) return;
      status.textContent = message;
      status.classList.remove('is-hidden');
      status.classList.add('map-provider-status--error');
    };
    if (!window.L) { showMapError('Map library unavailable · check your internet connection'); return; }
    const item = getActive();
    activeMapCenter = item && item.status !== 'Cancelled' ? [Number(item.lat), Number(item.lng)] : [11.2446, 125.0035];
    leafletMap = L.map(host, { zoomControl: false, scrollWheelZoom: false, minZoom: 3, maxZoom: 19, preferCanvas: true }).setView(activeMapCenter, 15);
    L.control.scale({ metric: true, imperial: false, maxWidth: 100, position: 'bottomleft' }).addTo(leafletMap);
    let loadedTileCount = 0;
    let failedTileCount = 0;
    const tileLayer = L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
      maxZoom: 19,
      attribution: '&copy; <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener">OpenStreetMap contributors</a>',
      keepBuffer: 1,
    }).addTo(leafletMap);
    tileLayer.on('tileload', () => { loadedTileCount += 1; });
    tileLayer.on('tileerror', () => { failedTileCount += 1; });
    tileLayer.once('load', () => {
      if (mapTileLoadTimer) { clearTimeout(mapTileLoadTimer); mapTileLoadTimer = null; }
      if (loadedTileCount > 0 && status) status.classList.add('is-hidden');
      else if (failedTileCount > 0) showMapError('Street tiles unavailable · check your internet connection');
    });
    mapTileLoadTimer = setTimeout(() => {
      if (loadedTileCount === 0) showMapError('Street tiles unavailable · check your internet connection');
    }, 9000);
    if (item && item.status !== 'Cancelled') {
      const incidentIcon = L.divIcon({
        className: 'tacresq-leaflet-marker',
        html: '<span class="tacresq-map-pin" aria-hidden="true"><svg viewBox="0 0 24 24"><path d="M12 21s7-6.1 7-12a7 7 0 1 0-14 0c0 5.9 7 12 7 12Z"/><circle cx="12" cy="9" r="2.4"/></svg></span>',
        iconSize: [42, 46],
        iconAnchor: [21, 44],
        popupAnchor: [0, -40],
      });
      L.marker(activeMapCenter, { icon: incidentIcon, title: `Sample incident ${item.id}`, riseOnHover: true })
        .addTo(leafletMap)
        .bindPopup(`<strong>Sample incident #${escapeHtml(item.id)}</strong><br>${escapeHtml(item.type)} · ${escapeHtml(item.status)}<br><small>Sample point only · not a live GPS fix</small>`);
    }
    requestAnimationFrame(() => { if (leafletMap) leafletMap.invalidateSize({ pan: false }); });
  };
  const mapMarkup = () => {
    const item = getActive();
    const isDis = state.role === 'dispatcher';
    const mapTop = state.role === 'requester' ? 'YOUR REQUEST MAP' : state.role === 'responder' ? 'INCIDENT NAVIGATION' : 'INCIDENT & UNIT OVERVIEW';
    const warning = state.network === 'offline'
      ? 'SIMULATED OFFLINE STATE · incident updates are not verified'
      : state.gps === 'stale'
        ? 'GPS STALE STATE SIMULATED · this point is sample data'
        : isDis
          ? 'SAMPLE INCIDENT ONLY · live field-unit GPS is not connected'
          : 'SAMPLE LOCATION · device GPS is not accessed';
    const mapLegend = item && item.status !== 'Cancelled' ? `<div class="map-legend"><span><i class="legend-dot"></i> Sample incident point</span></div>` : '';
    const backPage = state.role === 'requester' ? 'home' : state.role === 'responder' ? 'call' : 'queue';
    const centerLabel = item && item.status !== 'Cancelled' ? 'Center on sample incident' : 'Center on Tacloban sample area';
    const context = state.role === 'requester'
      ? 'The street map is real, but this incident point is sample data. The prototype does not read device GPS or continuously track anyone.'
      : state.role === 'responder'
        ? 'The incident point is sample data. No live unit position, road route, distance, or ETA is connected.'
        : 'Only the sample incident point is shown. Duty status is not GPS; no live field-unit location feed is connected.';
    return `<section class="map-fullscreen" aria-label="Full-screen GPS map">
      <div class="map-screen-topbar"><button class="map-back" data-action="page" data-page="${backPage}" aria-label="Close map and return to ${backPage}" title="Close map">${iconSvg('arrowLeft')}</button><div class="map-screen-heading"><strong>GPS Map</strong><span>${mapTop}</span></div><span class="badge ${state.network === 'offline' || state.gps === 'stale' ? 'pending' : ''}">${state.network === 'offline' ? 'Simulated offline' : state.gps === 'stale' ? 'Simulated stale GPS' : 'Sample view'}</span></div>
      <div class="map-fullscreen-stage" role="region" aria-label="Interactive OpenStreetMap street map. The incident point is sample data.">
        <div id="actual-map" class="actual-map" aria-label="OpenStreetMap street tiles centered on the Tacloban sample area"></div>
        <div class="map-warning">${warning}</div>
        <div id="map-provider-status" class="map-provider-status" role="status" aria-live="polite">Loading OpenStreetMap street tiles…</div>
        <div class="map-tools" role="group" aria-label="Map controls">
          <button class="map-tool" data-action="map-zoom-in" aria-label="Zoom in" title="Zoom in">${iconSvg('plus')}</button>
          <button class="map-tool" data-action="map-zoom-out" aria-label="Zoom out" title="Zoom out">${iconSvg('minus')}</button>
          <span class="map-control-divider" aria-hidden="true"></span>
          <button class="map-tool" data-action="map-center" aria-label="${centerLabel}" title="${centerLabel}">${iconSvg('target')}</button>
        </div>
      </div>
      <div class="map-fullscreen-bottom"><div class="map-legend-row">${mapLegend}<span class="map-coordinate-note">${item && item.status !== 'Cancelled' ? `${item.lat}, ${item.lng} · sample only` : 'Tacloban · sample area'}</span></div><div class="map-sheet-card"><div class="map-sheet-copy"><span class="micro-label">${item && item.status !== 'Cancelled' ? `INCIDENT #${item.id} · SAMPLE POINT` : 'NO ACTIVE INCIDENT'}</span><h2>${item && item.status !== 'Cancelled' ? `${escapeHtml(item.type)} · ${escapeHtml(item.status)}` : 'Map is ready for an incident'}</h2><p>${context}</p></div>${state.role === 'responder' && item?.assignedTo === 'responder' ? '<button class="btn secondary full" data-action="external-nav">Open navigation app</button>' : state.role === 'dispatcher' ? '<button class="btn secondary full" data-action="page" data-page="units">Review field units</button>' : !item ? '<button class="btn secondary full" data-action="page" data-page="home">Create a request</button>' : ''}</div></div>
    </section>`;
  };
  const authPage = () => `<div class="surface-card" style="margin-top:55px"><div class="eyebrow-phone">ROLE-CONTROLLED ACCESS</div><h2 style="margin-top:8px">Sign in to TACResQ</h2><p class="body-copy">Sign in with an authorized account. Requester registration may be public; responder, driver, and dispatcher accounts must be provisioned by an administrator.</p><label class="field-label" for="auth-email">Email address</label><input class="text-field" id="auth-email" placeholder="name@example.org"/><label class="field-label" for="auth-password">Password</label><input class="text-field" id="auth-password" type="password" placeholder="••••••••"/><button class="btn full" data-action="demo-sign-in">Continue as ${escapeHtml(currentRole().title)} · demo</button><button class="btn tertiary full" data-action="page" data-page="privacy">Privacy policy &amp; terms</button><p class="phone-footer-note">Demo only · no credentials are sent or stored.</p></div>`;
  const contentByPage = () => ({ auth: authPage, home: requesterHome, requests: requesterRequests, profile: requesterProfile, call: responderCall, history: responderHistory, queue: dispatcherQueue, units: dispatcherUnits, activity: dispatcherActivity, map: mapMarkup, privacy: privacyPage })[state.page]?.() || requesterHome();
  const updateInspector = () => {
    const info = descriptions[state.page] || descriptions.home;
    document.querySelector('#screen-title').textContent = info[0];
    document.querySelector('#screen-description').textContent = info[1];
    document.querySelector('#screen-tags').innerHTML = info[2].map(tag => `<span class="tag">${escapeHtml(tag)}</span>`).join('');
    const incident = getActive();
    document.querySelector('#state-summary').innerHTML = [
      ['Role', roles[state.role].title], ['Screen', info[0]], ['Network', networkDescription()], ['GPS', gpsDescription()],
      ['Incident', incident ? `#${incident.id} · ${incident.status}` : state.queuedIncident ? 'Queued · not sent' : 'None'],
      ['Unit', state.duty],
    ].map(([key, value]) => `<div class="state-line"><span>${escapeHtml(key)}</span><strong>${escapeHtml(value)}</strong></div>`).join('');
  };
  const render = () => {
    destroyMap();
    document.querySelectorAll('.role-choice').forEach(button => button.classList.toggle('is-active', button.dataset.role === state.role));
    appRoot.innerHTML = state.page === 'map'
      ? `<div class="phone-app map-mode">${mapMarkup()}</div>`
      : phoneShell(contentByPage(), { noNav: state.page === 'auth' });
    updateInspector();
    if (state.page === 'map') initializeMap();
  };
  const modal = (content) => { modalRoot.innerHTML = `<div class="modal-backdrop" data-action="backdrop-close"><section class="modal" role="dialog" aria-modal="true">${content}</section></div>`; };
  const closeModal = () => { modalRoot.innerHTML = ''; };
  const openConsent = (after = 'send') => {
    modal(`<button class="modal-close" data-action="close-modal" aria-label="Close">×</button><div class="modal-icon">${iconSvg('mapPin')}</div><div class="eyebrow-phone">LOCATION PRIVACY</div><h2>How TACResQ uses your location</h2><p>Your location is attached to this emergency request so dispatch and the assigned response team can find the reported incident. This prototype captures one sample fix only; it does not continuously track you.</p><p>Production release should disclose retention, access, deletion/support, and tracking stop controls before asking for consent.</p><label class="check-line"><input type="checkbox" id="consent-check" ${state.consented ? 'checked' : ''}/><span>I understand that my location will be shared with dispatch and the assigned response team for this request.</span></label><button class="btn" data-action="accept-consent" data-after="${after}">Continue</button><button class="btn tertiary" data-action="close-modal">Not now</button>`);
  };
  const openSosReview = () => {
    state.description = document.querySelector('#request-details')?.value ?? state.description;
    if (isPendingOrActive() || state.queuedIncident) { showToast('There is already an active or unsent request. Avoid duplicate SOS reports.', 'error'); return; }
    if (state.gps === 'permission') { openLocationPermission(); return; }
    if (state.gps === 'unavailable' || state.gps === 'stale') { openGpsUnavailable(); return; }
    if (!state.consented) { openConsent('send'); return; }
    openConfirmation();
  };
  const openLocationPermission = () => modal(`<div class="modal-icon">${iconSvg('mapPin')}</div><div class="eyebrow-phone">LOCATION ACCESS</div><h2>Allow location for this request?</h2><p>Precise location helps responders find the reported incident. You can choose not now, but this SOS cannot be sent without an incident location in this prototype.</p><button class="btn" data-action="grant-location">Allow location for this request</button><button class="btn tertiary" data-action="deny-location">Not now</button>`);
  const openGpsUnavailable = () => modal(`<div class="modal-icon danger-icon">${iconSvg('alert')}</div><div class="eyebrow-phone">GPS NEEDS ATTENTION</div><h2>${state.gps === 'stale' ? 'Location fix is too old' : 'Could not get a location fix'}</h2><p>${state.gps === 'stale' ? 'The last known location is not recent enough to safely use for this request.' : 'Turn on location services or move to a place with a clearer sky view, then try again.'} Do not send a request with a guessed location.</p><button class="btn" data-action="retry-gps">Try to get a new GPS fix</button><button class="btn tertiary" data-action="close-modal">Go back</button>`);
  const openConfirmation = () => {
    const weak = state.gps === 'poor';
    modal(`<div class="modal-icon danger-icon">${iconSvg('alert')}</div><div class="eyebrow-phone">FINAL REVIEW</div><h2>Send emergency request?</h2><p><b>${escapeHtml(state.incidentType)} emergency</b>${state.description.trim() ? ` · ${escapeHtml(state.description.trim())}` : ''}</p><div class="location-summary"><div class="micro-label">LOCATION TO SHARE</div><div class="body-copy" style="font-weight:800;color:#294b55">${weak ? 'Approximate GPS fix · ±180 m' : 'Sample location · Tacloban City'}</div>${weak ? '<div class="subtle">Accuracy is low. Your location may be off by about 180 m.</div>' : '<div class="subtle">Only a one-time location fix is shown in this prototype.</div>'}</div>${weak ? '<div class="inline-alert"><span>!</span><span>If safe, move to an open area and retry for a more accurate fix.</span></div>' : ''}<p>Continue only if emergency help is needed now. In this preview, this action changes local demo state only.</p><button class="btn danger" data-action="confirm-sos">Send SOS</button><button class="btn tertiary" data-action="close-modal">Go back</button>`);
  };
  const startRequest = () => {
    closeModal();
    if (state.network !== 'online') {
      state.queuedIncident = { type: state.incidentType, description: state.description, created: 'Not sent · waiting for connection' };
      render(); showToast('Not sent. Dispatch has not received this request.', 'error'); return;
    }
    const item = { ...incidentSeed, id: String(1042 + state.completed.length), type: state.incidentType, description: state.description.trim(), status: 'Waiting for dispatch', accuracy: state.gps === 'poor' ? 180 : 18, created: 'Just now', events: [{ title: 'Emergency request submitted', time: 'Just now', role: 'Requester' }] };
    state.activeIncident = item;
    state.queuedIncident = null;
    render(); setTimeout(() => setPage('requests'), 20); showToast(`Demo request #${item.id} created locally. No real dispatch notified.`, 'success');
  };
  const cancelDialog = () => modal(`<div class="modal-icon danger-icon">${iconSvg('alert')}</div><div class="eyebrow-phone">CANCEL REQUEST</div><h2>Cancel incident #${getActive()?.id}?</h2><p>Cancellation may stop responders from continuing to the reported location. If this is a real emergency, contact dispatch directly and do not rely on this demo.</p><label class="field-label" for="cancel-reason">Reason <span class="subtle">Optional</span></label><select class="select-field" id="cancel-reason"><option>Request sent by mistake</option><option>Situation resolved</option><option>Location was incorrect</option><option>Other</option></select><button class="btn danger" data-action="confirm-cancel">Confirm cancellation</button><button class="btn tertiary" data-action="close-modal">Keep request active</button>`);
  const assignDialog = (unit) => modal(`<div class="modal-icon">${iconSvg('assign')}</div><div class="eyebrow-phone">DISPATCH ASSIGNMENT</div><h2>Confirm assignment</h2><p>Assign incident #${getActive()?.id} to ${unit === 'responder' ? 'Rey Responder' : 'Joaquin Driver'}?</p><div class="location-summary"><div class="micro-label">CHECK BEFORE ASSIGNING</div><div class="body-copy">Unit is shown as available in sample data. Confirm availability and response suitability through the approved dispatch procedure.</div></div><button class="btn" data-action="confirm-assign" data-unit="${unit}" ${state.network === 'offline' ? 'disabled' : ''}>Assign unit</button><button class="btn tertiary" data-action="close-modal">Cancel</button>`);
  const statusDialog = () => {
    const next = ({ 'Unit assigned': 'Unit en route', 'Unit en route': 'Unit on scene', 'Unit on scene': 'Transporting', 'Transporting': 'Resolved' })[getActive()?.status];
    if (!next) return;
    const resolve = next === 'Resolved';
    modal(`<div class="modal-icon ${resolve ? 'danger-icon' : ''}">${iconSvg(resolve ? 'check' : 'arrowUpRight')}</div><div class="eyebrow-phone">STATUS CONFIRMATION</div><h2>${resolve ? 'Mark this incident resolved?' : `Confirm ${next.toLowerCase()}?`}</h2><p>${resolve ? 'Confirm that the response is complete according to your unit procedure. This closes the active response in the demo.' : `This updates incident #${getActive()?.id} to “${next}” and records a status event in the demo.`}</p>${state.network !== 'online' ? '<div class="inline-alert"><span>↻</span><span>You are offline. The update will be shown as queued; dispatch will not see it until connectivity returns.</span></div>' : ''}<button class="btn ${resolve ? 'danger' : ''}" data-action="confirm-status">${resolve ? 'Confirm resolved' : 'Confirm status'}</button><button class="btn tertiary" data-action="close-modal">Go back</button>`);
  };
  const handleAction = (button, evt) => {
    const action = button.dataset.action;
    switch (action) {
      case 'page': setPage(button.dataset.page); break;
      case 'map-zoom-in': if (leafletMap) leafletMap.zoomIn(); break;
      case 'map-zoom-out': if (leafletMap) leafletMap.zoomOut(); break;
      case 'map-center': if (leafletMap) leafletMap.setView(activeMapCenter, 15); break;
      case 'type': state.incidentType = button.dataset.type; render(); break;
      case 'start-sos': openSosReview(); break;
      case 'location-help': openLocationPermission(); break;
      case 'consent-details': openConsent('details'); break;
      case 'accept-consent': {
        if (!document.querySelector('#consent-check')?.checked) { showToast('Please confirm you understand the location sharing first.', 'error'); return; }
        state.consented = true; closeModal();
        if (button.dataset.after === 'send') openConfirmation(); else showToast('Location sharing disclosure reviewed.'); break;
      }
      case 'grant-location': state.gps = 'ready'; state.locationAllowed = true; closeModal(); showToast('Demo permission granted. Confirm an emergency request to continue.', 'success'); render(); break;
      case 'deny-location': state.gps = 'permission'; closeModal(); showToast('No request was sent. Location permission is not enabled.', 'error'); break;
      case 'retry-gps': {
        state.gpsRetry += 1;
        if (state.gps === 'unavailable' && state.gpsRetry % 2 === 1) { state.gps = 'ready'; state.locationAllowed = true; }
        else if (state.gps === 'stale') state.gps = 'ready';
        closeModal(); render(); showToast(state.gps === 'ready' ? 'A new sample GPS fix is ready.' : 'Still no GPS fix. Check location services and try again.', state.gps === 'ready' ? 'success' : 'error'); break;
      }
      case 'confirm-sos': startRequest(); break;
      case 'retry-sos': {
        if (!state.queuedIncident) break;
        if (state.network !== 'online') { showToast('Still offline. The request has not been sent.', 'error'); break; }
        state.incidentType = state.queuedIncident.type; state.description = state.queuedIncident.description; openConfirmation(); break;
      }
      case 'cancel-request': cancelDialog(); break;
      case 'confirm-cancel': {
        const reason = document.querySelector('#cancel-reason')?.value || 'Cancelled';
        if (state.network !== 'online') { closeModal(); showToast('Cancellation not sent. Contact dispatch by your approved channel.', 'error'); break; }
        const item = state.activeIncident; item.status = 'Cancelled'; item.events.unshift({ title: `Request cancelled · ${reason}`, time: 'Just now', role: 'Requester' }); state.completed.unshift(item); state.activeIncident = null; closeModal(); render(); showToast(`Demo incident #${item.id} cancelled.`, 'success'); break;
      }
      case 'start-assign': if (state.network !== 'online') showToast('Cannot assign while offline.', 'error'); else assignDialog(button.dataset.unit); break;
      case 'confirm-assign': {
        const item = getActive(); if (!item || item.status !== 'Waiting for dispatch' || state.network !== 'online') { closeModal(); showToast('Assignment could not be completed. Refresh and check unit availability.', 'error'); break; }
        item.assignedTo = button.dataset.unit; item.status = 'Unit assigned'; item.events.unshift({ title: `Assigned to ${button.dataset.unit === 'responder' ? 'Rey Responder' : 'Joaquin Driver'}`, time: 'Just now', role: 'Dispatcher' }); state.duty = 'Available'; closeModal(); render(); showToast('Demo assignment recorded. No real unit was notified.', 'success'); break;
      }
      case 'duty': {
        if (getActive()?.assignedTo === 'responder' && getActive().status !== 'Resolved') { showToast('Duty status follows your active call.', 'error'); return; }
        if (state.network !== 'online') { showToast('Status not updated: no network connection.', 'error'); return; }
        state.duty = button.dataset.value; render(); showToast(`Demo duty status set to ${state.duty}.`, 'success'); break;
      }
      case 'advance-status': statusDialog(); break;
      case 'confirm-status': {
        const item = getActive(); if (!item) { closeModal(); break; }
        const next = ({ 'Unit assigned': 'Unit en route', 'Unit en route': 'Unit on scene', 'Unit on scene': 'Transporting', 'Transporting': 'Resolved' })[item.status];
        if (!next) { closeModal(); break; }
        if (state.network !== 'online') { state.queuedStatus = next; closeModal(); render(); showToast(`Not synced: ${next}. Dispatch has not received the update.`, 'error'); break; }
        item.status = next; item.events.unshift({ title: `Status changed to ${next}`, time: 'Just now', role: 'Responder' }); state.queuedStatus = null;
        if (next === 'Unit en route') state.duty = 'En route'; if (next === 'Unit on scene') state.duty = 'On scene'; if (next === 'Transporting') state.duty = 'Transporting';
        if (next === 'Resolved') { state.duty = 'Available'; state.completed.unshift(item); state.activeIncident = null; }
        closeModal(); render(); showToast(next === 'Resolved' ? 'Demo incident resolved; no external system was updated.' : `Demo status updated: ${next}.`, 'success'); break;
      }
      case 'cannot-respond': modal(`<div class="modal-icon danger-icon">${iconSvg('alert')}</div><div class="eyebrow-phone">SAFETY FIRST</div><h2>Unable to respond safely?</h2><p>Do not continue if the scene or route is unsafe. Contact dispatch through its approved channel for reassignment. This preview has no live dispatcher connection.</p><button class="btn secondary" data-action="close-modal">Understood</button>`); break;
      case 'external-nav': modal(`<div class="modal-icon">${iconSvg('arrowUpRight')}</div><div class="eyebrow-phone">NAVIGATION HANDOFF</div><h2>Open a navigation app?</h2><p>In the real Android app, this opens a maps app with the incident's confirmed coordinates. This HTML prototype cannot launch phone navigation and uses sample coordinates only.</p><button class="btn" data-action="close-modal">Close preview</button>`); break;
      case 'support': modal(`<div class="modal-icon">${iconSvg('info')}</div><div class="eyebrow-phone">SUPPORT &amp; EMERGENCY CONTACT</div><h2>Contact details need verification</h2><p>Do not publish a guessed emergency number. Configure an approved dispatch phone, operating hours, escalation instructions, and support contact before release.</p><button class="btn secondary" data-action="close-modal">Close</button>`); break;
      case 'sign-out': modal(`<div class="modal-icon">${iconSvg('signOut')}</div><div class="eyebrow-phone">SIGN OUT</div><h2>End this demo session?</h2><p>This only returns to the sample sign-in screen. It does not sign out of a real account.</p><button class="btn" data-action="confirm-sign-out">Sign out</button><button class="btn tertiary" data-action="close-modal">Stay signed in</button>`); break;
      case 'confirm-sign-out': closeModal(); state.role = 'requester'; state.page = 'auth'; render(); break;
      case 'close-modal': closeModal(); break;
      case 'backdrop-close': if (evt?.target === button) closeModal(); break;
      case 'demo-sign-in': state.page = ({ requester: 'home', responder: 'call', dispatcher: 'queue' })[state.role]; render(); showToast(`Demo sign-in as ${currentRole().title}. No account was authenticated.`); break;
      default: break;
    }
  };
  document.addEventListener('click', event => {
    const button = event.target.closest('[data-action]');
    if (button) { handleAction(button, event); return; }
    const role = event.target.closest('[data-role]');
    if (role) setRole(role.dataset.role);
  });
  document.addEventListener('input', event => {
    if (event.target.id === 'request-details') state.description = event.target.value;
  });
  document.querySelector('#network-mode').addEventListener('change', event => {
    state.network = event.target.value;
    if (state.network === 'online' && state.queuedStatus) {
      const item = getActive(); const next = state.queuedStatus;
      if (item && next) {
        item.status = next; item.events.unshift({ title: `Queued status synchronized: ${next}`, time: 'Just now', role: 'Responder' });
        if (next === 'Resolved') { state.duty = 'Available'; state.completed.unshift(item); state.activeIncident = null; }
        state.queuedStatus = null; showToast('Queued demo status synchronized.');
      }
    }
    render();
  });
  document.querySelector('#gps-mode').addEventListener('change', event => {
    state.gps = event.target.value;
    state.locationAllowed = state.gps !== 'permission';
    render();
  });
  document.querySelector('#reset-prototype').addEventListener('click', () => {
    closeModal();
    state.role = 'requester'; state.page = 'home'; state.network = 'online'; state.gps = 'ready'; state.consented = false; state.locationAllowed = true;
    state.activeIncident = null; state.completed = []; state.queuedIncident = null; state.queuedStatus = null; state.duty = 'Available'; state.incidentType = 'Medical'; state.description = ''; state.gpsRetry = 0;
    document.querySelector('#network-mode').value = 'online'; document.querySelector('#gps-mode').value = 'ready'; render(); showToast('Prototype scenario reset.');
  });
  state.locationAllowed = true; // The initial screenshot state represents a device where location permission was already granted.
  render();
})();
