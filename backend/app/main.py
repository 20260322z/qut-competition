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

logging.basicConfig(level=logging.INFO, format='%(asctime)s %(levelname)s %(name)s %(message)s')
APP_VERSION = '1.1.0'


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
    scheduler = BackgroundScheduler(timezone='Asia/Shanghai')
    if os.getenv('DISABLE_SCHEDULER') != '1':
        scheduler.add_job(crawler.sync, 'interval', hours=1, id='hourly',
                          next_run_time=datetime.now(timezone.utc), max_instances=1, coalesce=True)
        scheduler.add_job(crawler.sync, 'cron', hour=5, minute=15,
                          kwargs={'revisit': True}, id='revisit', max_instances=1, coalesce=True)
        scheduler.add_job(qq_ingest.poll, 'interval', minutes=3, id='qq-poll',
                          max_instances=1, coalesce=True)
        scheduler.add_job(qq_review.review, 'interval', minutes=15, id='qq-review',
                          next_run_time=datetime.now(timezone.utc), max_instances=1, coalesce=True)
        scheduler.start()
    yield
    if scheduler.running:
        scheduler.shutdown(wait=False)


app = FastAPI(title='青理竞赛通 API', version=APP_VERSION, lifespan=lifespan,
              docs_url=None, redoc_url=None, openapi_url=None)


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
            updated_since: datetime | None = None, sync_before: datetime | None = None):
    conditions, values = [], []
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
    return {'items': [public_notice(row) for row in rows], 'total': total,
            'page': page, 'page_size': page_size, 'sync_before': before}


@app.get('/api/v1/notices/{notice_id}')
def notice(notice_id: str):
    with connect() as db:
        row = db.execute('SELECT * FROM notices WHERE id=?', (notice_id,)).fetchone()
    if row is None:
        raise HTTPException(status_code=404, detail='通知不存在')
    return public_notice(row)


@app.get('/api/v1/sources')
def sources():
    with connect() as db:
        official = dict(db.execute("SELECT * FROM source_state WHERE id='qut'").fetchone())
        qq = dict(db.execute("SELECT * FROM source_state WHERE id='qq'").fetchone())
        official_count = db.execute("SELECT COUNT(*) FROM notices WHERE source NOT LIKE 'QQ群%'").fetchone()[0]
        qq_count = db.execute("SELECT COUNT(*) FROM notices WHERE source LIKE 'QQ群%'").fetchone()[0]
        pending = db.execute(
            "SELECT COUNT(*) FROM qq_messages WHERE regex_matched=1 AND review_status IN ('pending', 'error')"
        ).fetchone()[0]
    official.update(name='学校创新创业学院', url=LIST_URL, notice_count=official_count, interval_minutes=60)
    qq.update(name='QQ竞赛群', mode='napcat', notice_count=qq_count,
              pending_review=pending, interval_minutes=15)
    return {'items': [official, qq], 'reference': {
        'name': '微信参考文章', 'mode': 'external_link',
        'url': 'https://mp.weixin.qq.com/s/BvhMwEWH8_bJdys8FNIByw'}}


@app.post('/internal/qq/event')
async def qq_event(request: Request, authorization: str | None = Header(default=None),
                   access_token: str | None = Query(default=None),
                   x_signature: str | None = Header(default=None)):
    body = await request.body()
    if not authorized_napcat(authorization, access_token, x_signature, body):
        logging.getLogger('qut.qq').warning(
            'QQ webhook rejected headers=%s',
            sorted(k.lower() for k in request.headers.keys()))
        raise HTTPException(status_code=403, detail='无权上报')
    payload = json.loads(body or b'{}')
    return qq_ingest.handle_event(payload)
