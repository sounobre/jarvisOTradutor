from pydantic import BaseModel, Field
from typing import List, Optional


class EmbedRequest(BaseModel):
    texts: List[str] = Field(..., description="Textos a serem vetorizados")
    normalize: bool = Field(False, description="Normalizar L2 (cosine ready)")

class EmbedResponse(BaseModel):
    model: str
    dims: int
    vectors: List[List[float]]

class QEPair(BaseModel):
    src: str
    tgt: str

class QEBatch(BaseModel):
    pairs: List[QEPair]

class QEResponse(BaseModel):
    scores: List[float]

class BTPair(BaseModel):
    src: str
    tgt: str

class BTBatch(BaseModel):
    pairs: List[BTPair]

class BTResponse(BaseModel):
    chrf: List[float]   # 0..100

class QEItem(BaseModel):
    src: str
    mt: Optional[str] = None
    tgt: Optional[str] = None

class QERequest(BaseModel):
    items: List[QEItem]