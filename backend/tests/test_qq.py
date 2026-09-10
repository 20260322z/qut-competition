from pathlib import Path

from app import qq_ingest, qq_review
from app.database import connect, upsert
from app.parser import parse_notice


def group_event(text, message_id=1, group_id=123456, **extra):
    event = {
        'post_type': 'message',
        'message_type': 'group',
        'group_id': group_id,
        'group_name': '青理竞赛群',
        'user_id': 10001,
        'message_id': message_id,
        'time': 1757347200,
        'raw_message': text,
        'sender': {'nickname': '通知员', 'card': '班委'},
    }
    event.update(extra)
    return event


def test_regex_keeps_competition_and_skips_chat():
    notice = '【通知】2026年数学建模校赛报名截止到10月20日，请同学尽快提交作品。'
    chat = '今晚食堂有比赛吗？哈哈'
    assert qq_ingest.looks_like_competition(notice)
    assert not qq_ingest.looks_like_competition(chat)
    assert qq_ingest.extract_text(group_event(notice)).startswith('【通知】')


def test_webhook_stores_only_regex_hits(client, monkeypatch):
    monkeypatch.setenv('NAPCAT_TOKEN', 'test-token')
    denied = client.post('/internal/qq/event', json=group_event('报名竞赛通知请看群文件', 8))
    assert denied.status_code == 403
    import hashlib, hmac, json
    payload = group_event('蓝桥杯校赛报名开始，附件见群文件 https://example.com/a.pdf', 9)
    raw = json.dumps(payload, ensure_ascii=False, separators=(',', ':')).encode()
    signature = 'sha1=' + hmac.new(b'test-token', raw, hashlib.sha1).hexdigest()
    hit = client.post('/internal/qq/event', headers={'X-Signature': signature}, content=raw)
    skip = client.post('/internal/qq/event', headers={'Authorization': 'Bearer test-token'},
                       json=group_event('晚上打球吗', 10))
    assert hit.json()['stored'] and hit.json()['regex_matched']
    assert skip.json()['stored'] and not skip.json()['regex_matched']
    with connect() as db:
        pending = db.execute("SELECT review_status FROM qq_messages WHERE message_id='9'").fetchone()[0]
        skipped = db.execute("SELECT review_status FROM qq_messages WHERE message_id='10'").fetchone()[0]
    assert pending == 'pending'
    assert skipped == 'skipped'


def test_review_saves_competition_notice(client, monkeypatch):
    monkeypatch.setenv('NAPCAT_TOKEN', 'test-token')
    text = '挑战杯校赛报名通知：即日起至2026年10月3日截止，请按学院通知提交报名表。'
    client.post('/internal/qq/event', headers={'Authorization': 'Bearer test-token'},
                json=group_event(text, 22))
    monkeypatch.setattr(qq_review, 'ask_deepseek', lambda *args, **kwargs: {
        'is_competition': True, 'title': '挑战杯校赛报名通知', 'category': '创业', 'reason': '明确的报名通知',
    })
    result = qq_review.review()
    assert result['accepted'] == 1
    items = client.get('/api/v1/notices', params={'source': 'qq'}).json()
    assert items['total'] == 1
    notice = items['items'][0]
    assert notice['source'].startswith('QQ群')
    assert notice['title'] == '挑战杯校赛报名通知'
    assert notice['category'] == '创业'
    assert notice['deadline'] == '2026-10-03T23:59:00+08:00'
    assert notice['url'].startswith('qq://')
    assert client.get('/api/v1/notices', params={'source': 'official'}).json()['total'] == 0


def test_official_and_qq_sources_are_separated(client, monkeypatch):
    item = parse_notice((Path(__file__).parent / 'fixtures' / 'detail.html').read_text(encoding='utf-8'),
                        'https://chuangye.qut.edu.cn/info/1088/4307.htm')
    upsert(item)
    monkeypatch.setenv('NAPCAT_TOKEN', 'test-token')
    client.post('/internal/qq/event', headers={'Authorization': 'Bearer test-token'},
                json=group_event('互联网+大赛报名开始，请有意参赛同学尽快联系辅导员。', 33))
    monkeypatch.setattr(qq_review, 'ask_deepseek', lambda *args, **kwargs: {
        'is_competition': True, 'title': '互联网+大赛报名', 'category': '创业', 'reason': '竞赛报名',
    })
    qq_review.review()
    sources = client.get('/api/v1/sources').json()['items']
    assert sources[0]['id'] == 'qut'
    assert sources[1]['id'] == 'qq'
    assert sources[1]['notice_count'] == 1
    health = client.get('/health').json()
    assert health['version'] == '1.1.0'
    assert health['qq_notice_count'] == 1
    assert health['notice_count'] == 2
