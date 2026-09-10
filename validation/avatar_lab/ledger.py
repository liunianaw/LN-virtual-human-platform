import json
import sqlite3
import uuid
from contextlib import contextmanager
from datetime import datetime, timezone


class Ledger:
    """One durable budget for this entire experiment, in integer CNY cents."""
    def __init__(self, path):
        path.parent.mkdir(parents=True, exist_ok=True)
        self.path = path
        with self.connect() as db:
            db.executescript('''
                CREATE TABLE IF NOT EXISTS attempts (
                    id TEXT PRIMARY KEY, job TEXT NOT NULL, state TEXT NOT NULL,
                    cost INTEGER NOT NULL, created TEXT NOT NULL, metadata TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS accepted (
                    job TEXT PRIMARY KEY, id TEXT NOT NULL, hash TEXT NOT NULL, note TEXT NOT NULL
                );
            ''')

    @contextmanager
    def connect(self):
        db = sqlite3.connect(self.path, timeout=10)
        db.row_factory = sqlite3.Row
        try:
            with db:
                yield db
        finally:
            db.close()

    def reserve(self, job, enforce_limits=True):
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            if enforce_limits and db.execute("SELECT 1 FROM attempts WHERE state IN ('reserved','unknown')").fetchone():
                raise ValueError('有待核对请求，禁止继续生成。请先查看 status。')
            if enforce_limits and db.execute('SELECT 1 FROM accepted WHERE job=?', (job,)).fetchone():
                raise ValueError('此项目已验收，不能在本样品内覆盖。')
            count = db.execute('SELECT COUNT(*) FROM attempts WHERE job=?', (job,)).fetchone()[0]
            total = db.execute('SELECT COALESCE(SUM(cost),0) FROM attempts').fetchone()[0]
            if enforce_limits and count >= 3:
                raise ValueError('该项已尝试三次，请先评估失败原因。')
            if enforce_limits and total + 50 > 2000:
                raise ValueError('达到本轮20元累计预算上限。')
            item = uuid.uuid4().hex
            db.execute('INSERT INTO attempts VALUES (?,?,?,?,?,?)',
                       (item, job, 'reserved', 100, datetime.now(timezone.utc).isoformat(), '{}'))
        return item;

    def finish(self, item, state, metadata):
        if state not in ('charged', 'unknown', 'rejected'):
            raise ValueError('无效状态')
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            row = db.execute('SELECT * FROM attempts WHERE id=?', (item,)).fetchone()
            if not row:
                raise ValueError('记录不存在')
            if row['state'] in ('charged', 'rejected') and row['state'] != state:
                raise ValueError('不能修改已确定的计费状态')
            merged = json.loads(row['metadata']) | metadata
            db.execute('UPDATE attempts SET state=?,cost=?,metadata=? WHERE id=?',
                       (state, 0 if state == 'rejected' else 50,
                        json.dumps(merged, ensure_ascii=False), item))

    def records(self):
        with self.connect() as db:
            return [dict(row) | {'metadata': json.loads(row['metadata'])}
                    for row in db.execute('SELECT * FROM attempts ORDER BY created')]

    def retain(self, item, note):
        """Operator-reviewed missing record: keep worst-case cost, allow progress.

        Never called automatically by the HTTP client. This is not a refund
        or a claim that the provider confirmed a charge.
        """
        if len(note.strip()) < 6:
            raise ValueError('需记录人工核对依据')
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            row = db.execute('SELECT * FROM attempts WHERE id=?', (item,)).fetchone()
            if not row or row['state'] not in ('reserved', 'unknown'):
                raise ValueError('只能处理待核对记录')
            metadata = json.loads(row['metadata']) | {'review_note': note, 'charge_confirmed': False}
            db.execute("UPDATE attempts SET state='retained', metadata=? WHERE id=?",
                       (json.dumps(metadata, ensure_ascii=False), item))

    def get(self, item):
        return next((row for row in self.records() if row['id'] == item), None)

    def accepted(self, job):
        with self.connect() as db:
            row = db.execute('SELECT * FROM accepted WHERE job=?', (job,)).fetchone()
            return dict(row) if row else None

    def accept(self, job, item, digest, note):
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            if db.execute('SELECT 1 FROM accepted WHERE job=?', (job,)).fetchone():
                raise ValueError('已有验收版本，不能覆盖')
            db.execute('INSERT INTO accepted VALUES (?,?,?,?)', (job, item, digest, note))

    def summary(self):
        rows = self.records()
        return {'budget_fen': 2000, 'committed_fen': sum(r['cost'] for r in rows),
                'charged_fen': sum(r['cost'] for r in rows if r['state'] == 'charged'),
                'retained_fen': sum(r['cost'] for r in rows if r['state'] == 'retained'),
                'pending_count': sum(r['state'] in ('reserved', 'unknown') for r in rows),
                'attempt_count': len(rows), 'billing_basis': '公开价估算，需核对厂商账单'}
