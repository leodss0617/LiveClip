import json
import sqlite3
import time
import uuid
from contextlib import contextmanager
from pathlib import Path

from .limits import MAX_SECONDS

ACTIVE = ("queued", "waiting", "monitoring", "reconnecting", "finishing", "stopping")


class Store:
    def __init__(self, path):
        self.path = str(path)
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        with self.connect() as db:
            db.executescript("""
                PRAGMA journal_mode=WAL;
                CREATE TABLE IF NOT EXISTS sessions (
                    id TEXT PRIMARY KEY, url TEXT NOT NULL, platform TEXT NOT NULL,
                    status TEXT NOT NULL, stage TEXT NOT NULL DEFAULT '',
                    message TEXT NOT NULL DEFAULT '', created REAL NOT NULL,
                    expires REAL NOT NULL, capture_seconds REAL NOT NULL DEFAULT 0,
                    analyzed_until REAL NOT NULL DEFAULT 0, stop_requested INTEGER NOT NULL DEFAULT 0);
                CREATE TABLE IF NOT EXISTS clips (
                    id TEXT PRIMARY KEY, session_id TEXT NOT NULL REFERENCES sessions(id),
                    start REAL NOT NULL, end REAL NOT NULL, title TEXT NOT NULL,
                    reason TEXT NOT NULL, score REAL NOT NULL, status TEXT NOT NULL,
                    filename TEXT NOT NULL DEFAULT '', error TEXT NOT NULL DEFAULT '',
                    created REAL NOT NULL, layout TEXT NOT NULL DEFAULT '');
                CREATE TABLE IF NOT EXISTS segments (
                    path TEXT PRIMARY KEY, session_id TEXT NOT NULL REFERENCES sessions(id),
                    start REAL NOT NULL, duration REAL NOT NULL);
                CREATE TABLE IF NOT EXISTS analysis (
                    clip_id TEXT PRIMARY KEY REFERENCES clips(id), words TEXT NOT NULL);
            """)
            columns = {r["name"] for r in db.execute("PRAGMA table_info(sessions)")}
            for column, default in [("activity", "{}"), ("events", "[]")]:
                if column not in columns:
                    db.execute(
                        f"ALTER TABLE sessions ADD COLUMN {column} TEXT NOT NULL DEFAULT '{default}'"
                    )
            if "cancel_requested" not in columns:
                db.execute(
                    "ALTER TABLE sessions ADD COLUMN cancel_requested INTEGER NOT NULL DEFAULT 0"
                )

            if "archived" not in columns:
                db.execute(
                    "ALTER TABLE sessions ADD COLUMN archived INTEGER NOT NULL DEFAULT 0"
                )

            if "final_reviewed" not in columns:
                db.execute(
                    "ALTER TABLE sessions ADD COLUMN final_reviewed REAL NOT NULL DEFAULT -1"
                )
            clip_columns = {r["name"] for r in db.execute("PRAGMA table_info(clips)")}
            for column in ("evaluation", "technical"):
                if column not in clip_columns:
                    db.execute(
                        f"ALTER TABLE clips ADD COLUMN {column} TEXT NOT NULL DEFAULT '{{}}'"
                    )
            if "user_rating" not in clip_columns:
                db.execute("ALTER TABLE clips ADD COLUMN user_rating REAL")

    def set_evaluation(self, ident, evaluation, technical=False):
        column = "technical" if technical else "evaluation"
        with self.connect() as db:
            db.execute(
                f"UPDATE clips SET {column}=? WHERE id=?",
                (json.dumps(evaluation, ensure_ascii=False), ident),
            )

    def set_feedback(self, ident, rating):
        with self.connect() as db:
            updated = db.execute(
                "UPDATE clips SET user_rating=? WHERE id=? AND status='ready'",
                (rating, ident),
            )
            return bool(updated.rowcount)

    def delete_session(self, ident):
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            session = db.execute(
                "SELECT * FROM sessions WHERE id=? AND archived=0", (ident,)
            ).fetchone()
            if not session:
                raise KeyError("Sessão não encontrada.")
            if (
                session["status"] in ACTIVE
                or db.execute(
                    "SELECT 1 FROM clips WHERE session_id=? AND status IN ('queued','rendering')",
                    (ident,),
                ).fetchone()
            ):
                raise ValueError(
                    "Cancele a tarefa e aguarde os processos encerrarem antes de excluir."
                )
            db.execute("DELETE FROM segments WHERE session_id=?", (ident,))
            db.execute(
                "UPDATE sessions SET archived=1,activity='{}',events='[]',message='',capture_seconds=0,analyzed_until=0 WHERE id=?",
                (ident,),
            )

    @staticmethod
    def decode_session(row):
        result = dict(row)
        for key in ("activity", "events"):
            result[key] = json.loads(result[key])
        return result

    def activity(self, ident, stage, detail, **values):
        now = time.time()
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute(
                "SELECT activity,events FROM sessions WHERE id=?", (ident,)
            ).fetchone()
            if not row:
                return
            old, events = json.loads(row["activity"]), json.loads(row["events"])
            changed = old.get("stage") != stage
            context = {
                k: old[k]
                for k in ("window_start", "window_end", "pending_music_start")
                if k in old
            }
            current = dict(
                old if not changed else context,
                **values,
                stage=stage,
                detail=detail,
                started=now if changed else old.get("started", now),
                heartbeat=now,
            )
            if changed or old.get("detail") != detail:
                events.append(dict(time=now, stage=stage, detail=detail))
            db.execute(
                "UPDATE sessions SET stage=?,activity=?,events=?,message=CASE WHEN ? IN ('preparing','loading_model','transcribing','selecting','reviewing','rendering') THEN '' ELSE message END WHERE id=?",
                (
                    stage,
                    json.dumps(current, ensure_ascii=False),
                    json.dumps(events[-30:], ensure_ascii=False),
                    stage,
                    ident,
                ),
            )

    @contextmanager
    def connect(self):
        db = sqlite3.connect(self.path, timeout=30)
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA foreign_keys=ON")
        try:
            with db:
                yield db
        finally:
            db.close()

    def create_session(self, url, platform):
        ident, now = uuid.uuid4().hex, time.time()
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            placeholders = ",".join("?" for _ in ACTIVE)
            if db.execute(
                f"SELECT 1 FROM sessions WHERE status IN ({placeholders})", ACTIVE
            ).fetchone():
                raise ValueError(
                    "Já existe uma live em andamento. Pare a atual antes de iniciar outra."
                )
            db.execute(
                "INSERT INTO sessions(id,url,platform,status,created,expires) VALUES(?,?,?,?,?,?)",
                (ident, url, platform, "queued", now, now + MAX_SECONDS),
            )
        return self.session(ident)

    def session(self, ident):
        with self.connect() as db:
            row = db.execute(
                "SELECT * FROM sessions WHERE id=? AND archived=0", (ident,)
            ).fetchone()
            return self.decode_session(row) if row else None

    def sessions(self):
        with self.connect() as db:
            return [
                self.decode_session(x)
                for x in db.execute(
                    "SELECT * FROM sessions WHERE archived=0 ORDER BY created DESC LIMIT 50"
                )
            ]

    def update_session(self, ident, **fields):
        allowed = {
            "status",
            "stage",
            "message",
            "capture_seconds",
            "analyzed_until",
            "final_reviewed",
            "stop_requested",
            "cancel_requested",
        }
        self._update("sessions", ident, fields, allowed)

    def _update(self, table, ident, fields, allowed):
        if not fields or set(fields) - allowed:
            raise ValueError("Campos inválidos.")
        with self.connect() as db:
            db.execute(
                f"UPDATE {table} SET "
                + ",".join(f"{k}=?" for k in fields)
                + " WHERE id=?",
                (*fields.values(), ident),
            )

    def checkpoint(self, ident, until):
        with self.connect() as db:
            db.execute(
                "UPDATE sessions SET analyzed_until=? WHERE id=? AND cancel_requested=0",
                (until, ident),
            )

    def request_cancel(self, ident):
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute(
                "SELECT status FROM sessions WHERE id=?", (ident,)
            ).fetchone()
            if not row:
                raise ValueError("Sessão não encontrada.")
            if row["status"] in ("completed", "cancelled"):
                return
            db.execute(
                "UPDATE sessions SET cancel_requested=1,stop_requested=1,status='stopping',message='Cancelamento solicitado.' WHERE id=?",
                (ident,),
            )

    def add_segment(self, session_id, path, start, duration):
        with self.connect() as db:
            db.execute(
                "INSERT OR IGNORE INTO segments VALUES(?,?,?,?)",
                (str(path), session_id, start, duration),
            )

    def segments(self, ident, start=0, end=1e12):
        with self.connect() as db:
            return [
                dict(x)
                for x in db.execute(
                    "SELECT * FROM segments WHERE session_id=? AND start+duration>? AND start<? ORDER BY start",
                    (ident, start, end),
                )
            ]

    def add_clip(self, session_id, start, end, title, reason, score, words=None):
        ident = uuid.uuid4().hex
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            session = db.execute(
                "SELECT cancel_requested FROM sessions WHERE id=?", (session_id,)
            ).fetchone()
            if not session or session["cancel_requested"]:
                return None
            for row in db.execute(
                "SELECT start,end FROM clips WHERE session_id=?", (session_id,)
            ):
                overlap = max(0, min(end, row["end"]) - max(start, row["start"]))
                if overlap / max(1, min(end - start, row["end"] - row["start"])) >= 0.5:
                    return None
            db.execute(
                "INSERT INTO clips(id,session_id,start,end,title,reason,score,status,created) VALUES(?,?,?,?,?,?,?,?,?)",
                (
                    ident,
                    session_id,
                    start,
                    end,
                    title[:160],
                    reason[:600],
                    score,
                    "queued",
                    time.time(),
                ),
            )
            if words is not None:
                import json

                db.execute(
                    "INSERT INTO analysis VALUES(?,?)",
                    (ident, json.dumps(words, ensure_ascii=False)),
                )
        return self.clip(ident)

    def clip(self, ident):
        with self.connect() as db:
            row = db.execute("SELECT * FROM clips WHERE id=?", (ident,)).fetchone()
            return dict(row) if row else None

    def clips(self, *, complete=False):
        with self.connect() as db:
            return [
                dict(x)
                for x in db.execute(
                    "SELECT * FROM clips ORDER BY created DESC"
                    + ("" if complete else " LIMIT 500")
                )
            ]

    def update_clip(self, ident, **fields):
        if fields.get("status") == "ready":
            allowed = {"status", "filename", "error", "layout"}
            if set(fields) - allowed:
                raise ValueError("Campos inválidos.")
            with self.connect() as db:
                result = db.execute(
                    "UPDATE clips SET "
                    + ",".join(f"{key}=?" for key in fields)
                    + " WHERE id=? AND NOT EXISTS(SELECT 1 FROM sessions WHERE sessions.id=clips.session_id AND cancel_requested=1)",
                    (*fields.values(), ident),
                )
                return result.rowcount > 0
        self._update("clips", ident, fields, {"status", "filename", "error", "layout"})

    def set_words(self, ident, words):
        import json

        with self.connect() as db:
            db.execute(
                "INSERT OR REPLACE INTO analysis VALUES(?,?)",
                (ident, json.dumps(words, ensure_ascii=False)),
            )

    def words(self, ident):
        import json

        with self.connect() as db:
            row = db.execute(
                "SELECT words FROM analysis WHERE clip_id=?", (ident,)
            ).fetchone()
            return json.loads(row["words"]) if row else []

    def delete_clip(self, ident):
        with self.connect() as db:
            db.execute("DELETE FROM analysis WHERE clip_id=?", (ident,))
            db.execute("DELETE FROM clips WHERE id=?", (ident,))

    def clear_segments(self, ident):
        with self.connect() as db:
            db.execute("DELETE FROM segments WHERE session_id=?", (ident,))
            db.execute(
                "UPDATE sessions SET capture_seconds=0,analyzed_until=0,stage='',activity='{}',message='Gravação original liberada.' WHERE id=?",
                (ident,),
            )

    def request_reanalysis(self, ident):
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            placeholders = ",".join("?" for _ in ACTIVE)
            if db.execute(
                f"SELECT 1 FROM sessions WHERE status IN ({placeholders})", ACTIVE
            ).fetchone():
                raise ValueError("Já existe uma sessão em andamento.")
            session = db.execute(
                "SELECT * FROM sessions WHERE id=? AND archived=0", (ident,)
            ).fetchone()
            if not session or not session["capture_seconds"]:
                raise ValueError("Gravação indisponível.")
            # Old captures could index absolute stream timestamps instead of
            # received duration. Repair only when no clip references those times.
            segments = list(
                db.execute(
                    "SELECT * FROM segments WHERE session_id=? ORDER BY start", (ident,)
                )
            )
            total = sum(x["duration"] for x in segments)
            if (
                segments
                and session["capture_seconds"] - total > 5
                and not db.execute(
                    "SELECT 1 FROM clips WHERE session_id=?", (ident,)
                ).fetchone()
            ):
                elapsed = 0
                for segment in segments:
                    db.execute(
                        "UPDATE segments SET start=? WHERE path=?",
                        (elapsed, segment["path"]),
                    )
                    elapsed += segment["duration"]
                db.execute(
                    "UPDATE sessions SET capture_seconds=?,analyzed_until=0 WHERE id=?",
                    (total, ident),
                )
            db.execute(
                "UPDATE sessions SET status='finishing',stop_requested=0,cancel_requested=0,final_reviewed=-1,analyzed_until=CASE WHEN analyzed_until>=capture_seconds THEN 0 ELSE analyzed_until END WHERE id=?",
                (ident,),
            )
            db.execute(
                "UPDATE clips SET status='queued',error='' WHERE session_id=? AND status='cancelled'",
                (ident,),
            )
