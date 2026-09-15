import json
import pytest
from app.grade_analysis import prepare,analyze

def rows():
    return [{'course':'高等数学','semester':'2025-3','credits':4,'score':80,'gpa':3,'status':'正常'},
      {'course':'大学英语','semester':'2025-3','credits':2,'score':90,'gpa':4,'status':'正常'},
      {'course':'实践课','semester':'2025-12','credits':1,'score':None,'gpa':4,'status':'正常'},
      {'course':'重修课程','semester':'2025-12','credits':3,'score':95,'gpa':4.5,'status':'重修'}]

def test_weighted_gpa_uses_official_points_even_for_qualitative_scores():
    p=prepare(json.dumps({'grades':rows()}))
    assert p['overall']['gpa']==pytest.approx(24/7)
    assert p['overall']['average']==pytest.approx(500/6)
    assert p['semesters']['2025-12']['gpa']==4
    assert p['overall']['excluded']==1

def test_missing_gpa_is_marked_partial_and_never_invented():
    g=rows();g[0]['gpa']=None
    p=prepare(json.dumps({'grades':g}))
    assert p['overall']['partial_gpa'] is True
    assert p['overall']['gpa']==4

@pytest.mark.parametrize('value',[float('nan'),float('inf'),-1,101,True])
def test_invalid_scores_rejected(value):
    g=rows();g[0]['score']=value
    with pytest.raises(ValueError):prepare(json.dumps({'grades':g}))

def test_reject_duplicate_or_missing_course():
    with pytest.raises(ValueError):prepare(json.dumps({'grades':[rows()[0],rows()[0]]}))
    with pytest.raises(ValueError):prepare(json.dumps({'grades':[]}))

def test_every_course_must_have_advice_and_numbers_are_copied_from_input():
    p=prepare(json.dumps({'grades':rows()}))
    def model(_,ctx):
        return {'courses':[{'id':g['id'],'assessment':'考核成绩待结合试卷核对','actions':['先复盘错题'],'check':'检查计算过程','score':999} for g in ctx['courses']]}
    result=analyze(p,model)
    assert len(result['courses'])==4
    assert result['courses'][0]['score']==80
    with pytest.raises(ValueError):analyze(p,lambda *args:{'courses':[]})

def test_batches_cover_large_semester_without_omitting_courses():
    g=[{**rows()[0],'course':f'课程{i}'} for i in range(24)]
    calls=[]
    def model(_,ctx):
        calls.append(len(ctx['courses']))
        return {'courses':[{'id':r['id'],'assessment':'分析','actions':['整理知识框架'],'check':'自查'} for r in ctx['courses']]}
    result=analyze(prepare(json.dumps({'grades':g})),model)
    assert calls==[10,10,4] and len(result['courses'])==24
