"""Contest identity, reviewed source registry, versioned notices and opt-in follows."""
import hashlib
import json
import re
import threading
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path
from urllib.parse import urljoin, urlparse

import httpx
from bs4 import BeautifulSoup
from fastapi import APIRouter, Depends, HTTPException, Query
from pydantic import BaseModel
from .database import connect
from .student_store import current_user, optional_user, encode, notify

router = APIRouter(prefix='/api/v1/student/contests')
LOCK = threading.Lock()
REGISTRY = json.loads(Path(__file__).with_name('contest_sources.json').read_text('utf-8'))
INDEX = {c['id']: c for c in REGISTRY}


def initialize():
    with connect() as db:
        db.executescript('''
        CREATE TABLE IF NOT EXISTS contest_checks(id TEXT PRIMARY KEY,attempt REAL,success REAL,error TEXT,baseline INTEGER DEFAULT 0);
        CREATE TABLE IF NOT EXISTS contest_events(id TEXT PRIMARY KEY,contest TEXT NOT NULL,url TEXT NOT NULL,title TEXT NOT NULL,
          body TEXT NOT NULL,scope TEXT NOT NULL,stages TEXT NOT NULL,hash TEXT NOT NULL,version INTEGER NOT NULL,
          created REAL NOT NULL,updated REAL NOT NULL,UNIQUE(contest,url));
        CREATE TABLE IF NOT EXISTS contest_history(event TEXT NOT NULL,version INTEGER NOT NULL,data TEXT NOT NULL,created REAL NOT NULL,PRIMARY KEY(event,version));
        CREATE TABLE IF NOT EXISTS contest_follows(owner TEXT NOT NULL,contest TEXT NOT NULL,news INTEGER NOT NULL,deadline INTEGER NOT NULL,
          created REAL NOT NULL,PRIMARY KEY(owner,contest));
        CREATE TABLE IF NOT EXISTS notice_contests(notice TEXT NOT NULL,contest TEXT NOT NULL,PRIMARY KEY(notice,contest));
        CREATE TABLE IF NOT EXISTS contest_event_details(event TEXT PRIMARY KEY,data TEXT NOT NULL);
        ''')
        for item in REGISTRY:
            db.execute('INSERT OR IGNORE INTO contest_checks(id) VALUES (?)', (item['id'],))


def item(key):
    if key not in INDEX: raise HTTPException(404, '赛事不存在')
    return INDEX[key]


def matches(c, text):
    compact = re.sub(r'[\s“”"·+＋—-]', '', text).lower()
    terms = [c['name'], c.get('current_name', '')] + c['aliases']
    found = any(re.sub(r'[\s“”"·+＋—-]', '', t).lower() in compact for t in terms if len(t) >= 3)
    if c['number'] == 2: return found and ('课外学术' in text or '作品竞赛' in text)
    if c['number'] == 3: return found and '创业计划' in text
    return found


@router.get('')
def listing(q: str = Query('', max_length=100), followed: bool = False, owner=Depends(optional_user)):
    with connect() as db:
        follows = {r['contest']:dict(r) for r in db.execute('SELECT * FROM contest_follows WHERE owner=?', (owner or '',))}
        checks = {r['id']:dict(r) for r in db.execute('SELECT * FROM contest_checks')}
        counts = {r['contest']:r['n'] for r in db.execute('SELECT contest,count(*) n FROM contest_events GROUP BY contest')}
    items = [{**c, 'follow':follows.get(c['id']), 'check':checks.get(c['id']), 'notice_count':counts.get(c['id'],0)} for c in REGISTRY
             if (not q or q.lower() in encode(c).lower()) and (not followed or c['id'] in follows)]
    return {'items':items,'total':len(items),'directory_year':2023,'verified_count':sum(c['verification']=='verified' for c in REGISTRY)}


@router.get('/{key}')
def detail(key: str, owner=Depends(optional_user)):
    c = dict(item(key))
    with connect() as db:
        row = db.execute('SELECT * FROM contest_follows WHERE owner=? AND contest=?', (owner or '',key)).fetchone()
        c['follow'] = dict(row) if row else None
        row = db.execute('SELECT * FROM contest_checks WHERE id=?',(key,)).fetchone()
        c['check'] = dict(row) if row else {}
        c['events'] = [{**dict(r),'stages':json.loads(r['stages'])} for r in db.execute('SELECT * FROM contest_events WHERE contest=? ORDER BY updated DESC LIMIT 100',(key,))]
    return c


class Follow(BaseModel):
    enabled: bool
    news: bool = True
    deadline: bool = True


@router.put('/{key}/follow')
def follow(key: str, body: Follow, owner=Depends(current_user)):
    item(key)
    with connect() as db:
        db.execute('BEGIN IMMEDIATE')
        db.execute("UPDATE mail_queue SET status='cancelled' WHERE owner=? AND id LIKE ? AND status='pending'",(owner,f'contest:{owner}:{key}:%'))
        if body.enabled:
            db.execute('INSERT INTO contest_follows VALUES (?,?,?,?,?) ON CONFLICT(owner,contest) DO UPDATE SET news=excluded.news,deadline=excluded.deadline',
                       (owner,key,int(body.news),int(body.deadline),time.time()))
            # Existing future deadlines are useful, but never send historical news on follow.
            if body.deadline:
                for row in db.execute('SELECT * FROM contest_events WHERE contest=?',(key,)).fetchall(): enqueue(db,row,owner,False,True)
        else: db.execute('DELETE FROM contest_follows WHERE owner=? AND contest=?',(owner,key))
    return {'ok':True, 'message':'已保存；邮件需在邮箱设置中绑定并启用对应提醒'}


@router.get('/{key}/events/{event}/history')
def history(key: str, event: str):
    item(key)
    with connect() as db:
        if not db.execute('SELECT 1 FROM contest_events WHERE id=? AND contest=?',(event,key)).fetchone():raise HTTPException(404)
        return {'items':[json.loads(r['data']) for r in db.execute('SELECT data FROM contest_history WHERE event=? ORDER BY version DESC LIMIT 20',(event,))]}


def stages(text, scope):
    """Only explicit full dates in one unambiguous deadline clause; no year/time guessing."""
    result=[]
    for sentence in re.split(r'[。；;\n]',text):
        if len(sentence)>240 or not re.search(r'截止|截至|最晚',sentence):continue
        kinds=[(name,word) for name,word in [('报名','报名'),('提交作品','提交'),('资格审核','审核')] if word in sentence]
        dates=list(re.finditer(r'(20\d{2})[年/.-](\d{1,2})[月/.-](\d{1,2})日?(?:\s*(\d{1,2})[:：](\d{2}))?',sentence))
        if len(kinds)!=1 or len(dates)!=1:continue
        d=dates[0]
        try:datetime(int(d[1]),int(d[2]),int(d[3]))
        except ValueError:continue
        hour=int(d[4]) if d[4] else None;minute=int(d[5]) if d[5] else None
        if hour is not None and (hour>23 or minute>59):continue
        day=f'{int(d[1]):04}-{int(d[2]):02}-{int(d[3]):02}'
        result.append({'stage':kinds[0][0], 'date':day,'time':f'{hour:02}:{minute:02}' if hour is not None else '',
                       'precision':'minute' if hour is not None else 'day','scope':scope,'evidence':sentence.strip(),'timezone':'Asia/Shanghai'})
    return result


def enqueue(db,event,owner,news,deadline):
    sub=db.execute('SELECT * FROM subscriptions WHERE owner=?',(owner,)).fetchone()
    if not sub:return
    config=json.loads(sub['data'])
    if not config.get('enabled'):return
    prefix=f'contest:{owner}:{event["contest"]}:{event["id"]}:'
    if news and config.get('news'):
        key=prefix+str(event['version'])+':news'
        db.execute('INSERT OR IGNORE INTO mail_queue(id,owner,recipient,subject,body,due,category) VALUES (?,?,?,?,?,?,?)',
          (key,owner,sub['email'],'赛事通知更新：'+event['title'],event['title']+'\n'+event['url']+'\n请查看原文及校内要求。',time.time(),'news'))
    if deadline and config.get('deadline'):
        for i,s in enumerate(json.loads(event['stages'])):
            # At 09:00 the prior day is a reminder time, never an inferred official deadline time.
            due=(datetime.fromisoformat(s['date']).replace(hour=9,tzinfo=timezone(timedelta(hours=8)))-timedelta(days=1)).timestamp()
            if due<=time.time():continue
            key=prefix+str(event['version'])+':deadline:'+str(i)
            db.execute("INSERT INTO mail_queue(id,owner,recipient,subject,body,due,category) VALUES (?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET status='pending',recipient=excluded.recipient,due=excluded.due WHERE mail_queue.status='cancelled'",
              (key,owner,sub['email'],'明日事项：'+event['title'],s['scope']+' · '+s['stage']+'\n原文：'+s['evidence']+'\n'+event['url'],due,'deadline'))


def save_event(contest,url,title,body,scope,alert=True,metadata=None):
    key=hashlib.sha256((contest+'|'+url).encode()).hexdigest()[:32]
    if metadata is None:
        with connect() as db:
            saved=db.execute('SELECT data FROM contest_event_details WHERE event=?',(key,)).fetchone()
        metadata=json.loads(saved['data']) if saved else {}
    digest=hashlib.sha256(encode([title,body,scope,metadata]).encode()).hexdigest();stamp=time.time()
    with connect() as db:
        db.execute('BEGIN IMMEDIATE')
        old=db.execute('SELECT * FROM contest_events WHERE id=?',(key,)).fetchone()
        if old and old['hash']==digest:return False
        version=old['version']+1 if old else 1
        event={'id':key,'contest':contest,'url':url,'title':title,'body':body,'scope':scope,'stages':encode(stages(body,scope)),
               'hash':digest,'version':version,'created':old['created'] if old else stamp,'updated':stamp}
        db.execute('INSERT OR REPLACE INTO contest_events VALUES (:id,:contest,:url,:title,:body,:scope,:stages,:hash,:version,:created,:updated)',event)
        db.execute('INSERT INTO contest_history VALUES (?,?,?,?)',(key,version,encode({**event,'metadata':metadata}),stamp))
        # Superseded deadlines must not remain queued.
        db.execute("UPDATE mail_queue SET status='cancelled' WHERE id LIKE ? AND status='pending'",(f'contest:%:{contest}:{key}:%',))
        for f in db.execute('SELECT * FROM contest_follows WHERE contest=?',(contest,)).fetchall():
            if alert and f['news']:notify(db,f['owner'],'赛事通知有变化',title,'contest:'+contest)
            enqueue(db,event,f['owner'],alert and f['news'],f['deadline'])
        if metadata is not None:
            db.execute('INSERT OR REPLACE INTO contest_event_details VALUES (?,?)',(key,encode(metadata)))
    project_event(event,silent=not alert)
    return True


def project_event(event,silent=False):
    from .notice_projection import project_event as project
    return project(event, INDEX, silent)


def project_existing():
    with connect() as db:
        events=[dict(r) for r in db.execute('SELECT * FROM contest_events ORDER BY updated')]
    for event in events: project_event(event,silent=True)


def sync_school():
    with connect() as db: rows=db.execute("SELECT * FROM notices WHERE source='青岛理工大学创新创业学院' ORDER BY updated_at DESC LIMIT 1000").fetchall()
    for c in REGISTRY:
        with connect() as db: baseline=bool(db.execute('SELECT baseline FROM contest_checks WHERE id=?',(c['id'],)).fetchone()[0])
        for r in rows:
            if matches(c,r['title']):save_event(c['id'],r['url'],r['title'],r['body'],'青岛理工大学校内',baseline)
    with connect() as db:db.execute('UPDATE contest_checks SET baseline=1')


def article_content(soup,url):
    """Read article scope before removing navigation; never infer publication from deadlines."""
    from .parser import source_url
    publication=''
    for selector in ('meta[property="article:published_time"]','meta[name="pubdate"]','meta[name="publishdate"]','time[datetime]'):
        node=soup.select_one(selector)
        raw=(node.get('content') or node.get('datetime') or '') if node else ''
        match=re.search(r'20\d{2}[-/]\d{1,2}[-/]\d{1,2}',raw)
        if match:
            try: publication=datetime.strptime(match[0].replace('/','-'),'%Y-%m-%d').date().isoformat()
            except ValueError: pass
            if publication: break
    main=soup.select_one('article, .v_news_content, #vsb_content, .article-content, .news-content, .TRS_Editor, .wp_articlecontent') or soup
    attachments=[]
    for a in main.select('a[href]'):
        link=source_url(a.get('href',''),url)
        if link and re.search(r'\.(pdf|docx?|xlsx?|zip|rar|pptx?)(?:$|\?)',link,re.I):
            attachments.append({'name':a.get_text(' ',strip=True) or urlparse(link).path.split('/')[-1],'url':link})
    for tag in main(['script','style','nav','header','footer']):tag.decompose()
    return main.get_text('\n',strip=True)[:40000],{'published_at':publication,'attachments':attachments,'images':[]}


def fetch(url,host):
    if urlparse(url).hostname!=host or urlparse(url).scheme not in ('http','https'):raise ValueError('source boundary')
    with httpx.Client(timeout=15,follow_redirects=False,headers={'User-Agent':'QutCompetition/2.2 (public competition notices)'}) as client:
        for _ in range(3):
            with client.stream('GET',url) as r:
                if r.is_redirect:
                    url=urljoin(url,r.headers['location'])
                    if urlparse(url).hostname!=host:raise ValueError('new domain requires review')
                    continue
                r.raise_for_status()
                if 'html' not in r.headers.get('content-type','').lower():raise ValueError('not an html page')
                raw=bytearray()
                for chunk in r.iter_bytes():
                    raw.extend(chunk)
                    if len(raw)>2_000_000:raise ValueError('page too large')
                return BeautifulSoup(bytes(raw),'html.parser'),url
    raise ValueError('redirect loop')


def sync_source(c):
    with connect() as db:
        db.execute('UPDATE contest_checks SET attempt=? WHERE id=?',(time.time(),c['id']))
        initial=not bool(db.execute('SELECT success FROM contest_checks WHERE id=?',(c['id'],)).fetchone()[0])
    try:
        host=urlparse(c['notice_url']).hostname
        soup,url=fetch(c['notice_url'],host)
        if c['verification']!='verified' and not matches(c,soup.get_text(' ',strip=True)):
            raise ValueError('identity_unconfirmed')
        links={}
        for a in soup.select('a[href]'):
            title=a.get_text(' ',strip=True);link=urljoin(url,a['href']).split('#')[0]
            if len(title)>=8 and re.search(r'通知|报名|规程|章程|竞赛规则|参赛须知',title) and urlparse(link).hostname==host and link!=url:
                links[link]=title
        collected=0
        for link,title in list(links.items())[:8]:
            try:
                article,canonical=fetch(link,host)
                body,metadata=article_content(article,canonical)
                if sum(urlparse(x['notice_url']).hostname==host for x in REGISTRY)>1 and not matches(c,title+' '+body):
                    continue
                if len(body)>100:
                    scope='赛事主办方（赛区适用范围请核对原文）' if c['verification']=='verified' else '目录候选来源（主办方身份仍待人工核验）'
                    save_event(c['id'],canonical,title,body,scope,not initial,metadata)
                    collected+=1
            except Exception:continue
        with connect() as db:
            if collected:
                db.execute('UPDATE contest_checks SET success=?,error=? WHERE id=?',(time.time(),'',c['id']))
            else:
                db.execute('UPDATE contest_checks SET error=? WHERE id=?',('网页可访问，但尚未成功解析通知正文；需动态适配或人工核对。',c['id']))
        return {'id':c['id'],'collected':collected}
    except Exception as exc:
        message='来源页面身份尚未核实，暂不收录内容。' if str(exc)=='identity_unconfirmed' else '官网检查未成功，已有内容保留；将自动重试。'
        with connect() as db:db.execute('UPDATE contest_checks SET error=? WHERE id=?',(message,c['id']))
        return {'id':c['id'],'collected':0,'error':message}


def tick(full=False):
    if not LOCK.acquire(False):return {'busy':True}
    try:
        sync_school()
        with connect() as db:
            checks={r['id']:r['attempt'] or 0 for r in db.execute('SELECT id,attempt FROM contest_checks')}
        candidates=sorted([c for c in REGISTRY if c['verification']!='archived' and (full or checks[c['id']]<time.time()-3600)],key=lambda c:checks[c['id']])
        # Cover the 84-entry directory in batches; historical sources stay visible but inactive.
        from concurrent.futures import ThreadPoolExecutor
        with ThreadPoolExecutor(max_workers=4) as pool:
            results=list(pool.map(sync_source,candidates if full else candidates[:8]))
        return {'checked':len(results),'items':results,'archived':sum(c['verification']=='archived' for c in REGISTRY)}
    finally:LOCK.release()
