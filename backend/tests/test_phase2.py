"""Phase 2 HTTP integration: bounded planning and shared daily feedback."""
import io
import json
import time
from datetime import date

import pytest
from PIL import Image

from backend import planning, server
from backend.tests.test_phase1 import PROFILE, FakeProvider, client, signup, seed_plan


@pytest.fixture
def ready(client, monkeypatch):
    monkeypatch.setattr(server, 'today', lambda tz='Asia/Kolkata': date(2026, 10, 1))
    account = signup(client)
    assert client.put('/api/profile', json=PROFILE).status_code == 200
    plan = seed_plan(account)
    monkeypatch.setenv('LLM_PROVIDER', 'test')
    monkeypatch.setitem(planning.PROVIDERS, 'test', FakeProvider)
    return client, account, plan


def finished(client, response):
    assert response.status_code == 202, response.text
    for _ in range(200):
        job = client.get('/api/jobs/' + response.json()['id']).json()
        if job['status'] in ('completed', 'failed'):
            return job
        time.sleep(.01)
    pytest.fail('The planning job did not finish.')


@pytest.mark.parametrize('days', [1, 7, 28])
def test_regeneration_publishes_requested_length_and_archives_old_plan(ready, days):
    client, account, original = ready
    response = client.post('/api/plans/regenerate', json={'days': days})
    assert response.json()['days'] == days
    assert finished(client, response)['status'] == 'completed'
    plan = client.get('/api/plan').json()['plan']
    assert len(plan['days']) == days
    assert plan['start_date'] == '2026-10-01'
    assert len(client.get('/api/progress').json()['history']) == 1


@pytest.mark.parametrize('days', [0, 29, True, '7', 7.5, None])
def test_regeneration_rejects_invalid_duration_without_queueing(ready, days):
    client, account, original = ready
    assert client.post('/api/plans/regenerate', json={'days': days}).status_code == 422
    with server.connect() as con:
        assert con.execute('SELECT COUNT(*) FROM jobs').fetchone()[0] == 0
    assert client.get('/api/plan').json()['plan'] == original


def test_retry_preserves_requested_regeneration_duration(ready, monkeypatch):
    client, account, original = ready
    class Broken(FakeProvider):
        def generate(self, *args, **kwargs):
            raise planning.PlanningError('Provider temporarily unavailable')
    monkeypatch.setitem(planning.PROVIDERS, 'test', Broken)
    response = client.post('/api/plans/regenerate', json={'days': 3})
    assert finished(client, response)['status'] == 'failed'
    assert client.get('/api/plan').json()['plan'] == original
    monkeypatch.setitem(planning.PROVIDERS, 'test', FakeProvider)
    retry = client.post('/api/jobs/' + response.json()['id'] + '/retry')
    assert retry.json()['days'] == 3
    assert finished(client, retry)['status'] == 'completed'
    assert len(client.get('/api/plan').json()['plan']['days']) == 3


def test_completed_skipped_and_checkin_return_current_feedback(ready):
    client, account, plan = ready
    day = plan['days'][0]
    workout = next(t for t in day['tasks'] if t['role'] == 'workout')
    meal = next(t for t in day['tasks'] if t['role'] == 'meal')
    result = client.put('/api/tasks/status', json={'date': day['date'], 'task_id': workout['id'], 'status': 'completed'})
    assert result.status_code == 200
    tracking = result.json()['tracking']
    assert tracking['counts']['completed'] == 1
    assert tracking['totals']['workout_minutes']['completed'] == workout['minutes']
    assert tracking['feedback']['summary']
    skipped = client.put('/api/tasks/status', json={'date': day['date'], 'task_id': meal['id'], 'status': 'skipped'}).json()['tracking']
    assert skipped['counts']['skipped'] == 1
    assert skipped['totals']['meal_calories']['completed'] == 0
    result = client.put('/api/checkins', json={'date': day['date'], 'water': 4, 'weight': 74, 'notes': 'Steady progress'})
    assert result.json()['tracking']['checkin']['water'] == 4
    assert client.get('/api/tracking/' + day['date']).json()['counts'] == skipped['counts']


def test_progress_upload_returns_tracking_and_tenant_isolated_photos(ready):
    client, account, plan = ready
    buffer = io.BytesIO()
    Image.new('RGB', (20, 20), 'blue').save(buffer, 'PNG')
    response = client.post('/api/media', files={'file': ('progress.png', buffer.getvalue(), 'image/png')}, data={'kind': 'progress', 'selected_date': '2026-10-01'})
    assert response.status_code == 201
    photo = response.json()
    assert photo['tracking']['photos'][0]['id'] == photo['id']
    assert photo['tracking']['photo_review'] is None
    client.post('/api/auth/logout')
    signup(client, 'other@example.com')
    assert client.get(photo['url']).status_code == 404
    tracking = client.get('/api/tracking/2026-10-01').json()
    assert tracking['photos'] == [] and tracking['counts']['total'] == 0


def test_progress_photo_default_date_uses_member_timezone(ready, monkeypatch):
    client, account, plan = ready
    client.put('/api/profile', json={**PROFILE, 'timezone': 'Pacific/Honolulu'})
    zones = []
    def local_today(tz='Asia/Kolkata'):
        zones.append(tz)
        return date(2026, 9, 30) if tz == 'Pacific/Honolulu' else date(2026, 10, 1)
    monkeypatch.setattr(server, 'today', local_today)
    buffer = io.BytesIO()
    Image.new('RGB', (20, 20), 'blue').save(buffer, 'PNG')
    response = client.post('/api/media', files={'file': ('photo.png', buffer.getvalue(), 'image/png')}, data={'kind': 'progress'})
    assert response.json()['date'] == '2026-09-30'
    assert zones and set(zones) == {'Pacific/Honolulu'}


@pytest.mark.parametrize('value', ['20261001', '2026-13-01', 'invalid'])
def test_detailed_tracking_rejects_noncanonical_dates(ready, value):
    client, account, plan = ready
    assert client.get('/api/tracking/' + value).status_code == 422


def test_phase2_member_endpoints_require_member_account(client):
    paths = ['/api/tracking/2026-10-01', '/api/progress/summary', '/api/mcp/info']
    for path in paths:
        assert client.get(path).status_code == 401
    client.post('/api/auth/login', json={'email': 'admin@example.com', 'password': 'admin123'})
    for path in paths:
        assert client.get(path).status_code == 403
    assert client.post('/api/plans/regenerate', json={'days': 7}).status_code == 403
    assert client.post('/api/progress/photos/analyze', json={'date': '2026-10-01'}).status_code == 403
