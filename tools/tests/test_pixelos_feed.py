# SPDX-FileCopyrightText: 2026 PixelOS
# SPDX-License-Identifier: Apache-2.0

from __future__ import annotations

import importlib.util
import tempfile
import unittest
import zipfile
from pathlib import Path


MODULE_PATH = Path(__file__).parents[1] / "pixelos_feed.py"
SPEC = importlib.util.spec_from_file_location("pixelos_feed", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
pixelos_feed = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(pixelos_feed)


class PixelOsFeedTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp_directory.cleanup)
        self.directory = Path(self.temp_directory.name)

    def create_ota(self, metadata_overrides: dict[str, str] | None = None) -> Path:
        metadata = {
            "post-timestamp": "1781858358",
            "post-security-patch-level": "2026-06-01",
            "post-sdk-level": "36",
            "ota-property-files": (
                "payload_metadata.bin:0:1,payload.bin:1:2,"
                "payload_properties.txt:3:1"
            ),
        }
        metadata.update(metadata_overrides or {})
        artifact = self.directory / "PixelOS_device-17.0-20260619-0000.zip"
        rendered = "\n".join(f"{key}={value}" for key, value in metadata.items()) + "\n"
        with zipfile.ZipFile(artifact, "w") as archive:
            archive.writestr(pixelos_feed.OTA_METADATA_PATH, rendered)
            archive.writestr("payload.bin", b"payload")
        return artifact

    def test_generate_and_validate_ota_feed(self) -> None:
        artifact = self.create_ota()
        feed = pixelos_feed.build_ota_feed(
            artifact,
            "https://downloads.pixelos.net/device/update.zip",
            "17.0",
        )

        pixelos_feed.validate_ota_feed(feed, artifact=artifact)
        update = feed[0]
        self.assertEqual(1781858358, update["datetime"])
        self.assertEqual(pixelos_feed.sha256_file(artifact), update["files"][0]["sha256"])
        self.assertEqual(artifact.stat().st_size, update["files"][0]["size"])

    def test_ota_feed_rejects_more_than_one_file(self) -> None:
        feed = pixelos_feed.build_ota_feed(
            self.create_ota(),
            "https://downloads.pixelos.net/device/update.zip",
            "17.0",
        )
        feed[0]["files"].append(dict(feed[0]["files"][0]))

        with self.assertRaisesRegex(pixelos_feed.ValidationError, "exactly one"):
            pixelos_feed.validate_ota_feed(feed)

    def test_ota_feed_rejects_uppercase_hash(self) -> None:
        feed = pixelos_feed.build_ota_feed(
            self.create_ota(),
            "https://downloads.pixelos.net/device/update.zip",
            "17.0",
        )
        feed[0]["files"][0]["sha256"] = "A" * 64

        with self.assertRaisesRegex(pixelos_feed.ValidationError, "lowercase"):
            pixelos_feed.validate_ota_feed(feed)

    def test_ota_feed_rejects_unknown_keys(self) -> None:
        feed = pixelos_feed.build_ota_feed(
            self.create_ota(),
            "https://downloads.pixelos.net/device/update.zip",
            "17.0",
        )
        feed[0]["unsupported"] = True

        with self.assertRaisesRegex(pixelos_feed.ValidationError, "unknown keys"):
            pixelos_feed.validate_ota_feed(feed)

    def test_ota_ranges_must_fit_artifact(self) -> None:
        artifact = self.create_ota(
            {"ota-property-files": "payload_metadata.bin:0:1,payload.bin:1:999999,payload_properties.txt:3:1"}
        )

        with self.assertRaisesRegex(pixelos_feed.ValidationError, "exceeds package size"):
            pixelos_feed.build_ota_feed(
                artifact,
                "https://downloads.pixelos.net/device/update.zip",
                "17.0",
            )

    def test_artifact_comparison_detects_hash_mismatch(self) -> None:
        artifact = self.create_ota()
        feed = pixelos_feed.build_ota_feed(
            artifact,
            "https://downloads.pixelos.net/device/update.zip",
            "17.0",
        )
        feed[0]["files"][0]["sha256"] = "0" * 64

        with self.assertRaisesRegex(pixelos_feed.ValidationError, "does not match artifact"):
            pixelos_feed.validate_ota_feed(feed, artifact=artifact)

    def test_generate_and_validate_certified_props_manifest(self) -> None:
        apk = self.directory / "certified-props.apk"
        apk.write_bytes(b"signed apk fixture")
        signer = "a" * 64

        manifest = pixelos_feed.build_certified_props_manifest(
            apk,
            "https://downloads.pixelos.net/certified-props.apk",
            42,
            signer,
        )

        pixelos_feed.validate_certified_props_manifest(manifest, apk=apk)
        self.assertEqual(pixelos_feed.DEFAULT_CERTIFIED_PROPS_PACKAGE, manifest["package_name"])
        self.assertEqual(signer, manifest["signing_certificate_sha256"])

    def test_certified_props_manifest_rejects_http(self) -> None:
        apk = self.directory / "certified-props.apk"
        apk.write_bytes(b"signed apk fixture")

        with self.assertRaisesRegex(pixelos_feed.ValidationError, "HTTPS"):
            pixelos_feed.build_certified_props_manifest(
                apk,
                "http://downloads.pixelos.net/certified-props.apk",
                42,
                "a" * 64,
            )

    def test_certified_props_manifest_rejects_oversized_apk(self) -> None:
        manifest = {
            "package_name": pixelos_feed.DEFAULT_CERTIFIED_PROPS_PACKAGE,
            "version_code": 42,
            "sha256": "a" * 64,
            "size": pixelos_feed.MAX_CERTIFIED_PROPS_APK_BYTES + 1,
            "url": "https://downloads.pixelos.net/certified-props.apk",
            "signing_certificate_sha256": "b" * 64,
        }

        with self.assertRaisesRegex(pixelos_feed.ValidationError, "APK limit"):
            pixelos_feed.validate_certified_props_manifest(manifest)


if __name__ == "__main__":
    unittest.main()
