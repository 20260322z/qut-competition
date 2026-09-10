import hashlib
import json
import logging
import os
import re
import threading
from datetime import datetime, timezone
from urllib.parse import urlsplit

import httpx

from .database import connect, now
from .parser import CHINA, clean

log = logging.getLogger('qut.qq')
LOCK = threading.Lock()

COMPETITION_RE = re.compile(
    r'竞赛|大赛|比赛|挑战赛|hackathon|报名|截[止至]|作品提交|赛项|'
    r'国赛|省赛|校赛|蓝桥|互联网\+|挑战杯|三创|数学建模|创青春|'
    r'学科竞赛|大学生.{0,12}赛|通知.{0,12}赛|赛.{0,12}通知|参赛|'
    r'奖项设置|赛道|组委会|报名方式|参赛须知',
    re.I,
)
CQ_AT = re.compile(r'\[CQ:at,[^\]]+\]')
CQ_IMAGE = re.compile(r'\[CQ:image,[^\]]+\]')
CQ_OTHER = re.compile(r'\[CQ:[^\]]+\]')
HTTP_LINK = re.compile(r'https?://[^\s<>\[\]\"\']+', re.I)


def allowed_groups():
    raw = os.getenv('QQ_GROUP_IDS', '').strip()
    if not raw:
        return None
    return {item.strip() for item in raw.split(',') if item.strip()}


def looks_like_competition(text):
    value = clean(text or '')
    if len(value) < 12:
        return False
    return bool(COMPETITION_RE.search(value))


def extract_links(text):
    links = []
    for match in HTTP_LINK.finditer(text or ''):
        url = match.group(0).rstrip(')。,，；;!?！？\'\"')
        parts = urlsplit(url)
        if parts.scheme in ('http', 'https') and url not in links:
            links.append(url)
    return links


def extract_text(event):
    raw = event.get('raw_message')
    if isinstance(raw, str) and raw.strip():
        text = CQ_AT.sub('', raw)
        text = CQ_IMAGE.sub('[图片]', text)
        text = CQ_OTHER.sub('', text)
        return clean(text.replace('\r\n', '\n'))
    message = event.get('message')
    if isinstance(message, str):
        return extract_text({'raw_message': message})
    parts = []
    for seg in message or []:
        if isinstance(seg, str):
            parts.append(seg)
            continue
        kind = seg.get('type')
        data = seg.get('data') or {}
        if kind == 'text':
            parts.append(data.get('text') or '')
        elif kind == 'image':
            parts.append('[图片]')
        elif kind == 'at':
            continue
        elif kind == 'json':
            payload = data.get('data') or ''
            if payload:
                parts.append(payload[:300])
    return clean('\n'.join(parts).replace('\r\n', '\n'))


def message_key(group_id, message_id):
    return hashlib.sha256(f'qq:{group_id}:{message_id}'.encode()).hexdigest()[:20]


def normalize_event(event):
    if not isinstance(event, dict):
        return None
    if event.get('post_type') not in (None, 'message', 'message_sent'):
        return None
    if event.get('message_type') and event.get('message_type') != 'group':
        return None
    if event.get('post_type') is None and not (event.get('group_id') and event.get('message_id')):
        return None
    group_id = str(event.get('group_id') or '')
    message_id = str(event.get('message_id') or '')
    if not group_id or not message_id:
        return None
    allowed = allowed_groups()
    if allowed is not None and group_id not in allowed:
        return None
    text = extract_text(event)
    if not text:
        return None
    sender = event.get('sender') or {}
    stamp = event.get('time')
    received = datetime.fromtimestamp(stamp, timezone.utc).isoformat(timespec='microseconds') if stamp else now()
    return {
        'id': message_key(group_id, message_id),
        'group_id': group_id,
        'group_name': clean(str(event.get('group_name') or '')),
        'sender_id': str(event.get('user_id') or sender.get('user_id') or ''),
        'sender_name': clean(str(sender.get('card') or sender.get('nickname') or '')),
        'message_id': message_id,
        'raw_text': text,
        'links': extract_links(text),
        'received_at': received,
        'regex_matched': int(looks_like_competition(text)),
    }


def store_candidate(item):
    if not item:
        return False
    with connect() as db:
        exists = db.execute('SELECT id FROM qq_messages WHERE id=?', (item['id'],)).fetchone()
        if exists:
            return False
        db.execute(
            '''INSERT INTO qq_messages
               (id, group_id, group_name, sender_id, sender_name, message_id, raw_text, links,
                received_at, regex_matched, review_status)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)''',
            (item['id'], item['group_id'], item['group_name'], item['sender_id'], item['sender_name'],
             item['message_id'], item['raw_text'], json.dumps(item['links'], ensure_ascii=False),
             item['received_at'], item['regex_matched'],
             'pending' if item['regex_matched'] else 'skipped'),
        )
    return True


def handle_event(event):
    item = normalize_event(event)
    if item is None:
        return {'ignored': True}
    stored = store_candidate(item)
    return {'stored': stored, 'regex_matched': bool(item['regex_matched']), 'id': item['id']}


def napcat_headers():
    token = os.getenv('NAPCAT_TOKEN', '').strip()
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = f'Bearer {token}'
    return headers


def napcat_call(client, action, payload=None):
    base = os.getenv('NAPCAT_BASE_URL', 'http://napcat:3000').rstrip('/')
    response = client.post(f'{base}/{action}', json=payload or {}, headers=napcat_headers())
    response.raise_for_status()
    data = response.json()
    if data.get('status') not in (None, 'ok') and data.get('retcode') not in (None, 0):
        raise RuntimeError(data.get('message') or data.get('wording') or action)
    return data.get('data', data)


def poll():
    if not LOCK.acquire(blocking=False):
        return {'skipped': True}
    stored, matched, errors = 0, 0, []
    try:
        with connect() as db:
            db.execute("UPDATE source_state SET running=1, last_attempt=? WHERE id='qq'", (now(),))
        timeout = httpx.Timeout(20.0)
        with httpx.Client(timeout=timeout) as client:
            groups = napcat_call(client, 'get_group_list') or []
            names = {str(item.get('group_id')): item.get('group_name') or '' for item in groups}
            allowed = allowed_groups()
            targets = [gid for gid in names if allowed is None or gid in allowed]
            if allowed and not targets:
                raise RuntimeError('已配置 QQ 群白名单，但机器人当前不在这些群中')
            for group_id in targets:
                try:
                    history = napcat_call(client, 'get_group_msg_history', {
                        'group_id': int(group_id) if str(group_id).isdigit() else group_id,
                        'count': 30,
                    }) or {}
                    messages = history.get('messages') if isinstance(history, dict) else history
                    for event in messages or []:
                        event = dict(event)
                        event.setdefault('group_id', group_id)
                        event.setdefault('group_name', names.get(group_id, ''))
                        event.setdefault('message_type', 'group')
                        event.setdefault('post_type', 'message')
                        result = handle_event(event)
                        stored += int(bool(result.get('stored')))
                        matched += int(bool(result.get('regex_matched') and result.get('stored')))
                except Exception as exc:
                    log.warning('QQ group %s poll failed: %s', group_id, type(exc).__name__)
                    errors.append(group_id)
        with connect() as db:
            db.execute("UPDATE source_state SET last_success=?, last_error=?, running=0 WHERE id='qq'",
                       (now(), f'{len(errors)} 个群暂未拉取，将在后续重试' if errors else None))
        return {'stored': stored, 'matched': matched, 'failed_groups': len(errors)}
    except Exception as exc:
        log.exception('QQ poll failed')
        with connect() as db:
            db.execute("UPDATE source_state SET last_error=?, running=0 WHERE id='qq'",
                       ('QQ 机器人暂时无法同步（' + type(exc).__name__ + '），已保留已有通知',))
        return {'error': type(exc).__name__, 'stored': stored, 'matched': matched}
    finally:
        LOCK.release()
