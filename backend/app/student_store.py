"""Additive student workspace schema; independent of the campus/assessment database."""
import hashlib
import json
import secrets
import time

from fastapi import Header, HTTPException
from .database import connect


def uid():
    return secrets.token_hex(16)


def encode(value):
    return json.dumps(value, ensure_ascii=False, separators=(',', ':'))


def digest(value):
    return hashlib.sha256(value.encode()).hexdigest()


def initialize():
    with connect() as db:
        db.executescript('''
        CREATE TABLE IF NOT EXISTS students(id TEXT PRIMARY KEY, email TEXT UNIQUE NOT NULL, created REAL NOT NULL);
        CREATE TABLE IF NOT EXISTS sessions(token TEXT PRIMARY KEY, owner TEXT NOT NULL, expires REAL NOT NULL);
        CREATE TABLE IF NOT EXISTS email_codes(id TEXT PRIMARY KEY, email TEXT NOT NULL, purpose TEXT NOT NULL,
            owner TEXT NOT NULL, hash TEXT NOT NULL, expires REAL NOT NULL, attempts INTEGER NOT NULL DEFAULT 0,
            created REAL NOT NULL, ip TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS student_records(owner TEXT NOT NULL, kind TEXT NOT NULL, id TEXT NOT NULL,
            data TEXT NOT NULL, revision INTEGER NOT NULL, updated REAL NOT NULL, PRIMARY KEY(owner,kind,id));
        CREATE TABLE IF NOT EXISTS subscriptions(owner TEXT PRIMARY KEY, email TEXT NOT NULL, data TEXT NOT NULL,
            baseline REAL NOT NULL, last_digest REAL NOT NULL, unsubscribe TEXT NOT NULL UNIQUE);
        CREATE TABLE IF NOT EXISTS mail_queue(id TEXT PRIMARY KEY, owner TEXT NOT NULL, recipient TEXT NOT NULL,
            subject TEXT NOT NULL, body TEXT NOT NULL, due REAL NOT NULL, status TEXT NOT NULL DEFAULT 'pending',
            attempts INTEGER NOT NULL DEFAULT 0, category TEXT NOT NULL, error TEXT, sent REAL);
        CREATE INDEX IF NOT EXISTS mail_due ON mail_queue(status,due);
        CREATE TABLE IF NOT EXISTS cloud_reminders(owner TEXT NOT NULL, id TEXT NOT NULL, title TEXT NOT NULL,
            due REAL NOT NULL, category TEXT NOT NULL, revision INTEGER NOT NULL,
            notice_id TEXT, offset_days INTEGER, enabled INTEGER NOT NULL, PRIMARY KEY(owner,id));
        CREATE TABLE IF NOT EXISTS teams(id TEXT PRIMARY KEY, owner TEXT NOT NULL, contest TEXT NOT NULL,
            title TEXT NOT NULL, data TEXT NOT NULL, capacity INTEGER NOT NULL, status TEXT NOT NULL, updated REAL NOT NULL);
        CREATE TABLE IF NOT EXISTS team_applications(team TEXT NOT NULL, owner TEXT NOT NULL, data TEXT NOT NULL,
            status TEXT NOT NULL, expires REAL, updated REAL NOT NULL, PRIMARY KEY(team,owner));
        CREATE TABLE IF NOT EXISTS team_tasks(id TEXT PRIMARY KEY, team TEXT NOT NULL, data TEXT NOT NULL, updated REAL NOT NULL);
        CREATE TABLE IF NOT EXISTS posts(id TEXT PRIMARY KEY, owner TEXT NOT NULL, contest TEXT NOT NULL,
            title TEXT NOT NULL, body TEXT NOT NULL, year TEXT NOT NULL, updated REAL NOT NULL, hidden INTEGER NOT NULL DEFAULT 0);
        CREATE TABLE IF NOT EXISTS comments(id TEXT PRIMARY KEY, post TEXT NOT NULL, owner TEXT NOT NULL, body TEXT NOT NULL, created REAL NOT NULL);
        CREATE TABLE IF NOT EXISTS reactions(post TEXT NOT NULL, owner TEXT NOT NULL, kind TEXT NOT NULL, PRIMARY KEY(post,owner,kind));
        CREATE TABLE IF NOT EXISTS reports(id TEXT PRIMARY KEY, owner TEXT NOT NULL, target TEXT NOT NULL, reason TEXT NOT NULL,
            status TEXT NOT NULL DEFAULT '待处理', created REAL NOT NULL);
        CREATE TABLE IF NOT EXISTS blocks(owner TEXT NOT NULL, target TEXT NOT NULL, PRIMARY KEY(owner,target));
        CREATE TABLE IF NOT EXISTS student_messages(id TEXT PRIMARY KEY, owner TEXT NOT NULL, title TEXT NOT NULL,
            body TEXT NOT NULL, link TEXT NOT NULL, seen INTEGER NOT NULL DEFAULT 0, created REAL NOT NULL);
        CREATE TABLE IF NOT EXISTS team_results(id TEXT PRIMARY KEY, team TEXT NOT NULL, data TEXT NOT NULL,
            recipients TEXT NOT NULL, created REAL NOT NULL);
        CREATE TABLE IF NOT EXISTS student_files(id TEXT PRIMARY KEY, owner TEXT NOT NULL, name TEXT NOT NULL,
            mime TEXT NOT NULL, size INTEGER NOT NULL, hash TEXT NOT NULL, data TEXT NOT NULL, public INTEGER NOT NULL DEFAULT 0, updated REAL NOT NULL);
        CREATE TABLE IF NOT EXISTS ai_jobs(id TEXT PRIMARY KEY, owner TEXT NOT NULL, request_key TEXT NOT NULL,
            kind TEXT NOT NULL, input TEXT NOT NULL, output TEXT, status TEXT NOT NULL, error TEXT,
            created REAL NOT NULL, updated REAL NOT NULL, UNIQUE(owner,request_key));
        ''')


def current_user(authorization: str | None = Header(default=None)):
    if not authorization or not authorization.startswith('Bearer '):
        raise HTTPException(401, '请先使用邮箱登录')
    with connect() as db:
        row = db.execute('SELECT owner FROM sessions WHERE token=? AND expires>?',
                         (digest(authorization[7:]), time.time())).fetchone()
    if not row:
        raise HTTPException(401, '登录已过期，请重新获取验证码')
    return row['owner']


def optional_user(authorization: str | None = Header(default=None)):
    return current_user(authorization) if authorization else ''


def notify(db, owner, title, body, link=''):
    db.execute('INSERT INTO student_messages(id,owner,title,body,link,created) VALUES (?,?,?,?,?,?)',
               (uid(),owner,title,body,link,time.time()))
