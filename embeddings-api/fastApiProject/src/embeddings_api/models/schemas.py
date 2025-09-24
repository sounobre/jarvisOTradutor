from pydantic import BaseModel, Field
from typing import List

class EmbedRequest(BaseModel):
    texts: List[str] = Field(..., description="Textos a serem vetorizados")
    normalize: bool = Field(False, description="Normalizar L2 (cosine ready)")

class EmbedResponse(BaseModel):
    model: str
    dims: int
    vectors: List[List[float]]
