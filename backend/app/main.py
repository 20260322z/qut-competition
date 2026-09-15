import hashlib
import hmac
import json
import logging
import os
from contextlib import asynccontextmanager
from datetime import datetime, timezone

from apscheduler.schedulers.background import BackgroundScheduler
from fastapi import FastAPI, Header, HTTPException, Query, Request

from . import crawler, qq_ingest, qq_review
from .database import connect, initialize, now, public_notice
from .parser import LIST_URL
from . import student_store, student_api, student_mail, student_ai, student_collaboration
from . import workspace_agents, workspace_files, workspace_contests

logging.basicConfig(level=logging.INFO, format='%(asctime)s %(levelname)s %(name)s %(message)s')
APP_VERSION = '2.4.0'


def authorized_napcat(authorization: str | None, access_token: str | None,
                      signature: str | None = None, body: bytes = b''):
    token = os.getenv('NAPCAT_TOKEN', '').strip()
    if not token:
        return False
    if (authorization or '') in {f'Bearer {token}', token} or (access_token or '') == token:
        return True
    if signature and body:
        expected = 'sha1=' + hmac.new(token.encode(), body, hashlib.sha1).hexdigest()
        if hmac.compare_digest(signature, expected):
            return True
    return False


@asynccontextmanager
async def lifespan(app):
    initialize()
    student_store.initialize()
    workspace_agents.initialize()
    workspace_files.initialize()
    workspace_contests.initialize()
    from .notice_migration import retire_qq
    retire_qq()
    workspace_contests.project_existing()
    scheduler = BackgroundScheduler(timezone='Asia/Shanghai')
    if os.getenv('DISABLE_SCHEDULER') != '1':
        scheduler.add_job(crawler.sync, 'interval', hours=1, id='hourly',
                          next_run_time=datetime.now(timezone.utc), max_instances=1, coalesce=True)
        scheduler.add_job(crawler.sync, 'cron', hour=5, minute=15,
                          kwargs={'revisit': True}, id='revisit', max_instances=1, coalesce=True)
        scheduler.start()
        scheduler.add_job(student_mail.tick, 'interval', seconds=30, id='student-mail', max_instances=1, coalesce=True)
        scheduler.add_job(student_ai.tick, 'interval', seconds=10, id='student-ai', max_instances=1, coalesce=True)
        scheduler.add_job(workspace_agents.tick, 'interval', seconds=3, id='workspace-agents', max_instances=1, coalesce=True)
        scheduler.add_job(workspace_contests.tick, 'interval', minutes=5, id='contest-sources', next_run_time=datetime.now(timezone.utc), max_instances=1, coalesce=True)
    yield
    if scheduler.running:
        scheduler.shutdown(wait=False)


app = FastAPI(title='青理竞赛通 API', version=APP_VERSION, lifespan=lifespan,
              docs_url=None, redoc_url=None, openapi_url=None)
app.include_router(student_mail.router)
app.include_router(student_api.router)
app.include_router(student_ai.router)
app.include_router(student_collaboration.router)
app.include_router(workspace_agents.router)
app.include_router(workspace_files.router)
app.include_router(workspace_contests.router)


@app.get('/health')
def health():
    with connect() as db:
        count = db.execute('SELECT COUNT(*) FROM notices').fetchone()[0]
        qq_saved = db.execute("SELECT COUNT(*) FROM notices WHERE source LIKE 'QQ群%'").fetchone()[0]
    return {'status': 'ok', 'version': APP_VERSION, 'notice_count': count,
            'qq_notice_count': qq_saved, 'time': now()}


@app.get('/api/v1/notices')
def notices(q: str = Query('', max_length=100), category: str = '', source: str = '',
            page: int = Query(1, ge=1), page_size: int = Query(20, ge=1, le=100),
            updated_since: datetime | None = None, sync_before: datetime | None = None, contest: str = ''):
    conditions, values = ["source NOT LIKE 'QQ群%'", "url NOT LIKE 'qq://%'"], []
    if q:
        conditions.append("(title LIKE ? ESCAPE '\\' OR body LIKE ? ESCAPE '\\')")
        escaped = q.replace('\\', '\\\\').replace('%', '\\%').replace('_', '\\_')
        values.extend(['%' + escaped + '%'] * 2)
    if category:
        conditions.append('category=?')
        values.append(category)
    if source in ('official', '官网'):
        conditions.append("source NOT LIKE 'QQ群%'")
    elif source in ('qq', 'QQ群'):
        conditions.append("source LIKE 'QQ群%'")
    if contest:
        conditions.append('id IN (SELECT notice FROM notice_contests WHERE contest=?)')
        values.append(contest)
    if updated_since:
        conditions.append('updated_at > ?')
        values.append(updated_since.astimezone(timezone.utc).isoformat(timespec='microseconds'))
    before = (sync_before or datetime.now(timezone.utc)).astimezone(timezone.utc).isoformat(timespec='microseconds')
    conditions.append('updated_at <= ?')
    values.append(before)
    where = ' WHERE ' + ' AND '.join(conditions)
    with connect() as db:
        total = db.execute('SELECT COUNT(*) FROM notices' + where, values).fetchone()[0]
        rows = db.execute('SELECT * FROM notices' + where + ' ORDER BY published_at DESC, id DESC LIMIT ? OFFSET ?',
                          values + [page_size, (page - 1) * page_size]).fetchall()
        deleted = [r['id'] for r in db.execute('SELECT id FROM notice_deletions WHERE deleted_at<=?', (before,))]
    return {'items': [public_notice(row) for row in rows], 'deleted_ids': deleted, 'total': total,
            'page': page, 'page_size': page_size, 'sync_before': before}


@app.get('/api/v1/notices/{notice_id}')
def notice(notice_id: str):
    with connect() as db:
        row = db.execute('SELECT * FROM notices WHERE id=?', (notice_id,)).fetchone()
    if row is None or row['source'].startswith('QQ群') or row['url'].startswith('qq://'):
        raise HTTPException(status_code=404, detail='通知不存在')
    return public_notice(row)


@app.get('/api/v1/sources')
def sources():
    with connect() as db:
        official = dict(db.execute("SELECT * FROM source_state WHERE id='qut'").fetchone())
        official_count = db.execute("SELECT COUNT(*) FROM notices WHERE source='青岛理工大学创新创业学院'").fetchone()[0]
        checks = [dict(r) for r in db.execute('SELECT * FROM contest_checks')]
        contest_count = db.execute("SELECT COUNT(*) FROM notices WHERE source LIKE '赛事%'").fetchone()[0]
    official.update(name='学校创新创业学院', url=LIST_URL, notice_count=official_count, interval_minutes=60)
    latest = max((r['success'] or 0 for r in checks), default=0)
    registry = {'id':'contests','name':'84项赛事来源','notice_count':contest_count,
        'last_success': datetime.fromtimestamp(latest,timezone.utc).isoformat() if latest else None,
        'last_error':None,'interval_minutes':60,'directory_count':len(workspace_contests.REGISTRY),
        'successful_sources':sum(bool(r['success']) and not bool(r['error']) for r in checks),
        'failed_sources':sum(bool(r['error']) for r in checks)}
    return {'items':[official,registry]}


@app.post('/internal/qq/event')
async def qq_event(request: Request, authorization: str | None = Header(default=None),
                   access_token: str | None = Query(default=None),
                   x_signature: str | None = Header(default=None)):
    raise HTTPException(status_code=410, detail='QQ群通知采集已停用')
