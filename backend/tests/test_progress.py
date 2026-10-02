import io
import json
from datetime import date
from types import SimpleNamespace

import pytest
from fastapi import HTTPException
from PIL import Image

from backend import progress, server
from backend.tests.test_phase1 import PROFILE, client, signup


def sample_day(selected):
    return {'date': selected, 'tasks': [
        {'id': 'workout-' + selected, 'title': 'Movement', 'role': 'workout', 'category': 'Workout', 'minutes': 25, 'calories': 120},
        {'id': 'meal-' + selected, 'title': 'Lunch', 'role': 'meal', 'category': 'Lunch', 'minutes': 10, 'calories': 650},
        {'id': 'care-' + selected, 'title': 'Routine', 'role': 'care', 'category': 'Skin care', 'minutes': 5, 'calories': 0},
    ]}


@pytest.fixture
def ready(client, monkeypatch):
    monkeypatch.setattr(server, 'today', lambda tz='Asia/Kolkata': date(2026, 10, 2))
    account = signup(client)
    assert client.put('/api/profile', json=PROFILE).status_code == 200
    plan = {'start_date': '2026-10-01', 'end_date': '2026-10-03',
        'days': [sample_day('2026-10-01'), sample_day('2026-10-02'), sample_day('2026-10-03')]}
    with server.connect() as con:
        progress.init_db(con)
        con.execute('INSERT INTO plans VALUES(?,?,?)', (account['id'], json.dumps(plan), server.now()))
    server.cache.delete(account['id'], 'plan')
    return client, account, plan


def set_status(account, selected, task, status):
    with server.connect() as con:
        con.execute('INSERT OR REPLACE INTO statuses VALUES(?,?,?,?)', (account['id'], selected, task, status))


def upload(client, selected='2026-10-02', kind='progress', color='blue'):
    output = io.BytesIO()
    Image.new('RGB', (30, 30), color).save(output, 'PNG')
    response = client.post('/api/media', files={'file': ('photo.png', output.getvalue(), 'image/png')},
        data={'kind': kind, 'selected_date': selected})
    assert response.status_code == 201, response.text
    return response.json()


class RecordingPhotoProvider:
    model = 'controlled-vision'

    def __init__(self):
        self.contexts = []

    def analyze(self, context):
        self.contexts.append(context)
        return {'summary': 'The saved images and self-reported tracking were reviewed.',
            'observations': ['The current image has a plain background.'],
            'next_steps': ['Use consistent framing for later photos.'],
            'limitations': ['Photos do not establish body composition or health changes.']}


def test_day_counts_totals_and_feedback_reflect_tracking_immediately(ready):
    client, account, plan = ready
    selected = '2026-10-02'
    initial = progress.day_tracking(server, account)
    assert initial['date'] == selected
    assert initial['counts'] == {'completed': 0, 'skipped': 0, 'pending': 3, 'total': 3}
    assert initial['photo_review'] is None
    set_status(account, selected, 'workout-' + selected, 'completed')
    set_status(account, selected, 'meal-' + selected, 'skipped')
    result = progress.day_tracking(server, account, selected)
    assert result['counts'] == {'completed': 1, 'skipped': 1, 'pending': 1, 'total': 3}
    assert result['adherence'] == {'completed_percent': 33.3, 'recorded_percent': 66.7}
    assert result['totals'] == {'workout_minutes': {'planned': 25, 'completed': 25},
        'workout_calories': {'planned': 120, 'completed': 120}, 'meal_calories': {'planned': 650, 'completed': 0}}
    assert result['feedback']['summary'] == '1 of 3 planned tasks completed; 1 skipped and 1 pending.'
    assert client.put('/api/checkins', json={'date': selected, 'water': 3, 'weight': 74.5, 'notes': 'Steady'}).status_code == 200
    result = progress.day_tracking(server, account, selected)
    assert result['checkin'] == {'date': selected, 'water': 3, 'weight': 74.5, 'notes': 'Steady'}
    photo = upload(client)
    result = progress.day_tracking(server, account, selected)
    assert result['photos'][0]['id'] == photo['id']
    assert any('Uploads alone do not establish' in text for text in result['feedback']['observations'])


def test_summary_excludes_future_even_when_explicitly_requested(ready):
    _, account, _ = ready
    set_status(account, '2026-10-02', 'workout-2026-10-02', 'completed')
    set_status(account, '2026-10-03', 'meal-2026-10-03', 'completed')
    default = progress.progress_summary(server, account)
    assert default['start_date'] == '2026-10-01'
    assert default['end_date'] == '2026-10-02'
    assert default['counts'] == {'completed': 1, 'skipped': 0, 'pending': 5, 'total': 6}
    explicit = progress.progress_summary(server, account, '2026-10-01', '2026-10-03')
    assert explicit['counts'] == default['counts']
    assert explicit['totals'] == default['totals']
    assert explicit['excluded_future_tasks'] == 3
    assert len(explicit['days']) == 3
    assert explicit['days'][-1]['counts']['completed'] == 1
    assert 'upcoming' in explicit['days'][-1]['feedback']['observations'][-1]


def test_empty_days_and_invalid_dates_are_explicit(ready):
    _, account, _ = ready
    empty = progress.day_tracking(server, account, '2026-09-30')
    assert empty['has_plan'] is False and empty['counts']['total'] == 0
    assert empty['adherence']['completed_percent'] is None
    for invalid in ('2026-13-01', '20261001', '', 'not a date'):
        with pytest.raises(HTTPException) as err:
            progress.day_tracking(server, account, invalid)
        assert err.value.status_code == 422
    with pytest.raises(HTTPException) as err:
        progress.progress_summary(server, account, '2026-10-02', '2026-10-01')
    assert err.value.status_code == 422
    with pytest.raises(HTTPException) as err:
        progress.day_tracking(server, {**account, 'role': 'admin'})
    assert err.value.status_code == 403


def test_history_remains_available_after_regeneration(ready):
    _, account, plan = ready
    archived_status = [{'date': '2026-10-01', 'task_id': 'workout-2026-10-01', 'status': 'completed'}]
    replacement = {'start_date': '2026-10-02', 'end_date': '2026-10-02', 'days': plan['days'][1:2]}
    replacement['days'][0]['tasks'][0]['title'] = 'Current movement'
    with server.connect() as con:
        con.execute('INSERT INTO plan_history VALUES(?,?,?,?,?)', ('archive', account['id'], json.dumps(plan), json.dumps(archived_status), server.now()))
        con.execute('UPDATE plans SET data=? WHERE tenant=?', (json.dumps(replacement), account['id']))
    server.cache.delete(account['id'], 'plan')
    old = progress.day_tracking(server, account, '2026-10-01')
    assert old['has_plan'] and old['counts']['completed'] == 1
    assert old['tasks'][0]['title'] == 'Movement'
    assert progress.day_tracking(server, account, '2026-10-02')['tasks'][0]['title'] == 'Current movement'
    # Future dates in an abandoned longer plan are not active again.
    assert progress.day_tracking(server, account, '2026-10-03')['has_plan'] is False


def test_abandoned_future_archive_tasks_never_reappear_as_calendar_advances(ready, monkeypatch):
    _, account, old_plan = ready
    old_plan['days'].append(sample_day('2026-10-04'))
    replacement = {'start_date': '2026-10-02', 'end_date': '2026-10-02', 'days': [old_plan['days'][1]]}
    with server.connect() as con:
        # This is October 2 in Kolkata, even though UTC is still October 1.
        con.execute('INSERT INTO plan_history VALUES(?,?,?,?,?)', ('archive', account['id'], json.dumps(old_plan), '[]', '2026-10-01T20:00:00+00:00'))
        con.execute('UPDATE plans SET data=? WHERE tenant=?', (json.dumps(replacement), account['id']))
    server.cache.delete(account['id'], 'plan')
    monkeypatch.setattr(server, 'today', lambda tz='Asia/Kolkata': date(2026, 10, 4))
    assert progress.day_tracking(server, account, '2026-10-01')['has_plan']
    assert progress.day_tracking(server, account, '2026-10-02')['has_plan']
    assert progress.day_tracking(server, account, '2026-10-03')['has_plan'] is False
    assert progress.day_tracking(server, account, '2026-10-04')['has_plan'] is False
    summary = progress.progress_summary(server, account)
    assert [day['date'] for day in summary['days']] == ['2026-10-01', '2026-10-02']
    assert summary['counts']['total'] == 6


def test_photo_review_uses_only_own_current_images_and_earlier_baseline(ready):
    client, account, _ = ready
    baseline = upload(client, '2026-09-20', 'body', 'red')
    upload(client, '2026-10-01', 'equipment', 'green')
    current = upload(client)
    client.post('/api/auth/logout')
    other = signup(client, 'other@example.com')
    foreign = upload(client, '2026-09-01', 'body', 'yellow')
    upload(client, color='white')
    provider = RecordingPhotoProvider()
    result = progress.analyze_photos(server, account, provider=provider)
    context = provider.contexts[0]
    assert {i['id'] for i in context['images']} == {current['id'], baseline['id']}
    assert foreign['id'] not in str(context)
    assert all(i['bytes'].startswith(b'\xff\xd8') for i in context['images'])
    assert context['baseline_date'] == '2026-09-20'
    assert context['days_since_baseline'] == 12
    assert context['adherence_context']['counts']['total'] == 6
    review = result['photo_review']
    assert review['source_photo_ids'] == [current['id']]
    assert review['baseline_photo_ids'] == [baseline['id']]
    assert review['stale'] is False
    assert 'bytes' not in json.dumps(result) and 'base64' not in json.dumps(result)
    assert progress.day_tracking(server, other)['photo_review'] is None
    assert progress.progress_summary(server, other)['counts']['total'] == 0


def test_photo_reviews_are_cached_and_stale_when_any_evidence_changes(ready):
    client, account, _ = ready
    first = upload(client)
    provider = RecordingPhotoProvider()
    result = progress.analyze_photos(server, account, provider=provider)
    cached = progress.analyze_photos(server, account, provider=provider)
    assert len(provider.contexts) == 1
    assert cached['photo_review']['created'] == result['photo_review']['created']
    set_status(account, '2026-10-02', 'workout-2026-10-02', 'completed')
    assert progress.day_tracking(server, account)['photo_review']['stale']
    progress.analyze_photos(server, account, provider=provider)
    assert len(provider.contexts) == 2
    assert client.put('/api/checkins', json={'date': '2026-10-02', 'water': 2}).status_code == 200
    assert progress.day_tracking(server, account)['photo_review']['stale']
    progress.analyze_photos(server, account, provider=provider)
    second = upload(client, color='green')
    assert progress.day_tracking(server, account)['photo_review']['stale']
    progress.analyze_photos(server, account, provider=provider)
    assert len(provider.contexts) == 4
    assert client.delete(second['url']).status_code == 200
    assert progress.day_tracking(server, account)['photo_review']['stale']
    assert client.delete(first['url']).status_code == 200
    assert progress.day_tracking(server, account)['photo_review']['stale']
    with pytest.raises(HTTPException) as err:
        progress.analyze_photos(server, account, provider=provider)
    assert err.value.status_code == 422


def test_baseline_deletion_and_reencoded_file_change_invalidate_review(ready):
    client, account, _ = ready
    baseline = upload(client, '2026-10-01', 'body', 'red')
    current = upload(client)
    provider = RecordingPhotoProvider()
    progress.analyze_photos(server, account, provider=provider)
    path = server.DATA / 'media' / account['id'] / (current['id'] + '.jpg')
    Image.new('RGB', (30, 30), 'white').save(path, 'JPEG')
    assert progress.day_tracking(server, account)['photo_review']['stale']
    progress.analyze_photos(server, account, provider=provider)
    client.delete(baseline['url'])
    assert progress.day_tracking(server, account)['photo_review']['stale']


def test_past_photo_review_remains_current_when_calendar_advances(ready, monkeypatch):
    client, account, _ = ready
    upload(client)
    provider = RecordingPhotoProvider()
    progress.analyze_photos(server, account, provider=provider)
    monkeypatch.setattr(server, 'today', lambda tz='Asia/Kolkata': date(2026, 10, 3))
    assert progress.day_tracking(server, account, '2026-10-02')['photo_review']['stale'] is False
    progress.analyze_photos(server, account, '2026-10-02', provider)
    assert len(provider.contexts) == 1


def test_photo_provider_configuration_and_failure_never_fabricate_reviews(ready, monkeypatch):
    client, account, _ = ready
    upload(client)
    monkeypatch.delenv('OPEN_API_KEY', raising=False)
    monkeypatch.delenv('OPENAI_API_KEY', raising=False)
    monkeypatch.setenv('LLM_PROVIDER', 'openai')
    with pytest.raises(HTTPException) as err:
        progress.analyze_photos(server, account)
    assert err.value.status_code == 503 and 'API key' in err.value.detail
    assert progress.day_tracking(server, account)['photo_review'] is None
    class Fails:
        def analyze(self, context):
            raise RuntimeError('secret provider credentials should not escape')
    with pytest.raises(HTTPException) as err:
        progress.analyze_photos(server, account, provider=Fails())
    assert err.value.status_code == 503 and 'secret' not in err.value.detail
    assert progress.day_tracking(server, account)['photo_review'] is None
    good = progress.analyze_photos(server, account, provider=RecordingPhotoProvider())
    set_status(account, '2026-10-02', 'meal-2026-10-02', 'completed')
    with pytest.raises(HTTPException):
        progress.analyze_photos(server, account, provider=Fails())
    unchanged = progress.day_tracking(server, account)
    assert unchanged['photo_review']['assessment'] == good['photo_review']['assessment']
    assert unchanged['photo_review']['stale']


def test_photo_review_rejects_missing_progress_and_concurrent_mutation(ready):
    client, account, _ = ready
    upload(client, kind='body')
    with pytest.raises(HTTPException) as err:
        progress.analyze_photos(server, account, provider=RecordingPhotoProvider())
    assert err.value.status_code == 422
    upload(client)
    class ChangesDuringReview(RecordingPhotoProvider):
        def analyze(self, context):
            set_status(account, '2026-10-02', 'meal-2026-10-02', 'skipped')
            return super().analyze(context)
    with pytest.raises(HTTPException) as err:
        progress.analyze_photos(server, account, provider=ChangesDuringReview())
    assert err.value.status_code == 409
    assert progress.day_tracking(server, account)['photo_review'] is None
    with pytest.raises(HTTPException) as err:
        progress.analyze_photos(server, account, '2026-10-03', RecordingPhotoProvider())
    assert err.value.status_code == 422


def test_openai_adapter_sends_actual_images_without_local_paths_or_storage(ready, monkeypatch):
    client, account, _ = ready
    upload(client)
    captured = []
    assessment = progress.PhotoAssessment(summary='Visible image reviewed.', observations=[], next_steps=[], limitations=['No physical change established.'])
    class Responses:
        def parse(self, **kwargs):
            captured.append(kwargs)
            return SimpleNamespace(output_parsed=assessment)
    class OpenAI:
        def __init__(self, **kwargs):
            self.responses = Responses()
    import openai
    monkeypatch.setattr(openai, 'OpenAI', OpenAI)
    monkeypatch.setenv('OPENAI_API_KEY', 'test-key')
    progress.analyze_photos(server, account, provider=progress.OpenAIPhotoProvider())
    request = captured[0]
    assert request['text_format'] is progress.PhotoAssessment
    assert request['store'] is False
    content = request['input'][0]['content']
    image_input = next(item for item in content if item['type'] == 'input_image')
    assert image_input['image_url'].startswith('data:image/jpeg;base64,/9j/')
    assert str(server.DATA) not in json.dumps(content)
    assert 'Never infer weight' in request['instructions']


def test_photo_analysis_api_returns_review_and_refreshes_after_route_updates(ready, monkeypatch):
    client, account, _ = ready
    provider = RecordingPhotoProvider()
    monkeypatch.setenv('LLM_PROVIDER', 'controlled')
    monkeypatch.setitem(progress.PHOTO_PROVIDERS, 'controlled', lambda: provider)
    initial = upload(client)
    response = client.post('/api/progress/photos/analyze', json={'date': '2026-10-02'})
    assert response.status_code == 200, response.text
    body = response.json()
    assert body['photo_review'] == body['tracking']['photo_review']
    assert body['photo_review']['stale'] is False
    repeated = client.post('/api/progress/photos/analyze', json={'date': '2026-10-02'})
    assert repeated.status_code == 200 and len(provider.contexts) == 1
    status = client.put('/api/tasks/status', json={'date': '2026-10-02', 'task_id': 'meal-2026-10-02', 'status': 'completed'})
    assert status.status_code == 200
    assert status.json()['tracking']['photo_review']['stale']
    updated = client.post('/api/progress/photos/analyze', json={'date': '2026-10-02'})
    assert updated.status_code == 200 and len(provider.contexts) == 2
    assert updated.json()['tracking']['counts']['completed'] == 1
    added = upload(client, color='purple')
    assert added['tracking']['photo_review']['stale']
    client.post('/api/progress/photos/analyze', json={'date': '2026-10-02'})
    assert client.delete(added['url']).status_code == 200
    tracking = client.get('/api/tracking/2026-10-02').json()
    assert tracking['photo_review']['stale']
    assert tracking['photos'][0]['id'] == initial['id']
    client.post('/api/auth/logout')
    signup(client, 'other@example.com')
    assert client.get('/api/tracking/2026-10-02').json()['photo_review'] is None
    assert client.post('/api/progress/photos/analyze', json={'date': '2026-10-02'}).status_code == 422
