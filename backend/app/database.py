import json
import os
import sqlite3
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path


def now():
    return datetime.now(timezone.utc).isoformat(timespec='microseconds')


@contextmanager
def connect():
    path = Path(os.getenv('DATABASE_PATH', '/data/notices.db'))
    path.parent.mkdir(parents=True, exist_ok=True)
    db = sqlite3.connect(path, timeout=30)
    db.row_factory = sqlite3.Row
    try:
        yield db
        db.commit()
    except Exception:
        db.rollback()
        raise
    finally:
        db.close()


def initialize():
    with connect() as db:
        db.execute('PRAGMA journal_mode=WAL')
        db.executescript('''
            CREATE TABLE IF NOT EXISTS notices (
                id TEXT PRIMARY KEY, url TEXT NOT NULL UNIQUE,
                title TEXT NOT NULL, published_at TEXT NOT NULL,
                category TEXT NOT NULL, summary TEXT NOT NULL,
                body TEXT NOT NULL, deadline TEXT,
                deadline_evidence TEXT, attachments TEXT NOT NULL,
                images TEXT NOT NULL, content_hash TEXT NOT NULL,
                created_at TEXT NOT NULL, updated_at TEXT NOT NULL
            );
            CREATE INDEX IF NOT EXISTS notices_published ON notices(published_at DESC);
            CREATE INDEX IF NOT EXISTS notices_updated ON notices(updated_at);
            CREATE TABLE IF NOT EXISTS source_state (
                id TEXT PRIMARY KEY, last_attempt TEXT, last_success TEXT,
                last_error TEXT, running INTEGER NOT NULL DEFAULT 0
            );
            INSERT OR IGNORE INTO source_state(id) VALUES ('qut');
            INSERT OR IGNORE INTO source_state(id) VALUES ('qq');
            CREATE TABLE IF NOT EXISTS qq_messages (
                id TEXT PRIMARY KEY,
                group_id TEXT NOT NULL,
                group_name TEXT NOT NULL DEFAULT '',
                sender_id TEXT NOT NULL DEFAULT '',
                sender_name TEXT NOT NULL DEFAULT '',
                message_id TEXT NOT NULL,
                raw_text TEXT NOT NULL,
                links TEXT NOT NULL DEFAULT '[]',
                received_at TEXT NOT NULL,
                regex_matched INTEGER NOT NULL DEFAULT 0,
                review_status TEXT NOT NULL DEFAULT 'pending',
                review_reason TEXT,
                review_attempts INTEGER NOT NULL DEFAULT 0,
                reviewed_at TEXT,
                notice_id TEXT,
                UNIQUE(group_id, message_id)
            );
            CREATE INDEX IF NOT EXISTS qq_messages_status ON qq_messages(review_status, regex_matched);
        ''')
        columns = {row[1] for row in db.execute('PRAGMA table_info(notices)')}
        if 'source' not in columns:
            db.execute("ALTER TABLE notices ADD COLUMN source TEXT NOT NULL DEFAULT '青岛理工大学创新创业学院'")
        db.execute("CREATE INDEX IF NOT EXISTS notices_source ON notices(source)")


def upsert(notice):
    stamp = now()
    with connect() as db:
        existing = db.execute('SELECT content_hash FROM notices WHERE id=?', (notice['id'],)).fetchone()
        if existing and existing['content_hash'] == notice['content_hash']:
            return False
        record = {**notice, 'created_at': stamp, 'updated_at': stamp}
        for field in ('attachments', 'images'):
            record[field] = json.dumps(record[field], ensure_ascii=False)
        columns = list(record)
        updates = ','.join(f'{c}=excluded.{c}' for c in columns if c not in ('id', 'created_at'))
        db.execute(f"INSERT INTO notices ({','.join(columns)}) VALUES ({','.join('?' for _ in columns)}) "
                   f'ON CONFLICT(id) DO UPDATE SET {updates}', tuple(record.values()))
        return True


def public_notice(row):
    item = dict(row)
    item.pop('content_hash', None)
    item['source'] = item.get('source') or '青岛理工大学创新创业学院'
    for field in ('attachments', 'images'):
        item[field] = json.loads(item[field])
    return item
