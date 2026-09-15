import logging
import threading
import time
import ssl
from pathlib import Path
from datetime import datetime, timezone

import httpx

from .database import connect, now, upsert
from .parser import LIST_URL, parse_list, parse_notice, WithdrawnNotice

log = logging.getLogger('qut.crawler')
LOCK = threading.Lock()


def fetch(client, url):
    for attempt in range(3):
        try:
            response = client.get(url)
            response.raise_for_status()
            # This site declares UTF-8 in HTML but does not always send it in HTTP.
            return response.content.decode('utf-8-sig')
        except (httpx.HTTPError, UnicodeDecodeError):
            if attempt == 2:
                raise
            time.sleep(2 ** attempt)


def sync(full=False, revisit=False):
    if not LOCK.acquire(blocking=False):
        return {'skipped': True}
    changed, fetched, errors = 0, 0, []
    try:
        with connect() as db:
            db.execute("UPDATE source_state SET running=1,last_attempt=? WHERE id='qut'", (now(),))
            count = db.execute('SELECT COUNT(*) FROM notices').fetchone()[0]
        page_limit = 10 if full or count == 0 else 2
        urls, page = [], LIST_URL
        tls = ssl.create_default_context()
        # The school server omits this publicly issued intermediate certificate.
        # Add its CA-supplied chain; hostname and root verification stay enabled.
        tls.load_verify_locations(str(Path(__file__).parent / 'certs' / 'qut-issuer.pem'))
        with httpx.Client(timeout=25, follow_redirects=True, verify=tls,
                          headers={'User-Agent': 'QutCompetition/1.0 (personal campus notification reader)'}) as client:
            for _ in range(page_limit):
                if not page:
                    break
                links, next_page = parse_list(fetch(client, page), page)
                if not links:
                    raise ValueError('通知列表为空，请检查官网结构')
                urls.extend(x for x in links if x not in urls)
                page = next_page
                time.sleep(0.6)
            if revisit:
                with connect() as db:
                    for row in db.execute("SELECT url FROM notices WHERE source='青岛理工大学创新创业学院' AND deadline IS NOT NULL AND deadline >= ?",
                                          (datetime.now(timezone.utc).isoformat(),)):
                        if row['url'] not in urls:
                            urls.append(row['url'])
            for url in urls:
                try:
                    changed += int(upsert(parse_notice(fetch(client, url), url)))
                    fetched += 1
                except WithdrawnNotice:
                    log.info('School withdrew article %s', url)
                except Exception as exc:
                    log.warning('Failed article %s: %s', url, type(exc).__name__)
                    errors.append(url)
                time.sleep(0.4)
        if not fetched:
            raise RuntimeError('此次未能读取任何通知，已保留已有数据')
        with connect() as db:
            db.execute("UPDATE source_state SET last_success=?,last_error=?,running=0 WHERE id='qut'",
                       (now(), f'{len(errors)} 条通知暂未同步，将在后续重试' if errors else None))
        return {'fetched': fetched, 'changed': changed, 'failed': len(errors)}
    except Exception as exc:
        log.exception('Sync failed')
        with connect() as db:
            db.execute("UPDATE source_state SET last_error=?,running=0 WHERE id='qut'",
                       ('官网暂时无法同步（' + type(exc).__name__ + '），已保留已有通知',))
        return {'error': type(exc).__name__, 'fetched': fetched, 'changed': changed}
    finally:
        LOCK.release()
