"""Project collected articles into the shared notice store without duplicating URLs."""
import hashlib
import json
from urllib.parse import urlparse
from .database import connect

def encode(value):
    return json.dumps(value,ensure_ascii=False,sort_keys=True)

def project_event(event, index, silent=False):
    """One canonical notice per URL; contest memberships are separate from content."""
    from .database import upsert, now
    from .parser import categorize
    with connect() as db:
        existing = db.execute('SELECT * FROM notices WHERE url=?',(event['url'],)).fetchone()
        extra = db.execute('SELECT data FROM contest_event_details WHERE event=?',(event['id'],)).fetchone()
    metadata = json.loads(extra['data']) if extra else {}
    key = existing['id'] if existing else hashlib.sha256(event['url'].encode()).hexdigest()[:20]
    if not existing or existing['source'].startswith('赛事'):
        dates = [s for s in json.loads(event['stages']) if s['stage']=='报名']
        # Only precise, unambiguous official times become device deadline timestamps.
        date = dates[0] if len(dates)==1 else None
        deadline = date['date']+'T'+date['time']+':00+08:00' if date and date['time'] else None
        record = dict(id=key,url=event['url'],title=event['title'],published_at=metadata.get('published_at',''),
            category=categorize(event['title']+' '+index[event['contest']]['name']),summary=event['body'][:180],
            body=event['body'],deadline=deadline,deadline_evidence=date['evidence'] if date else None,
            attachments=metadata.get('attachments',[]),images=metadata.get('images',[]),
            source=('赛事官网 · ' if index[event['contest']].get('verification')=='verified' else '赛事来源（待核验） · ')+urlparse(event['url']).hostname)
        record['content_hash']=hashlib.sha256(encode(record).encode()).hexdigest()
        upsert(record)
    with connect() as db:
        if not existing and silent:
            db.execute('UPDATE notices SET silent_import=1 WHERE id=?',(key,))
        result=db.execute('INSERT OR IGNORE INTO notice_contests VALUES (?,?)',(key,event['contest']))
        if result.rowcount:
            db.execute('UPDATE notices SET updated_at=? WHERE id=?',(now(),key))
