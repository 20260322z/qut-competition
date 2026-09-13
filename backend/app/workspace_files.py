"""Permission-checked document reading and version metadata for student tools."""
import io
import json
import time
import zipfile
from pathlib import Path
from xml.etree import ElementTree

from fastapi import APIRouter, Depends, HTTPException, Query
from pydantic import BaseModel, Field
from .database import connect
from .student_store import current_user, optional_user, encode
from .student_api import files_root

router = APIRouter(prefix='/api/v1/student/library')


def initialize():
    with connect() as db:
        db.executescript('''
        CREATE TABLE IF NOT EXISTS file_texts(id TEXT PRIMARY KEY,hash TEXT NOT NULL,pages TEXT NOT NULL,updated REAL NOT NULL);
        CREATE TABLE IF NOT EXISTS file_versions(id TEXT PRIMARY KEY,root TEXT NOT NULL,note TEXT NOT NULL,created REAL NOT NULL);
        CREATE TABLE IF NOT EXISTS file_bookmarks(owner TEXT NOT NULL,file TEXT NOT NULL,page INTEGER NOT NULL,
          favorite INTEGER NOT NULL,updated REAL NOT NULL,PRIMARY KEY(owner,file));
        ''')


def accessible(db, key, owner):
    row = db.execute('SELECT * FROM student_files WHERE id=? AND (owner=? OR public=1)', (key, owner)).fetchone()
    if not row or not (files_root()/row['id']).is_file():
        raise HTTPException(404, '资料不存在或已取消访问权限')
    return row


def extract_bytes(content, name):
    suffix = Path(name).suffix.lower()
    if suffix == '.txt':
        try: text = content.decode('utf-8-sig')
        except UnicodeDecodeError: text = content.decode('gb18030', errors='replace')
        return [text[:100000]]
    if suffix == '.pdf':
        from pypdf import PdfReader
        try:
            reader = PdfReader(io.BytesIO(content))
            if reader.is_encrypted: raise HTTPException(422, '加密 PDF 请先由本人解密再上传')
            if len(reader.pages) > 300: raise HTTPException(422, '最多解析 300 页，请拆分文件')
            return [(p.extract_text() or '')[:12000] for p in reader.pages]
        except HTTPException: raise
        except Exception: raise HTTPException(422, 'PDF 无法解析，请核对文件或改用文字内容')
    if suffix == '.docx':
        try:
            with zipfile.ZipFile(io.BytesIO(content)) as archive:
                info = archive.getinfo('word/document.xml')
                if info.file_size > 4*1024*1024: raise ValueError('expanded limit')
                data = archive.read(info)
                if b'<!DOCTYPE' in data or b'<!ENTITY' in data: raise ValueError('entities')
                tree = ElementTree.fromstring(data)
                paragraphs = [''.join(p.itertext()) for p in tree.iter('{http://schemas.openxmlformats.org/wordprocessingml/2006/main}p')]
                # DOCX has no stable page layout. Label chunks as sections, not PDF pages.
                text = '\n'.join(paragraphs)[:100000]
                return [text[i:i+6000] for i in range(0, len(text), 6000)] or ['']
        except Exception: raise HTTPException(422, 'Word 文件无法解析，请使用正常的 DOCX 文件')
    raise HTTPException(422, '当前支持 PDF、DOCX 和 TXT 内容读取；图片和其他格式请先转成可选中文字的 PDF')


def extract_owned(key, owner):
    with connect() as db:
        row = accessible(db, key, owner)
        cached = db.execute('SELECT * FROM file_texts WHERE id=? AND hash=?', (key, row['hash'])).fetchone()
    chunks = json.loads(cached['pages']) if cached else extract_bytes((files_root()/key).read_bytes(), row['name'])
    if sum(len(p) for p in chunks) > 120000:
        raise HTTPException(422, '文字超过本次处理范围，请拆分后上传')
    if not cached:
        with connect() as db:
            accessible(db, key, owner)
            db.execute('INSERT OR REPLACE INTO file_texts VALUES (?,?,?,?)', (key, row['hash'], encode(chunks), time.time()))
    if not any(p.strip() for p in chunks):
        raise HTTPException(422, '文件没有可读取的文字，可能是扫描件；请先识别文字再导入，原文件仍可下载')
    return {'id': key, 'name': row['name'], 'hash': row['hash'], 'locator': 'page' if row['name'].lower().endswith('.pdf') else 'section',
            'pages': [{'source_id': key, 'name': row['name'], 'page': i+1, 'text': t} for i, t in enumerate(chunks)],
            'empty_pages': [i+1 for i,t in enumerate(chunks) if not t.strip()]}


@router.get('/files/{key}/text')
def text(key: str, owner=Depends(optional_user)):
    return extract_owned(key, owner)


@router.get('/search')
def search(q: str = Query(min_length=1, max_length=100), owner=Depends(optional_user)):
    with connect() as db:
        rows = db.execute('SELECT f.id,f.name,f.public,t.pages FROM student_files f JOIN file_texts t ON t.id=f.id AND t.hash=f.hash WHERE (f.owner=? OR f.public=1) AND instr(lower(t.pages),lower(?))>0 LIMIT 40', (owner, q)).fetchall()
        result = []
        for r in rows:
            for i, page in enumerate(json.loads(r['pages'])):
                at = page.casefold().find(q.casefold())
                if at >= 0:
                    result.append({'id': r['id'], 'name': r['name'], 'public':r['public'], 'page': i+1, 'excerpt': page[max(0, at-80):at+180]}); break
    return {'items': result, 'scope': '已成功读取且你有权限访问的文件'}


class Bookmark(BaseModel):
    page: int = Field(default=1, ge=1, le=300)
    favorite: bool = True


@router.put('/files/{key}/bookmark')
def bookmark(key: str, body: Bookmark, owner=Depends(current_user)):
    with connect() as db:
        accessible(db, key, owner)
        db.execute('INSERT OR REPLACE INTO file_bookmarks VALUES (?,?,?,?,?)', (owner, key, body.page, int(body.favorite), time.time()))
    return {'ok': True}


@router.get('/bookmarks')
def bookmarks(owner=Depends(current_user)):
    with connect() as db:
        return {'items': [dict(r) for r in db.execute('SELECT f.id,f.name,b.page,b.favorite FROM file_bookmarks b JOIN student_files f ON f.id=b.file WHERE b.owner=? AND (f.owner=? OR f.public=1) ORDER BY b.updated DESC', (owner, owner))]}


class Version(BaseModel):
    previous: str = Field(min_length=1, max_length=100)
    note: str = Field(default='', max_length=1000)


@router.post('/files/{key}/version')
def version(key: str, body: Version, owner=Depends(current_user)):
    if key == body.previous: raise HTTPException(422, '请选择不同的新旧文件')
    with connect() as db:
        db.execute('BEGIN IMMEDIATE')
        for item in (key, body.previous):
            row = accessible(db, item, owner)
            if row['owner'] != owner: raise HTTPException(403, '只能管理本人文件的版本')
        if db.execute('SELECT 1 FROM file_versions WHERE id=?', (key,)).fetchone():
            raise HTTPException(409, '该文件已经归入版本组')
        parent = db.execute('SELECT root FROM file_versions WHERE id=?', (body.previous,)).fetchone()
        root = parent['root'] if parent else body.previous
        if not parent: db.execute('INSERT INTO file_versions VALUES (?,?,?,?)', (root, root, '初始版本', time.time()))
        db.execute('INSERT INTO file_versions VALUES (?,?,?,?)', (key, root, body.note, time.time()))
    return {'ok': True, 'root': root}


@router.get('/files/{key}/versions')
def versions(key: str, owner=Depends(current_user)):
    with connect() as db:
        accessible(db, key, owner)
        row = db.execute('SELECT root FROM file_versions WHERE id=?', (key,)).fetchone()
        if not row: return {'items': []}
        return {'items': [dict(r) for r in db.execute('SELECT f.id,f.name,v.note,v.created FROM file_versions v JOIN student_files f ON f.id=v.id WHERE v.root=? AND (f.owner=? OR f.public=1) ORDER BY v.created DESC', (row['root'], owner))]}
