import base64
import io
import json
import time
import zipfile

import pytest
from app import workspace_agents as agents
from app.database import connect
from app.student_store import digest, uid


def auth(name='a'):
    token=uid()
    with connect() as db:
        db.execute('INSERT INTO students VALUES (?,?,?)',(name,name+'@example.com',time.time()))
        db.execute('INSERT INTO sessions VALUES (?,?,?)',(digest(token),name,time.time()+3600))
    return {'Authorization':'Bearer '+token}


@pytest.fixture
def ai(monkeypatch):
    monkeypatch.setenv('DEEPSEEK_API_KEY','test-only')
    responses=[]
    def fake(instruction,context):
        assert responses,'Unexpected model call'
        return responses.pop(0)
    monkeypatch.setattr(agents,'model_json',fake)
    return responses


def create(client,headers,kind='resume',**changes):
    body={'request_key':uid(),'kind':kind,'text':'本人参加课程项目，负责使用 Python 整理实验数据，并编写项目文档。','consent':True,**changes}
    r=client.post('/api/v1/student/agents/runs',headers=headers,json=body)
    assert r.status_code==200,r.text
    return r.json()['id'],body


def get(client,headers,key):
    r=client.get('/api/v1/student/agents/runs/'+key,headers=headers)
    assert r.status_code==200,r.text
    return r.json()


def test_grade_agent_persists_all_courses_and_remains_private(client,ai):
    from app.grade_analysis import prepare
    h=auth('grades-a');other=auth('grades-b')
    text=json.dumps({'grades':[{'semester':'2025-3','course':'高等数学','credits':4,'score':80,'gpa':3,'status':'正常'},
        {'semester':'2025-3','course':'英语','credits':2,'score':90,'gpa':4,'status':'正常'}]})
    key,_=create(client,h,kind='grades',text=text)
    agents.tick()
    ai.append({'courses':[{'id':g['id'],'assessment':'结合原始成绩核对','actions':['复盘作业和错题'],'check':'自测薄弱内容'} for g in prepare(text)['grades']]})
    agents.tick();agents.tick()
    r=get(client,h,key)
    assert r['status']=='completed' and len(r['output']['courses'])==2
    assert r['output']['calculation']['gpa']==pytest.approx(20/6)
    assert client.get('/api/v1/student/agents/runs?kind=grades',headers=h).json()['items'][0]['id']==key
    assert client.get('/api/v1/student/agents/runs/'+key,headers=other).status_code==404


def test_resume_durable_steps_wait_audit_delivery(client,ai):
    headers=auth();key,body=create(client,headers)
    agents.tick();assert get(client,headers,key)['stage']==1
    ai.append({'summary':'需要明确职责','questions':['具体用什么工具？']})
    agents.tick();r=get(client,headers,key);assert r['status']=='waiting'
    resp=client.post('/api/v1/student/agents/runs/'+key+'/continue',headers=headers,json={'revision':r['revision'],'answer':'使用 Python，整理实验记录。','consent':True})
    assert resp.status_code==200
    ai.extend([{'summary':'修改完成','suggestions':[{'before':body['text'],'after':'负责课程项目的 Python 数据整理与项目文档编写。','reason':'突出本人职责'}],'questions':[]},
               {'summary':'无新增事实','unsafe_indices':[],'warnings':['导出前核对格式']}])
    for _ in range(3):agents.tick()
    result=get(client,headers,key)
    assert result['status']=='completed' and len(result['steps'])==5
    assert result['output']['suggestions'][0]['before']==body['text']
    assert result['input']['text']==body['text']


def test_resume_fabricated_number_fails_and_retry_preserves_steps(client,ai):
    h=auth();key,body=create(client,h)
    agents.tick();ai.append({'summary':'结构调整','questions':[]});agents.tick()
    ai.append({'summary':'修改','suggestions':[{'before':body['text'],'after':'效率提升 30%','reason':'量化'}]})
    agents.tick();r=get(client,h,key)
    assert r['status']=='failed' and r['stage']==2
    before=r['steps'][0]['output']
    assert client.post('/api/v1/student/agents/runs/'+key+'/retry',headers=h,json={'revision':r['revision']}).status_code==200
    assert get(client,h,key)['steps'][0]['output']==before


def test_same_key_is_idempotent_and_content_conflict_rejected(client,ai):
    h=auth();key,body=create(client,h)
    assert client.post('/api/v1/student/agents/runs',headers=h,json=body).json()['id']==key
    body['text']+='不同材料'
    assert client.post('/api/v1/student/agents/runs',headers=h,json=body).status_code==409


def test_ownership_consent_and_assessment_kind(client,ai):
    a,b=auth('a'),auth('b');key,body=create(client,a)
    assert client.get('/api/v1/student/agents/runs/'+key,headers=b).status_code==404
    assert client.delete('/api/v1/student/agents/runs/'+key,headers=b).status_code==404
    body['kind']='assessment';body['request_key']=uid()
    assert client.post('/api/v1/student/agents/runs',headers=a,json=body).status_code==422
    body['kind']='resume';body['consent']=False
    assert client.post('/api/v1/student/agents/runs',headers=a,json=body).status_code==422


def test_cancel_while_model_running_cannot_publish_output(client,ai,monkeypatch):
    h=auth();key,_=create(client,h);agents.tick()
    def cancelling(*_):
        r=get(client,h,key)
        assert client.post('/api/v1/student/agents/runs/'+key+'/cancel',headers=h,json={'revision':r['revision']}).status_code==200
        return {'summary':'late','questions':[]}
    monkeypatch.setattr(agents,'model_json',cancelling)
    agents.tick()
    assert get(client,h,key)['status']=='cancelled'
    assert get(client,h,key)['output']['summary']!='late'


def test_interview_three_turns_adaptive_resume_and_final_tasks(client,ai):
    h=auth();key,_=create(client,h,'interview',question_count=3)
    for i in range(3):
        ai.append({'summary':'练习','question':f'问题 {i+1}','feedback':f'反馈 {i}','focus':'本人贡献'})
        agents.tick();r=get(client,h,key)
        assert r['status']=='waiting' and r['output']['question_number']==i+1
        assert client.post('/api/v1/student/agents/runs/'+key+'/continue',headers=h,json={'revision':r['revision'],'answer':'这是我的具体回答','consent':True}).status_code==200
        assert client.post('/api/v1/student/agents/runs/'+key+'/continue',headers=h,json={'revision':r['revision'],'answer':'重复回答','consent':True}).status_code==409
    ai.append({'summary':'复盘','tasks':[{'title':'练习项目介绍','note':'突出本人职责','due':''}]})
    agents.tick();r=get(client,h,key)
    assert r['status']=='completed' and len(r['turns'])==3
    body={'revision':r['revision'],'item':0,'title':'练习项目介绍','due':'2026-10-01','note':'本人确认'}
    one=client.post('/api/v1/student/agents/runs/'+key+'/apply-task',headers=h,json=body)
    two=client.post('/api/v1/student/agents/runs/'+key+'/apply-task',headers=h,json=body)
    assert one.json()['id']==two.json()['id']
    with connect() as db:assert db.execute("SELECT count(*) FROM student_records WHERE kind='task'").fetchone()[0]==1


def test_recovery_from_interrupted_step(client,ai):
    h=auth();key,_=create(client,h)
    with connect() as db:db.execute("UPDATE agent_runs SET status='running',updated=? WHERE id=?",(time.time()-300,key))
    agents.tick()
    assert get(client,h,key)['status']=='failed'


def upload(client,h,name='资料.txt',content='这是私人材料。学校要求两封推荐信。'):
    b=content.encode() if isinstance(content,str) else content
    r=client.post('/api/v1/student/files',headers=h,json={'name':name,'content':base64.b64encode(b).decode()})
    assert r.status_code==200,r.text
    return r.json()['id']


def test_file_text_page_citations_and_permission_revocation(client,ai):
    a,b=auth('a'),auth('b');fid=upload(client,a)
    assert client.get(f'/api/v1/student/library/files/{fid}/text',headers=b).status_code==404
    own=client.get(f'/api/v1/student/library/files/{fid}/text',headers=a)
    assert own.status_code==200 and own.json()['pages'][0]['page']==1
    client.put(f'/api/v1/student/files/{fid}/sharing',headers=a,json={'public':True,'rights_confirmed':True})
    assert client.get('/api/v1/student/library/search?q=推荐信',headers=b).json()['items']
    client.put(f'/api/v1/student/files/{fid}/sharing',headers=a,json={'public':False})
    assert not client.get('/api/v1/student/library/search?q=推荐信',headers=b).json()['items']
    key,_=create(client,a,'resource',file_ids=[fid]);agents.tick()
    ai.append({'summary':'两封推荐信','sections':[],'citations':[{'source_id':fid,'page':1,'quote':'学校要求两封推荐信。'}]})
    agents.tick();assert get(client,a,key)['stage']==2
    ai.append({'summary':'已整理','tasks':[]});agents.tick()
    assert get(client,a,key)['output']['citations'][0]['name']=='资料.txt'


def test_document_version_and_docx_sections(client,ai):
    h=auth();stream=io.BytesIO()
    with zipfile.ZipFile(stream,'w') as z:z.writestr('word/document.xml','<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:p><w:r><w:t>个人经历</w:t></w:r></w:p></w:document>')
    old=upload(client,h);new=upload(client,h,'简历.docx',stream.getvalue())
    assert client.get(f'/api/v1/student/library/files/{new}/text',headers=h).json()['locator']=='section'
    assert client.post(f'/api/v1/student/library/files/{new}/version',headers=h,json={'previous':old,'note':'更新内容'}).status_code==200
    assert len(client.get(f'/api/v1/student/library/files/{new}/versions',headers=h).json()['items'])==2


def test_agent_rejects_false_source_quotes(client,ai):
    h=auth();fid=upload(client,h);key,_=create(client,h,'resource',file_ids=[fid]);agents.tick()
    ai.append({'summary':'假引文','citations':[{'source_id':fid,'page':1,'quote':'学校要求五封推荐信'}]})
    agents.tick();assert get(client,h,key)['status']=='failed'
