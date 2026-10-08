from liveclip.worker import review_target, Engine


def test_first_review_waits_for_twenty_minutes_of_received_video():
    assert review_target(1199, False) == 0
    assert review_target(1200, False) == 1200
    assert review_target(2399, False) == 1200
    assert review_target(2400, False) == 2400


def test_finalization_releases_short_tail():
    assert review_target(99, True) == 99
    assert review_target(1250, True) == 1250


def test_partial_batch_can_resume_without_waiting_another_twenty_minutes():
    e = object.__new__(Engine)
    start, end = e.analysis_bounds(
        dict(analyzed_until=1170, capture_seconds=1210, activity={}), False
    )
    assert end == 1200
    assert start <= 1170


def test_actual_final_boundary_is_revisited_once(tmp_path, monkeypatch):
    from liveclip.store import Store
    from liveclip.settings import Settings

    store = Store(tmp_path / "db")
    session = store.create_session("https://kick.com/test", "kick")
    store.update_session(session["id"], capture_seconds=1200, analyzed_until=1200)
    store.add_segment(session["id"], tmp_path / "source.ts", 870, 330)
    engine = Engine(store, Settings(data=tmp_path, password="long-local-password"))
    jobs = []
    monkeypatch.setattr(
        engine,
        "run_job",
        lambda kind, payload, work: (
            jobs.append(payload) or dict(candidates=[], words=[])
        ),
    )
    engine.analyze(store.session(session["id"]), final=True)
    assert len(jobs) == 1 and jobs[0]["final"] is True
    engine.analyze(store.session(session["id"]), final=True)
    assert len(jobs) == 1


def test_final_retry_backoff_does_not_mark_unreviewed_tail_complete(tmp_path):
    import time
    from liveclip.store import Store
    from liveclip.settings import Settings

    store = Store(tmp_path / "db")
    session = store.create_session("https://kick.com/test", "kick")
    store.update_session(
        session["id"], status="finishing", capture_seconds=1200, analyzed_until=1200
    )
    e = Engine(store, Settings(data=tmp_path, password="long-local-password"))
    e.retry_at[session["id"]] = time.monotonic() + 45
    e.tick()
    assert store.session(session["id"])["status"] == "finishing"
