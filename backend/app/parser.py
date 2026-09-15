import hashlib
import json
import re
from datetime import datetime, timedelta, timezone
from urllib.parse import urljoin, urlsplit, urlunsplit


BASE_URL = 'https://chuangye.qut.edu.cn'
LIST_URL = BASE_URL + '/index/sy/tzgg.htm'
CHINA = timezone(timedelta(hours=8))

class WithdrawnNotice(ValueError):
    pass


def clean(text):
    text = text.replace('\xa0', ' ').replace('\u200b', '')
    text = re.sub(r'(?<=\d)\s+(?=\d)', '', text)
    text = re.sub(r'\s*([年月日：:])\s*', r'\1', text)
    return re.sub(r'[ \t]+', ' ', text).strip()


def source_url(href, base):
    value = urljoin(base, href)
    parts = urlsplit(value)
    if parts.scheme not in ('http', 'https'):
        return None
    return urlunsplit((parts.scheme, parts.netloc, parts.path, parts.query, ''))


def parse_list(html, page_url=LIST_URL):
    from bs4 import BeautifulSoup
    soup = BeautifulSoup(html, 'html.parser')
    links = []
    for a in soup.select('a[href]'):
        url = source_url(a['href'], page_url)
        if url and urlsplit(url).netloc == 'chuangye.qut.edu.cn' and re.search(r'/info/1088/\d+\.htm$', url):
            if url not in links:
                links.append(url)
    next_link = soup.find('a', string=re.compile(r'^\s*下页\s*$'))
    next_url = source_url(next_link['href'], page_url) if next_link else None
    if next_url and urlsplit(next_url).netloc != 'chuangye.qut.edu.cn':
        next_url = None
    return links, next_url


def categorize(title):
    groups = [
        ('外语', r'外语|外研|外教|英语|翻译|跨文化|多语种|笔译'),
        ('设计', r'设计|艺术|创意|成图|广告'),
        ('创业', r'创业|商业|电子商务|三创|市场调查|企业竞争'),
        ('科技', r'机器人|人工智能|数学|建模|程序|计算机|软件|电子|机械|智能|蓝桥|iCAN|工程|物理|化学'),
    ]
    return next((name for name, pattern in groups if re.search(pattern, title, re.I)), '综合')


DATE = re.compile(r'(?:(20\d{2})年)?(\d{1,2})月(\d{1,2})日')


def extract_deadline(body, published_at):
    """Conservative extraction: distinct registration deadlines remain unknown.

    Local (校赛) explicitly labelled deadlines win over general registration.
    Dates in competition/results sections cannot become a registration deadline.
    """
    lines = [clean(s) for block in body.splitlines() for s in re.split(r'[。；;]', block) if clean(s)]
    candidates = []
    publication = datetime.fromisoformat(published_at)
    for i, line in enumerate(lines):
        previous = lines[i - 1] if i else ''
        context = line
        if not re.search(r'报名|报送|投稿|提交.*作品|上传.*作品|作品.*(?:上传|提交)', line):
            if len(previous) < 70 and re.search(r'报名.*(?:时间|截止)|参赛报名', previous):
                context = previous + ' ' + line
            else:
                continue
        if not re.search(r'截止|截至|日.{0,18}前|即日起至|即日起\s*[-—~～]|时间.{0,40}至|报名日期.*[-—~～]', context):
            continue
        matches = list(DATE.finditer(line))
        if not matches:
            continue
        # A start/end range has one end date; unrelated multiple dates are ambiguous.
        if len(matches) > 1:
            between = line[matches[-2].end():matches[-1].start()]
            if not re.fullmatch(r'[\s\-—~～至到]*(?:20\d{2}年)?', between):
                continue
            matches = [matches[-1]]
        match = matches[0]
        year = int(match[1] or publication.year)
        month, day = int(match[2]), int(match[3])
        # Missing year around a year boundary is deliberately not guessed.
        if not match[1] and month < publication.month - 1:
            continue
        trailing = line[match.end():match.end() + 35]
        time = re.search(r'(\d{1,2})[:：](\d{2})|(\d{1,2})[点时]', trailing)
        hour, minute = (int(time[1] or time[3]), int(time[2] or 0)) if time else (23, 59)
        if ('下午' in trailing or '晚上' in trailing) and hour < 12:
            hour += 12
        if '上午' in trailing and hour == 12:
            hour = 0
        try:
            if hour == 24 and minute == 0:
                date = datetime(year, month, day, tzinfo=CHINA) + timedelta(days=1)
            else:
                date = datetime(year, month, day, hour, minute, tzinfo=CHINA)
        except ValueError:
            continue
        if date.date() < publication.date():
            continue
        priority = 2 if '校赛' in context and '报名' in context else (1 if '报名' in context else 0)
        candidates.append((priority, date.isoformat(), context[:240]))
    if not candidates:
        return None, None
    best = max(x[0] for x in candidates)
    selected = [x for x in candidates if x[0] == best]
    if len({x[1] for x in selected}) != 1:
        return None, None
    return selected[0][1], selected[0][2]


def parse_notice(html, url):
    from bs4 import BeautifulSoup
    soup = BeautifulSoup(html, 'html.parser')
    if '该内容已经被撤销' in soup.get_text():
        raise WithdrawnNotice('学校已撤销这条通知')
    article = soup.select_one('.article_contain')
    content = soup.select_one('#vsb_content, #vsb_content_2')
    if not article or not content:
        raise ValueError('通知正文结构发生变化')
    heading = article.find(['h1', 'h2', 'h3'])
    title = clean(heading.get_text(' ', strip=True)) if heading else clean(soup.title.get_text()).split('-青岛')[0]
    date_match = re.search(r'发布时间[：:]\s*(\d{4}-\d{2}-\d{2})', article.get_text(' ', strip=True))
    if not date_match:
        raise ValueError('通知缺少可核实的发布日期')
    published = date_match[1]
    for node in content(['script', 'style', 'iframe']):
        node.decompose()
    blocks = content.find_all(['p', 'tr', 'li'])
    lines = [clean(p.get_text(' ', strip=True)) for p in blocks if not p.find_parent(['p', 'tr', 'li'])]
    body = '\n\n'.join(x for x in lines if x) or clean(content.get_text(' ', strip=True))
    attachments = []
    for a in article.select('a[href]'):
        href = source_url(a['href'], url)
        if href and (re.search(r'\.(pdf|docx?|xlsx?|zip|rar|pptx?)(\?|$)', href, re.I) or '/download.jsp' in href or '/_upload/' in href):
            if href not in [x['url'] for x in attachments]:
                attachments.append({'name': clean(a.get_text(' ', strip=True)) or '查看附件', 'url': href})
    images = []
    for img in content.select('img[src]'):
        href = source_url(img['src'], url)
        if href and href not in images:
            images.append(href)
    if not title or (not body and not images and not attachments):
        raise ValueError('通知内容不完整')
    if not body:
        body = '本通知以图片或附件发布，请查看下方原文图片、附件或打开原文。'
    deadline, evidence = extract_deadline(body, published)
    record = dict(id=hashlib.sha256(url.encode()).hexdigest()[:20], url=url, title=title,
                  published_at=published, category=categorize(title), summary=body[:150],
                  body=body, deadline=deadline, deadline_evidence=evidence,
                  attachments=attachments, images=images, source='青岛理工大学创新创业学院')
    record['content_hash'] = hashlib.sha256(json.dumps(record, sort_keys=True, ensure_ascii=False).encode()).hexdigest()
    return record
