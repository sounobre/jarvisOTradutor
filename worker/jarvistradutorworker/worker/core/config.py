# worker/core/config.py
from pathlib import Path
from pydantic_settings import BaseSettings, SettingsConfigDict
from pydantic import Field

class Settings(BaseSettings):
    # diretórios (snake_case)
    base_dir: Path = Field(Path(r"C:\Users\souno\Desktop\Projects2025\jarvistradutor"), alias="BASE_DIR")
    uploads_dirname: str = Field("uploads", alias="UPLOADS_DIRNAME")
    translated_dirname: str = Field("translated", alias="TRANSLATED_DIRNAME")
    max_tokens_per_batch: int = Field(360, alias="MAX_TOKENS_PER_BATCH")
    max_new_tokens: int = Field(220, alias="MAX_NEW_TOKENS")
    num_beams: int = Field(3, alias="NUM_BEAMS")
    length_penalty: float = Field(1.05, alias="LENGTH_PENALTY")
    repetition_penalty: float = Field(1.04, alias="REPETITION_PENALTY")

    # modelo
    #model_name: str = Field("Helsinki-NLP/opus-mt-tc-big-en-pt", alias="MODEL_NAME") - primeiro tradutor
    model_name: str = Field("facebook/m2m100_418M", alias="MODEL_NAME")
    src_lang: str = Field("en", alias="SRC_LANG")  # para M2M100
    tgt_lang: str = Field("pt", alias="TGT_LANG")  # para M2M100
    nllb_src: str = Field("eng_Latn", alias="NLLB_SRC")  # para NLLB
    nllb_tgt: str = Field("por_Latn", alias="NLLB_TGT")  # para NLLB

    # fastapi
    debug: bool = Field(True, alias="DEBUG")

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
        env_prefix="",
        protected_namespaces=("settings_",),
    )

    # --- caminhos prontos (conveniência) ---
    @property
    def uploads_path(self) -> Path:
        return self.base_dir / self.uploads_dirname

    @property
    def translated_path(self) -> Path:
        return self.base_dir / self.translated_dirname

    # --- retrocompat: MAIÚSCULAS que o código antigo usa ---
    @property
    def BASE_DIR(self) -> Path: return self.base_dir
    @property
    def UPLOADS_DIRNAME(self) -> str: return self.uploads_dirname
    @property
    def TRANSLATED_DIRNAME(self) -> str: return self.translated_dirname
    @property
    def MODEL_NAME(self) -> str: return self.model_name
    @property
    def DEBUG(self) -> bool: return self.debug

settings = Settings()
