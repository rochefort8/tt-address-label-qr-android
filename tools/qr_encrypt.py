#!/usr/bin/env python3
"""
Generate QR payloads from T.csv using AES-256-GCM.

Payload format (compatible with app/src/main/java/.../decryptEncryptedQrPayload):
    v1:<nonce_base64>:<ciphertext_plus_tag_base64>
"""

from __future__ import annotations

import argparse
import base64
import csv
import os
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

PAYLOAD_VERSION = "v1"
NONCE_BYTES = 12
KEY_BYTES = 32
KEY_HEX_RE = re.compile(r"^[0-9a-fA-F]{64}$")


@dataclass(frozen=True)
class IdNameRow:
    id: str
    name: str

    @property
    def plaintext(self) -> str:
        return f"{self.id},{self.name}"


def _aesgcm(key: bytes):
    try:
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    except ModuleNotFoundError as exc:
        raise RuntimeError(
            "Missing dependency: cryptography. Install in your venv with: pip install cryptography"
        ) from exc
    return AESGCM(key)


def validate_key_hex(key_hex: str) -> bytes:
    normalized = key_hex.strip()
    if not KEY_HEX_RE.fullmatch(normalized):
        raise ValueError("Key must be exactly 64 hex chars (32 bytes, AES-256)")
    key = bytes.fromhex(normalized)
    if len(key) != KEY_BYTES:
        raise ValueError("Key must decode to 32 bytes")
    return key


def encrypt_payload(plaintext: str, key: bytes) -> str:
    nonce = os.urandom(NONCE_BYTES)
    cipher_tag = _aesgcm(key).encrypt(nonce, plaintext.encode("utf-8"), None)
    nonce_b64 = base64.b64encode(nonce).decode("ascii")
    cipher_tag_b64 = base64.b64encode(cipher_tag).decode("ascii")
    return f"{PAYLOAD_VERSION}:{nonce_b64}:{cipher_tag_b64}"


def decrypt_payload(payload: str, key: bytes) -> str:
    parts = payload.split(":")
    if len(parts) != 3 or parts[0] != PAYLOAD_VERSION:
        raise ValueError("Invalid payload format")

    nonce = base64.b64decode(parts[1], validate=True)
    cipher_tag = base64.b64decode(parts[2], validate=True)
    if len(nonce) != NONCE_BYTES:
        raise ValueError("Invalid nonce size")
    if len(cipher_tag) <= 16:
        raise ValueError("Invalid cipher+tag size")

    plain = _aesgcm(key).decrypt(nonce, cipher_tag, None)
    return plain.decode("utf-8")


def read_id_name_rows(input_csv: Path) -> tuple[list[dict[str, str]], list[IdNameRow]]:
    source_rows: list[dict[str, str]] = []
    id_name_rows: list[IdNameRow] = []
    with input_csv.open("r", encoding="utf-8-sig", newline="") as f:
        reader = csv.DictReader(f)
        if not reader.fieldnames:
            raise ValueError("Input CSV has no header")
        if "ID" not in reader.fieldnames or "姓名" not in reader.fieldnames:
            raise ValueError("Input CSV must contain ID and 姓名 columns")
        for row in reader:
            source_rows.append(row)
            id_name_rows.append(
                IdNameRow(
                    id=(row.get("ID") or "").strip(),
                    name=(row.get("姓名") or "").strip(),
                )
            )
    return source_rows, id_name_rows


def anonymize_personal_fields(
    source_rows: list[dict[str, str]],
    id_name_rows: list[IdNameRow],
) -> tuple[list[dict[str, str]], list[IdNameRow]]:
    if len(source_rows) != len(id_name_rows):
        raise ValueError("source_rows and id_name_rows length mismatch")

    out_source_rows: list[dict[str, str]] = []
    out_id_name_rows: list[IdNameRow] = []

    for i, (source, id_name) in enumerate(zip(source_rows, id_name_rows), start=1):
        dummy_sei = f"姓{i:04d}"
        dummy_mei = f"名{i:04d}"
        dummy_full_name = f"{dummy_sei} {dummy_mei}"
        dummy_address = f"住所{i:04d}"

        out = dict(source)
        if "姓" in out:
            out["姓"] = dummy_sei
        if "名" in out:
            out["名"] = dummy_mei
        if "姓名" in out:
            out["姓名"] = dummy_full_name
        if "住所" in out:
            out["住所"] = dummy_address

        out_source_rows.append(out)
        out_id_name_rows.append(IdNameRow(id=id_name.id, name=dummy_full_name))

    return out_source_rows, out_id_name_rows


def write_id_name_csv(rows: Iterable[IdNameRow], output_csv: Path) -> None:
    with output_csv.open("w", encoding="utf-8", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(["ID", "姓名"])
        for row in rows:
            writer.writerow([row.id, row.name])


def write_qr_csv(
    source_rows: list[dict[str, str]],
    id_name_rows: list[IdNameRow],
    key: bytes,
    output_csv: Path,
) -> list[str]:
    if len(source_rows) != len(id_name_rows):
        raise ValueError("source_rows and id_name_rows length mismatch")
    fieldnames = list(source_rows[0].keys()) if source_rows else []
    if "QR" not in fieldnames:
        fieldnames.append("QR")

    payloads: list[str] = []
    with output_csv.open("w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        writer.writeheader()
        for source, id_name in zip(source_rows, id_name_rows):
            payload = encrypt_payload(id_name.plaintext, key)
            payloads.append(payload)
            out = dict(source)
            out["QR"] = payload
            writer.writerow(out)
    return payloads


def verify_roundtrip(rows: list[IdNameRow], payloads: list[str], key: bytes, samples: int) -> None:
    if samples <= 0:
        return
    check_count = min(samples, len(rows), len(payloads))
    for i in range(check_count):
        expected = rows[i].plaintext
        actual = decrypt_payload(payloads[i], key)
        if expected != actual:
            raise ValueError(
                f"Roundtrip mismatch at row {i + 1}: expected={expected!r}, actual={actual!r}"
            )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Encrypt ID,姓名 values from T.csv into v1 AES-256-GCM QR payloads."
    )
    parser.add_argument(
        "--input",
        type=Path,
        default=Path("tools/T.csv"),
        help="Source CSV containing ID and 姓名 columns",
    )
    parser.add_argument(
        "--out-id-name",
        type=Path,
        default=Path("tools/T_id_name.csv"),
        help="Output path for extracted ID,姓名 CSV",
    )
    parser.add_argument(
        "--out-qr",
        type=Path,
        default=Path("tools/T_Q.csv"),
        help="Output path for source CSV with QR column",
    )
    parser.add_argument(
        "--key-hex",
        default=os.environ.get("QR_KEY_HEX", ""),
        help="64-char hex AES key. If omitted, QR_KEY_HEX env var is used.",
    )
    parser.add_argument(
        "--verify-samples",
        type=int,
        default=3,
        help="How many top rows to decrypt and verify after writing",
    )
    parser.add_argument(
        "--print-generated-key",
        action="store_true",
        help="Print a newly generated 64-char hex key and exit",
    )
    parser.add_argument(
        "--dummy-personal-data",
        action="store_true",
        help="Replace 姓/名/姓名/住所 with dummy values before output and encryption",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()

    if args.print_generated_key:
        print(os.urandom(KEY_BYTES).hex())
        return 0

    try:
        key = validate_key_hex(args.key_hex)
        source_rows, id_name_rows = read_id_name_rows(args.input)
        if args.dummy_personal_data:
            source_rows, id_name_rows = anonymize_personal_fields(source_rows, id_name_rows)
        write_id_name_csv(id_name_rows, args.out_id_name)
        payloads = write_qr_csv(source_rows, id_name_rows, key, args.out_qr)
        verify_roundtrip(id_name_rows, payloads, key, args.verify_samples)
    except Exception as exc:
        print(f"Error: {exc}", file=sys.stderr)
        return 1

    print(f"Rows processed: {len(id_name_rows)}")
    print(f"ID/Name CSV: {args.out_id_name}")
    print(f"QR CSV: {args.out_qr}")
    if payloads:
        first_plain = id_name_rows[0].plaintext
        first_roundtrip = decrypt_payload(payloads[0], key)
        print(f"Sample plain: {first_plain}")
        print(f"Sample decrypted: {first_roundtrip}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
