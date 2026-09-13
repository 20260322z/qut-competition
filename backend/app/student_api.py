import base64
import hashlib
import json
import os
import time
from pathlib import Path
from typing import Literal

from fastapi import APIRouter, Depends, HTTPException, Query
from fastapi.responses import FileResponse
from pydantic import BaseModel, Field
from .database import connect
from .student_store import current_user, optional_user, notify, encode, uid
from .student_mail import configured

router = APIRouter(prefix='/api/v1/student')
KINDS = {'profile','grade','portfolio','target','application','task','interview','resume','favorite','draft','scenario'}


@router.get('/capabilities')
def capabilities():
    return {'email':configured(), 'ai':bool(os.getenv('DEEPSEEK_API_KEY','').strip()),
            'merchant':False,'catalog_year':2023,'campus_system':'existing_zhcp', 'version':2}


class Record(BaseModel):
    data: dict
    revision: int = Field(default=0,ge=0)


@router.get('/records/{kind}')
def records(kind: str, owner=Depends(current_user)):
    if kind not in KINDS:
        raise HTTPException(404)
    with connect() as db:
        return {'items':[{'id':r['id'],'data':json.loads(r['data']),'revision':r['revision'],'updated':r['updated']}
                         for r in db.execute('SELECT * FROM student_records WHERE owner=? AND kind=? ORDER BY updated DESC',(owner,kind))]}


@router.put('/records/{kind}/{record_id}')
def save_record(kind: str, record_id: str, body: Record, owner=Depends(current_user)):
    if kind not in KINDS or len(record_id)>100 or len(encode(body.data))>100000:
        raise HTTPException(422,'记录类型或内容大小无效')
    with connect() as db:
        db.execute('BEGIN IMMEDIATE')
        row = db.execute('SELECT revision FROM student_records WHERE owner=? AND kind=? AND id=?',(owner,kind,record_id)).fetchone()
        if (row['revision'] if row else 0)!=body.revision:
            raise HTTPException(409,'云端记录已变化，请重新读取后合并，不会覆盖你的本机记录')
        revision = body.revision+1
        db.execute('INSERT INTO student_records VALUES (?,?,?,?,?,?) ON CONFLICT(owner,kind,id) DO UPDATE SET data=excluded.data,revision=excluded.revision,updated=excluded.updated',
                   (owner,kind,record_id,encode(body.data),revision,time.time()))
    return {'revision':revision,'synced':True}


@router.delete('/records/{kind}/{record_id}')
def delete_record(kind: str, record_id: str, revision: int, owner=Depends(current_user)):
    with connect() as db:
        row = db.execute('SELECT revision FROM student_records WHERE owner=? AND kind=? AND id=?',(owner,kind,record_id)).fetchone()
        if row and row['revision']!=revision:
            raise HTTPException(409,'记录已变化，请重新读取')
        db.execute('DELETE FROM student_records WHERE owner=? AND kind=? AND id=?',(owner,kind,record_id))
    return {'ok':True}


@router.get('/catalog')
def catalog(q: str=Query('',max_length=100), year: int=2023):
    path = Path(__file__).parent/'catalog.json'
    data = json.loads(path.read_text('utf-8')) if path.exists() else []
    return {'items':[c for c in data if c['year']==year and q.casefold() in (c['name']+' '+c.get('aliases','')).casefold()],
            'recognition':'目录收录不等于本学院综测或推免认定，请核对学院当年文件'}


class Team(BaseModel):
    contest: str = Field(min_length=1,max_length=120)
    title: str = Field(min_length=2,max_length=120)
    track: str = Field(min_length=1,max_length=100)
    capacity: int = Field(ge=2,le=30)
    roles: str = Field(min_length=1,max_length=500)
    schedule: str = Field(min_length=1,max_length=500)
    goal: str = Field(default='',max_length=500)
    location: str = Field(default='',max_length=120)
    deadline: float | None = None


def expire(db):
    db.execute("UPDATE team_applications SET status='expired' WHERE status='offered' AND expires<?",(time.time(),))


def team_view(db, row, owner=''):
    data = dict(row)
    data['data'] = json.loads(data['data'])
    data['members'] = 1+db.execute("SELECT COUNT(*) FROM team_applications WHERE team=? AND status='member'",(row['id'],)).fetchone()[0]
    data['reserved'] = db.execute("SELECT COUNT(*) FROM team_applications WHERE team=? AND status='offered'",(row['id'],)).fetchone()[0]
    data['mine'] = row['owner']==owner
    return data


@router.get('/teams')
def teams(contest: str='', q: str=Query('',max_length=100), page: int=Query(1,ge=1)):
    with connect() as db:
        expire(db)
        rows = db.execute("SELECT * FROM teams WHERE status!='closed' AND (?='' OR contest=?) AND instr(title,?)>0 ORDER BY updated DESC LIMIT 30 OFFSET ?",(contest,contest,q,(page-1)*30)).fetchall()
        return {'items':[team_view(db,r) for r in rows]}


@router.post('/teams')
def create_team(body: Team, owner=Depends(current_user)):
    key = uid()
    if body.deadline and body.deadline<=time.time():
        raise HTTPException(422,'招募截止时间须在未来')
    with connect() as db:
        if db.execute('SELECT COUNT(*) FROM teams WHERE owner=? AND updated>?',(owner,time.time()-86400)).fetchone()[0]>=10:
            raise HTTPException(429,'今天发布的队伍较多，请先处理现有招募')
        db.execute('INSERT INTO teams VALUES (?,?,?,?,?,?,?,?)',(key,owner,body.contest,body.title,encode(body.model_dump()),body.capacity,'open',time.time()))
    return {'id':key}


@router.get('/teams/mine')
def my_teams(owner=Depends(current_user)):
    with connect() as db:
        expire(db)
        rows = db.execute('SELECT DISTINCT t.* FROM teams t LEFT JOIN team_applications a ON a.team=t.id WHERE t.owner=? OR a.owner=? ORDER BY t.updated DESC',(owner,owner)).fetchall()
        return {'items':[team_view(db,r,owner) for r in rows]}


@router.get('/teams/{key}')
def team_detail(key: str, owner=Depends(current_user)):
    with connect() as db:
        expire(db)
        row = db.execute('SELECT * FROM teams WHERE id=?',(key,)).fetchone()
        if not row:
            raise HTTPException(404,'队伍不存在')
        result = team_view(db,row,owner)
        applications = db.execute('SELECT * FROM team_applications WHERE team=? AND (?=1 OR owner=?)',(key,int(row['owner']==owner),owner)).fetchall()
        result['applications'] = [{**dict(a),'data':json.loads(a['data'])} for a in applications]
        membership = db.execute("SELECT 1 FROM team_applications WHERE team=? AND owner=? AND status='member'",(key,owner)).fetchone()
        result['tasks'] = [json.loads(t['data'])|{'id':t['id']} for t in db.execute('SELECT * FROM team_tasks WHERE team=?',(key,))] if row['owner']==owner or membership else []
    return result


class Application(BaseModel):
    nickname: str = Field(min_length=1,max_length=40)
    skills: str = Field(min_length=1,max_length=1000)
    availability: str = Field(min_length=1,max_length=500)
    reason: str = Field(min_length=1,max_length=1000)


@router.post('/teams/{key}/apply')
def apply(key: str, body: Application, owner=Depends(current_user)):
    with connect() as db:
        db.execute('BEGIN IMMEDIATE')
        row = db.execute('SELECT * FROM teams WHERE id=?',(key,)).fetchone()
        if not row or row['status']!='open' or row['owner']==owner:
            raise HTTPException(409,'当前队伍不能申请')
        deadline = json.loads(row['data']).get('deadline')
        if deadline and deadline<time.time():
            raise HTTPException(409,'招募已经截止')
        existing = db.execute('SELECT status FROM team_applications WHERE team=? AND owner=?',(key,owner)).fetchone()
        if existing and existing['status'] in ('pending','offered','member'):
            raise HTTPException(409,'已提交申请，请在我的队伍中查看')
        db.execute('INSERT INTO team_applications VALUES (?,?,?,?,?,?) ON CONFLICT(team,owner) DO UPDATE SET data=excluded.data,status=excluded.status,expires=NULL,updated=excluded.updated',
                   (key,owner,encode(body.model_dump()),'pending',None,time.time()))
        notify(db,row['owner'],'收到新的入队申请',body.nickname+'申请加入'+row['title'],'team:'+key)
    return {'status':'pending'}


class TeamAction(BaseModel):
    action: Literal['offer','reject','confirm','withdraw','leave','pause','resume','close','transfer']
    applicant: str = Field(default='',max_length=64)


@router.post('/teams/{key}/action')
def team_action(key: str, body: TeamAction, owner=Depends(current_user)):
    with connect() as db:
        db.execute('BEGIN IMMEDIATE')
        expire(db)
        row = db.execute('SELECT * FROM teams WHERE id=?',(key,)).fetchone()
        if not row:
            raise HTTPException(404)
        captain = row['owner']==owner
        action = body.action
        if action in ('offer','reject','pause','resume','close','transfer') and not captain:
            raise HTTPException(403,'只有队长可以操作')
        if row['status']=='closed':
            raise HTTPException(409,'队伍已关闭')
        if action in ('pause','resume','close'):
            if action=='close' and db.execute("SELECT 1 FROM team_applications WHERE team=? AND status='member'",(key,)).fetchone():
                raise HTTPException(409,'请先安排成员退出；队长离队请移交队伍')
            db.execute('UPDATE teams SET status=?,updated=? WHERE id=?',({'pause':'paused','resume':'open','close':'closed'}[action],time.time(),key))
            if action=='close':
                db.execute("UPDATE team_applications SET status='cancelled' WHERE team=? AND status IN ('pending','offered')",(key,))
        elif action in ('offer','reject'):
            application = db.execute('SELECT * FROM team_applications WHERE team=? AND owner=?',(key,body.applicant)).fetchone()
            if not application or application['status']!='pending':
                raise HTTPException(409,'该申请已经处理')
            slots = db.execute("SELECT COUNT(*) FROM team_applications WHERE team=? AND status IN ('offered','member')",(key,)).fetchone()[0]+1
            if action=='offer' and (slots>=row['capacity'] or row['status']!='open'):
                raise HTTPException(409,'队伍没有可用名额或已经暂停')
            db.execute('UPDATE team_applications SET status=?,expires=?,updated=? WHERE team=? AND owner=?',
                       ('offered' if action=='offer' else 'rejected',time.time()+48*3600 if action=='offer' else None,time.time(),key,body.applicant))
            notify(db,body.applicant,'组队申请有进展','队长已录用，请在 48 小时内确认' if action=='offer' else '此次申请未通过，可以继续寻找其他队伍','team:'+key)
        elif action=='transfer':
            member = db.execute("SELECT * FROM team_applications WHERE team=? AND owner=? AND status='member'",(key,body.applicant)).fetchone()
            if not member:
                raise HTTPException(409,'只能移交给已入队成员')
            db.execute('DELETE FROM team_applications WHERE team=? AND owner=?',(key,body.applicant))
            db.execute("INSERT INTO team_applications VALUES (?,?,?,'member',NULL,?) ON CONFLICT(team,owner) DO UPDATE SET status='member',expires=NULL",(key,owner,encode({'nickname':'原队长'}),time.time()))
            db.execute('UPDATE teams SET owner=?,updated=? WHERE id=?',(body.applicant,time.time(),key))
        else:
            a = db.execute('SELECT * FROM team_applications WHERE team=? AND owner=?',(key,owner)).fetchone()
            if not a or (action=='confirm' and a['status']!='offered') or (action=='leave' and a['status']!='member') or (action=='withdraw' and a['status'] not in ('pending','offered')):
                raise HTTPException(409,'申请状态已变化，请刷新')
            db.execute('UPDATE team_applications SET status=?,expires=NULL,updated=? WHERE team=? AND owner=?',
                       ('member' if action=='confirm' else 'withdrawn',time.time(),key,owner))
            notify(db,row['owner'],'队伍成员状态变化','一位同学已确认入队' if action=='confirm' else '一位同学已撤回或退出','team:'+key)
    return {'ok':True}


class TeamTask(BaseModel):
    title: str = Field(min_length=1,max_length=160)
    assignee: str = Field(default='',max_length=64)
    due: str = Field(default='',max_length=30)
    done: bool = False


@router.put('/teams/{key}/tasks/{task_id}')
def team_task(key: str, task_id: str, body: TeamTask, owner=Depends(current_user)):
    if len(task_id)>64:
        raise HTTPException(422)
    with connect() as db:
        team = db.execute('SELECT * FROM teams WHERE id=?',(key,)).fetchone()
        if not team or team['status']=='closed':
            raise HTTPException(409,'队伍不可用')
        members = {r[0] for r in db.execute("SELECT owner FROM team_applications WHERE team=? AND status='member'",(key,))}|{team['owner']}
        if owner not in members or (body.assignee and body.assignee not in members):
            raise HTTPException(403,'任务只对队内成员开放')
        prior = db.execute('SELECT * FROM team_tasks WHERE id=?',(task_id,)).fetchone()
        if prior and prior['team']!=key:
            raise HTTPException(403)
        db.execute('INSERT INTO team_tasks VALUES (?,?,?,?) ON CONFLICT(id) DO UPDATE SET data=excluded.data,updated=excluded.updated',(task_id,key,encode(body.model_dump()),time.time()))
    return {'ok':True}


class Post(BaseModel):
    contest: str = Field(min_length=1,max_length=120)
    title: str = Field(min_length=2,max_length=120)
    body: str = Field(min_length=10,max_length=20000)
    year: str = Field(pattern=r'^20\d{2}$')


@router.get('/posts')
def posts(contest: str='', q: str=Query('',max_length=100), page: int=Query(1,ge=1), owner=Depends(optional_user)):
    with connect() as db:
        rows = db.execute("SELECT id,owner,contest,title,body,year,updated FROM posts WHERE hidden=0 AND (?='' OR contest=?) AND instr(title||body,?)>0 AND owner NOT IN (SELECT target FROM blocks WHERE owner=?) ORDER BY updated DESC LIMIT 30 OFFSET ?",(contest,contest,q,owner,(page-1)*30)).fetchall()
        return {'items':[dict(r) for r in rows]}


@router.post('/posts')
def publish_post(body: Post, owner=Depends(current_user)):
    key = uid()
    with connect() as db:
        if db.execute('SELECT COUNT(*) FROM posts WHERE owner=? AND updated>?',(owner,time.time()-86400)).fetchone()[0]>=20:
            raise HTTPException(429,'发布过于频繁')
        db.execute('INSERT INTO posts VALUES (?,?,?,?,?,?,?,0)',(key,owner,body.contest,body.title,body.body,body.year,time.time()))
    return {'id':key}


@router.get('/posts/{key}')
def post_detail(key: str):
    with connect() as db:
        row=db.execute('SELECT * FROM posts WHERE id=? AND hidden=0',(key,)).fetchone()
    if not row:raise HTTPException(404,'内容已下架或不存在')
    return dict(row)


@router.put('/posts/{key}')
def edit_post(key: str, body: Post, owner=Depends(current_user)):
    with connect() as db:
        if not db.execute('SELECT 1 FROM posts WHERE id=? AND owner=? AND hidden=0',(key,owner)).fetchone():
            raise HTTPException(404)
        db.execute('UPDATE posts SET contest=?,title=?,body=?,year=?,updated=? WHERE id=?',(body.contest,body.title,body.body,body.year,time.time(),key))
    return {'ok':True}


class Comment(BaseModel):
    body: str = Field(min_length=1,max_length=2000)


@router.get('/posts/{key}/comments')
def comments(key: str):
    with connect() as db:
        if not db.execute('SELECT 1 FROM posts WHERE id=? AND hidden=0',(key,)).fetchone():
            raise HTTPException(404)
        return {'items':[dict(r) for r in db.execute('SELECT * FROM comments WHERE post=? ORDER BY created LIMIT 200',(key,))]}


@router.post('/posts/{key}/comments')
def comment(key: str, body: Comment, owner=Depends(current_user)):
    with connect() as db:
        if not db.execute('SELECT 1 FROM posts WHERE id=? AND hidden=0',(key,)).fetchone():
            raise HTTPException(404)
        if db.execute('SELECT COUNT(*) FROM comments WHERE owner=? AND created>?',(owner,time.time()-60)).fetchone()[0]>=5:
            raise HTTPException(429,'评论过于频繁')
        db.execute('INSERT INTO comments VALUES (?,?,?,?,?)',(uid(),key,owner,body.body,time.time()))
        post=db.execute('SELECT owner,title FROM posts WHERE id=?',(key,)).fetchone()
        if post['owner']!=owner:
            notify(db,post['owner'],'经验帖收到评论',post['title']+'：'+body.body[:120],'post:'+key)
    return {'ok':True}


class Reaction(BaseModel):
    kind: Literal['helpful','favorite']
    enabled: bool


@router.post('/posts/{key}/reaction')
def reaction(key: str, body: Reaction, owner=Depends(current_user)):
    with connect() as db:
        if not db.execute('SELECT 1 FROM posts WHERE id=? AND hidden=0',(key,)).fetchone():
            raise HTTPException(404)
        if body.enabled:
            db.execute('INSERT OR IGNORE INTO reactions VALUES (?,?,?)',(key,owner,body.kind))
        else:
            db.execute('DELETE FROM reactions WHERE post=? AND owner=? AND kind=?',(key,owner,body.kind))
    return {'ok':True}


class Report(BaseModel):
    target: str = Field(min_length=1,max_length=100)
    reason: str = Field(min_length=2,max_length=1000)


@router.post('/reports')
def report(body: Report, owner=Depends(current_user)):
    with connect() as db:
        db.execute('INSERT INTO reports(id,owner,target,reason,created) VALUES (?,?,?,?,?)',(uid(),owner,body.target,body.reason,time.time()))
    return {'status':'待处理'}


@router.get('/reports')
def reports(owner=Depends(current_user)):
    with connect() as db:
        return {'items':[dict(r) for r in db.execute('SELECT * FROM reports WHERE owner=? ORDER BY created DESC LIMIT 100',(owner,))]}


class FileUpload(BaseModel):
    name: str = Field(min_length=1,max_length=160)
    content: str = Field(max_length=14_000_000)
    category: str = Field(default='其他',max_length=100)
    context: str = Field(default='',max_length=120)
    year: str = Field(default='',max_length=20)
    public: bool = False
    rights_confirmed: bool = False


def files_root():
    root = Path(os.getenv('STUDENT_FILES_PATH',str(Path(os.getenv('DATABASE_PATH','/data/notices.db')).parent/'student-files')))
    root.mkdir(parents=True,exist_ok=True)
    return root


@router.post('/files')
def upload(body: FileUpload, owner=Depends(current_user)):
    if body.public and not body.rights_confirmed:
        raise HTTPException(422,'公开分享前请确认有权分享且文件不含私人信息')
    if any(c in body.name for c in '/\\\r\n'):
        raise HTTPException(422,'文件名不能含路径')
    mime = {'.pdf':'application/pdf','.txt':'text/plain','.docx':'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
            '.xlsx':'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet','.png':'image/png','.jpg':'image/jpeg','.jpeg':'image/jpeg','.zip':'application/zip'}.get(Path(body.name).suffix.lower())
    if not mime:
        raise HTTPException(422,'支持 PDF、TXT、DOCX、XLSX、图片及 ZIP')
    try:
        content = base64.b64decode(body.content,validate=True)
    except ValueError:
        raise HTTPException(422,'文件编码无效')
    if not content or len(content)>10*1024*1024:
        raise HTTPException(413,'单个文件不超过 10 MB')
    sha = hashlib.sha256(content).hexdigest()
    key = uid()
    with connect() as db:
        db.execute('BEGIN IMMEDIATE')
        used = db.execute('SELECT COALESCE(SUM(size),0) FROM student_files WHERE owner=?',(owner,)).fetchone()[0]
        if used+len(content)>200*1024*1024:
            raise HTTPException(413,'个人空间已达到 200 MB，请自行整理文件')
        prior = db.execute('SELECT id FROM student_files WHERE owner=? AND hash=?',(owner,sha)).fetchone()
        if prior:
            return {'id':prior['id'],'duplicate':True,'message':'已存在相同文件，保留原记录及公开状态'}
        (files_root()/key).write_bytes(content)
        db.execute('INSERT INTO student_files VALUES (?,?,?,?,?,?,?,?,?)',(key,owner,body.name,mime,len(content),sha,
                   encode({'category':body.category,'context':body.context,'year':body.year}),int(body.public),time.time()))
    return {'id':key,'duplicate':False}


@router.get('/files')
def files(q: str=Query('',max_length=100), owner=Depends(current_user)):
    with connect() as db:
        return {'items':[{**dict(r),'data':json.loads(r['data'])} for r in db.execute('SELECT * FROM student_files WHERE owner=? AND instr(name||data,?)>0 ORDER BY updated DESC LIMIT 200',(owner,q))]}


@router.get('/resources')
def resources(q: str=Query('',max_length=100), page: int=Query(1,ge=1)):
    with connect() as db:
        return {'items':[{**dict(r),'data':json.loads(r['data'])} for r in db.execute('SELECT id,name,size,data,updated FROM student_files WHERE public=1 AND instr(name||data,?)>0 ORDER BY updated DESC LIMIT 30 OFFSET ?',(q,(page-1)*30))]}


@router.get('/resources/{key}/download')
def resource_download(key: str):
    with connect() as db:
        row = db.execute('SELECT * FROM student_files WHERE id=? AND public=1',(key,)).fetchone()
    return file_response(row)


def file_response(row):
    if not row or not (files_root()/row['id']).is_file():
        raise HTTPException(404,'文件已经取消分享或不存在')
    return FileResponse(files_root()/row['id'],media_type=row['mime'],filename=row['name'],
                        headers={'X-Content-Type-Options':'nosniff','Cache-Control':'private, no-store'})


@router.get('/files/{key}/download')
def download(key: str, owner=Depends(current_user)):
    with connect() as db:
        row = db.execute('SELECT * FROM student_files WHERE id=? AND owner=?',(key,owner)).fetchone()
    return file_response(row)


class Share(BaseModel):
    public: bool
    rights_confirmed: bool = False


@router.put('/files/{key}/sharing')
def sharing(key: str, body: Share, owner=Depends(current_user)):
    if body.public and not body.rights_confirmed:
        raise HTTPException(422,'请确认有权公开分享')
    with connect() as db:
        if not db.execute('SELECT 1 FROM student_files WHERE id=? AND owner=?',(key,owner)).fetchone():
            raise HTTPException(404)
        db.execute('UPDATE student_files SET public=?,updated=? WHERE id=?',(int(body.public),time.time(),key))
    return {'ok':True}


@router.delete('/files/{key}')
def delete_file(key: str, owner=Depends(current_user)):
    with connect() as db:
        row = db.execute('SELECT id FROM student_files WHERE id=? AND owner=?',(key,owner)).fetchone()
        if not row:
            raise HTTPException(404)
        db.execute('DELETE FROM student_files WHERE id=?',(key,))
    (files_root()/row['id']).unlink(missing_ok=True)
    return {'ok':True}
