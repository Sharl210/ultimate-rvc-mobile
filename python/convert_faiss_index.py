#!/usr/bin/env python3
"""把标准 RVC/FAISS .index 转成移动端可读取的 .mobile.index。

用法：
    python3 python/convert_faiss_index.py input.index output.mobile.index
"""

from __future__ import annotations

import argparse
import struct
from pathlib import Path

import faiss
import numpy as np


FEATURE_INDEX_MAGIC = b"URVCIDX1"
FEATURE_DIMENSION = 768


def reconstruct_vectors(index: faiss.Index) -> np.ndarray:
    count = index.ntotal
    if count <= 0:
        raise ValueError("FAISS index 没有可导出的向量")
    if index.d != FEATURE_DIMENSION:
        raise ValueError(f"FAISS index 维度是 {index.d}，移动端只支持 {FEATURE_DIMENSION}")

    try:
        vectors = np.empty((count, index.d), dtype=np.float32)
        for row in range(count):
            vectors[row] = index.reconstruct(row)
        return np.ascontiguousarray(vectors, dtype=np.float32)
    except RuntimeError as exc:
        if "direct map not initialized" not in str(exc):
            raise
        return reconstruct_ivf_flat_vectors(index)


def reconstruct_ivf_flat_vectors(index: faiss.Index) -> np.ndarray:
    ivf = faiss.extract_index_ivf(index)
    if ivf.code_size != index.d * np.dtype("float32").itemsize:
        raise ValueError("当前仅支持 RVC 常见的 IVFFlat float32 索引")

    invlists = ivf.invlists
    chunks: list[np.ndarray] = []
    for list_no in range(ivf.nlist):
        list_size = invlists.list_size(list_no)
        if list_size == 0:
            continue
        codes = faiss.rev_swig_ptr(invlists.get_codes(list_no), list_size * ivf.code_size)
        chunks.append(codes.view("<f4").reshape(list_size, index.d).copy())

    if not chunks:
        raise ValueError("FAISS index 没有可导出的倒排向量")
    vectors = np.vstack(chunks)
    if vectors.shape[0] != index.ntotal:
        raise ValueError(f"导出向量数量不一致：{vectors.shape[0]} != {index.ntotal}")
    return np.ascontiguousarray(vectors, dtype=np.float32)


def convert_index(input_path: Path, output_path: Path) -> None:
    index = faiss.read_index(str(input_path))
    vectors = reconstruct_vectors(index)

    output_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open("wb") as output:
        output.write(FEATURE_INDEX_MAGIC)
        output.write(struct.pack("<i", vectors.shape[0]))
        vectors.astype("<f4", copy=False).tofile(output)


def main() -> None:
    parser = argparse.ArgumentParser(
        description="将标准 RVC/FAISS .index 转换为移动端可读取的 .mobile.index",
    )
    parser.add_argument("input", type=Path, help="标准 RVC/FAISS .index")
    parser.add_argument("output", type=Path, help="移动端可读取的 .mobile.index")
    args = parser.parse_args()

    convert_index(args.input, args.output)
    print(f"已写入 {args.output}")


if __name__ == "__main__":
    main()
