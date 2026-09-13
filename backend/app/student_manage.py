"""Local-only moderation and job operations. No public admin password or endpoint."""
import argparse
import json
from .database import initialize, connect
from . import student_store, student_mail, student_ai


def main():
    parser=argparse.ArgumentParser(description='学生工作台维护（仅服务器本地）')
    parser.add_argument('action',choices=['mail-tick','ai-tick','reports','resolve-report'])
    parser.add_argument('--id')
    parser.add_argument('--decision',choices=['驳回','已处理'])
    parser.add_argument('--hide',action='store_true',help='处理举报时同时隐藏被举报帖子或取消文件公开')
    args=parser.parse_args();initialize();student_store.initialize()
    if args.action=='mail-tick':student_mail.tick()
    elif args.action=='ai-tick':student_ai.tick()
    elif args.action=='reports':
        with connect() as db:
            print(json.dumps([dict(r) for r in db.execute('SELECT id,target,reason,status,created FROM reports ORDER BY created DESC LIMIT 100')],ensure_ascii=False,indent=2))
    else:
        if not args.id or not args.decision:parser.error('需要 --id 和 --decision')
        with connect() as db:
            row=db.execute('SELECT * FROM reports WHERE id=?',(args.id,)).fetchone()
            if not row:parser.error('举报不存在')
            if args.hide:
                kind,_,key=row['target'].partition(':')
                if kind=='post':db.execute('UPDATE posts SET hidden=1 WHERE id=?',(key,))
                elif kind=='file':db.execute('UPDATE student_files SET public=0 WHERE id=?',(key,))
                else:parser.error('此举报目标类型不能隐藏')
            db.execute('UPDATE reports SET status=? WHERE id=?',(args.decision,args.id))
        print('处理结果已保存')


if __name__=='__main__':main()
