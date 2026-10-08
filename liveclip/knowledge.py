"""Bounded local experience; human ratings are distinct from model predictions."""

from contextlib import contextmanager
import json
import math
import sqlite3
import time
from pathlib import Path


class Knowledge:
    def __init__(self, path):
        self.path = str(path)
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        with self.connect() as db:
            db.execute(
                'CREATE TABLE IF NOT EXISTS experience (key TEXT PRIMARY KEY,kind TEXT NOT NULL,payload TEXT NOT NULL,updated REAL NOT NULL,user_rating REAL,note TEXT NOT NULL DEFAULT "")'
            )

    @contextmanager
    def connect(self):
        db = sqlite3.connect(self.path, timeout=30)
        try:
            with db:
                yield db
        finally:
            db.close()

    def record(self, key, kind, payload):
        with self.connect() as db:
            db.execute(
                "INSERT INTO experience(key,kind,payload,updated) VALUES(?,?,?,?) ON CONFLICT(key) DO UPDATE SET kind=excluded.kind,payload=excluded.payload,updated=excluded.updated",
                (key, kind, json.dumps(payload, ensure_ascii=False), time.time()),
            )
            db.execute(
                "DELETE FROM experience WHERE key NOT IN (SELECT key FROM experience ORDER BY updated DESC,key DESC LIMIT 1000)"
            )

    def feedback(self, key, rating, note):
        if (
            type(rating) not in (int, float)
            or not math.isfinite(rating)
            or not 0 <= rating <= 10
        ):
            raise ValueError("Nota deve estar entre 0 e 10.")
        with self.connect() as db:
            result = db.execute(
                'UPDATE experience SET user_rating=?,note=?,updated=? WHERE key=? AND kind="render"',
                (rating, str(note)[:600], time.time(), key),
            )
            if not result.rowcount:
                raise KeyError("Corte sem registro na memória.")

    def context(self):
        with self.connect() as db:
            rows = db.execute(
                "SELECT kind,payload,user_rating,note FROM experience ORDER BY updated DESC,key DESC"
            ).fetchall()
        rendered = failures = analyses = 0
        technical_gaps = {}
        recent_rejections = []
        examples = []
        durations = []
        for kind, payload, rating, note in rows:
            item = json.loads(payload)
            if kind == "analysis":
                analyses += 1
                for review in item.get("reviews", []):
                    if not review.get("accepted", False) and len(recent_rejections) < 4:
                        recent_rejections.append(
                            dict(
                                reason=str(review.get("reason", ""))[:200],
                                rating=review.get("rating"),
                                source="model_estimate_not_human_feedback",
                            )
                        )
            if kind == "render":
                for check, passed in (
                    item.get("technical", {}).get("checks", {}).items()
                ):
                    if passed is False:
                        technical_gaps[check] = technical_gaps.get(check, 0) + 1
                if item.get("status") == "ready":
                    rendered += 1
                if item.get("status") == "error":
                    failures += 1
                if rating is not None and len(examples) < 8:
                    examples.append(
                        dict(
                            title=str(item.get("title", ""))[:160],
                            duration=item.get("duration"),
                            predicted_rating=item.get("rating"),
                            user_rating=rating,
                            note=note[:240],
                        )
                    )
                    if rating >= 7:
                        durations.append(item.get("duration", 0))
        return dict(
            records=len(rows),
            analyses=analyses,
            rendered=rendered,
            render_failures=failures,
            examples=examples,
            technical_gaps=technical_gaps,
            recent_rejections=recent_rejections,
            preferred_duration=round(sum(durations) / len(durations), 1)
            if durations
            else None,
            mode="Memória de experiências e preferências; sem treinamento de pesos.",
        )

    def clear(self):
        with self.connect() as db:
            db.execute("DELETE FROM experience")
