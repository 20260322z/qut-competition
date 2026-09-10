import hashlib
import json
import logging
import os
import re
import threading
import time
from datetime import datetime

import httpx

from .database import connect, now, upsert
from .parser import categorize, extract_deadline

log = logging.getLogger('qut.qq.review')
LOCK = threading.Lock()
CATEGORIES = {'科技', '创业', '设计', '外语', '综合'}
SYSTEM_PROMPT = (
    '你是高校竞赛通知审核助手。根据 QQ 群消息判断它是否是面向学生发布的竞赛/大赛通知，'
    '例如比赛报名、赛项安排、作品征集、挑战赛通知。闲聊、表情、问答、已结束成绩炫耀、'
    '无关广告、单纯转发新闻标题都不是竞赛通知。必须返回 JSON 对象，字段为：'
    '{"is_competition": true或false, "title": "简洁中文标题", '
    '"category": "科技|创业|设计|外语|综合 之一", "reason": "一句话理由"}。'
)


def authorized_headers():
    key = os.getenv('DEEPSEEK_API_KEY', '').strip()
    return {'Authorization': f'Bearer {key}', 'Content-Type': 'application/json'}


def parse_model_json(content):
    text = (content or '').strip()
    if text.startswith('```'):
        text = re.sub(r'^```(?:json)?\s*', '', text)
        text = re.sub(r'\s*```$', '', text)
    return json.loads(text)


def ask_deepseek(text, group_name=''):
    key = os.getenv('DEEPSEEK_API_KEY', '').strip()
    if not key:
        raise RuntimeError('未配置 DEEPSEEK_API_KEY')
    model = os.getenv('DEEPSEEK_MODEL', 'deepseek-chat').strip() or 'deepseek-chat'
    payload = {
        'model': model,
        'messages': [
            {'role': 'system', 'content': SYSTEM_PROMPT},
            {'role': 'user', 'content': f'群名称：{group_name or "未知"}\n消息如下，请用 JSON 审核：\n{text[:4000]}'},
        ],
        'response_format': {'type': 'json_object'},
        'temperature': 0.1,
        'max_tokens': 500,
    }
    last_error = None
    for attempt in range(3):
        try:
            with httpx.Client(timeout=40) as client:
                response = client.post('https://api.deepseek.com/chat/completions',
                                       headers=authorized_headers(), json=payload)
                response.raise_for_status()
                content = response.json()['choices'][0]['message']['content']
            data = parse_model_json(content)
            if 'is_competition' not in data:
                raise ValueError('模型未返回 is_competition')
            return data
        except Exception as exc:
            last_error = exc
            time.sleep(1.5 * (attempt + 1))
    raise RuntimeError(type(last_error).__name__) from last_error


def notice_from_message(row, decision):
    body = row['raw_text']
    title = (decision.get('title') or '').strip() or body.splitlines()[0][:80]
    category = decision.get('category') if decision.get('category') in CATEGORIES else categorize(title)
    try:
        published = datetime.fromisoformat(row['received_at']).astimezone().strftime('%Y-%m-%d')
    except ValueError:
        published = now()[:10]
    deadline, evidence = extract_deadline(body, published)
    links = json.loads(row['links'] or '[]')
    attachments = [{'name': '群内链接', 'url': url} for url in links]
    source = f"QQ群 · {row['group_name']}" if row['group_name'] else 'QQ群'
    record = dict(
        id=row['id'],
        url=f"qq://{row['group_id']}/{row['message_id']}",
        title=title[:120],
        published_at=published,
        category=category,
        summary=body[:150],
        body=body,
        deadline=deadline,
        deadline_evidence=evidence,
        attachments=attachments,
        images=[],
        source=source,
    )
    record['content_hash'] = hashlib.sha256(
        json.dumps(record, sort_keys=True, ensure_ascii=False).encode()).hexdigest()
    return record


def pending_rows(limit=15):
    with connect() as db:
        return [dict(row) for row in db.execute(
            '''SELECT * FROM qq_messages
               WHERE regex_matched=1 AND review_status IN ('pending', 'error')
               ORDER BY received_at ASC LIMIT ?''', (limit,))]


def mark(row_id, status, reason, notice_id=None):
    with connect() as db:
        db.execute(
            '''UPDATE qq_messages
               SET review_status=?, review_reason=?, reviewed_at=?, notice_id=?,
                   review_attempts=review_attempts+1
               WHERE id=?''',
            (status, reason, now(), notice_id, row_id),
        )


def review(limit=15):
    if not LOCK.acquire(blocking=False):
        return {'skipped': True}
    accepted, rejected, failed = 0, 0, 0
    try:
        rows = pending_rows(limit)
        if not rows:
            return {'accepted': 0, 'rejected': 0, 'failed': 0, 'pending': 0}
        for row in rows:
            try:
                decision = ask_deepseek(row['raw_text'], row['group_name'])
                if decision.get('is_competition') in (True, 'true', 1, '1'):
                    notice = notice_from_message(row, decision)
                    upsert(notice)
                    mark(row['id'], 'accepted', decision.get('reason') or '模型判定为竞赛通知', notice['id'])
                    accepted += 1
                else:
                    mark(row['id'], 'rejected', decision.get('reason') or '模型判定不是竞赛通知')
                    rejected += 1
            except Exception as exc:
                log.warning('QQ review failed for %s: %s', row['id'], type(exc).__name__)
                attempts = row.get('review_attempts') or 0
                status = 'error' if attempts + 1 < 5 else 'error'
                mark(row['id'], status, '审核暂时失败（' + type(exc).__name__ + '）')
                failed += 1
            time.sleep(0.6)
        with connect() as db:
            pending = db.execute(
                "SELECT COUNT(*) FROM qq_messages WHERE regex_matched=1 AND review_status IN ('pending', 'error')"
            ).fetchone()[0]
            error = f'{failed} 条消息审核失败，将重试' if failed else None
            db.execute("UPDATE source_state SET last_success=?, last_error=? WHERE id='qq'", (now(), error))
        return {'accepted': accepted, 'rejected': rejected, 'failed': failed, 'pending': pending}
    finally:
        LOCK.release()
