#!/usr/bin/env python3

import os
import sys

sys.path.insert(0, "./vits-models")

import json
from typing import Any, Dict, List

from pypinyin import load_phrases_dict, phrases_dict, pinyin_dict

new_phrases = {
    "行长": [["háng"], ["zhǎng"]],
    "还我": [["huán"], ["wǒ"]],
}

load_phrases_dict(new_phrases)

import commons
import onnx
import torch
import utils
from models import SynthesizerTrn
from text import _clean_text, text_to_sequence
from text.symbols import _punctuation


class OnnxModel(torch.nn.Module):
    def __init__(self, model: SynthesizerTrn):
        super().__init__()
        self.model = model

    def forward(
        self,
        x,
        x_lengths,
        noise_scale=1,
        length_scale=1,
        noise_scale_w=1.0,
        sid=0,
        max_len=None,
    ):
        return self.model.infer(
            x=x,
            x_lengths=x_lengths,
            sid=sid,
            noise_scale=noise_scale,
            length_scale=length_scale,
            noise_scale_w=noise_scale_w,
            max_len=max_len,
        )[0]


def add_meta_data(filename: str, meta_data: Dict[str, Any]):
    """Add meta data to an ONNX model. It is changed in-place.

    Args:
      filename:
        Filename of the ONNX model to be changed.
      meta_data:
        Key-value pairs.
    """
    model = onnx.load(filename)
    for key, value in meta_data.items():
        meta = model.metadata_props.add()
        meta.key = key
        meta.value = str(value)

    onnx.save(model, filename)


def get_text(text, hps, is_symbol):
    text_norm, clean_text = text_to_sequence(
        text, hps.symbols, [] if is_symbol else hps.data.text_cleaners
    )
    if hps.data.add_blank:
        text_norm = commons.intersperse(text_norm, 0)
    text_norm = torch.LongTensor(text_norm)
    return text_norm, clean_text


def get_phones(word, text_cleaners) -> List[str]:
    text = f"[ZH]{word}[ZH]"
    phones: str = _clean_text(text, text_cleaners)
    return list(phones)[:-1]


@torch.no_grad()
def main():
    name = os.environ.get("NAME", None)
    if not name:
        print("Please provide the environment variable NAME")
        return

    hps_ms = utils.get_hparams_from_file(r"vits-models/config/config.json")
    symbol_to_id = {s: i for i, s in enumerate(hps_ms.symbols)}

    with open("tokens.txt", "w", encoding="utf-8") as f:
        for s, i in symbol_to_id.items():
            f.write(f"{s} {i}\n")
    print("Generated tokens.txt")

    words = list()

    phrases = phrases_dict.phrases_dict
    word_dict = pinyin_dict.pinyin_dict
    for key in word_dict:
        if not (0x4E00 <= key <= 0x9FFF):
            continue
        w = chr(key)
        words.append(w)

    for key in phrases:
        words.append(key)

    for key in new_phrases:
        words.append(key)

    word2phone = []
    for w in words:
        phones = get_phones(w, hps_ms.data.text_cleaners)
        oov = False
        for p in phones:
            if p not in symbol_to_id:
                oov = True
                break
        if oov:
            print(f"Skip {w}")
            continue

        word2phone.append([w, " ".join(phones)])

    with open("lexicon.txt", "w", encoding="utf-8") as f:
        for w, phones in word2phone:
            f.write(f"{w} {phones}\n")

    with open("vits-models/pretrained_models/info.json", "r", encoding="utf-8") as f:
        models_info = json.load(f)

    info = models_info[name]
    print(info)

    checkpoint = f"./vits-models/pretrained_models/{name}/{name}.pth"
    net_g_ms = SynthesizerTrn(
        len(hps_ms.symbols),
        hps_ms.data.filter_length // 2 + 1,
        hps_ms.train.segment_size // hps_ms.data.hop_length,
        n_speakers=hps_ms.data.n_speakers if info["type"] == "multi" else 0,
        **hps_ms.model,
    )
    utils.load_checkpoint(checkpoint, net_g_ms, None)
    net_g_ms.eval()
    x = torch.randint(low=1, high=30, size=(50,), dtype=torch.int64)
    x_length = torch.tensor([x.size(0)], dtype=torch.int64)
    x = x.unsqueeze(0)
    noise_scale = torch.tensor([0.667], dtype=torch.float32)
    length_scale = torch.tensor([1], dtype=torch.float32)
    noise_scale_w = torch.tensor([0.8], dtype=torch.float32)
    sid = torch.tensor([0], dtype=torch.int64)
    model = OnnxModel(net_g_ms)

    opset_version = 13

    filename = f"{name}.onnx"

    torch.onnx.export(
        model,
        (x, x_length, noise_scale, length_scale, noise_scale_w, sid),
        filename,
        opset_version=opset_version,
        input_names=[
            "x",
            "x_length",
            "noise_scale",
            "length_scale",
            "noise_scale_w",
            "sid",
        ],
        output_names=["y"],
        dynamic_axes={
            "x": {0: "N", 1: "L"},  # n_audio is also known as batch_size
            "x_length": {0: "N"},
            "y": {0: "N", 2: "L"},
        },
    )
    meta_data = {
        "model_type": "vits",
        "comment": "hf-vits-models-doom",
        "language": "Chinese",
        "jieba": 1,
        "add_blank": int(hps_ms.data.add_blank),
        "n_speakers": int(hps_ms.data.n_speakers),
        "sample_rate": hps_ms.data.sampling_rate,
        "punctuation": " ".join(list(_punctuation)),
    }
    print("meta_data", meta_data)
    add_meta_data(filename=filename, meta_data=meta_data)


if __name__ == "__main__":
    main()
