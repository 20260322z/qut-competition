from pathlib import Path
import pytest

from app import crawler
from app.database import initialize, upsert
from app.parser import extract_deadline, parse_list, parse_notice, source_url, WithdrawnNotice

FIXTURES = Path(__file__).parent / 'fixtures'


def sample():
    return parse_notice((FIXTURES / 'detail.html').read_text(encoding='utf-8'),
                        'https://chuangye.qut.edu.cn/info/1088/4307.htm')


def test_real_page_and_pagination():
    links, next_page = parse_list((FIXTURES / 'list.html').read_text(encoding='utf-8'))
    assert len(links) == 10
    assert links[0].endswith('/4307.htm')
    assert next_page == 'https://chuangye.qut.edu.cn/index/sy/tzgg/17.htm'
    item = sample()
    assert item['published_at'] == '2026-09-04'
    assert item['deadline'] == '2026-11-17T09:00:00+08:00'
    assert item['category'] == '外语'
    assert len(item['images']) == 2
    assert '下一条' not in item['body']


@pytest.mark.parametrize('text,expected', [
    ('比赛时间：2026年10月1日；颁奖时间：2026年11月1日。', None),
    ('报名系统开放时间：2026年9月8日。', None),
    ('报名截止到2026年9月16日24:00。', '2026-09-17T00:00:00+08:00'),
    ('报名截止时间2026年9月10日；另一赛道报名截止时间2026年9月20日。', None),
    ('报名时间\n即日起至2026年10月3日。', '2026-10-03T23:59:00+08:00'),
    ('全国报名截止2026年9月10日\n校赛报名截止2026年9月8日17:00', '2026-09-08T17:00:00+08:00'),
    ('报名截止时间2026年2月30日', None),
    ('报名时间：2026年9月3日至2026年9月20日', '2026-09-20T23:59:00+08:00'),
    ('作品上传截止时间：2026年9月16日24:00', '2026-09-17T00:00:00+08:00'),
    ('校赛初赛：2026年9月20日前完成。报名参赛同学需参加知识赛。', None),
])
def test_deadline_ambiguity(text, expected):
    assert extract_deadline(text, '2026-09-01')[0] == expected


def test_relative_attachments_and_sanitized_content():
    html = '''<title>测试</title><div class="article_contain"><h3>测试竞赛</h3>
    发布时间：2026-09-01<div id="vsb_content"><p>这是用于测试的竞赛正文，含有报名信息和附件。</p>
    <script>evil()</script><a href="../../file/guide.pdf">指南</a><a href="javascript:alert(1)">链接</a></div></div>'''
    item = parse_notice(html, 'https://chuangye.qut.edu.cn/info/1088/1.htm')
    assert item['attachments'][0]['url'] == 'https://chuangye.qut.edu.cn/file/guide.pdf'
    assert 'evil()' not in item['body']
    assert source_url('javascript:alert(1)', 'https://example.com') is None


def test_registration_wins_over_other_competition_stages():
    item = parse_notice((FIXTURES / 'multiple-dates.html').read_text(encoding='utf-8'),
                        'https://chuangye.qut.edu.cn/info/1088/4295.htm')
    assert item['deadline'] == '2026-08-26T23:59:00+08:00'


def test_api_dedup_incremental_and_persistence(client):
    item = sample()
    assert upsert(item)
    assert not upsert(item)
    results = client.get('/api/v1/notices?q=跨文化&category=外语').json()
    assert results['total'] == 1
    assert client.get('/api/v1/notices', params={'q': '%_no_match'}).json()['total'] == 0
    assert client.get('/api/v1/notices?page_size=101').status_code == 422
    assert client.get('/api/v1/notices/missing').status_code == 404
    cutoff = results['sync_before']
    assert client.get('/api/v1/notices', params={'updated_since': cutoff}).json()['total'] == 0
    item['body'] += '\n官网更新'
    item['content_hash'] = 'changed'
    assert upsert(item)
    assert client.get('/api/v1/notices', params={'updated_since': cutoff}).json()['total'] == 1
    initialize()
    assert client.get('/health').json()['notice_count'] == 1


def test_failure_keeps_existing_data(client, monkeypatch):
    upsert(sample())
    monkeypatch.setattr(crawler, 'fetch', lambda *args: (_ for _ in ()).throw(TimeoutError()))
    result = crawler.sync()
    assert 'error' in result
    assert client.get('/health').json()['notice_count'] == 1
    assert client.get('/api/v1/sources').json()['items'][0]['last_error']


def test_invalid_article_is_rejected():
    with pytest.raises(ValueError):
        parse_notice('<html>维护中</html>', 'https://chuangye.qut.edu.cn/info/1088/1.htm')


def test_image_only_and_attachment_only_pages():
    for name in ['problem-4261.html', 'problem-4237.html']:
        item = parse_notice((FIXTURES / name).read_text(encoding='utf-8'),
                            'https://chuangye.qut.edu.cn/info/1088/' + name[8:-5] + '.htm')
        assert item['images'] or item['attachments']
        assert item['deadline'] is None
    with pytest.raises(WithdrawnNotice):
        parse_notice((FIXTURES / 'problem-3882.html').read_text(encoding='utf-8'),
                     'https://chuangye.qut.edu.cn/info/1088/3882.htm')
