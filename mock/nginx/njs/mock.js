// Mocked user service.
//   GET    /api/users/{id}              → stored user (1042 is seeded), 404 otherwise
//   PUT    /api/users/{id}              → replace user, echo body
//   PATCH  /api/users/{id}/preferences  → {theme, smsEnabled, pushEnabled, updatedBy} into the user, 204
//   DELETE /api/users/{id}/sessions     → 204 (404 for unknown user)
//   POST   /api/contacts                → 201 {id, ...body}
//   (customers + snapshots require Authorization: Bearer <jwt>, else 401)
//   GET    /api/customers/{id}/{resource} → one of 10 resource objects of customer 501, 404 otherwise
//          resources: profile contact address preferences account orders loyalty billing shipping consents
//   POST   /api/customer-snapshots      → 201 {id, ...body}
//   GET    /__mock/calls                → every request received (method, uri, args, headers, body)
//   DELETE /__mock/calls                → reset all state

const SEED = {
  '1042': {
    user: {
      id: 1042,
      name: 'Maya Lin',
      contact: { email: 'maya.lin@example.com', phone: '+1-555-0192' },
      preferences: { theme: 'light', notifications: { email: true, sms: false } },
      internalScore: 99
    }
  }
};

// 10 different objects of one customer. Each has noise (_links, internal fields) the target schema
// drops; "loyalty" also carries account.tier, overriding the "account" response (later source wins).
const CUSTOMER_ID = '501';
const CUSTOMER_RESOURCES = {
  profile: { customer: { id: 501, firstName: 'Noa', lastName: 'Levi', birthDate: '1990-04-12', internalNotes: 'vip' },
             _links: { self: '/api/customers/501/profile' } },
  contact: { customer: { email: 'noa.levi@example.com', phone: '+972-50-555-0101' },
             _links: { self: '/api/customers/501/contact' } },
  address: { address: { street: 'Herzl 10', city: 'Tel Aviv', zip: '6100000', country: 'IL', geo: { lat: 32.06, lng: 34.77 } },
             _links: { self: '/api/customers/501/address' } },
  preferences: { preferences: { language: 'he', currency: 'ILS', newsletter: true, uiDensity: 'compact' },
                 _links: { self: '/api/customers/501/preferences' } },
  account: { account: { status: 'active', tier: 'gold', openedAt: '2019-02-01' },
             _links: { self: '/api/customers/501/account' } },
  orders: { orders: { count: 42, lastOrderId: 'o-9001', totalSpent: '1534.50' },
            _links: { self: '/api/customers/501/orders' } },
  loyalty: { loyalty: { points: 1280, level: 'gold', expiresAt: '2027-01-01' }, account: { tier: 'platinum' },
             _links: { self: '/api/customers/501/loyalty' } },
  billing: { billing: { method: 'card', last4: '4242', cardToken: 'tok_secret' },
             _links: { self: '/api/customers/501/billing' } },
  shipping: { shipping: { preferredCarrier: 'DHL', instructions: 'leave at door' },
              _links: { self: '/api/customers/501/shipping' } },
  consents: { consents: { marketing: true, analytics: false, updatedAt: '2026-10-01T09:00:00Z' },
              _links: { self: '/api/customers/501/consents' } }
};

const RECORDED_HEADERS = ['Content-Type', 'Accept', 'X-Tenant', 'X-Correlation-Id', 'Authorization'];

// customer endpoints need 'Authorization: Bearer <jwt>' (shape only: header.payload.signature, not verified)
const JWT = /^Bearer [A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]*$/;

function authorized(r) { return JWT.test(r.headersIn['Authorization'] || ''); }

function store() { return ngx.shared.mock; }

function getJson(key, fallback) {
  const v = store().get(key);
  return v === undefined ? fallback : JSON.parse(v);
}

function setJson(key, value) { store().set(key, JSON.stringify(value)); }

function getUser(id) {
  const user = getJson('user:' + id, undefined);
  if (user !== undefined) return user;
  if (SEED[id] && !store().get('deleted:' + id)) {
    setJson('user:' + id, SEED[id]);
    return SEED[id];
  }
  return undefined;
}

function record(r, body) {
  const headers = {};
  RECORDED_HEADERS.forEach(function (h) {
    if (r.headersIn[h] !== undefined) headers[h] = r.headersIn[h];
  });
  const calls = getJson('calls', []);
  calls.push({ method: r.method, uri: r.uri, args: r.variables.args || null, headers: headers, body: body });
  setJson('calls', calls);
}

function send(r, status, obj) {
  if (obj === undefined) {
    r.return(status);
  } else {
    r.headersOut['Content-Type'] = 'application/json';
    r.return(status, JSON.stringify(obj));
  }
}

function parseBody(r) {
  const text = r.requestText;
  if (!text) return null;
  try { return JSON.parse(text); } catch (e) { return text; }
}

function handle(r) {
  const body = parseBody(r);
  let m;

  // ---- mock control
  if (r.uri === '/__mock/calls') {
    if (r.method === 'GET') return send(r, 200, getJson('calls', []));
    if (r.method === 'DELETE') { store().clear(); return send(r, 204); }
    return send(r, 405, { message: 'method not allowed' });
  }

  record(r, body);

  // ---- /api/users/{id}
  if ((m = r.uri.match(/^\/api\/users\/([^/]+)$/))) {
    const id = m[1];
    if (r.method === 'GET') {
      const user = getUser(id);
      return user ? send(r, 200, user) : send(r, 404, { message: 'no such user ' + id });
    }
    if (r.method === 'PUT') {
      if (!body || typeof body !== 'object' || !body.user) return send(r, 400, { message: 'body.user required' });
      if (String(body.user.id) !== id) return send(r, 409, { message: 'id mismatch' });
      setJson('user:' + id, body);
      return send(r, 200, body);
    }
    return send(r, 405, { message: 'method not allowed' });
  }

  // ---- /api/users/{id}/preferences
  if ((m = r.uri.match(/^\/api\/users\/([^/]+)\/preferences$/))) {
    if (r.method !== 'PATCH') return send(r, 405, { message: 'method not allowed' });
    const id = m[1];
    const user = getUser(id);
    if (!user) return send(r, 404, { message: 'no such user ' + id });
    if (!body || typeof body !== 'object') return send(r, 400, { message: 'json body required' });
    const prefs = user.user.preferences || (user.user.preferences = { notifications: {} });
    prefs.notifications = prefs.notifications || {};
    if (body.theme !== undefined) prefs.theme = body.theme;
    if (body.smsEnabled !== undefined) prefs.notifications.sms = body.smsEnabled;
    if (body.pushEnabled !== undefined) prefs.notifications.push = body.pushEnabled;
    if (body.updatedBy !== undefined) user.user.updatedBy = body.updatedBy;
    setJson('user:' + id, user);
    return send(r, 204);
  }

  // ---- /api/users/{id}/sessions
  if ((m = r.uri.match(/^\/api\/users\/([^/]+)\/sessions$/))) {
    if (r.method !== 'DELETE') return send(r, 405, { message: 'method not allowed' });
    return getUser(m[1]) ? send(r, 204) : send(r, 404, { message: 'no such user ' + m[1] });
  }

  // ---- /api/contacts
  if (r.uri === '/api/contacts') {
    if (r.method !== 'POST') return send(r, 405, { message: 'method not allowed' });
    if (!body || typeof body !== 'object') return send(r, 400, { message: 'json body required' });
    const seq = (getJson('contactSeq', 0)) + 1;
    setJson('contactSeq', seq);
    const contact = Object.assign({ id: 'c-' + seq }, body);
    setJson('contact:' + contact.id, contact);
    return send(r, 201, contact);
  }

  // ---- /api/customers/{id}/{resource}
  if ((m = r.uri.match(/^\/api\/customers\/([^/]+)\/([a-z]+)$/))) {
    if (!authorized(r)) return send(r, 401, { message: 'bearer token required' });
    if (r.method !== 'GET') return send(r, 405, { message: 'method not allowed' });
    const resource = CUSTOMER_RESOURCES[m[2]];
    if (m[1] !== CUSTOMER_ID || !resource) return send(r, 404, { message: 'no ' + m[2] + ' for customer ' + m[1] });
    return send(r, 200, resource);
  }

  // ---- /api/customer-snapshots
  if (r.uri === '/api/customer-snapshots') {
    if (!authorized(r)) return send(r, 401, { message: 'bearer token required' });
    if (r.method !== 'POST') return send(r, 405, { message: 'method not allowed' });
    if (!body || typeof body !== 'object') return send(r, 400, { message: 'json body required' });
    const seq = (getJson('snapshotSeq', 0)) + 1;
    setJson('snapshotSeq', seq);
    return send(r, 201, Object.assign({ id: 's-' + seq }, body));
  }

  return send(r, 404, { message: 'no route for ' + r.method + ' ' + r.uri });
}

export default { handle };
