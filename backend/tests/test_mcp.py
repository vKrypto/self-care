"""Wire-level MCP requests plus member-token and OAuth security boundaries."""
import base64
import hashlib
import io
import json
import os
import re
from datetime import date
from urllib.parse import parse_qs, urlsplit

import pytest
from fastapi.testclient import TestClient
from PIL import Image

from backend import server
from backend.mcp_auth import digest, resource_url


@pytest.fixture
def client(tmp_path,monkeypatch):
    monkeypatch.setattr(server,'DATA',tmp_path)
    monkeypatch.setattr(server,'DB',tmp_path/'mcp.sqlite3')
    monkeypatch.setattr(server,'cache',server.Cache(tmp_path/'cache'))
    monkeypatch.setattr(server,'today',lambda tz='Asia/Kolkata': date(2026,10,2))
    monkeypatch.setenv('FORMA_PUBLIC_URL','http://127.0.0.1:8000')
    monkeypatch.setenv('SMTP_HOST','')
    with TestClient(server.app,base_url='http://127.0.0.1:8000') as test_client:
        yield test_client


def signup(client,email='member@example.com'):
    response = client.post('/api/auth/signup',json={'name':'Member','email':email,'password':'password123'})
    assert response.status_code == 201
    account = response.json()['account']
    with server.connect() as con:
        con.execute('INSERT INTO profiles VALUES(?,?)',(account['id'],json.dumps({'timezone':'Asia/Kolkata'})))
    return account


def token(client,name='Local agent'):
    response = client.post('/api/mcp/tokens',json={'name':name,'expires_days':30})
    assert response.status_code == 201, response.text
    return response.json()


def seed_plan(account):
    tasks = [dict(id='workout-1',title='Walk',category='Workout',role='workout',minutes=20,calories=100),
             dict(id='meal-1',title='Lunch',category='Lunch',role='meal',minutes=15,calories=600)]
    plan = {'start_date':'2026-10-02','end_date':'2026-10-02','days':[{'date':'2026-10-02','tasks':tasks}]}
    with server.connect() as con:
        con.execute('INSERT INTO plans VALUES(?,?,?)',(account['id'],json.dumps(plan),server.now()))
    return plan


def rpc(client,bearer,method,params=None,identifier=1,**extra_headers):
    headers = {'Authorization':'Bearer '+bearer,'Accept':'application/json, text/event-stream',
               'MCP-Protocol-Version':'2025-11-25',**extra_headers}
    body = {'jsonrpc':'2.0','id':identifier,'method':method}
    if params is not None:
        body['params'] = params
    return client.post('/mcp/',json=body,headers=headers)


def call(client,bearer,name,arguments=None):
    response = rpc(client,bearer,'tools/call',{'name':name,'arguments':arguments or {}})
    assert response.status_code == 200,response.text
    return response.json()['result']


def result_value(result):
    return result.get('structuredContent') or json.loads(result['content'][0]['text'])


def test_mcp_initialize_list_tracking_and_mark(client):
    account = signup(client)
    seed_plan(account)
    credential = token(client)
    client.cookies.clear()  # The wire service uses bearer identity, not browser cookies.
    response = rpc(client,credential['token'],'initialize',{
        'protocolVersion':'2025-11-25','capabilities':{},'clientInfo':{'name':'integration-test','version':'1'}})
    assert response.status_code == 200,response.text
    assert response.json()['result']['serverInfo']['name'] == 'Forma'
    tools = rpc(client,credential['token'],'tools/list').json()['result']['tools']
    assert {t['name'] for t in tools} == {'regenerate_plan','get_current_day_plan','get_progress_summary',
        'mark_task','get_date_tracking','upload_progress_photo','analyze_progress_photos','get_planning_job'}
    regeneration = next(t for t in tools if t['name']=='regenerate_plan')
    assert regeneration['inputSchema']['properties']['days']['maximum'] == 28
    assert 'account' not in json.dumps([t['inputSchema'] for t in tools])
    tracking = result_value(call(client,credential['token'],'get_current_day_plan'))
    assert tracking['date'] == '2026-10-02'
    assert tracking['counts']['pending'] == 2
    completed = result_value(call(client,credential['token'],'mark_task',{'task_id':'workout-1','status':'completed'}))
    assert completed['saved'] is True
    assert completed['tracking']['counts']['completed'] == 1
    skipped = result_value(call(client,credential['token'],'mark_task',{
        'task_id':'meal-1','status':'skipped','selected_date':'2026-10-02'}))
    assert skipped['tracking']['counts']['skipped'] == 1
    tracking = result_value(call(client,credential['token'],'get_date_tracking',{'selected_date':'2026-10-02'}))
    assert tracking['adherence']['completed_percent'] == 50
    summary = result_value(call(client,credential['token'],'get_progress_summary'))
    assert summary['counts']['completed'] == 1
    assert call(client,credential['token'],'mark_task',{'task_id':'meal-1','status':'pending'})['isError']
    assert call(client,credential['token'],'mark_task',{'task_id':'meal-1','status':'completed','selected_date':''})['isError']
    assert call(client,credential['token'],'get_date_tracking',{'selected_date':'not-a-date'})['isError']


def test_mcp_regenerate_strict_boundaries_and_job_adapter(client,monkeypatch):
    account = signup(client)
    credential = token(client)
    seen = []
    def regenerate(days,member):
        seen.append((days,member['id']))
        return {'id':'queued-job','status':'queued','days':days}
    monkeypatch.setattr(server,'regenerate_for_days',regenerate)
    for invalid in (0,29,1.2,True,'7'):
        assert call(client,credential['token'],'regenerate_plan',{'days':invalid})['isError']
    for valid in (1,28):
        result = result_value(call(client,credential['token'],'regenerate_plan',{'days':valid}))
        assert result['days'] == valid
    assert seen == [(1,account['id']),(28,account['id'])]
    with server.connect() as con:
        con.execute('INSERT INTO jobs(id,tenant,status,created,updated) VALUES(?,?,?,?,?)',
                    ('queued-job',account['id'],'completed',server.now(),server.now()))
    job = result_value(call(client,credential['token'],'get_planning_job',{'job_id':'queued-job'}))
    assert job['status'] == 'completed'


def test_mcp_photo_upload_tracking_and_analysis_adapter(client,monkeypatch):
    account = signup(client)
    seed_plan(account)
    credential = token(client)
    buffer = io.BytesIO()
    Image.new('RGB',(20,20),'blue').save(buffer,'PNG')
    result = result_value(call(client,credential['token'],'upload_progress_photo',{
        'image_base64':base64.b64encode(buffer.getvalue()).decode()}))
    assert result['kind'] == 'progress' and result['date'] == '2026-10-02'
    assert len(result['tracking']['photos']) == 1
    assert 'saved' in result['tracking']['feedback']['observations'][-1].lower()
    assert client.get(result['url']).headers['content-type'] == 'image/jpeg'
    assert call(client,credential['token'],'upload_progress_photo',{'image_base64':'%%%invalid'})['isError']
    assert call(client,credential['token'],'upload_progress_photo',{'image_base64':base64.b64encode(b'fake image').decode()})['isError']
    seen = []
    def analyze(data,account):
        seen.append((data.date,account['id']))
        return {'photo_review':{'summary':'Controlled provider review'},'tracking':{}}
    monkeypatch.setattr(server,'analyze_progress',analyze)
    reviewed = result_value(call(client,credential['token'],'analyze_progress_photos',{'selected_date':'2026-10-02'}))
    assert reviewed['photo_review']['summary'] == 'Controlled provider review'
    assert seen == [('2026-10-02',account['id'])]


def test_mcp_photo_transport_accepts_above_sdk_four_mib_default(client):
    signup(client)
    credential = token(client)
    buffer = io.BytesIO()
    # An incompressible RGB PNG makes the real JSON request exceed 4 MiB.
    Image.frombytes('RGB',(1400,1100),os.urandom(1400*1100*3)).save(buffer,'PNG')
    encoded = base64.b64encode(buffer.getvalue()).decode()
    assert len(encoded) > 4 * 1024 * 1024
    result = result_value(call(client,credential['token'],'upload_progress_photo',{'image_base64':encoded}))
    assert result['kind'] == 'progress'
    assert result['tracking']['photos'][0]['id'] == result['id']


def test_tokens_hashed_scoped_revocable_and_survive_restart(client):
    first = signup(client)
    seed_plan(first)
    credential = token(client)
    with server.connect() as con:
        stored = con.execute('SELECT * FROM mcp_tokens WHERE id=?',(credential['id'],)).fetchone()
        assert stored['token_hash'] == digest(credential['token'])
        assert credential['token'] not in json.dumps(dict(stored))
    listing = client.get('/api/mcp/tokens').json()
    assert listing[0]['id'] == credential['id'] and 'token' not in listing[0]
    server.init_db()
    assert rpc(client,credential['token'],'tools/list').status_code == 200
    client.post('/api/auth/logout')
    signup(client,'another@example.com')
    assert client.get('/api/mcp/tokens').json() == []
    assert client.delete('/api/mcp/tokens/'+credential['id']).status_code == 404
    other = token(client)
    other_day = result_value(call(client,other['token'],'get_current_day_plan'))
    assert not other_day['has_plan']
    assert call(client,other['token'],'mark_task',{'task_id':'workout-1','status':'completed'})['isError']
    # The original token still scopes every request to its original member.
    assert result_value(call(client,credential['token'],'get_current_day_plan'))['has_plan']
    client.post('/api/auth/login',json={'email':'member@example.com','password':'password123'})
    assert client.delete('/api/mcp/tokens/'+credential['id']).status_code == 200
    assert rpc(client,credential['token'],'tools/list').status_code == 401


def test_mcp_rejects_cookie_sessions_expired_tokens_origins_and_admins(client):
    signup(client)
    credential = token(client)
    payload = {'jsonrpc':'2.0','id':1,'method':'tools/list'}
    denied = client.post('/mcp/',json=payload)
    assert denied.status_code == 401
    assert 'resource_metadata=' in denied.headers['www-authenticate']
    assert rpc(client,credential['token'],'tools/list',Origin='https://untrusted.example').status_code == 403
    assert rpc(client,credential['token'],'tools/list',Host='untrusted.example').status_code == 421
    assert client.post('/api/mcp/tokens',json={'name':'Bad'},headers={'Origin':'https://untrusted.example'}).status_code == 403
    for days in (0,366,True):
        assert client.post('/api/mcp/tokens',json={'name':'Bad','expires_days':days}).status_code == 422
    with server.connect() as con:
        con.execute('UPDATE mcp_tokens SET audience=? WHERE id=?',('https://another.example/mcp',credential['id']))
    assert rpc(client,credential['token'],'tools/list').status_code == 401
    with server.connect() as con:
        con.execute('UPDATE mcp_tokens SET audience=?,expires=0 WHERE id=?',(resource_url(),credential['id']))
    assert rpc(client,credential['token'],'tools/list').status_code == 401
    client.post('/api/auth/login',json={'email':'admin@example.com','password':'admin123'})
    assert client.get('/api/mcp/tokens').status_code == 403
    assert client.post('/api/mcp/tokens',json={'name':'Admin'}).status_code == 403


def register(client,method='none',redirect='https://agent.example/callback'):
    response = client.post('/oauth/register',json={'client_name':'Test agent','redirect_uris':[redirect],
                                                  'token_endpoint_auth_method':method})
    assert response.status_code == 201,response.text
    return response.json()


def authorization(client,registration,**changes):
    verifier = 'a' * 64
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b'=').decode()
    query = {'client_id':registration['client_id'],'redirect_uri':registration['redirect_uris'][0],
             'response_type':'code','code_challenge':challenge,'code_challenge_method':'S256',
             'resource':resource_url(),'scope':'forma:mcp','state':'original-state',**changes}
    response = client.get('/oauth/authorize',params=query)
    return response,verifier


def consent_form(response):
    return {name:re.search(r'name="'+name+r'" value="([^"]+)"',response.text).group(1)
            for name in ('request_id','csrf')}


def code_for(client,registration):
    response,verifier = authorization(client,registration)
    assert response.status_code == 200,response.text
    assert 'Connect' in response.text and 'Test agent' in response.text
    form = {**consent_form(response),'decision':'approve','email':'member@example.com','password':'password123'}
    redirect = client.post('/oauth/authorize',data=form,follow_redirects=False)
    assert redirect.status_code == 303,redirect.text
    query = parse_qs(urlsplit(redirect.headers['location']).query)
    assert query['state'] == ['original-state']
    return query['code'][0],verifier


def exchange(client,registration,code,verifier,**changes):
    data = {'grant_type':'authorization_code','client_id':registration['client_id'],'code':code,
            'redirect_uri':registration['redirect_uris'][0],'code_verifier':verifier,'resource':resource_url(),**changes}
    if registration.get('client_secret'):
        data['client_secret'] = registration['client_secret']
    return client.post('/oauth/token',data=data)


def test_oauth_discovery_dcr_pkce_login_and_hashed_tokens(client):
    account = signup(client)
    seed_plan(account)
    client.cookies.clear()  # Consent includes member login when no browser session exists.
    protected = client.get('/.well-known/oauth-protected-resource/mcp').json()
    metadata = client.get('/.well-known/oauth-authorization-server').json()
    assert protected['resource'] == resource_url()
    assert protected['authorization_servers'] == [metadata['issuer']]
    assert metadata['code_challenge_methods_supported'] == ['S256']
    registration = register(client)
    code,verifier = code_for(client,registration)
    assert exchange(client,registration,code,'b'*64).status_code == 400
    assert exchange(client,registration,code,verifier,redirect_uri='https://agent.example/other').status_code == 400
    assert exchange(client,registration,code,verifier,resource='https://other.example/mcp').status_code == 400
    issued = exchange(client,registration,code,verifier)
    assert issued.status_code == 200,issued.text
    credentials = issued.json()
    assert credentials['expires_in'] == 3600 and credentials['token_type'] == 'Bearer'
    assert exchange(client,registration,code,verifier).status_code == 400
    assert result_value(call(client,credentials['access_token'],'get_current_day_plan'))['has_plan']
    with server.connect() as con:
        assert con.execute('SELECT token_hash FROM mcp_tokens WHERE kind=\'oauth\'').fetchone()['token_hash'] == digest(credentials['access_token'])
        assert con.execute('SELECT token_hash FROM mcp_refresh_tokens').fetchone()['token_hash'] == digest(credentials['refresh_token'])
        assert con.execute('SELECT code_hash FROM mcp_codes').fetchone()['code_hash'] == digest(code)


def test_oauth_refresh_rotation_reuse_and_member_revocation(client):
    signup(client)
    registration = register(client)
    code,verifier = code_for(client,registration)
    issued = exchange(client,registration,code,verifier).json()
    data = {'grant_type':'refresh_token','client_id':registration['client_id'],
            'refresh_token':issued['refresh_token'],'resource':resource_url()}
    rotated = client.post('/oauth/token',data=data)
    assert rotated.status_code == 200,rotated.text
    newer = rotated.json()
    assert newer['refresh_token'] != issued['refresh_token']
    assert rpc(client,newer['access_token'],'tools/list').status_code == 200
    assert client.post('/oauth/token',data=data).status_code == 400
    assert rpc(client,newer['access_token'],'tools/list').status_code == 401
    # New grant can be immediately revoked through the same Settings endpoints.
    code,verifier = code_for(client,registration)
    fresh = exchange(client,registration,code,verifier).json()
    connections = client.get('/api/mcp/connections').json()
    active = next(c for c in connections if c['revoked'] is None)
    assert active['name'] == 'Test agent'
    assert client.delete('/api/mcp/connections/'+active['id']).status_code == 200
    assert rpc(client,fresh['access_token'],'tools/list').status_code == 401


def test_oauth_foreign_client_and_member_cannot_use_or_revoke_grant(client):
    first = signup(client)
    seed_plan(first)
    registration = register(client)
    code,verifier = code_for(client,registration)
    foreign_client = register(client)
    assert exchange(client,foreign_client,code,verifier).status_code == 400
    issued = exchange(client,registration,code,verifier).json()
    grant_id = client.get('/api/mcp/connections').json()[0]['id']
    assert client.post('/oauth/token',data={'grant_type':'refresh_token','client_id':foreign_client['client_id'],
        'refresh_token':issued['refresh_token'],'resource':resource_url()}).status_code == 400
    client.post('/api/auth/logout')
    signup(client,'second@example.com')
    assert client.get('/api/mcp/connections').json() == []
    assert client.delete('/api/mcp/connections/'+grant_id).status_code == 404
    assert client.post('/oauth/revoke',data={'client_id':foreign_client['client_id'],'token':issued['access_token']}).status_code == 200
    assert result_value(call(client,issued['access_token'],'get_current_day_plan'))['has_plan']


def test_oauth_exact_redirects_csrf_and_no_admin_grants(client):
    registration = register(client)
    for uri in ('http://remote.example/callback','javascript:alert(1)','https://agent.example/callback#fragment'):
        assert client.post('/oauth/register',json={'redirect_uris':[uri]}).status_code == 400
    assert authorization(client,registration,redirect_uri='https://agent.example/callback/')[0].status_code == 400
    assert authorization(client,registration,code_challenge_method='plain')[0].status_code == 400
    assert authorization(client,registration,resource='https://other.example/mcp')[0].status_code == 400
    response,_ = authorization(client,registration)
    form = {**consent_form(response),'decision':'approve','email':'admin@example.com','password':'admin123'}
    assert client.post('/oauth/authorize',data={**form,'csrf':'forged'}).status_code == 400
    assert client.post('/oauth/authorize',data=form,headers={'Origin':'https://untrusted.example'}).status_code == 403
    rejected = client.post('/oauth/authorize',data=form,follow_redirects=False)
    assert rejected.status_code == 200 and 'Incorrect member email or password.' in rejected.text
    with server.connect() as con:
        assert con.execute('SELECT COUNT(*) FROM mcp_grants').fetchone()[0] == 0
    cancelled = client.post('/oauth/authorize',data={**form,'decision':'deny'},follow_redirects=False)
    assert cancelled.status_code == 303 and 'error=access_denied' in cancelled.headers['location']
    assert client.post('/oauth/authorize',data=form).status_code == 400


@pytest.mark.parametrize('method',['client_secret_post','client_secret_basic'])
def test_oauth_confidential_clients_and_revoke_endpoint(client,method):
    signup(client)
    registration = register(client,method)
    code,verifier = code_for(client,registration)
    data = {'grant_type':'authorization_code','client_id':registration['client_id'],'code':code,
            'redirect_uri':registration['redirect_uris'][0],'code_verifier':verifier,'resource':resource_url()}
    assert client.post('/oauth/token',data=data).status_code == 401
    if method == 'client_secret_basic':
        auth = (registration['client_id'],registration['client_secret'])
        issued = client.post('/oauth/token',data=data,auth=auth)
        revoke = {'token':issued.json()['access_token']}
        revoked = client.post('/oauth/revoke',data=revoke,auth=auth)
    else:
        issued = client.post('/oauth/token',data={**data,'client_secret':registration['client_secret']})
        revoked = client.post('/oauth/revoke',data={'client_id':registration['client_id'],
            'client_secret':registration['client_secret'],'token':issued.json()['access_token']})
    assert issued.status_code == 200,issued.text
    assert revoked.status_code == 200
    assert rpc(client,issued.json()['access_token'],'tools/list').status_code == 401
