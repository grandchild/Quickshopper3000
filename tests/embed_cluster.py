#!/usr/bin/env -S uv run
# /// script
# dependencies = [
#   "torch",
#   "transformers",
#   "scikit-learn",
#   "numpy",
# ]
# ///

import argparse
from collections import defaultdict
from pathlib import Path

import numpy as np
import torch
import torch.nn.functional as F
from sklearn.cluster import DBSCAN, AgglomerativeClustering
from transformers import AutoModel, AutoTokenizer

DEFAULT_MODELS = [
    "intfloat/multilingual-e5-small",
    "sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2",
    "sentence-transformers/distiluse-base-multilingual-cased-v2",
    "sentence-transformers/LaBSE",
]
ITEM_PREFIX = "query: shopping: "


def average_pool(last_hidden_states, attention_mask):
    masked = last_hidden_states.masked_fill(~attention_mask[..., None].bool(), 0.0)
    return masked.sum(dim=1) / attention_mask.sum(dim=1)[..., None]


def load_items(path):
    if path is None:
        return [
            "Apfel",
            "Banane",
            "Tomate",
            "Gurke",
            "Brot",
            "Brötchen",
            "Milch",
            "Joghurt",
            "Käse",
            "Seife",
        ]
    text = Path(path).read_text(encoding="utf-8")
    return [line.strip() for line in text.splitlines() if line.strip()]


def parse_range(value):
    parts = value.split(",")
    if len(parts) != 2:
        raise argparse.ArgumentTypeError("Expected min,max")
    try:
        low = int(parts[0])
        high = int(parts[1])
    except ValueError:
        raise argparse.ArgumentTypeError("Expected integers")
    if low < 1 or high < 1 or low > high:
        raise argparse.ArgumentTypeError("Invalid range")
    return low, high


def range_penalty(value, low, high):
    if value < low:
        return low - value
    if value > high:
        return value - high
    return 0


def format_bytes(value):
    size = float(value)
    units = ["B", "KB", "MB", "GB", "TB"]
    for unit in units:
        if size < 1024.0 or unit == units[-1]:
            return f"{size:.2f} {unit}"
        size /= 1024.0
    return f"{size:.2f} TB"


def model_memory_bytes(model):
    param_bytes = sum(p.numel() * p.element_size() for p in model.parameters())
    buffer_bytes = sum(b.numel() * b.element_size() for b in model.buffers())
    return param_bytes + buffer_bytes


def model_param_count(model):
    return sum(p.numel() for p in model.parameters())


def cluster_labels_agglomerative(embeddings, distance_threshold):
    return AgglomerativeClustering(
        n_clusters=None,
        distance_threshold=distance_threshold,
        metric="cosine",
        linkage="average",
    ).fit_predict(embeddings)


def cluster_labels_dbscan(embeddings, eps, min_samples):
    return DBSCAN(
        eps=eps,
        min_samples=min_samples,
        metric="cosine",
    ).fit_predict(embeddings)


def choose_threshold_agglomerative(
    embeddings, num_clusters_range, num_cluster_items_range
):
    thresholds = np.linspace(0.05, 0.9, 30)
    best = None
    for threshold in thresholds:
        labels = cluster_labels_agglomerative(embeddings, threshold)
        cluster_labels_set = set(labels)
        cluster_sizes = [int((labels == label).sum()) for label in cluster_labels_set]
        cluster_count = len(cluster_labels_set)
        if num_clusters_range is not None:
            low, high = num_clusters_range
            penalty = range_penalty(cluster_count, low, high)
        else:
            low, high = num_cluster_items_range
            penalty = sum(
                max(0, low - size) + max(0, size - high) for size in cluster_sizes
            )
        score = (penalty,)
        if best is None or score < best[0]:
            best = (score, threshold, labels)
    return best[1], best[2]


def choose_eps_dbscan(
    embeddings, min_samples, num_clusters_range, num_cluster_items_range
):
    eps_values = np.linspace(0.05, 0.6, 28)
    best = None
    for eps in eps_values:
        labels = cluster_labels_dbscan(embeddings, eps, min_samples)
        noise_count = int((labels == -1).sum())
        cluster_labels_set = [label for label in set(labels) if label != -1]
        cluster_sizes = [int((labels == label).sum()) for label in cluster_labels_set]
        cluster_count = len(cluster_labels_set)
        if num_clusters_range is not None:
            low, high = num_clusters_range
            penalty = range_penalty(cluster_count, low, high)
        else:
            low, high = num_cluster_items_range
            if not cluster_sizes:
                penalty = 1_000_000
            else:
                penalty = sum(
                    max(0, low - size) + max(0, size - high) for size in cluster_sizes
                )
        score = (penalty, noise_count)
        if best is None or score < best[0]:
            best = (score, eps, labels)
    return best[1], best[2]


def load_model(model_name):
    try:
        tokenizer = AutoTokenizer.from_pretrained(model_name, local_files_only=True)
        model = AutoModel.from_pretrained(model_name, local_files_only=True)
    except Exception:
        tokenizer = AutoTokenizer.from_pretrained(model_name)
        model = AutoModel.from_pretrained(model_name)
    model.eval()
    return tokenizer, model


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--items-file", type=str)
    parser.add_argument("--models", type=str, default=",".join(DEFAULT_MODELS))
    parser.add_argument(
        "--clusterer",
        type=str,
        default="agglomerative",
        choices=["agglomerative", "dbscan"],
    )
    parser.add_argument("--distance-threshold", type=float, default=0.4)
    parser.add_argument("--eps", type=float, default=0.25)
    parser.add_argument("--min-samples", type=int, default=2)
    parser.add_argument("--num-clusters", type=parse_range)
    parser.add_argument("--num-cluster-items", type=parse_range)
    parser.add_argument("--item-prefix", type=str, default=ITEM_PREFIX)
    args = parser.parse_args()

    if args.num_clusters is not None and args.num_cluster_items is not None:
        raise ValueError("Use either --num-clusters or --num-cluster-items")

    items = load_items(args.items_file)
    prefixed = [f"{args.item_prefix}{item}" for item in items]
    model_names = [name.strip() for name in args.models.split(",") if name.strip()]

    for model_name in model_names:
        print(f"Model: {model_name}")
        try:
            tokenizer, model = load_model(model_name)
        except Exception as exc:
            print(f"Skipping {model_name}: {exc}")
            print()
            continue
        param_count = model_param_count(model)
        model_bytes = model_memory_bytes(model)
        print(f"Model params: {param_count} ({format_bytes(model_bytes)})")

        batch = tokenizer(
            prefixed,
            max_length=128,
            padding=True,
            truncation=True,
            return_tensors="pt",
        )

        with torch.no_grad():
            outputs = model(**batch)

        embeddings = average_pool(outputs.last_hidden_state, batch["attention_mask"])
        embeddings = F.normalize(embeddings, p=2, dim=1).cpu().numpy()
        n_items, dim = embeddings.shape
        embeddings_bytes = embeddings.nbytes
        distance_matrix_bytes = n_items * n_items * 8
        print(
            f"Embeddings: {n_items}x{dim} ({format_bytes(embeddings_bytes)}), "
            f"Agglomerative distance matrix upper bound: {format_bytes(distance_matrix_bytes)}"
        )

        if args.clusterer == "agglomerative":
            if args.num_clusters is not None or args.num_cluster_items is not None:
                selected_threshold, labels = choose_threshold_agglomerative(
                    embeddings,
                    args.num_clusters,
                    args.num_cluster_items,
                )
                print(f"Selected threshold: {selected_threshold:.3f}")
            else:
                labels = cluster_labels_agglomerative(
                    embeddings,
                    args.distance_threshold,
                )
        else:
            if args.num_clusters is not None or args.num_cluster_items is not None:
                selected_eps, labels = choose_eps_dbscan(
                    embeddings,
                    args.min_samples,
                    args.num_clusters,
                    args.num_cluster_items,
                )
                print(f"Selected eps: {selected_eps:.3f}")
            else:
                labels = cluster_labels_dbscan(
                    embeddings,
                    args.eps,
                    args.min_samples,
                )

        groups = defaultdict(list)
        for item, label in zip(items, labels):
            groups[label].append(item)

        for label in sorted(groups.keys()):
            header = "Noise" if label == -1 else f"Group {label}"
            print(header)
            for item in groups[label]:
                print(f"  {item}")
            print()


if __name__ == "__main__":
    main()
