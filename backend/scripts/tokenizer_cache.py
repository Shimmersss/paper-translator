import hashlib
import os
from pathlib import Path


O200K_CACHE_KEY = "fb374d419588a4632f3f557e76b4b70aebbca790"
O200K_SHA256 = "446a9538cb6c348e3516120d7c08b09f57c36495e2acfffe59a5bf8b0cfb1a2d"


def configure_tokenizer_cache(configured=None):
    """Use the packaged tokenizer data so PDF translation never downloads it at runtime."""
    if configured:
        cache_dir = Path(configured)
        os.environ["TIKTOKEN_CACHE_DIR"] = str(cache_dir)
        return cache_dir

    cache_dir = Path(__file__).resolve().parent / "tiktoken-cache"
    cache_file = cache_dir / O200K_CACHE_KEY
    if not cache_file.is_file():
        raise RuntimeError(f"缺少内置分词表: {cache_file}")

    actual_hash = hashlib.sha256(cache_file.read_bytes()).hexdigest()
    if actual_hash != O200K_SHA256:
        raise RuntimeError("内置 o200k_base 分词表校验失败")

    os.environ["TIKTOKEN_CACHE_DIR"] = str(cache_dir)
    return cache_dir
