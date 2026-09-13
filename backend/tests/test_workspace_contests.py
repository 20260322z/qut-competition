import json
import time
from app import workspace_contests as c
from app.database import connect
from app.student_store import encode
from test_workspace_agents import auth


def test_registry_and_identity(client):
    data=client.get('/api/v1/student/contests').json()
    assert data['total']==84
    assert len({r['id'] for r in data['items']})==84
    assert all(r['evidence'] and r['official_url'] and r['check'] for r in data['items'])
    math=client.get('/api/v1/student/contests/qut-2023-005').json()
    assert math['official_url']!=math['registration_url']
    assert c.matches(c.INDEX['qut-2023-001'],'关于中国国际大学生创新大赛校赛通知')
    assert not c.matches(c.INDEX['qut-2023-005'],'美国大学生数学建模竞赛报名')
    assert not c.matches(c.INDEX['qut-2023-002'],'挑战杯创业计划竞赛')


def test_date_precision_and_ambiguous_stages():
    values=c.stages('报名截止2030年10月1日。提交作品截止2030年10月3日 16:30。','校内')
    assert values[0]['time']=='' and values[0]['precision']=='day'
    assert values[1]['time']=='16:30'
    assert c.stages('报名及提交作品截止2030年10月1日。','全国')==[]
    assert c.stages('报名自2030年9月1日至2030年10月1日截止。','全国')==[]
    assert c.stages('报名本周五截止。报名截止10月1日。报名截止2030年2月30日。','全国')==[]
    assert c.stages('获奖名单公布于2030年10月1日。','全国')==[]


def test_event_revisions_follow_and_requeue(client):
    headers=auth('follow-test');contest='qut-2023-005'
    with connect() as db:
        db.execute('INSERT INTO subscriptions VALUES (?,?,?,?,?,?)',('follow-test','student@example.com',encode({'enabled':True,'news':True,'deadline':True}),time.time(),time.time(),'test-unsubscribe'))
    c.save_event(contest,'https://www.mcm.edu.cn/a','报名通知','报名截止2030年10月1日。','全国',False)
    url='/api/v1/student/contests/'+contest+'/follow'
    assert client.put(url,headers=headers,json={'enabled':True}).status_code==200
    with connect() as db:
        assert db.execute("SELECT count(*) FROM mail_queue WHERE category='news'").fetchone()[0]==0
        assert db.execute("SELECT count(*) FROM mail_queue WHERE status='pending'").fetchone()[0]==1
    assert not c.save_event(contest,'https://www.mcm.edu.cn/a','报名通知','报名截止2030年10月1日。','全国')
    assert c.save_event(contest,'https://www.mcm.edu.cn/a','报名通知','报名截止2030年10月3日。','全国')
    with connect() as db:
        assert db.execute('SELECT count(*) FROM contest_events').fetchone()[0]==1
        assert db.execute('SELECT count(*) FROM contest_history').fetchone()[0]==2
        assert db.execute("SELECT count(*) FROM mail_queue WHERE status='cancelled'").fetchone()[0]==1
        assert db.execute("SELECT count(*) FROM mail_queue WHERE status='pending'").fetchone()[0]==2
    client.put(url,headers=headers,json={'enabled':False})
    with connect() as db:assert db.execute("SELECT count(*) FROM mail_queue WHERE status='pending'").fetchone()[0]==0
    client.put(url,headers=headers,json={'enabled':True})
    with connect() as db:
        assert db.execute("SELECT count(*) FROM mail_queue WHERE status='pending'").fetchone()[0]==1
        assert db.execute("SELECT count(*) FROM mail_queue WHERE status='pending' AND category='news'").fetchone()[0]==0


def test_failure_keeps_content(client,monkeypatch):
    c.save_event('qut-2023-001','https://cy.ncss.cn/x','历史通知','历史正文','全国',False)
    monkeypatch.setattr(c,'fetch',lambda *args: (_ for _ in ()).throw(TimeoutError()))
    c.tick()
    data=client.get('/api/v1/student/contests/qut-2023-001').json()
    assert len(data['events'])==1 and data['check']['error']


def test_follow_permissions_and_owner_partition(client):
    one=auth('one');two=auth('two');url='/api/v1/student/contests/qut-2023-005/follow'
    assert client.put(url,json={'enabled':True}).status_code==401
    client.put(url,headers=one,json={'enabled':True})
    assert client.get('/api/v1/student/contests?followed=true',headers=one).json()['total']==1
    assert client.get('/api/v1/student/contests?followed=true',headers=two).json()['total']==0
