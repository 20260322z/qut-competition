import json
import time
from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field
from .database import connect
from .student_store import current_user, notify, encode, uid
from .student_api import Team

router=APIRouter(prefix='/api/v1/student')


@router.get('/messages')
def messages(owner=Depends(current_user)):
    with connect() as db:
        return {'items':[dict(r) for r in db.execute('SELECT * FROM student_messages WHERE owner=? ORDER BY created DESC LIMIT 100',(owner,))]}


@router.post('/messages/{key}/seen')
def seen(key: str, owner=Depends(current_user)):
    with connect() as db:
        db.execute('UPDATE student_messages SET seen=1 WHERE id=? AND owner=?',(key,owner))
    return {'ok':True}


class Block(BaseModel):
    target: str = Field(min_length=1,max_length=64)
    enabled: bool


@router.post('/blocks')
def block(body: Block, owner=Depends(current_user)):
    if body.target==owner:
        raise HTTPException(422,'不能屏蔽自己')
    with connect() as db:
        if body.enabled:db.execute('INSERT OR IGNORE INTO blocks VALUES (?,?)',(owner,body.target))
        else:db.execute('DELETE FROM blocks WHERE owner=? AND target=?',(owner,body.target))
    return {'ok':True}


@router.get('/blocks')
def blocks(owner=Depends(current_user)):
    with connect() as db:
        return {'items':[dict(r) for r in db.execute('SELECT target FROM blocks WHERE owner=?',(owner,))]}


@router.put('/teams/{key}')
def edit_team(key: str, body: Team, owner=Depends(current_user)):
    with connect() as db:
        db.execute('BEGIN IMMEDIATE')
        row=db.execute('SELECT * FROM teams WHERE id=? AND owner=?',(key,owner)).fetchone()
        if not row:raise HTTPException(404,'队伍不存在或不是队长')
        count=1+db.execute("SELECT COUNT(*) FROM team_applications WHERE team=? AND status IN ('member','offered') AND (expires IS NULL OR expires>?)",(key,time.time())).fetchone()[0]
        if body.capacity<count:raise HTTPException(409,'人数不能小于成员与已预留名额总数')
        if body.deadline and body.deadline<=time.time():raise HTTPException(422,'截止时间须在未来')
        db.execute('UPDATE teams SET title=?,contest=?,data=?,capacity=?,updated=? WHERE id=?',(body.title,body.contest,encode(body.model_dump()),body.capacity,time.time(),key))
        if json.loads(row['data'])!=body.model_dump():
            for member in db.execute("SELECT owner FROM team_applications WHERE team=? AND status IN ('pending','offered','member')",(key,)).fetchall():
                notify(db,member['owner'],'队伍招募内容更新',body.title+'的赛道、安排或要求可能变化，请重新核对。','team:'+key)
    return {'ok':True}


class Question(BaseModel):
    recipient: str = Field(min_length=1,max_length=64)
    text: str = Field(min_length=1,max_length=1500)


@router.post('/teams/{key}/question')
def question(key: str, body: Question, owner=Depends(current_user)):
    with connect() as db:
        row=db.execute('SELECT owner,title FROM teams WHERE id=?',(key,)).fetchone()
        if not row:raise HTTPException(404)
        applicant=body.recipient if row['owner']==owner else owner
        if row['owner'] not in (owner,body.recipient) or not db.execute('SELECT 1 FROM team_applications WHERE team=? AND owner=?',(key,applicant)).fetchone():
            raise HTTPException(403,'仅队长与申请人可就该申请交流')
        if db.execute('SELECT COUNT(*) FROM student_messages WHERE owner=? AND link=? AND created>?',(body.recipient,'team:'+key,time.time()-60)).fetchone()[0]>=10:
            raise HTTPException(429,'发送过于频繁')
        notify(db,body.recipient,'组队补充交流',body.text,'team:'+key)
    return {'ok':True}


class Result(BaseModel):
    title: str = Field(min_length=2,max_length=120)
    result: str = Field(min_length=1,max_length=1000)
    evidence: str = Field(default='',max_length=500)


@router.post('/teams/{key}/results')
def result(key: str, body: Result, owner=Depends(current_user)):
    result_id=uid()
    with connect() as db:
        team=db.execute('SELECT * FROM teams WHERE id=? AND owner=?',(key,owner)).fetchone()
        if not team:raise HTTPException(403,'只有队长可以发起成果记录')
        recipients=[r[0] for r in db.execute("SELECT owner FROM team_applications WHERE team=? AND status='member'",(key,))]+[owner]
        db.execute('INSERT INTO team_results VALUES (?,?,?,?,?)',(result_id,key,encode(body.model_dump()),encode(recipients),time.time()))
        for recipient in recipients:
            notify(db,recipient,'团队成果待本人确认',body.title+'；这只是队长填写的成果，确认个人贡献前不会进入成长档案或综测。','result:'+result_id)
    return {'id':result_id}


@router.get('/team-results/{key}')
def result_detail(key: str, owner=Depends(current_user)):
    with connect() as db:
        row=db.execute('SELECT * FROM team_results WHERE id=?',(key,)).fetchone()
    if not row or owner not in json.loads(row['recipients']):raise HTTPException(404)
    return {'id':key,'data':json.loads(row['data'])}


class ConfirmResult(BaseModel):
    role: str = Field(min_length=1,max_length=500)
    note: str = Field(min_length=1,max_length=2000)
    accepted: bool


@router.post('/team-results/{key}/confirm')
def confirm_result(key: str, body: ConfirmResult, owner=Depends(current_user)):
    data=result_detail(key,owner)['data']
    if not body.accepted:return {'saved':False}
    value={**data,**body.model_dump(exclude={'accepted'}),'type':'竞赛','verification':'本人确认经历，非学校审核','points':None}
    record_id='team-result-'+key
    with connect() as db:
        existing=db.execute("SELECT 1 FROM student_records WHERE owner=? AND kind='portfolio' AND id=?",(owner,record_id)).fetchone()
        if not existing:
            db.execute("INSERT INTO student_records VALUES (?,'portfolio',?,?,1,?)",(owner,record_id,encode(value),time.time()))
    return {'saved':True,'id':record_id,'data':value,'duplicate':bool(existing)}
