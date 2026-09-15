import argparse
import json
import sqlite3
from pathlib import Path
from .database import initialize, connect
from .crawler import sync
from .qq_ingest import poll
from .qq_review import review


def main():
    parser = argparse.ArgumentParser(description='青理竞赛通维护工具（仅服务器本地执行）')
    parser.add_argument('action', choices=['sync', 'backup', 'qq-poll', 'qq-review', 'contest-sync', 'retire-qq'])
    parser.add_argument('--full', action='store_true')
    parser.add_argument('--output', default='/data/backup.db')
    args = parser.parse_args()
    initialize()
    if args.action in ('contest-sync','retire-qq'):
        from . import student_store, workspace_contests
        from .notice_migration import retire_qq
        student_store.initialize(); workspace_contests.initialize()
        print(json.dumps(retire_qq() if args.action=='retire-qq' else workspace_contests.tick(full=args.full),ensure_ascii=False))
    elif args.action == 'sync':
        result = sync(full=args.full, revisit=True)
        print(json.dumps(result, ensure_ascii=False))
        if 'error' in result:
            raise SystemExit(1)
    elif args.action == 'qq-poll':
        result = poll()
        print(json.dumps(result, ensure_ascii=False))
        if 'error' in result:
            raise SystemExit(1)
    elif args.action == 'qq-review':
        result = review()
        print(json.dumps(result, ensure_ascii=False))
    else:
        output = Path(args.output)
        output.parent.mkdir(parents=True, exist_ok=True)
        with connect() as source, sqlite3.connect(output) as target:
            source.backup(target)
        print('备份完成: ' + str(output))


if __name__ == '__main__':
    main()
