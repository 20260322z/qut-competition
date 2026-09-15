import base64
import json
import re
import time
from datetime import datetime, timezone
from concurrent.futures import ThreadPoolExecutor

import pytest
from app.database import connect, upsert
from app import student_mail, student_ai
from app.student_store import digest, uid


def account(name='student'):
    token = uid()
    with connect() as db:
        db.execute('INSERT INTO students VALUES (?,?,?)',(name,f'{name}@qq.com',time.time()))
        db.execute('INSERT INTO sessions VALUES (?,?,?)',(digest(token),name,time.time()+3600))
    return {'Authorization':f'Bearer {token}'}


def test_publication_feeds_and_my_profile_share_same_records(client):
    a,b=account('publisher-a'),account('publisher-b')
    team={'contest':'全国大学生数学建模竞赛','title':'合成测试招募','track':'本科组','capacity':3,'roles':'Python 数据分析','schedule':'每周周末'}
    r=client.post('/api/v1/student/teams',headers=a,json=team);assert r.status_code==200,r.text
    key=r.json()['id']
    post={ 'contest':team['contest'],'title':'合成经验分享','year':'2026','body':'从历年题目开始复盘，记录分工和遇到的问题。'}
    r=client.post('/api/v1/student/posts',headers=a,json=post);assert r.status_code==200,r.text
    pkey=r.json()['id']
    assert key in [x['id'] for x in client.get('/api/v1/student/teams?q=Python').json()['items']]
    assert key in [x['id'] for x in client.get('/api/v1/student/teams/mine',headers=a).json()['items']]
    assert pkey in [x['id'] for x in client.get('/api/v1/student/posts?mine=true',headers=a).json()['items']]
    assert client.get('/api/v1/student/posts?mine=true',headers=b).json()['items']==[]


@pytest.fixture
def smtp(monkeypatch):
    for key in ('SMTP_HOST','SMTP_USER','SMTP_PASSWORD','SMTP_FROM'):
        monkeypatch.setenv(key,'test-only')
    sent=[]
    monkeypatch.setattr(student_mail,'send',lambda *args:sent.append(args))
    return sent


def subscribe(owner, **settings):
    with connect() as db:
        db.execute('INSERT INTO subscriptions VALUES (?,?,?,?,?,?)',(owner,f'{owner}@qq.com',json.dumps({'enabled':True,**settings}),time.time()-7200,time.time()-7200,uid()))


def test_capabilities_and_catalog(client,monkeypatch):
    for key in ('SMTP_HOST','DEEPSEEK_API_KEY'):
        monkeypatch.delenv(key,raising=False)
    c=client.get('/api/v1/student/capabilities').json()
    assert not c['email'] and not c['ai'] and not c['merchant']
    catalog=client.get('/api/v1/student/catalog').json()['items']
    assert len(catalog)==84 and len({r['number'] for r in catalog})==84
    assert all(r['source'].startswith('https://') and r['year']==2023 for r in catalog)
    assert client.get('/api/v1/student/catalog?year=2026').json()['items']==[]


def test_login_codes_expire_single_use_attempt_limit(client,smtp):
    req=client.post('/api/v1/student/auth/code',json={'email':'me@qq.com'})
    assert req.status_code==200
    challenge=req.json()['challenge']; code=re.search(r'\d{6}',smtp[0][2]).group()
    assert 'code' not in req.json()
    bad='000000' if code!='000000' else '111111'
    for _ in range(5):
        assert client.post('/api/v1/student/auth/verify',json={'challenge':challenge,'code':bad}).status_code==400
    assert client.post('/api/v1/student/auth/verify',json={'challenge':challenge,'code':code}).status_code==400
    second=client.post('/api/v1/student/auth/code',json={'email':'another@qq.com'}).json()['challenge']
    code=re.search(r'\d{6}',smtp[-1][2]).group()
    result=client.post('/api/v1/student/auth/verify',json={'challenge':second,'code':code})
    assert result.status_code==200 and not result.json()['campus_verified']
    assert client.post('/api/v1/student/auth/verify',json={'challenge':second,'code':code}).status_code==400


def test_email_rate_limit_and_invalid_address(client,smtp):
    assert client.post('/api/v1/student/auth/code',json={'email':'x\r\nBcc:evil@qq.com'}).status_code==422
    assert client.post('/api/v1/student/auth/code',json={'email':'me@qq.com'}).status_code==200
    assert client.post('/api/v1/student/auth/code',json={'email':'me@qq.com'}).status_code==429


def test_private_records_isolation_and_revision(client):
    a,b=account('a'),account('b')
    path='/api/v1/student/records/grade/g1'
    assert client.put(path,json={'data':{'score':91}},headers=a).json()['revision']==1
    assert client.get('/api/v1/student/records/grade',headers=b).json()['items']==[]
    assert client.get('/api/v1/student/records/grade').status_code==401
    assert client.put(path,json={'data':{'score':0},'revision':0},headers=a).status_code==409
    assert client.get('/api/v1/student/records/grade',headers=a).json()['items'][0]['data']['score']==91
    assert client.delete(path+'?revision=0',headers=a).status_code==409


def test_bind_requires_owner_and_does_not_enable_news(client,smtp):
    a,b=account('a'),account('b');subscribe('a',news=True)
    req=client.post('/api/v1/student/email/code',json={'email':'new@qq.com'},headers=a).json()
    code=re.search(r'\d{6}',smtp[-1][2]).group()
    assert client.post('/api/v1/student/email/verify',json={'challenge':req['challenge'],'code':code},headers=b).status_code==400
    before=client.get('/api/v1/student/email',headers=a).json()
    assert before['email']=='a@qq.com'
    assert client.post('/api/v1/student/email/verify',json={'challenge':req['challenge'],'code':code},headers=a).status_code==200
    after=client.get('/api/v1/student/email',headers=a).json()
    assert after['email']=='new@qq.com' and not after['settings']['enabled']


def test_reminder_dedup_cancel_and_preferences(client,smtp):
    a=account('a');subscribe('a',custom=True)
    body={'title':'记得准备材料','due':time.time()+60,'category':'custom'}
    assert client.put('/api/v1/student/reminders/r1',headers=a,json=body).status_code==200
    with connect() as db: db.execute('UPDATE cloud_reminders SET due=?',(time.time()-1,))
    student_mail.tick();student_mail.tick()
    assert len(smtp)==1
    with connect() as db: assert db.execute("SELECT COUNT(*) FROM mail_queue WHERE status='submitted'").fetchone()[0]==1
    assert client.put('/api/v1/student/reminders/r1',headers=a,json={**body,'enabled':False}).status_code==200
    assert client.put('/api/v1/student/reminders/past',headers=a,json={**body,'due':time.time()-30}).status_code==422
    client.delete('/api/v1/student/email',headers=a)
    student_mail.tick();assert len(smtp)==1


def notice(key,title,category='科技',created=None):
    upsert(dict(id=key,url='https://example.com/'+key,title=title,published_at='2026-09-11',category=category,
                summary=title,body=title,deadline=None,deadline_evidence=None,attachments=[],images=[],content_hash=key))
    if created:
        with connect() as db: db.execute('UPDATE notices SET created_at=? WHERE id=?',(created,key))


def test_digest_filters_and_no_historical_notifications(client,smtp):
    a=account('a');subscribe('a',news=True,frequency='hourly',categories=['科技'],keywords=['数学','建模'])
    notice('one','数学建模');notice('two','创业大赛','创业');notice('three','程序设计')
    old=datetime.fromtimestamp(time.time()-20000,timezone.utc).isoformat(timespec='microseconds')
    notice('old','数学旧通知',created=old)
    student_mail.tick();student_mail.tick()
    assert len(smtp)==1 and '数学建模' in smtp[0][2] and '程序设计' not in smtp[0][2] and '数学旧通知' not in smtp[0][2]
    # Starting a new subscription establishes a new baseline.
    client.put('/api/v1/student/email',headers=a,json={'enabled':False})
    client.put('/api/v1/student/email',headers=a,json={'enabled':True,'news':True,'frequency':'hourly'})
    student_mail.tick();assert len(smtp)==1


def team_body():
    return {'contest':'全国大学生数学建模竞赛','title':'寻找建模队友','track':'本科组','capacity':2,'roles':'建模与论文','schedule':'每周五小时'}


def test_team_application_offer_confirmation_and_capacity(client):
    a,b,c=account('a'),account('b'),account('c')
    key=client.post('/api/v1/student/teams',headers=a,json=team_body()).json()['id']
    application={'nickname':'同学','skills':'数学建模','availability':'周末','reason':'一起学习'}
    for h in (b,c): assert client.post(f'/api/v1/student/teams/{key}/apply',headers=h,json=application).status_code==200
    assert client.post(f'/api/v1/student/teams/{key}/apply',headers=b,json=application).status_code==409
    action=f'/api/v1/student/teams/{key}/action'
    assert client.post(action,headers=b,json={'action':'offer','applicant':'c'}).status_code==403
    assert client.post(action,headers=a,json={'action':'offer','applicant':'b'}).status_code==200
    assert client.post(action,headers=a,json={'action':'offer','applicant':'c'}).status_code==409
    assert client.get(f'/api/v1/student/teams/{key}',headers=c).json()['applications'][0]['owner']=='c'
    assert client.post(action,headers=b,json={'action':'confirm'}).status_code==200
    assert client.post(action,headers=b,json={'action':'confirm'}).status_code==409
    assert client.post(action,headers=a,json={'action':'close'}).status_code==409
    assert client.post(action,headers=a,json={'action':'transfer','applicant':'b'}).status_code==200
    assert client.post(action,headers=a,json={'action':'leave'}).status_code==200


def test_expired_offer_releases_slot(client):
    a,b=account('a'),account('b')
    key=client.post('/api/v1/student/teams',headers=a,json=team_body()).json()['id']
    client.post(f'/api/v1/student/teams/{key}/apply',headers=b,json={'nickname':'同学','skills':'数学','availability':'周末','reason':'学习'})
    client.post(f'/api/v1/student/teams/{key}/action',headers=a,json={'action':'offer','applicant':'b'})
    with connect() as db: db.execute('UPDATE team_applications SET expires=?',(time.time()-1,))
    assert client.post(f'/api/v1/student/teams/{key}/action',headers=b,json={'action':'confirm'}).status_code==409
    assert client.get(f'/api/v1/student/teams/{key}',headers=a).json()['reserved']==0


def test_file_private_share_unshare_and_duplicate(client):
    a,b=account('a'),account('b')
    body={'name':'笔记.txt','content':base64.b64encode('我的笔记'.encode()).decode()}
    key=client.post('/api/v1/student/files',headers=a,json=body).json()['id']
    assert client.get(f'/api/v1/student/files/{key}/download',headers=b).status_code==404
    assert client.get(f'/api/v1/student/resources/{key}/download').status_code==404
    assert client.post('/api/v1/student/files',headers=a,json=body).json()['duplicate']
    path=f'/api/v1/student/files/{key}/sharing'
    assert client.put(path,headers=a,json={'public':True}).status_code==422
    assert client.put(path,headers=a,json={'public':True,'rights_confirmed':True}).status_code==200
    assert client.get(f'/api/v1/student/resources/{key}/download').content=='我的笔记'.encode()
    client.put(path,headers=a,json={'public':False})
    assert client.get(f'/api/v1/student/resources/{key}/download').status_code==404


def test_ai_consent_idempotency_and_ownership(client,monkeypatch):
    monkeypatch.setenv('DEEPSEEK_API_KEY','test-not-real')
    a,b=account('a'),account('b')
    body={'request_key':'same-request','kind':'resume','text':'我参与过一个课程项目，负责整理数据与实现界面。','consent':False}
    assert client.post('/api/v1/student/ai/jobs',headers=a,json=body).status_code==422
    body['consent']=True
    first=client.post('/api/v1/student/ai/jobs',headers=a,json=body).json()
    second=client.post('/api/v1/student/ai/jobs',headers=a,json=body).json()
    assert first['id']==second['id']
    assert client.get('/api/v1/student/ai/jobs/'+first['id'],headers=b).status_code==404
    assert client.get('/api/v1/student/ai/jobs',headers=a).json()['items'][0]['status']=='queued'


def test_no_service_configuration_is_explicit(client,monkeypatch):
    a=account('a')
    monkeypatch.delenv('SMTP_HOST',raising=False);monkeypatch.delenv('DEEPSEEK_API_KEY',raising=False)
    assert client.post('/api/v1/student/auth/code',json={'email':'valid@qq.com'}).status_code==503
    assert client.post('/api/v1/student/ai/jobs',headers=a,json={'request_key':'no-config','kind':'resume','text':'这是一份足够长的真实学生简历文本用于测试无配置状态。','consent':True}).status_code==503


def test_team_results_require_personal_confirmation_and_are_private(client):
    a,b=account('a'),account('b')
    key=client.post('/api/v1/student/teams',headers=a,json=team_body()).json()['id']
    result=client.post(f'/api/v1/student/teams/{key}/results',headers=a,json={'title':'课程竞赛成果','result':'完成了作品展示'}).json()['id']
    assert client.get('/api/v1/student/records/portfolio',headers=a).json()['items']==[]
    assert client.get(f'/api/v1/student/team-results/{result}',headers=b).status_code==404
    path=f'/api/v1/student/team-results/{result}/confirm'
    confirmed=client.post(path,headers=a,json={'role':'数据整理','note':'清理了原始记录','accepted':True})
    assert confirmed.status_code==200
    assert client.post(path,headers=a,json={'role':'数据整理','note':'清理了原始记录','accepted':True}).json()['duplicate']
    saved=client.get('/api/v1/student/records/portfolio',headers=a).json()['items']
    assert len(saved)==1 and saved[0]['data']['points'] is None
    assert len(client.get('/api/v1/student/messages',headers=a).json()['items'])==1
    assert client.get('/api/v1/student/messages',headers=b).json()['items']==[]


def test_blocking_and_post_edit_are_owner_bound(client):
    a,b=account('a'),account('b')
    post={'contest':'数学建模','title':'我的备赛经历','year':'2023','body':'准备过程中，我主要负责资料整理和论文排版。'}
    key=client.post('/api/v1/student/posts',headers=a,json=post).json()['id']
    assert client.put('/api/v1/student/posts/'+key,headers=b,json=post).status_code==404
    assert client.post('/api/v1/student/blocks',headers=b,json={'target':'a','enabled':True}).status_code==200
    assert client.get('/api/v1/student/posts',headers=b).json()['items']==[]
    assert len(client.get('/api/v1/student/posts').json()['items'])==1


def test_concurrent_offers_reserve_one_last_slot(client):
    a,b,c=account('a'),account('b'),account('c')
    key=client.post('/api/v1/student/teams',headers=a,json=team_body()).json()['id']
    payload={'nickname':'同学','skills':'数学','availability':'周末','reason':'学习'}
    for user in (b,c):client.post(f'/api/v1/student/teams/{key}/apply',headers=user,json=payload)
    def offer(owner):return client.post(f'/api/v1/student/teams/{key}/action',headers=a,json={'action':'offer','applicant':owner}).status_code
    with ThreadPoolExecutor(2) as pool:statuses=list(pool.map(offer,['b','c']))
    assert sorted(statuses)==[200,409]
