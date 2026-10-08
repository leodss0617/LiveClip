import importlib.util


def test_audio_event_module_exists():
    assert importlib.util.find_spec('liveclip.audio_events') is not None


def frames(start=5, end=80, duration=100):
    return [(float(t), .95 if start <= t < end else .02, .05, .01) for t in range(duration)]


def test_complete_music_preserves_intro_middle_outro():
    from liveclip.audio_events import music_spans, protect_candidates
    spans = music_spans(frames(), 100)
    selected, waiting = protect_candidates([dict(start=30, end=50, title='Reação', reason='Teste', score=.9)], spans, 100, final=False)
    assert len(selected) == 1
    assert selected[0]['start'] <= 5
    assert selected[0]['end'] >= 80
    assert waiting is None


def test_ongoing_music_is_not_exported_as_a_partial_clip():
    from liveclip.audio_events import music_spans, protect_candidates
    spans = music_spans(frames(end=100), 100)
    selected, waiting = protect_candidates([dict(start=30, end=70, title='Reação', reason='Teste', score=.9)], spans, 100, final=True)
    assert selected == []
    assert waiting == 5


def test_music_started_before_window_is_not_called_complete():
    from liveclip.audio_events import music_spans, music_candidates
    spans = music_spans(frames(start=0), 100)
    assert music_candidates(spans, 100) == []


def test_short_musical_pause_is_not_a_song_ending():
    from liveclip.audio_events import music_spans
    f=frames(end=100, duration=120)
    for i in range(40, 45): f[i]=(float(i), .02, .05, .01)
    spans=music_spans(f, 120)
    assert len(spans)==1 and spans[0]['end'] >= 100


def test_background_music_under_speech_does_not_block_every_story():
    from liveclip.audio_events import music_spans, protect_candidates
    spans=music_spans([(float(i),.8,.95,.8) for i in range(100)],100)
    assert not spans
    candidate=dict(start=5,end=70,title='História',reason='Teste',score=.9)
    assert protect_candidates([candidate],spans,100,final=False)[0] == [candidate]


def test_unfinished_edge_of_live_requires_more_context():
    from liveclip.audio_events import protect_candidates
    candidate=dict(start=5,end=95,title='História',reason='Teste',score=.9)
    assert protect_candidates([candidate],[],100,final=False)[0] == []
    assert protect_candidates([candidate],[],100,final=True)[0] == [candidate]


def test_long_music_is_preserved_up_to_ten_minutes():
    from liveclip.audio_events import music_spans, music_candidates
    result=music_candidates(music_spans(frames(start=5,end=425,duration=445),445),445)
    assert len(result)==1 and result[0]['end']-result[0]['start'] >= 420


def test_actual_yamnet_classifies_silence(tmp_path):
    import subprocess
    from liveclip.audio_events import classify_music
    path=tmp_path/'silent.wav'
    subprocess.run(['ffmpeg','-v','error','-f','lavfi','-i','anullsrc=r=16000:cl=mono','-t','3',str(path)],check=True)
    assert classify_music(path,3,cpu_threads=2)==[]


def test_real_audio_model_finds_entire_synthetic_melody(tmp_path):
    import numpy as np
    import wave
    from liveclip.audio_events import classify_music, music_candidates
    rate=16000
    note=np.arange(rate//2)/rate
    song=np.concatenate([sum(np.sin(2*np.pi*f*h*note)/h for h in range(1,5))*np.exp(-3*note) for f in [261.6,329.6,392,523.2]*10])
    audio=np.concatenate([np.zeros(rate*5),song*.15,np.zeros(rate*10)])
    path=tmp_path/'melody.wav'
    with wave.open(str(path),'wb') as stream:
        stream.setnchannels(1);stream.setsampwidth(2);stream.setframerate(rate)
        stream.writeframes((audio*32767).astype('<i2').tobytes())
    events=classify_music(path,len(audio)/rate)
    clips=music_candidates(events,len(audio)/rate)
    assert clips
    assert clips[0]['start'] <= 5 and clips[0]['end'] >= 24


def test_pending_music_extends_next_analysis_window():
    from liveclip.worker import Engine
    engine=object.__new__(Engine)
    start,end=engine.analysis_bounds(dict(analyzed_until=500,capture_seconds=700,activity={'pending_music_start':20}), final=True)
    assert start <= 20 and end==590


def test_reaction_with_setup_and_payoff_can_be_selected():
    from liveclip.intelligence import validate_candidates
    transcript=[dict(start=0,end=10,text='Preparação'),dict(start=10,end=25,text='Desfecho')]
    item=dict(kind='reaction',score=.9,complete=True,title='Reação',reason='Preparação e desfecho',arc=dict(opening_index=0,development_index=0,ending_index=1,starts_mid_story=False,ends_mid_story=False))
    assert len(validate_candidates({'clips':[item]},transcript,30,require_arc=True))==1
    item['kind']='story'
    assert validate_candidates({'clips':[item]},transcript,30,require_arc=True)==[]


def test_speech_over_ongoing_music_does_not_end_song():
    from liveclip.audio_events import music_spans, music_candidates
    f=frames(end=100,duration=120)
    for i in range(40,60): f[i]=(float(i),.95,.95,.01)
    spans=music_spans(f,120)
    assert len(spans)==1
    assert music_candidates(spans,120)[0]['end']>=100


def test_analysis_job_delivers_music_even_without_spoken_story(tmp_path,monkeypatch):
    import json
    from liveclip import job
    request=tmp_path/'job.json';result=tmp_path/'result.json'
    request.write_text(json.dumps(dict(segments=[],source='fixture',data=str(tmp_path),whisper_model='tiny',cpu_threads=2,ollama_model='test',ollama_url='http://localhost')))
    monkeypatch.setattr('sys.argv',['job','analysis',str(request),str(result)])
    monkeypatch.setattr(job,'concat_segments',lambda *a:None)
    monkeypatch.setattr(job,'probe',lambda *a:{'format':{'duration':100}})
    from liveclip.audio_events import music_spans
    monkeypatch.setattr(job,'classify_music',lambda *a:music_spans(frames(),100))
    monkeypatch.setattr(job.Intelligence,'transcribe',lambda *a,**kw:([],[]))
    monkeypatch.setattr(job.Intelligence,'select',lambda *a,**kw:[])
    monkeypatch.setattr(job.Intelligence,'_chat',lambda *a,**kw:dict(ratings=[dict(index=0,interest=8,context=9,ending=9,reason='Evento musical com contexto e desfecho')]))
    job.main()
    clips=json.loads(result.read_text())['candidates']
    assert len(clips)==1 and clips[0]['start']<=5 and clips[0]['end']>=80
