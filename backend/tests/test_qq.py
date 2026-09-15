from app import qq_ingest, qq_review
from app.database import connect

def test_retired_webhook_rejects_even_configured_token(client,monkeypatch):
    monkeypatch.setenv('NAPCAT_TOKEN','test-token')
    for headers in ({},{'Authorization':'Bearer test-token'}):
        response=client.post('/internal/qq/event',headers=headers,json={'post_type':'message','message_type':'group','group_id':123,'message_id':1,'raw_message':'数学建模报名通知'})
        assert response.status_code==410
    with connect() as db:assert db.execute('SELECT count(*) FROM qq_messages').fetchone()[0]==0

def test_retired_manual_collectors_cannot_repopulate(client):
    assert qq_ingest.handle_event({})['disabled']
    assert qq_ingest.poll()['disabled']
    assert qq_review.review()['disabled']

def test_public_sources_no_longer_expose_qq(client):
    sources=client.get('/api/v1/sources').json()['items']
    assert [x['id'] for x in sources]==['qut','contests']
    assert sources[1]['directory_count']==84
    assert client.get('/api/v1/notices?source=qq').json()['total']==0
    from app.main import APP_VERSION
    assert client.get('/health').json()['version']==APP_VERSION
