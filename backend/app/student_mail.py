"""SMTP is opt-in. Queue results mean server submission, never inbox delivery."""
import os
import re
import secrets
import smtplib
import ssl
import threading
import time
import json
from datetime import datetime, timezone
from email.message import EmailMessage

from fastapi import APIRouter, Depends, HTTPException, Request
from pydantic import BaseModel, Field
from .database import connect
from .student_store import current_user, uid, digest, encode

router = APIRouter(prefix='/api/v1/student')
LOCK = threading.Lock()


def configured():
    return all(os.getenv(k, '').strip() for k in ('SMTP_HOST', 'SMTP_USER', 'SMTP_PASSWORD', 'SMTP_FROM'))


def email_address(value):
    value = value.strip().lower()
    if len(value) > 254 or not re.fullmatch(r'[a-z0-9.!#$%&\x27*+/=?^_`{|}~-]+@[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?\.[a-z]{2,}', value):
        raise HTTPException(422, '请输入有效邮箱地址，例如 123456@qq.com')
    return value


def send(recipient, subject, body, message_id=None):
    if not configured():
        raise RuntimeError('SMTP_NOT_CONFIGURED')
    message = EmailMessage()
    message['From'] = os.environ['SMTP_FROM']
    message['To'] = recipient
    message['Subject'] = subject
    if message_id:
        message['Message-ID'] = f'<{digest(message_id)}@qingli.local>'
    message.set_content(body)
    mode = os.getenv('SMTP_SECURITY', 'ssl')
    if mode not in ('ssl', 'starttls'):
        raise RuntimeError('SMTP_REQUIRES_TLS')
    factory = smtplib.SMTP_SSL if mode == 'ssl' else smtplib.SMTP
    kwargs = {'timeout': 20}
    if mode == 'ssl':
        kwargs['context'] = ssl.create_default_context()
    with factory(os.environ['SMTP_HOST'], int(os.getenv('SMTP_PORT', '465' if mode == 'ssl' else '587')), **kwargs) as smtp:
        if mode == 'starttls':
            smtp.starttls(context=ssl.create_default_context())
        smtp.login(os.environ['SMTP_USER'], os.environ['SMTP_PASSWORD'])
        smtp.send_message(message)


class CodeRequest(BaseModel):
    email: str = Field(max_length=254)


class VerifyRequest(BaseModel):
    challenge: str = Field(max_length=64)
    code: str = Field(pattern=r'^\d{6}$')


def issue_code(email, purpose, owner, ip):
    if not configured():
        raise HTTPException(503, '平台尚未配置发信邮箱；游客浏览和本机工具仍可使用')
    email = email_address(email)
    stamp = time.time()
    challenge, code = uid(), f'{secrets.randbelow(1000000):06d}'
    with connect() as db:
        db.execute('BEGIN IMMEDIATE')
        recent = db.execute('SELECT COUNT(*) FROM email_codes WHERE (email=? AND created>?) OR (ip=? AND created>?)',
                            (email, stamp - 60, ip, stamp - 3600)).fetchone()[0]
        per_email = db.execute('SELECT COUNT(*) FROM email_codes WHERE email=? AND created>?', (email, stamp-3600)).fetchone()[0]
        if recent >= 10 or per_email >= 5 or db.execute('SELECT 1 FROM email_codes WHERE email=? AND created>?', (email, stamp-60)).fetchone():
            raise HTTPException(429, '发送过于频繁，请稍后重试')
        db.execute('INSERT INTO email_codes VALUES (?,?,?,?,?,?,0,?,?)',
                   (challenge, email, purpose, owner, digest(challenge + code), stamp+600, stamp, ip))
    try:
        send(email, '青理竞赛通验证码', f'验证码：{code}\n10 分钟内有效。仅用于{ "登录" if purpose == "login" else "绑定提醒邮箱" }。请勿转发。')
    except Exception:
        with connect() as db:
            db.execute('UPDATE email_codes SET expires=0 WHERE id=?', (challenge,))
        raise HTTPException(503, '验证码暂时发送失败，请稍后重试')
    return {'challenge': challenge, 'expires_in': 600, 'message': '验证码已提交到发信服务器，请检查收件箱和垃圾箱'}


def verify(body, purpose, owner):
    error = None
    email = None
    with connect() as db:
        db.execute('BEGIN IMMEDIATE')
        row = db.execute('SELECT * FROM email_codes WHERE id=? AND purpose=? AND owner=?', (body.challenge,purpose,owner)).fetchone()
        if not row or row['expires'] <= time.time() or row['attempts'] >= 5:
            error = '验证码已过期或尝试过多，请重新发送'
        else:
            db.execute('UPDATE email_codes SET attempts=attempts+1 WHERE id=?', (body.challenge,))
            if not secrets.compare_digest(row['hash'], digest(body.challenge+body.code)):
                error = '验证码不正确'
            else:
                email = row['email']
                db.execute('UPDATE email_codes SET expires=0 WHERE id=?', (body.challenge,))
    if error:
        raise HTTPException(400, error)
    return email


@router.post('/auth/code')
def login_code(body: CodeRequest, request: Request):
    return issue_code(body.email, 'login', '', request.client.host if request.client else 'unknown')


@router.post('/auth/verify')
def login_verify(body: VerifyRequest):
    email = verify(body, 'login', '')
    token = secrets.token_urlsafe(32)
    with connect() as db:
        db.execute('INSERT OR IGNORE INTO students VALUES (?,?,?)', (uid(),email,time.time()))
        owner = db.execute('SELECT id FROM students WHERE email=?',(email,)).fetchone()[0]
        db.execute('INSERT INTO sessions VALUES (?,?,?)', (digest(token),owner,time.time()+30*86400))
    return {'token':token, 'id':owner, 'email':email, 'campus_verified':False}


@router.post('/auth/logout')
def logout(request: Request, owner=Depends(current_user)):
    with connect() as db:
        db.execute('DELETE FROM sessions WHERE token=? AND owner=?',(digest(request.headers['authorization'][7:]),owner))
    return {'ok':True}


@router.post('/email/code')
def bind_code(body: CodeRequest, request: Request, owner=Depends(current_user)):
    return issue_code(body.email, 'bind', owner, request.client.host if request.client else 'unknown')


@router.post('/email/verify')
def bind_verify(body: VerifyRequest, owner=Depends(current_user)):
    email = verify(body,'bind',owner)
    stamp = time.time()
    with connect() as db:
        db.execute("UPDATE mail_queue SET status='cancelled' WHERE owner=? AND status='pending'",(owner,))
        db.execute('INSERT INTO subscriptions VALUES (?,?,?,?,?,?) ON CONFLICT(owner) DO UPDATE SET email=excluded.email, data=excluded.data, baseline=excluded.baseline, last_digest=excluded.last_digest, unsubscribe=excluded.unsubscribe',
                   (owner,email,encode({'enabled':False}),stamp,stamp,uid()))
    return {'email':email,'enabled':False,'message':'邮箱已验证，请主动开启需要的提醒'}


class Subscription(BaseModel):
    enabled: bool = False
    news: bool = False
    deadline: bool = False
    admission: bool = False
    custom: bool = False
    categories: list[str] = Field(default_factory=list, max_length=10)
    keywords: list[str] = Field(default_factory=list, max_length=10)
    frequency: str = Field(default='daily',pattern='^(hourly|daily)$')


@router.get('/email')
def email_settings(owner=Depends(current_user)):
    with connect() as db:
        row = db.execute('SELECT * FROM subscriptions WHERE owner=?',(owner,)).fetchone()
        queue = [dict(r) for r in db.execute('SELECT subject,status,error,sent FROM mail_queue WHERE owner=? ORDER BY due DESC LIMIT 20',(owner,))]
    return {'configured':configured(),'email':row['email'] if row else '', 'settings':json.loads(row['data']) if row else {},'queue':queue}


@router.put('/email')
def save_subscription(body: Subscription, owner=Depends(current_user)):
    if any(len(k)>80 for k in body.keywords):
        raise HTTPException(422,'关键词最多 80 字')
    stamp = time.time()
    with connect() as db:
        db.execute('BEGIN IMMEDIATE')
        row = db.execute('SELECT * FROM subscriptions WHERE owner=?',(owner,)).fetchone()
        if not row:
            raise HTTPException(409,'请先验证提醒邮箱')
        previous = json.loads(row['data'])
        reset = body.enabled and body.news and not (previous.get('enabled') and previous.get('news'))
        db.execute('UPDATE subscriptions SET data=?,baseline=?,last_digest=? WHERE owner=?',
                   (encode(body.model_dump()),stamp if reset else row['baseline'],stamp if reset else row['last_digest'],owner))
        db.execute("UPDATE mail_queue SET status='cancelled' WHERE owner=? AND status='pending'",(owner,))
    return {'saved':True,'message':'订阅已保存，开启时不补发历史通知'}


@router.delete('/email')
def unbind(owner=Depends(current_user)):
    with connect() as db:
        db.execute('DELETE FROM subscriptions WHERE owner=?',(owner,))
        db.execute("UPDATE mail_queue SET status='cancelled' WHERE owner=? AND status='pending'",(owner,))
    return {'ok':True}


@router.post('/email/test')
def test_email(owner=Depends(current_user)):
    with connect() as db:
        row = db.execute('SELECT * FROM subscriptions WHERE owner=?',(owner,)).fetchone()
        if not row:
            raise HTTPException(409,'请先验证提醒邮箱')
        if db.execute("SELECT 1 FROM mail_queue WHERE owner=? AND category='test' AND due>?",(owner,time.time()-60)).fetchone():
            raise HTTPException(429,'一分钟内只能测试一次')
        if not configured():
            raise HTTPException(503,'平台尚未配置发信邮箱')
        db.execute('INSERT INTO mail_queue(id,owner,recipient,subject,body,due,category) VALUES (?,?,?,?,?,?,?)',
                   (uid(),owner,row['email'],'青理竞赛通测试邮件','这是一封由你主动请求的测试邮件。',time.time(),'test'))
    return {'message':'已加入发送队列，可在发送记录查看状态；不代表已经送达收件箱'}


class Reminder(BaseModel):
    title: str = Field(min_length=1,max_length=160)
    due: float
    category: str = Field(default='custom',pattern='^(deadline|admission|custom)$')
    notice_id: str | None = Field(default=None,max_length=100)
    offset_days: int | None = Field(default=None,ge=0,le=30)
    enabled: bool = True


@router.put('/reminders/{reminder_id}')
def set_reminder(reminder_id: str, body: Reminder, owner=Depends(current_user)):
    if len(reminder_id)>100 or (body.enabled and body.due<=time.time()):
        raise HTTPException(422,'请选择未来的提醒时间')
    with connect() as db:
        if body.notice_id:
            row = db.execute('SELECT deadline FROM notices WHERE id=?',(body.notice_id,)).fetchone()
            if not row:
                raise HTTPException(404,'通知不存在')
            if body.offset_days is not None:
                if not row['deadline']:
                    raise HTTPException(422,'官方截止日期待确认，请设置绝对提醒时间')
                body.due = datetime.fromisoformat(row['deadline']).timestamp()-body.offset_days*86400
                if body.enabled and body.due <= time.time():
                    raise HTTPException(422,'提前提醒时间已经过去，请设置未来的绝对时间')
        db.execute('INSERT INTO cloud_reminders VALUES (?,?,?,?,?,?,?, ?,1) ON CONFLICT(owner,id) DO UPDATE SET title=excluded.title,due=excluded.due,category=excluded.category,revision=cloud_reminders.revision+1,notice_id=excluded.notice_id,offset_days=excluded.offset_days,enabled=excluded.enabled',
                   (owner,reminder_id,body.title,body.due,body.category,1,body.notice_id,body.offset_days))
        if not body.enabled:
            db.execute('UPDATE cloud_reminders SET enabled=0 WHERE owner=? AND id=?',(owner,reminder_id))
        db.execute("UPDATE mail_queue SET status='cancelled' WHERE owner=? AND id LIKE ? AND status='pending'",(owner,f'reminder:{owner}:{reminder_id}:%'))
    return {'synced':True,'due':body.due}


@router.get('/reminders')
def list_reminders(owner=Depends(current_user)):
    with connect() as db:
        return {'items':[dict(r) for r in db.execute('SELECT * FROM cloud_reminders WHERE owner=? ORDER BY due',(owner,))]}


@router.post('/email/unsubscribe/{token}')
def unsubscribe(token: str):
    with connect() as db:
        row = db.execute('SELECT owner FROM subscriptions WHERE unsubscribe=?',(token,)).fetchone()
        if row:
            db.execute('UPDATE subscriptions SET data=? WHERE owner=?',(encode({'enabled':False}),row['owner']))
            db.execute("UPDATE mail_queue SET status='cancelled' WHERE owner=? AND status='pending'",(row['owner'],))
    return {'message':'已关闭订阅'}


@router.get('/email/unsubscribe/{token}', response_class=__import__('fastapi.responses',fromlist=['HTMLResponse']).HTMLResponse)
def unsubscribe_page(token: str):
    if not re.fullmatch('[a-f0-9]{32}',token):
        raise HTTPException(404)
    return '<meta charset="utf-8"><h2>关闭青理竞赛通邮箱提醒</h2><form method="post"><button>确认退订</button></form>'


def tick():
    if not configured() or not LOCK.acquire(blocking=False):
        return
    try:
        stamp = time.time()
        with connect() as db:
            # Interrupted SMTP submission is ambiguous. Do not automatically duplicate it.
            db.execute("UPDATE mail_queue SET status='uncertain',error='发送中服务重启，请查看收件箱' WHERE status='sending'")
            for sub in db.execute('SELECT * FROM subscriptions').fetchall():
                config = json.loads(sub['data'])
                if not config.get('enabled'):
                    continue
                owner = sub['owner']
                base = os.getenv('PUBLIC_BASE_URL','').rstrip('/')
                footer = '\n\n可在 App 我的→邮箱提醒中关闭订阅。'
                if base:
                    footer += f'\n退订：{base}/api/v1/student/email/unsubscribe/{sub["unsubscribe"]}'
                interval = 3600 if config.get('frequency')=='hourly' else 86400
                if config.get('news') and stamp-sub['last_digest']>=interval:
                    notices = db.execute('SELECT * FROM notices WHERE created_at>? AND created_at<=? ORDER BY created_at',
                        (datetime.fromtimestamp(max(sub['last_digest'],sub['baseline']),timezone.utc).isoformat(timespec='microseconds'),datetime.fromtimestamp(stamp,timezone.utc).isoformat(timespec='microseconds'))).fetchall()
                    chosen = [n for n in notices if (not config.get('categories') or n['category'] in config['categories']) and
                              (not config.get('keywords') or any(k.casefold() in (n['title']+' '+n['body']).casefold() for k in config['keywords']))]
                    if chosen:
                        body = '\n\n'.join(f'{n["title"]}\n{n["url"]}' for n in chosen[:100])+footer
                        db.execute('INSERT OR IGNORE INTO mail_queue(id,owner,recipient,subject,body,due,category) VALUES (?,?,?,?,?,?,?)',
                                   (f'digest:{owner}:{sub["last_digest"]}',owner,sub['email'],f'青理竞赛通：{len(chosen)} 条新通知',body,stamp,'news'))
                    db.execute('UPDATE subscriptions SET last_digest=? WHERE owner=?',(stamp,owner))
                for r in db.execute('SELECT * FROM cloud_reminders WHERE owner=? AND enabled=1',(owner,)).fetchall():
                    if not config.get(r['category']):
                        continue
                    due = r['due']
                    revision = r['revision']
                    if r['notice_id'] and r['offset_days'] is not None:
                        notice = db.execute('SELECT deadline FROM notices WHERE id=?',(r['notice_id'],)).fetchone()
                        if not notice or not notice['deadline']:
                            continue
                        due = datetime.fromisoformat(notice['deadline']).timestamp()-r['offset_days']*86400
                        if due != r['due']:
                            revision += 1
                            db.execute('UPDATE cloud_reminders SET due=?,revision=? WHERE owner=? AND id=?',(due,revision,owner,r['id']))
                            db.execute("UPDATE mail_queue SET status='cancelled' WHERE owner=? AND id LIKE ? AND status='pending'",(owner,f'reminder:{owner}:{r["id"]}:%'))
                    key = f'reminder:{owner}:{r["id"]}:{revision}'
                    if stamp-86400<=due<=stamp:
                        db.execute('INSERT OR IGNORE INTO mail_queue(id,owner,recipient,subject,body,due,category) VALUES (?,?,?,?,?,?,?)',
                                   (key,owner,sub['email'],r['title'],r['title']+'\n这是你设置的提醒，请查看 App 中的事项与原文。'+footer,due,r['category']))
        # Claim and re-check preferences inside a short transaction before the SMTP call.
        for _ in range(50):
            with connect() as db:
                db.execute('BEGIN IMMEDIATE')
                row = db.execute("SELECT * FROM mail_queue WHERE status='pending' AND due<=? ORDER BY due LIMIT 1",(time.time(),)).fetchone()
                if not row:
                    break
                sub = db.execute('SELECT * FROM subscriptions WHERE owner=?',(row['owner'],)).fetchone()
                settings = json.loads(sub['data']) if sub else {}
                allowed = sub and sub['email']==row['recipient'] and (row['category']=='test' or (settings.get('enabled') and settings.get(row['category'])))
                db.execute('UPDATE mail_queue SET status=? WHERE id=?',('sending' if allowed else 'cancelled',row['id']))
            if not allowed:
                continue
            try:
                send(row['recipient'],row['subject'],row['body'],row['id'])
                status,error = 'submitted',None
            except (smtplib.SMTPServerDisconnected, TimeoutError):
                status,error = 'uncertain','提交结果不确定，请检查收件箱；为避免重复未自动重发'
            except Exception:
                status,error = ('failed','发送失败，请检查平台发信配置') if row['attempts']>=2 else ('pending','暂时失败，稍后重试')
            with connect() as db:
                db.execute('UPDATE mail_queue SET status=?,error=?,attempts=attempts+1,sent=?,due=? WHERE id=?',
                    (status,error,time.time() if status=='submitted' else None,time.time()+300,row['id']))
    finally:
        LOCK.release()
