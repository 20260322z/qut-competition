import logging
import os
from contextlib import asynccontextmanager
from datetime import datetime, timezone

from apscheduler.schedulers.background import BackgroundScheduler
from fastapi import FastAPI, HTTPException, Query

from . import crawler
from .database import connect, initialize, now, public_notice
from .parser import LIST_URL

logging.basicConfig(level=logging.INFO, format='%(asctime)s %(levelname)s %(name)s %(message)s')


@asynccontextmanager
async def lifespan(app):
    initialize()
    scheduler = BackgroundScheduler(timezone='Asia/Shanghai')
    if os.getenv('DISABLE_SCHEDULER') != '1':
        scheduler.add_job(crawler.sync, 'interval', hours=1, id='hourly',
                          next_run_time=datetime.now(timezone.utc), max_instances=1, coalesce=True)
        scheduler.add_job(crawler.sync, 'cron', hour=5, minute=15,
                          kwargs={'revisit': True}, id='revisit', max_instances=1, coalesce=True)
        scheduler.start()
    yield
    if scheduler.running:
        scheduler.shutdown(wait=False)


app = FastAPI(title='青理竞赛通 API', version='1.0.0', lifespan=lifespan,
              docs_url=None, redoc_url=None, openapi_url=None)


@app.get('/health')
def health():
    with connect() as db:
        count = db.execute('SELECT COUNT(*) FROM notices').fetchone()[0]
    return {'status': 'ok', 'version': '1.0.0', 'notice_count': count, 'time': now()}


@app.get('/api/v1/notices')
def notices(q: str = Query('', max_length=100), category: str = '',
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
        state = dict(db.execute("SELECT * FROM source_state WHERE id='qut'").fetchone())
        count = db.execute('SELECT COUNT(*) FROM notices').fetchone()[0]
    state.update(name='学校创新创业学院', url=LIST_URL, notice_count=count, interval_minutes=60)
    return {'items': [state], 'reference': {
        'name': '微信参考文章', 'mode': 'external_link',
        'url': 'https://mp.weixin.qq.com/s/BvhMwEWH8_bJdys8FNIByw'}}
