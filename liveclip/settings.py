import os
from dataclasses import dataclass, field
from pathlib import Path


@dataclass
class Settings:
    data: Path = field(
        default_factory=lambda: Path(os.getenv("LIVECLIP_DATA", "data")).resolve()
    )
    password: str = field(default_factory=lambda: os.getenv("LIVECLIP_PASSWORD", ""))
    whisper_model: str = field(
        default_factory=lambda: os.getenv("WHISPER_MODEL", "small")
    )
    ollama_model: str = field(
        default_factory=lambda: os.getenv(
            "OLLAMA_MODEL", "qwen3:4b-instruct-2507-q4_K_M"
        )
    )
    ollama_url: str = field(
        default_factory=lambda: os.getenv("OLLAMA_URL", "http://127.0.0.1:11434")
    )
    cpu_threads: int = field(
        default_factory=lambda: max(1, int(os.getenv("CPU_THREADS", "4")))
    )
    min_free_gb: float = field(
        default_factory=lambda: float(os.getenv("MIN_FREE_GB", "2"))
    )
    max_data_gb: float = field(
        default_factory=lambda: float(os.getenv("MAX_DATA_GB", "20"))
    )
    secure_cookie: bool = field(
        default_factory=lambda: os.getenv("SECURE_COOKIE", "false").lower() == "true"
    )
    start_worker: bool = True
    processing_enabled: bool = field(
        default_factory=lambda: (
            os.getenv("LIVECLIP_PROCESSING_ENABLED", "true").lower() == "true"
        )
    )
    processing_error: str = field(
        default_factory=lambda: os.getenv("LIVECLIP_PROCESSING_ERROR", "")
    )

    def __post_init__(self):
        self.data = Path(self.data).resolve()
        self.data.mkdir(parents=True, exist_ok=True)
        if len(self.password) < 12:
            raise ValueError(
                "Configure LIVECLIP_PASSWORD com pelo menos 12 caracteres. Use iniciar.sh ou iniciar.ps1."
            )
