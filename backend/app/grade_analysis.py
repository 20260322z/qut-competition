"""Deterministic GPA and course-by-course coaching; never exposes campus credentials."""
import hashlib
import json
import math

def numeric(value, low, high):
    return isinstance(value,(int,float)) and not isinstance(value,bool) and math.isfinite(value) and low<=value<=high

def prepare(text):
    raw=json.loads(text)
    rows=raw.get('grades')
    if not isinstance(rows,list) or not 1<=len(rows)<=60:raise ValueError('请选择一个学期或一门课程，最多 60 门')
    grades=[]
    for r in rows:
        if not isinstance(r,dict) or not isinstance(r.get('course'),str) or not r['course'].strip():raise ValueError('缺少课程名称')
        if not numeric(r.get('credits'),0,50):raise ValueError('学分无效')
        for k,limit in [('score',100),('gpa',5)]:
            if r.get(k) is not None and not numeric(r[k],0,limit):raise ValueError('成绩或绩点无效')
        g={k:r.get(k) for k in ('semester','course','credits','score','gpa','status')}
        if not isinstance(g['semester'],str) or not g['semester'].strip():raise ValueError('缺少学期')
        if g['status'] not in ('正常','未发布','补考','重修','免修','不计入'):raise ValueError('课程状态无效')
        g['id']=hashlib.sha256((g['semester']+'\0'+g['course']).encode()).hexdigest()[:20]
        grades.append(g)
    if len({r['id'] for r in grades})!=len(grades):raise ValueError('存在重复课程，请先核对')
    def summary(items):
        eligible=[g for g in items if g['status']=='正常' and g['credits']>0]
        scores=[g for g in eligible if g['score'] is not None];gp=[g for g in eligible if g['gpa'] is not None]
        sc=sum(g['credits'] for g in scores);gc=sum(g['credits'] for g in gp)
        return {'courses':len(items),'credits':sum(g['credits'] for g in eligible),'score_credits':sc,'gpa_credits':gc,
          'average':sum(g['score']*g['credits'] for g in scores)/sc if sc else None,
          'gpa':sum(g['gpa']*g['credits'] for g in gp)/gc if gc else None,
          'partial_gpa':len(gp)<len(eligible),'excluded':len(items)-len(eligible)}
    return {'summary':'成绩核对完成，绩点由原始数据按学分加权计算。','grades':grades,
      'overall':summary(grades),'semesters':{t:summary([g for g in grades if g['semester']==t]) for t in sorted({g['semester'] for g in grades})},
      'rule':'原表绩点按学分加权；补考、重修、免修和不计入课程暂排除，缺失绩点不猜测。'}

def analyze(prepared,model):
    result=[]
    for offset in range(0,len(prepared['grades']),10):
        batch=prepared['grades'][offset:offset+10]
        answer=model('逐门分析课程成绩。成绩只能说明结果，不能据此断言学生懒惰、知识点未掌握或班级排名。'
          '结合课程名称给出适合该课程的可执行复习建议，但知识薄弱点须写成待自测的假设。每门必须返回且仅返回一次。'
          '不要重新计算或改写分数。JSON {"courses":[{"id":"输入课程id","assessment":"基于现有分数的简短分析",'
          '"actions":["具体建议1","具体建议2"],"check":"需要学生确认的知识点或原因"}]}。',
          {'courses':batch,'semester_summary':prepared['semesters'],'rule':prepared['rule']})
        items=answer.get('courses')
        if not isinstance(items,list) or sorted(x.get('id','') for x in items if isinstance(x,dict))!=sorted(g['id'] for g in batch):raise ValueError('模型遗漏或重复课程')
        for item in items:
            if not isinstance(item.get('assessment'),str) or not isinstance(item.get('check'),str):raise ValueError('模型分析不完整')
            actions=item.get('actions')
            if not isinstance(actions,list) or not 1<=len(actions)<=5 or any(not isinstance(a,str) or not a.strip() for a in actions):raise ValueError('模型建议不完整')
            source=next(g for g in batch if g['id']==item['id'])
            result.append({**source,'assessment':item['assessment'][:2000],'actions':[a[:1000] for a in actions],'check':item['check'][:1000]})
    return {'summary':f'已逐门分析 {len(result)} 门课程。建议用于复习安排，不代表学校评价。','courses':result,
      'calculation':prepared['overall'],'semesters':prepared['semesters'],'rule':prepared['rule']}
