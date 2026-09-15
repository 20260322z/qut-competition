from bs4 import BeautifulSoup
from app import workspace_contests as c
from app.database import connect

def test_official_event_is_available_in_shared_notice_api(client):
    key='qut-2023-005'
    c.save_event(key,'https://www.mcm.edu.cn/test.html','数学建模报名通知','报名截止2030年10月1日 16:30。','全国',False,
                 {'published_at':'2026-09-14','attachments':[{'name':'报名表','url':'https://www.mcm.edu.cn/a.xlsx'}]})
    first=client.get('/api/v1/notices?contest='+key).json()
    assert first['total']==1
    notice=first['items'][0]
    assert notice['contest_ids']==[key]
    assert notice['attachments'][0]['url'].endswith('.xlsx')
    assert client.get('/api/v1/notices/'+notice['id']).json()['body']=='报名截止2030年10月1日 16:30。'
    assert client.get('/api/v1/notices',params={'updated_since':first['sync_before']}).json()['total']==0
    c.save_event(key,'https://www.mcm.edu.cn/test.html','数学建模报名通知','报名截止2030年10月1日 16:30。','全国',False,
                 {'published_at':'2026-09-14','attachments':[{'name':'报名表修订','url':'https://www.mcm.edu.cn/b.xlsx'}]})
    changed=client.get('/api/v1/notices',params={'updated_since':first['sync_before']}).json()
    assert changed['total']==1 and changed['items'][0]['attachments'][0]['url'].endswith('b.xlsx')

def test_article_metadata_and_relative_attachments():
    soup=BeautifulSoup('<meta name="publishdate" content="2026-09-14"><nav>不是正文</nav><article><p>报名截止2030年10月1日</p><a href="../file/a.xlsx">报名表</a><a href="javascript:alert(1)">忽略</a></article>','html.parser')
    body,metadata=c.article_content(soup,'https://example.edu/news/item.html')
    assert '不是正文' not in body
    assert metadata['published_at']=='2026-09-14'
    assert metadata['attachments']==[{'name':'报名表','url':'https://example.edu/file/a.xlsx'}]

def test_source_with_unreadable_articles_does_not_claim_success(client,monkeypatch):
    candidate=dict(c.INDEX['qut-2023-005'])
    def fetch(url,host):
        if url==candidate['notice_url']:
            return BeautifulSoup('<a href="/new.html">全国大学生数学建模竞赛报名通知</a>','html.parser'),url
        raise TimeoutError()
    monkeypatch.setattr(c,'fetch',fetch)
    result=c.sync_source(candidate)
    assert result['collected']==0
    with connect() as db:
        state=db.execute('SELECT * FROM contest_checks WHERE id=?',(candidate['id'],)).fetchone()
        assert not state['success'] and state['error']

def test_unverified_identity_never_enters_feed(client,monkeypatch):
    candidate=dict(c.INDEX['qut-2023-002'])
    monkeypatch.setattr(c,'fetch',lambda url,host:(BeautifulSoup('<html>域名出售，点击购买</html>','html.parser'),url))
    result=c.sync_source(candidate)
    assert result['error']
    assert client.get('/api/v1/notices').json()['total']==0

def test_mine_posts_requires_login(client):
    assert client.get('/api/v1/student/posts?mine=true').status_code==401
