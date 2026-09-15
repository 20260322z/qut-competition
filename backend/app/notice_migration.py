"""Retire the QQ feed and retain a local, recoverable pre-migration database."""
import os
import sqlite3
from pathlib import Path
from contextlib import closing
from .database import connect, now

QQ_WHERE = "(source LIKE 'QQ群%' OR url LIKE 'qq://%')"


def retire_qq():
    with connect() as db:
        db.execute('CREATE TABLE IF NOT EXISTS notice_deletions(id TEXT PRIMARY KEY, deleted_at TEXT NOT NULL)')
        rows = db.execute('SELECT id,url FROM notices WHERE ' + QQ_WHERE).fetchall()
    if not rows:
        return {'removed': 0}
    path = Path(os.getenv('DATABASE_PATH', '/data/notices.db'))
    backup = path.with_name('before-qq-retirement.db')
    if not backup.exists():
        # A separate connection avoids backing up an open write transaction.
        with connect() as source, closing(sqlite3.connect(backup)) as target:
            source.backup(target)
    with connect() as db:
        db.execute('BEGIN IMMEDIATE')
        for row in rows:
            db.execute('INSERT OR IGNORE INTO notice_deletions VALUES (?,?)', (row['id'], now()))
            for reminder in db.execute('SELECT owner,id FROM cloud_reminders WHERE notice_id=?',(row['id'],)).fetchall():
                db.execute("UPDATE mail_queue SET status='cancelled' WHERE status='pending' AND instr(id,?)=1",
                           (f"reminder:{reminder['owner']}:{reminder['id']}:",))
            db.execute('UPDATE cloud_reminders SET enabled=0 WHERE notice_id=?', (row['id'],))
            # Cancel pending mail containing this retired notice, including old digests.
            db.execute("UPDATE mail_queue SET status='cancelled' WHERE status='pending' AND instr(body,?)>0", (row['url'],))
            db.execute('DELETE FROM notices WHERE id=?', (row['id'],))
        db.execute("UPDATE qq_messages SET review_status='retired' WHERE review_status IN ('pending','error')")
        db.execute("UPDATE source_state SET running=0,last_error='QQ通知来源已停用' WHERE id='qq'")
    return {'removed': len(rows), 'backup': str(backup)}
