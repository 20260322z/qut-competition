"""Dependency-free DB regression tests, also runnable with unittest offline."""
import json
import os
import sqlite3
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from contextlib import closing
from app.database import initialize, connect, upsert, public_notice
from app.notice_migration import retire_qq
from app.notice_projection import project_event


class UnifiedNoticeTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.db_path=Path(self.temp.name)/'notices.db'
        self.env=patch.dict(os.environ,{'DATABASE_PATH':str(self.db_path)})
        self.env.start();initialize()
        with connect() as db:
            db.executescript('''
            CREATE TABLE cloud_reminders(notice_id TEXT,enabled INTEGER,owner TEXT DEFAULT 'u',id TEXT DEFAULT 'r');
            CREATE TABLE mail_queue(body TEXT,status TEXT,id TEXT DEFAULT '');
            CREATE TABLE notice_contests(notice TEXT,contest TEXT,PRIMARY KEY(notice,contest));
            CREATE TABLE contest_event_details(event TEXT PRIMARY KEY,data TEXT);
            ''')
        self.registry={'a':{'name':'数学建模竞赛'},'b':{'name':'科技创新竞赛'}}

    def tearDown(self):
        self.env.stop();self.temp.cleanup()

    def notice(self,key,url,source):
        record=dict(id=key,url=url,title='报名通知',published_at='2026-09-01',category='科技',summary='摘要',body='学校原文',
            deadline=None,deadline_evidence=None,attachments=[],images=[],content_hash=key,source=source)
        upsert(record)

    def event(self,contest='a',url='https://example.edu/notice/1'):
        return dict(id=contest,contest=contest,url=url,title='数学建模报名通知',body='报名事项的公开正文',stages='[]')

    def test_retirement_preserves_school_and_database_backup(self):
        self.notice('school','https://school.edu/a','青岛理工大学创新创业学院')
        self.notice('qq','qq://group/123/message/9','QQ群 · 竞赛')
        self.notice('qq-web','https://group.example/a','QQ群 · 转发')
        with connect() as db:
            db.executemany('INSERT INTO cloud_reminders(notice_id,enabled) VALUES (?,1)',[('qq',),('school',)])
            db.executemany('INSERT INTO mail_queue(body,status) VALUES (?,?)',[('qq://group/123/message/9','pending'),('https://school.edu/a','pending')])
            db.execute("INSERT INTO mail_queue VALUES ('只有提醒标题，没有来源链接','pending','reminder:u:r:1')")
        report=retire_qq()
        self.assertEqual(report['removed'],2)
        with connect() as db:
            self.assertEqual(db.execute('SELECT id FROM notices').fetchall()[0][0],'school')
            self.assertEqual(db.execute('SELECT count(*) FROM notice_deletions').fetchone()[0],2)
            self.assertEqual(db.execute("SELECT enabled FROM cloud_reminders WHERE notice_id='qq'").fetchone()[0],0)
            self.assertEqual(db.execute("SELECT enabled FROM cloud_reminders WHERE notice_id='school'").fetchone()[0],1)
            self.assertEqual(db.execute("SELECT count(*) FROM mail_queue WHERE status='cancelled'").fetchone()[0],2)
        with closing(sqlite3.connect(report['backup'])) as db:self.assertEqual(db.execute('SELECT count(*) FROM notices').fetchone()[0],3)
        self.assertEqual(retire_qq()['removed'],0)

    def test_same_url_two_contests_one_notice(self):
        project_event(self.event(),self.registry)
        project_event(self.event('b'),self.registry)
        with connect() as db:
            rows=db.execute('SELECT * FROM notices').fetchall()
            self.assertEqual(len(rows),1)
        self.assertEqual(set(public_notice(rows[0])['contest_ids']),{'a','b'})

    def test_school_url_preserves_original_content_and_id(self):
        self.notice('school','https://school.edu/a','青岛理工大学创新创业学院')
        project_event(self.event(url='https://school.edu/a'),self.registry)
        with connect() as db:
            row=db.execute('SELECT * FROM notices').fetchone()
            self.assertEqual(row['id'],'school');self.assertEqual(row['body'],'学校原文')
            self.assertEqual(db.execute('SELECT count(*) FROM notices').fetchone()[0],1)

    def test_repeat_projection_preserves_incremental_cursor(self):
        event=self.event();project_event(event,self.registry)
        with connect() as db:first=db.execute('SELECT updated_at FROM notices').fetchone()[0]
        project_event(event,self.registry)
        with connect() as db:self.assertEqual(db.execute('SELECT updated_at FROM notices').fetchone()[0],first)

    def test_first_source_import_does_not_emit_historical_alerts(self):
        event=self.event();project_event(event,self.registry,silent=True)
        with connect() as db:self.assertEqual(db.execute('SELECT silent_import FROM notices').fetchone()[0],1)
        project_event(self.event(url='https://example.edu/notice/new'),self.registry,silent=False)
        with connect() as db:self.assertEqual(db.execute('SELECT count(*) FROM notices WHERE silent_import=0').fetchone()[0],1)

    def test_missing_publication_not_replaced_by_deadline(self):
        event=self.event();event['stages']=json.dumps([{'stage':'报名','date':'2030-10-01','time':'','evidence':'报名截止2030年10月1日'}])
        project_event(event,self.registry)
        with connect() as db:
            row=db.execute('SELECT * FROM notices').fetchone()
            self.assertIsNone(row['deadline']);self.assertEqual(row['published_at'],'')
            self.assertIn('2030',row['deadline_evidence'])

    def test_precise_deadline_and_attachment_projection(self):
        event=self.event();event['stages']=json.dumps([{'stage':'报名','date':'2030-10-01','time':'16:30','evidence':'报名截止2030年10月1日16:30'}])
        metadata={'published_at':'2026-09-02','attachments':[{'name':'报名表.xlsx','url':'https://example.edu/a.xlsx'}]}
        with connect() as db:db.execute('INSERT INTO contest_event_details VALUES (?,?)',(event['id'],json.dumps(metadata)))
        project_event(event,self.registry)
        with connect() as db:row=db.execute('SELECT * FROM notices').fetchone()
        self.assertEqual(row['deadline'],'2030-10-01T16:30:00+08:00')
        self.assertEqual(public_notice(row)['attachments'],metadata['attachments'])

    def test_multiple_registration_dates_do_not_guess(self):
        event=self.event();event['stages']=json.dumps([{'stage':'报名','date':'2030-10-01','time':'16:30','evidence':'第一批'}, {'stage':'报名','date':'2030-10-02','time':'12:00','evidence':'第二批'}])
        project_event(event,self.registry)
        with connect() as db:self.assertIsNone(db.execute('SELECT deadline FROM notices').fetchone()[0])


if __name__=='__main__':unittest.main()
