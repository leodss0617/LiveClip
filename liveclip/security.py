import re
from urllib.parse import parse_qs, urlsplit


def validate_url(url: str) -> tuple[str, str]:
    if len(url) > 2048 or any(ord(c) < 32 for c in url):
        raise ValueError("Link inválido.")
    p = urlsplit(url.strip())
    if p.scheme != "https" or p.username or p.password or p.port not in (None, 443):
        raise ValueError("Use um link HTTPS público do YouTube, Kick ou Twitch.")
    host = (p.hostname or "").lower()
    if host in ("www.youtube.com", "youtube.com", "m.youtube.com", "youtu.be"):
        if host == "youtu.be":
            video = p.path.strip("/")
        elif p.path == "/watch":
            video = parse_qs(p.query).get("v", [""])[0]
        elif re.fullmatch(r"/live/[A-Za-z0-9_-]+/?", p.path):
            video = p.path.split("/")[2]
        elif re.fullmatch(r"/@[A-Za-z0-9_.-]+/live/?", p.path):
            return "youtube", "https://www.youtube.com" + p.path.rstrip("/")
        else:
            raise ValueError("Cole o link da transmissão do YouTube.")
        if not re.fullmatch(r"[A-Za-z0-9_-]{6,64}", video):
            raise ValueError("Identificador do vídeo inválido.")
        return "youtube", "https://www.youtube.com/watch?v=" + video
    channel = r"[A-Za-z0-9_-]{1,100}"
    uuid = r"[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"
    if host in ("kick.com", "www.kick.com"):
        if re.fullmatch(rf"/{channel}/videos/{uuid}/?", p.path):
            return "kick", "https://kick.com" + p.path.rstrip("/")
        if p.path.startswith("/video/"):
            raise ValueError(
                "Copie o link completo da gravação: https://kick.com/canal/videos/identificador."
            )
    if host in ("twitch.tv", "www.twitch.tv"):
        if re.fullmatch(r"/videos/[0-9]{1,20}/?", p.path):
            return "twitch", "https://twitch.tv" + p.path.rstrip("/")
    for platform, domains in [
        ("twitch", ("www.twitch.tv", "twitch.tv")),
        ("kick", ("www.kick.com", "kick.com")),
    ]:
        if host in domains and re.fullmatch(r"/[A-Za-z0-9_-]{1,100}/?", p.path):
            return platform, f"https://{domains[1]}" + p.path.rstrip("/")
    raise ValueError("Plataforma ou endereço não suportado.")
