#!/usr/bin/env python3

# SPDX-FileCopyrightText: 2026 PixelOS
# SPDX-License-Identifier: Apache-2.0

"""Generate and validate PixelOS Updater metadata."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
import zipfile
from pathlib import Path
from typing import Any
from urllib.parse import urlparse


OTA_METADATA_PATH = "META-INF/com/android/metadata"
REQUIRED_AB_FILES = {
    "payload_metadata.bin",
    "payload.bin",
    "payload_properties.txt",
}
OTA_KEYS = {"datetime", "files", "type", "version"}
OTA_OPTIONAL_KEYS = {"additional_images"}
OTA_FILE_REQUIRED_KEYS = {
    "filename",
    "os_patch_level",
    "os_sdk_level",
    "sha256",
    "size",
    "url",
}
OTA_FILE_OPTIONAL_KEYS = {"ota_property_files"}
CERTIFIED_PROPS_KEYS = {
    "package_name",
    "version_code",
    "sha256",
    "size",
    "url",
    "signing_certificate_sha256",
}
DEFAULT_CERTIFIED_PROPS_PACKAGE = "net.pixelos.certifiedprops.overlay"
SHA256_RE = re.compile(r"[0-9a-f]{64}\Z")
MAX_JSON_BYTES = 1024 * 1024
MAX_METADATA_BYTES = 1024 * 1024
MAX_CERTIFIED_PROPS_MANIFEST_BYTES = 64 * 1024
MAX_CERTIFIED_PROPS_APK_BYTES = 16 * 1024 * 1024


class ValidationError(ValueError):
    """Raised when generated or supplied metadata is unsafe or malformed."""


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise ValidationError(message)


def _is_integer(value: Any) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def _require_exact_keys(
    value: dict[str, Any],
    required: set[str],
    optional: set[str] | None = None,
    *,
    label: str,
) -> None:
    optional = optional or set()
    actual = set(value)
    missing = required - actual
    unknown = actual - required - optional
    _require(not missing, f"{label} is missing keys: {', '.join(sorted(missing))}")
    _require(not unknown, f"{label} has unknown keys: {', '.join(sorted(unknown))}")


def _require_https(url: Any, label: str) -> str:
    _require(isinstance(url, str) and bool(url), f"{label} must be a non-empty string")
    parsed = urlparse(url)
    _require(
        parsed.scheme.lower() == "https" and bool(parsed.netloc),
        f"{label} must be an absolute HTTPS URL",
    )
    _require(parsed.username is None and parsed.password is None, f"{label} must not contain credentials")
    return url


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_ota_metadata(artifact: Path) -> dict[str, str]:
    _require(artifact.is_file(), f"OTA artifact does not exist: {artifact}")
    try:
        with zipfile.ZipFile(artifact) as archive:
            info = archive.getinfo(OTA_METADATA_PATH)
            _require(info.file_size <= MAX_METADATA_BYTES, "OTA metadata exceeds the size limit")
            raw = archive.read(info)
    except (KeyError, zipfile.BadZipFile) as error:
        raise ValidationError(f"Unable to read {OTA_METADATA_PATH}: {error}") from error

    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError as error:
        raise ValidationError("OTA metadata is not valid UTF-8") from error

    metadata: dict[str, str] = {}
    for line_number, raw_line in enumerate(text.splitlines(), start=1):
        if not raw_line.strip():
            continue
        _require("=" in raw_line, f"Malformed OTA metadata line {line_number}")
        key, value = raw_line.split("=", 1)
        key = key.strip()
        _require(bool(key), f"Empty OTA metadata key on line {line_number}")
        _require(key not in metadata, f"Duplicate OTA metadata key: {key}")
        metadata[key] = value
    return metadata


def _metadata_value(metadata: dict[str, str], key: str) -> str:
    _require(key in metadata, f"OTA metadata is missing {key}")
    value = metadata[key].strip()
    _require(bool(value), f"OTA metadata value is blank: {key}")
    return value


def parse_ota_property_files(value: Any, package_size: int) -> dict[str, tuple[int, int]]:
    _require(isinstance(value, str) and bool(value.strip()), "ota_property_files must be non-empty")
    ranges: dict[str, tuple[int, int]] = {}
    for token in value.split(","):
        parts = [part.strip() for part in token.split(":", 2)]
        _require(len(parts) == 3, f"Malformed ota_property_files entry: {token}")
        name, offset_text, size_text = parts
        _require(bool(name), "ota_property_files contains an empty name")
        _require(name not in ranges, f"ota_property_files contains duplicate name: {name}")
        try:
            offset = int(offset_text)
            size = int(size_text)
        except ValueError as error:
            raise ValidationError(f"Invalid range for {name}") from error
        _require(offset >= 0 and size > 0, f"Invalid range for {name}")
        _require(offset + size <= package_size, f"Range for {name} exceeds package size")
        ranges[name] = (offset, size)

    missing = REQUIRED_AB_FILES - set(ranges)
    _require(
        not missing,
        "ota_property_files is missing required entries: " + ", ".join(sorted(missing)),
    )
    return ranges


def _metadata_integer(metadata: dict[str, str], key: str) -> int:
    value = _metadata_value(metadata, key)
    try:
        result = int(value)
    except ValueError as error:
        raise ValidationError(f"OTA metadata value is not an integer: {key}") from error
    _require(result > 0, f"OTA metadata value must be positive: {key}")
    return result


def build_ota_feed(artifact: Path, url: str, version: str) -> list[dict[str, Any]]:
    _require_https(url, "OTA URL")
    _require(bool(version.strip()), "version must not be blank")
    metadata = read_ota_metadata(artifact)
    package_size = artifact.stat().st_size
    _require(package_size > 0, "OTA artifact is empty")

    file_metadata: dict[str, Any] = {
        "filename": artifact.name,
        "os_patch_level": _metadata_value(metadata, "post-security-patch-level"),
        "os_sdk_level": _metadata_integer(metadata, "post-sdk-level"),
        "sha256": sha256_file(artifact),
        "size": package_size,
        "url": url,
    }
    if "ota-property-files" in metadata:
        ota_property_files = metadata["ota-property-files"]
        parse_ota_property_files(ota_property_files, package_size)
        file_metadata["ota_property_files"] = ota_property_files

    feed = [
        {
            "datetime": _metadata_integer(metadata, "post-timestamp"),
            "files": [file_metadata],
            "type": "ci",
            "version": version.strip(),
        }
    ]
    validate_ota_feed(feed, artifact=artifact)
    return feed


def validate_ota_feed(data: Any, artifact: Path | None = None) -> None:
    _require(isinstance(data, list) and bool(data), "OTA feed must be a non-empty JSON array")
    for update_index, update in enumerate(data):
        label = f"update[{update_index}]"
        _require(isinstance(update, dict), f"{label} must be an object")
        _require_exact_keys(update, OTA_KEYS, OTA_OPTIONAL_KEYS, label=label)
        _require(_is_integer(update["datetime"]) and update["datetime"] > 0, f"{label}.datetime must be positive")
        _require(update["type"] == "ci", f"{label}.type must be ci")
        _require(isinstance(update["version"], str) and bool(update["version"].strip()), f"{label}.version must not be blank")
        files = update["files"]
        _require(isinstance(files, list) and len(files) == 1, f"{label}.files must contain exactly one file")
        file_value = files[0]
        file_label = f"{label}.files[0]"
        _require(isinstance(file_value, dict), f"{file_label} must be an object")
        _require_exact_keys(
            file_value,
            OTA_FILE_REQUIRED_KEYS,
            OTA_FILE_OPTIONAL_KEYS,
            label=file_label,
        )
        for key in ("filename", "os_patch_level"):
            _require(
                isinstance(file_value[key], str) and bool(file_value[key].strip()),
                f"{file_label}.{key} must not be blank",
            )
        _require(
            _is_integer(file_value["os_sdk_level"]) and file_value["os_sdk_level"] > 0,
            f"{file_label}.os_sdk_level must be positive",
        )
        _require(
            isinstance(file_value["sha256"], str) and bool(SHA256_RE.fullmatch(file_value["sha256"])),
            f"{file_label}.sha256 must be lowercase hexadecimal",
        )
        _require(
            _is_integer(file_value["size"]) and file_value["size"] > 0,
            f"{file_label}.size must be positive",
        )
        _require_https(file_value["url"], f"{file_label}.url")
        if "ota_property_files" in file_value:
            parse_ota_property_files(file_value["ota_property_files"], file_value["size"])

    if artifact is not None:
        _require(len(data) == 1, "Artifact comparison requires exactly one update")
        _require(artifact.is_file(), f"OTA artifact does not exist: {artifact}")
        update = data[0]
        file_value = update["files"][0]
        metadata = read_ota_metadata(artifact)
        _require(file_value["filename"] == artifact.name, "OTA filename does not match artifact")
        _require(file_value["size"] == artifact.stat().st_size, "OTA size does not match artifact")
        _require(file_value["sha256"] == sha256_file(artifact), "OTA SHA-256 does not match artifact")
        _require(update["datetime"] == _metadata_integer(metadata, "post-timestamp"), "OTA datetime does not match metadata")
        _require(file_value["os_patch_level"] == _metadata_value(metadata, "post-security-patch-level"), "OTA patch level does not match metadata")
        _require(file_value["os_sdk_level"] == _metadata_integer(metadata, "post-sdk-level"), "OTA SDK level does not match metadata")
        metadata_ranges = metadata.get("ota-property-files")
        _require(
            file_value.get("ota_property_files") == metadata_ranges,
            "ota_property_files does not match OTA metadata",
        )


def build_certified_props_manifest(
    apk: Path,
    url: str,
    version_code: int,
    signer_sha256: str,
    package_name: str = DEFAULT_CERTIFIED_PROPS_PACKAGE,
) -> dict[str, Any]:
    _require(apk.is_file(), f"Certified properties APK does not exist: {apk}")
    _require(apk.stat().st_size > 0, "Certified properties APK is empty")
    _require(
        apk.stat().st_size <= MAX_CERTIFIED_PROPS_APK_BYTES,
        "Certified properties APK exceeds the size limit",
    )
    manifest = {
        "package_name": package_name,
        "version_code": version_code,
        "sha256": sha256_file(apk),
        "size": apk.stat().st_size,
        "url": url,
        "signing_certificate_sha256": signer_sha256,
    }
    validate_certified_props_manifest(manifest, apk=apk)
    return manifest


def validate_certified_props_manifest(data: Any, apk: Path | None = None) -> None:
    _require(isinstance(data, dict), "Certified properties manifest must be a JSON object")
    _require_exact_keys(data, CERTIFIED_PROPS_KEYS, label="certified properties manifest")
    _require(
        isinstance(data["package_name"], str) and bool(data["package_name"].strip()),
        "package_name must not be blank",
    )
    _require(_is_integer(data["version_code"]) and data["version_code"] > 0, "version_code must be positive")
    _require(isinstance(data["sha256"], str) and bool(SHA256_RE.fullmatch(data["sha256"])), "sha256 must be lowercase hexadecimal")
    _require(
        _is_integer(data["size"]) and data["size"] in range(1, MAX_CERTIFIED_PROPS_APK_BYTES + 1),
        "size must be within the certified properties APK limit",
    )
    _require_https(data["url"], "Certified properties APK URL")
    _require(
        isinstance(data["signing_certificate_sha256"], str)
        and bool(SHA256_RE.fullmatch(data["signing_certificate_sha256"])),
        "signing_certificate_sha256 must be lowercase hexadecimal",
    )
    if apk is not None:
        _require(apk.is_file(), f"Certified properties APK does not exist: {apk}")
        _require(data["size"] == apk.stat().st_size, "APK size does not match manifest")
        _require(data["sha256"] == sha256_file(apk), "APK SHA-256 does not match manifest")


def load_json(path: Path, max_bytes: int = MAX_JSON_BYTES) -> Any:
    _require(path.is_file(), f"JSON file does not exist: {path}")
    _require(path.stat().st_size <= max_bytes, "JSON file exceeds the size limit")
    try:
        with path.open(encoding="utf-8") as stream:
            return json.load(stream)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValidationError(f"Invalid JSON: {error}") from error


def write_json(data: Any, output: str) -> None:
    rendered = json.dumps(data, indent=2, ensure_ascii=False) + "\n"
    if output == "-":
        sys.stdout.write(rendered)
    else:
        Path(output).write_text(rendered, encoding="utf-8")


def create_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    generate_ota = subparsers.add_parser("generate-ota", help="generate one OTA feed entry")
    generate_ota.add_argument("artifact", type=Path)
    generate_ota.add_argument("--url", required=True)
    generate_ota.add_argument("--version", required=True)
    generate_ota.add_argument("--output", default="-")

    validate_ota = subparsers.add_parser("validate-ota", help="validate an OTA feed")
    validate_ota.add_argument("feed", type=Path)
    validate_ota.add_argument("--artifact", type=Path)

    generate_props = subparsers.add_parser(
        "generate-certified-props",
        help="generate a certified properties manifest",
    )
    generate_props.add_argument("apk", type=Path)
    generate_props.add_argument("--url", required=True)
    generate_props.add_argument("--version-code", required=True, type=int)
    generate_props.add_argument("--signer-sha256", required=True)
    generate_props.add_argument("--package-name", default=DEFAULT_CERTIFIED_PROPS_PACKAGE)
    generate_props.add_argument("--output", default="-")

    validate_props = subparsers.add_parser(
        "validate-certified-props",
        help="validate a certified properties manifest",
    )
    validate_props.add_argument("manifest", type=Path)
    validate_props.add_argument("--apk", type=Path)
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = create_parser()
    args = parser.parse_args(argv)
    try:
        if args.command == "generate-ota":
            write_json(
                build_ota_feed(args.artifact, args.url, args.version),
                args.output,
            )
        elif args.command == "validate-ota":
            validate_ota_feed(load_json(args.feed), artifact=args.artifact)
        elif args.command == "generate-certified-props":
            write_json(
                build_certified_props_manifest(
                    args.apk,
                    args.url,
                    args.version_code,
                    args.signer_sha256,
                    args.package_name,
                ),
                args.output,
            )
        elif args.command == "validate-certified-props":
            validate_certified_props_manifest(
                load_json(args.manifest, MAX_CERTIFIED_PROPS_MANIFEST_BYTES),
                apk=args.apk,
            )
        else:  # pragma: no cover - argparse enforces the command choices.
            parser.error(f"Unknown command: {args.command}")
    except (OSError, ValidationError) as error:
        parser.error(str(error))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
