"""Member-only MCP credentials and OAuth 2.1 authorization.

All credentials are opaque, hashed at rest, revocable, and bound to this MCP
resource. Browser sessions are used only for consent and token management;
the MCP endpoint always requires its own bearer token.
"""
import base64
import hashlib
import hmac
import html
import json
import os
import re
import secrets
import time
import uuid
from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit

from fastapi import Depends, HTTPException, Request
from fastapi.responses import HTMLResponse, JSONResponse, RedirectResponse
from pydantic import BaseModel, ConfigDict, Field, StrictInt

SCOPE = 'forma:mcp'
ACCESS_TTL = 3600
REFRESH_TTL = 30 * 86400
REQUEST_TTL = 600
CODE_TTL = 120
OAUTH_COOKIE = 'forma_oauth_browser'


def digest(value):
    return hashlib.sha256(value.encode()).hexdigest()


def public_url():
    value = os.getenv('FORMA_PUBLIC_URL', 'http://127.0.0.1:8000').rstrip('/')
    parsed = urlsplit(value)
    if (parsed.scheme not in ('http', 'https') or not parsed.hostname or
            parsed.username or parsed.password or parsed.query or parsed.fragment or parsed.path or
            (parsed.scheme == 'http' and parsed.hostname not in ('localhost', '127.0.0.1', '::1'))):
        raise ValueError('FORMA_PUBLIC_URL must be an HTTPS origin, or an HTTP loopback origin for local development.')
    return value


def resource_url():
    return public_url() + '/mcp'


def init_auth_db(server):
    """Call after the application's core database transaction has committed."""
    with server.connect() as con:
        con.executescript('''
        CREATE TABLE IF NOT EXISTS mcp_clients(
            id TEXT PRIMARY KEY, name TEXT NOT NULL, redirects TEXT NOT NULL,
            auth_method TEXT NOT NULL, secret_hash TEXT, created TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS mcp_grants(
            id TEXT PRIMARY KEY, account TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
            client TEXT NOT NULL REFERENCES mcp_clients(id) ON DELETE CASCADE,
            audience TEXT NOT NULL, created TEXT NOT NULL, revoked REAL);
        CREATE TABLE IF NOT EXISTS mcp_tokens(
            id TEXT PRIMARY KEY, token_hash TEXT UNIQUE NOT NULL,
            account TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
            name TEXT NOT NULL, kind TEXT NOT NULL, audience TEXT NOT NULL,
            created TEXT NOT NULL, expires REAL NOT NULL, revoked REAL,
            last_used REAL, grant_id TEXT REFERENCES mcp_grants(id) ON DELETE CASCADE);
        CREATE TABLE IF NOT EXISTS mcp_auth_requests(
            id TEXT PRIMARY KEY, data TEXT NOT NULL, csrf_hash TEXT NOT NULL,
            browser_hash TEXT NOT NULL, expires REAL NOT NULL);
        CREATE TABLE IF NOT EXISTS mcp_codes(
            code_hash TEXT PRIMARY KEY, account TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
            client TEXT NOT NULL REFERENCES mcp_clients(id) ON DELETE CASCADE,
            redirect_uri TEXT NOT NULL, challenge TEXT NOT NULL, audience TEXT NOT NULL,
            grant_id TEXT NOT NULL REFERENCES mcp_grants(id) ON DELETE CASCADE,
            expires REAL NOT NULL, used REAL);
        CREATE TABLE IF NOT EXISTS mcp_refresh_tokens(
            token_hash TEXT PRIMARY KEY, grant_id TEXT NOT NULL REFERENCES mcp_grants(id) ON DELETE CASCADE,
            expires REAL NOT NULL, used REAL);
        CREATE INDEX IF NOT EXISTS mcp_tokens_account ON mcp_tokens(account);
        ''')


def resolve_mcp_token(server, token):
    if not token or len(token) > 512:
        raise HTTPException(401, 'A valid MCP bearer token is required.')
    with server.connect() as con:
        row = con.execute('''SELECT a.*, t.id AS mcp_token_id FROM mcp_tokens t
            JOIN accounts a ON a.id=t.account
            LEFT JOIN mcp_grants g ON g.id=t.grant_id
            WHERE t.token_hash=? AND t.revoked IS NULL AND t.expires>?
            AND t.audience=? AND a.role='user'
            AND (t.grant_id IS NULL OR (g.revoked IS NULL AND g.audience=t.audience))''',
            (digest(token), time.time(), resource_url())).fetchone()
        if not row:
            raise HTTPException(401, 'Your MCP token is invalid, expired, or revoked.')
        con.execute('UPDATE mcp_tokens SET last_used=? WHERE id=?', (time.time(), row['mcp_token_id']))
    return {key: row[key] for key in ('id', 'email', 'name', 'role', 'created')}


def check_origin(request):
    origin = request.headers.get('origin')
    allowed = {public_url(), os.getenv('FRONTEND_URL', 'http://localhost:5173').rstrip('/')}
    if origin and origin.rstrip('/') not in allowed:
        raise HTTPException(403, 'This origin is not allowed.')


def require_member_session(server, request):
    account = server.resolve_session(request.cookies.get(server.COOKIE))
    if account['role'] != 'user' or request.cookies.get('forma_admin'):
        raise HTTPException(403, 'MCP connections require a member signing in to their own account.')
    return account


class TokenRequest(BaseModel):
    model_config = ConfigDict(extra='forbid')
    name: str = Field(min_length=1, max_length=80)
    expires_days: StrictInt = Field(default=90, ge=1, le=365)


class ClientRegistration(BaseModel):
    model_config = ConfigDict(extra='ignore')
    redirect_uris: list[str] = Field(min_length=1, max_length=16)
    client_name: str = Field(default='MCP client', min_length=1, max_length=160)
    token_endpoint_auth_method: str = 'none'
    grant_types: list[str] = Field(default_factory=lambda: ['authorization_code', 'refresh_token'])
    response_types: list[str] = Field(default_factory=lambda: ['code'])
    scope: str = SCOPE


def valid_redirect(value):
    if len(value) > 2048 or any(ord(c) < 32 for c in value):
        return False
    try:
        parsed = urlsplit(value)
        parsed.port
    except ValueError:
        return False
    return bool(parsed.hostname and not parsed.username and not parsed.password and not parsed.fragment and
                (parsed.scheme == 'https' or
                 (parsed.scheme == 'http' and parsed.hostname in ('localhost', '127.0.0.1', '::1'))))


def oauth_error(error, description, status=400):
    return JSONResponse({'error': error, 'error_description': description}, status_code=status,
                        headers={'Cache-Control': 'no-store', 'Pragma': 'no-cache'})


async def oauth_form(request):
    """OAuth bodies are small URL-encoded forms, never image/file uploads."""
    if request.headers.get('content-type','').split(';',1)[0].strip().lower() != 'application/x-www-form-urlencoded':
        raise HTTPException(400, 'Use application/x-www-form-urlencoded for OAuth requests.')
    body = bytearray()
    async for chunk in request.stream():
        body.extend(chunk)
        if len(body) > 16 * 1024:
            raise HTTPException(413, 'OAuth request body is too large.')
    try:
        entries = parse_qsl(bytes(body).decode('utf-8'),keep_blank_values=True,max_num_fields=12)
    except (ValueError,UnicodeError):
        raise HTTPException(400, 'Invalid OAuth request form.') from None
    form = dict(entries)
    if len(form) != len(entries):
        raise HTTPException(400, 'Duplicate OAuth parameters are not allowed.')
    return form


def redirect_result(uri, **params):
    parsed = urlsplit(uri)
    query = parse_qsl(parsed.query, keep_blank_values=True) + [(k, v) for k, v in params.items() if v is not None]
    return RedirectResponse(urlunsplit(parsed._replace(query=urlencode(query))), status_code=303,
                            headers={'Cache-Control': 'no-store', 'Referrer-Policy': 'no-referrer'})


def authenticate_client(con, request, form):
    client_id = form.get('client_id', '')
    secret = form.get('client_secret', '')
    basic = request.headers.get('authorization', '')
    using_basic = basic.lower().startswith('basic ')
    if using_basic:
        try:
            decoded = base64.b64decode(basic[6:], validate=True).decode()
            basic_id, secret = decoded.split(':', 1)
            from urllib.parse import unquote
            basic_id, secret = unquote(basic_id), unquote(secret)
            if client_id and client_id != basic_id:
                return None
            client_id = basic_id
        except (ValueError, UnicodeError):
            return None
    client = con.execute('SELECT * FROM mcp_clients WHERE id=?', (client_id,)).fetchone()
    if not client:
        return None
    if client['auth_method'] == 'none':
        return client if not secret and not using_basic else None
    expected_basic = client['auth_method'] == 'client_secret_basic'
    if using_basic != expected_basic or not secret or not hmac.compare_digest(digest(secret), client['secret_hash']):
        return None
    return client


def issue_oauth_tokens(con, server, grant):
    access = 'forma_oauth_' + secrets.token_urlsafe(40)
    refresh = 'forma_refresh_' + secrets.token_urlsafe(40)
    identifier = str(uuid.uuid4())
    con.execute('''INSERT INTO mcp_tokens(id,token_hash,account,name,kind,audience,created,expires,grant_id)
        VALUES(?,?,?,?,?,?,?,?,?)''', (identifier,digest(access),grant['account'],'OAuth connection','oauth',
                                      grant['audience'],server.now(),time.time()+ACCESS_TTL,grant['id']))
    con.execute('INSERT INTO mcp_refresh_tokens VALUES(?,?,?,NULL)',
                (digest(refresh),grant['id'],time.time()+REFRESH_TTL))
    return {'access_token':access,'token_type':'Bearer','expires_in':ACCESS_TTL,
            'refresh_token':refresh,'scope':SCOPE}


def consent_page(identifier, csrf, client_name, redirect_uri, account=None, message=''):
    esc = html.escape
    identity = ('<p>Signed in as <strong>' + esc(account['email']) + '</strong>.</p>') if account else '''
        <label>Email <input type="email" name="email" required autocomplete="username"></label>
        <label>Password <input type="password" name="password" required autocomplete="current-password"></label>'''
    return HTMLResponse('''<!doctype html><html lang="en"><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1"><title>Connect to Forma</title>
        <style>body{font:17px system-ui;background:#f2f4ef;color:#223329;margin:0;padding:5vh 20px}
        main{max-width:520px;margin:auto;background:white;padding:32px;border-radius:20px}
        label{display:block;margin:16px 0}input{display:block;width:100%;box-sizing:border-box;padding:10px}
        button{padding:12px 20px;margin-right:8px;border:0;border-radius:8px;background:#28523b;color:white}
        .cancel{background:#e7ebe5;color:#223329}.error{color:#a32323}small{overflow-wrap:anywhere}</style>
        <main><h1>Connect to Forma</h1><p><strong>''' + esc(client_name) + '''</strong> requests access to
        your personal plan, meal and workout tracking, progress photos, and feedback. It can regenerate up to
        28 days, mark tasks, upload photos, and request photo analysis.</p><p>Approve only an agent you trust.
        You can revoke this connection in Forma Settings.</p><small>Return address: ''' + esc(redirect_uri) +
        '''</small><p class="error">''' + esc(message) + '''</p>
        <form method="post" action="/oauth/authorize"><input type="hidden" name="request_id" value="''' +
        esc(identifier) + '''"><input type="hidden" name="csrf" value="''' + esc(csrf) + '''">''' + identity + '''
        <button name="decision" value="approve">Connect</button>
        <button name="decision" value="deny" class="cancel" formnovalidate>Cancel</button></form></main></html>''',
        headers={'Cache-Control':'no-store','Content-Security-Policy':"default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'",
                 'Referrer-Policy':'no-referrer','X-Content-Type-Options':'nosniff'})


def install_auth_routes(app, server):
    """Register settings endpoints and same-server OAuth discovery/consent."""
    def member(request: Request):
        return require_member_session(server, request)

    @app.get('/api/mcp/tokens')
    def tokens(account=Depends(member)):
        with server.connect() as con:
            rows = con.execute('''SELECT id,name,created,expires,revoked,last_used FROM mcp_tokens
                WHERE account=? AND kind='pat' ORDER BY created DESC''', (account['id'],)).fetchall()
        return [dict(r) for r in rows]

    @app.post('/api/mcp/tokens', status_code=201)
    def create_token(data: TokenRequest, request: Request, account=Depends(member)):
        check_origin(request)
        name = data.name.strip()
        if not name:
            raise HTTPException(422, 'Give this connection a name.')
        identifier, token = str(uuid.uuid4()), 'forma_pat_' + secrets.token_urlsafe(40)
        created, expires = server.now(), time.time() + data.expires_days * 86400
        with server.connect() as con:
            con.execute('''INSERT INTO mcp_tokens(id,token_hash,account,name,kind,audience,created,expires)
                VALUES(?,?,?,?,?,?,?,?)''', (identifier,digest(token),account['id'],name,'pat',resource_url(),created,expires))
        return JSONResponse({'id':identifier,'name':name,'token':token,'created':created,'expires':expires},
                            status_code=201, headers={'Cache-Control':'no-store'})

    @app.delete('/api/mcp/tokens/{identifier}')
    def revoke_token(identifier: str, request: Request, account=Depends(member)):
        check_origin(request)
        with server.connect() as con:
            cursor = con.execute("UPDATE mcp_tokens SET revoked=? WHERE id=? AND account=? AND kind='pat'",
                                 (time.time(),identifier,account['id']))
            if not cursor.rowcount:
                raise HTTPException(404, 'MCP connection not found.')
        return {'saved':True}

    @app.get('/api/mcp/connections')
    def connections(account=Depends(member)):
        with server.connect() as con:
            rows = con.execute('''SELECT g.id,c.name,g.created,g.revoked FROM mcp_grants g
                JOIN mcp_clients c ON c.id=g.client WHERE g.account=? ORDER BY g.created DESC''', (account['id'],)).fetchall()
        return [dict(r) for r in rows]

    @app.delete('/api/mcp/connections/{identifier}')
    def revoke_connection(identifier: str, request: Request, account=Depends(member)):
        check_origin(request)
        with server.connect() as con:
            cursor = con.execute('UPDATE mcp_grants SET revoked=? WHERE id=? AND account=?',
                                 (time.time(),identifier,account['id']))
            if not cursor.rowcount:
                raise HTTPException(404, 'OAuth connection not found.')
        return {'saved':True}

    @app.get('/.well-known/oauth-protected-resource')
    @app.get('/.well-known/oauth-protected-resource/mcp')
    def resource_metadata():
        return {'resource':resource_url(),'authorization_servers':[public_url()],
                'scopes_supported':[SCOPE],'bearer_methods_supported':['header'],'resource_name':'Forma personal plan'}

    @app.get('/.well-known/oauth-authorization-server')
    def authorization_metadata():
        base = public_url()
        return {'issuer':base,'authorization_endpoint':base+'/oauth/authorize','token_endpoint':base+'/oauth/token',
                'registration_endpoint':base+'/oauth/register','revocation_endpoint':base+'/oauth/revoke',
                'response_types_supported':['code'],'grant_types_supported':['authorization_code','refresh_token'],
                'token_endpoint_auth_methods_supported':['none','client_secret_post','client_secret_basic'],
                'revocation_endpoint_auth_methods_supported':['none','client_secret_post','client_secret_basic'],
                'code_challenge_methods_supported':['S256'],'scopes_supported':[SCOPE]}

    @app.post('/oauth/register', status_code=201)
    def register_client(data: ClientRegistration):
        if any(not valid_redirect(uri) for uri in data.redirect_uris):
            return oauth_error('invalid_redirect_uri', 'Use exact HTTPS or loopback redirect URLs without fragments.')
        if (data.token_endpoint_auth_method not in ('none','client_secret_post','client_secret_basic') or
                set(data.grant_types) - {'authorization_code','refresh_token'} or
                'authorization_code' not in data.grant_types or data.response_types != ['code'] or data.scope != SCOPE):
            return oauth_error('invalid_client_metadata', 'Use authorization_code with S256 PKCE and scope forma:mcp.')
        client_id = str(uuid.uuid4())
        secret = secrets.token_urlsafe(40) if data.token_endpoint_auth_method != 'none' else None
        with server.connect() as con:
            con.execute('INSERT INTO mcp_clients VALUES(?,?,?,?,?,?)',
                        (client_id,data.client_name,json.dumps(data.redirect_uris),data.token_endpoint_auth_method,
                         digest(secret) if secret else None,server.now()))
        result = {**data.model_dump(),'client_id':client_id,'client_id_issued_at':int(time.time())}
        if secret:
            result.update(client_secret=secret, client_secret_expires_at=0)
        return JSONResponse(result,status_code=201,headers={'Cache-Control':'no-store'})

    @app.get('/oauth/authorize')
    def authorize(request: Request):
        query = request.query_params
        if any(len(query.getlist(key)) != 1 for key in ('client_id','redirect_uri','response_type','code_challenge','code_challenge_method','resource')):
            return oauth_error('invalid_request', 'Supply one client, redirect URI, resource and S256 PKCE challenge.')
        with server.connect() as con:
            client = con.execute('SELECT * FROM mcp_clients WHERE id=?', (query['client_id'],)).fetchone()
        if not client or query['redirect_uri'] not in json.loads(client['redirects']):
            return oauth_error('invalid_request', 'The client or exact redirect URI is not registered.')
        if query['resource'].rstrip('/') != resource_url():
            return oauth_error('invalid_target', 'The resource must be this Forma MCP endpoint.')
        if (query['response_type'] != 'code' or query['code_challenge_method'] != 'S256' or
                not re.fullmatch(r'[A-Za-z0-9_-]{43}', query['code_challenge']) or
                query.get('scope', SCOPE) != SCOPE or len(query.get('state','')) > 2048):
            return oauth_error('invalid_request', 'Authorization code with S256 PKCE and scope forma:mcp is required.')
        identifier, csrf = secrets.token_urlsafe(24), secrets.token_urlsafe(32)
        browser = request.cookies.get(OAUTH_COOKIE) or secrets.token_urlsafe(32)
        data = dict(query)
        data['client_name'] = client['name']
        with server.connect() as con:
            con.execute('DELETE FROM mcp_auth_requests WHERE expires<?', (time.time(),))
            con.execute('INSERT INTO mcp_auth_requests VALUES(?,?,?,?,?)',
                        (identifier,json.dumps(data),digest(csrf),digest(browser),time.time()+REQUEST_TTL))
        try:
            account = require_member_session(server, request)
        except HTTPException:
            account = None
        response = consent_page(identifier, csrf, client['name'], query['redirect_uri'], account)
        response.set_cookie(OAUTH_COOKIE,browser,httponly=True,secure=urlsplit(public_url()).scheme=='https',
                            samesite='lax',max_age=REQUEST_TTL,path='/oauth/authorize')
        return response

    @app.post('/oauth/authorize')
    async def authorize_consent(request: Request):
        check_origin(request)
        form = await oauth_form(request)
        identifier, csrf, browser = form.get('request_id',''), form.get('csrf',''), request.cookies.get(OAUTH_COOKIE,'')
        with server.connect() as con:
            stored = con.execute('SELECT * FROM mcp_auth_requests WHERE id=? AND expires>?',
                                 (identifier,time.time())).fetchone()
        if (not stored or not csrf or not browser or
                not hmac.compare_digest(stored['csrf_hash'],digest(csrf)) or
                not hmac.compare_digest(stored['browser_hash'],digest(browser))):
            return oauth_error('invalid_request', 'The consent request expired or its CSRF check failed.')
        data = json.loads(stored['data'])
        if form.get('decision') == 'deny':
            with server.connect() as con:
                con.execute('DELETE FROM mcp_auth_requests WHERE id=?', (identifier,))
            return redirect_result(data['redirect_uri'],error='access_denied',state=data.get('state'))
        if form.get('decision') != 'approve':
            return oauth_error('invalid_request', 'Choose Connect or Cancel.')
        try:
            account = require_member_session(server, request)
        except HTTPException:
            with server.connect() as con:
                row = con.execute("SELECT * FROM accounts WHERE email=? AND role='user'",
                                  (str(form.get('email','')).strip().lower(),)).fetchone()
            if not row or not server.verify_password(str(form.get('password','')),row['password']):
                return consent_page(identifier,csrf,data['client_name'],data['redirect_uri'],
                                    message='Incorrect member email or password.')
            account = dict(row)
        code, grant_id = secrets.token_urlsafe(40), str(uuid.uuid4())
        with server.connect() as con:
            con.execute('BEGIN IMMEDIATE')
            removed = con.execute('DELETE FROM mcp_auth_requests WHERE id=? AND expires>?', (identifier,time.time()))
            if not removed.rowcount:
                return oauth_error('invalid_request', 'This consent request was already used or expired.')
            con.execute('INSERT INTO mcp_grants VALUES(?,?,?,?,?,NULL)',
                        (grant_id,account['id'],data['client_id'],resource_url(),server.now()))
            con.execute('INSERT INTO mcp_codes VALUES(?,?,?,?,?,?,?,?,NULL)',
                        (digest(code),account['id'],data['client_id'],data['redirect_uri'],data['code_challenge'],
                         resource_url(),grant_id,time.time()+CODE_TTL))
        return redirect_result(data['redirect_uri'],code=code,state=data.get('state'))

    @app.post('/oauth/token')
    async def exchange_token(request: Request):
        form = await oauth_form(request)
        if form.get('resource','').rstrip('/') != resource_url():
            return oauth_error('invalid_target', 'Specify this Forma MCP resource.')
        if form.get('scope',SCOPE) != SCOPE:
            return oauth_error('invalid_scope', 'Only forma:mcp is supported.')
        with server.connect() as con:
            con.execute('BEGIN IMMEDIATE')
            client = authenticate_client(con,request,form)
            if not client:
                return oauth_error('invalid_client','The registered client credentials are required.',401)
            if form.get('grant_type') == 'authorization_code':
                verifier = form.get('code_verifier','')
                if not re.fullmatch(r'[A-Za-z0-9._~-]{43,128}',verifier):
                    return oauth_error('invalid_grant','The original PKCE verifier is required.')
                challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b'=').decode()
                code = con.execute('SELECT * FROM mcp_codes WHERE code_hash=?', (digest(form.get('code','')),)).fetchone()
                if (not code or code['used'] is not None or code['expires'] <= time.time() or
                        code['client'] != client['id'] or code['redirect_uri'] != form.get('redirect_uri') or
                        code['audience'] != resource_url() or not hmac.compare_digest(code['challenge'],challenge)):
                    return oauth_error('invalid_grant','The authorization code, redirect or PKCE verifier is invalid.')
                grant = con.execute('SELECT * FROM mcp_grants WHERE id=? AND revoked IS NULL', (code['grant_id'],)).fetchone()
                if not grant:
                    return oauth_error('invalid_grant','The connection was revoked.')
                con.execute('UPDATE mcp_codes SET used=? WHERE code_hash=?', (time.time(),code['code_hash']))
            elif form.get('grant_type') == 'refresh_token':
                refresh = con.execute('SELECT * FROM mcp_refresh_tokens WHERE token_hash=?',
                                      (digest(form.get('refresh_token','')),)).fetchone()
                grant = con.execute('SELECT * FROM mcp_grants WHERE id=?', (refresh['grant_id'],)).fetchone() if refresh else None
                if not grant or grant['client'] != client['id'] or grant['audience'] != resource_url():
                    return oauth_error('invalid_grant','The refresh token does not belong to this client and resource.')
                if refresh['used'] is not None:
                    con.execute('UPDATE mcp_grants SET revoked=? WHERE id=?', (time.time(),grant['id']))
                    return oauth_error('invalid_grant','Refresh token reuse revoked this connection. Connect again.')
                if grant['revoked'] is not None or refresh['expires'] <= time.time():
                    return oauth_error('invalid_grant','The refresh token expired or the connection was revoked.')
                con.execute('UPDATE mcp_refresh_tokens SET used=? WHERE token_hash=?', (time.time(),refresh['token_hash']))
            else:
                return oauth_error('unsupported_grant_type','Use authorization_code or refresh_token.')
            account = con.execute("SELECT id FROM accounts WHERE id=? AND role='user'", (grant['account'],)).fetchone()
            if not account:
                return oauth_error('invalid_grant','This member account is unavailable.')
            result = issue_oauth_tokens(con,server,grant)
        return JSONResponse(result,headers={'Cache-Control':'no-store','Pragma':'no-cache'})

    @app.post('/oauth/revoke')
    async def revoke_oauth(request: Request):
        form = await oauth_form(request)
        with server.connect() as con:
            client = authenticate_client(con,request,form)
            if not client:
                return oauth_error('invalid_client','The registered client credentials are required.',401)
            token_hash = digest(form.get('token',''))
            row = con.execute('''SELECT grant_id FROM mcp_tokens WHERE token_hash=? AND kind='oauth'
                UNION SELECT grant_id FROM mcp_refresh_tokens WHERE token_hash=?''', (token_hash,token_hash)).fetchone()
            if row:
                con.execute('UPDATE mcp_grants SET revoked=? WHERE id=? AND client=?',
                            (time.time(),row['grant_id'],client['id']))
        return JSONResponse({},headers={'Cache-Control':'no-store'})
