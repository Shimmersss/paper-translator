import io
import os
import stat
import sys
import tarfile
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from paper_translator_cli import archive, mirrors, paths, releases, service, system  # noqa: E402


class EnvFileTest(unittest.TestCase):
    def test_updates_keys_in_place_and_keeps_comments(self):
        with tempfile.TemporaryDirectory() as tmp:
            env = Path(tmp) / ".env"
            env.write_text("# keep me\nBABELDOC_OPENAI_API_KEY=\nOTHER=1\n", encoding="utf-8")
            paths.update_env_file(env, {"BABELDOC_OPENAI_API_KEY": "sk-x", "NEW": "2"})
            self.assertEqual("# keep me\nBABELDOC_OPENAI_API_KEY=sk-x\nOTHER=1\nNEW=2\n", env.read_text(encoding="utf-8"))
            self.assertEqual({"BABELDOC_OPENAI_API_KEY": "sk-x", "OTHER": "1", "NEW": "2"}, paths.read_env_file(env))
            if os.name == "posix":
                self.assertEqual(0o600, stat.S_IMODE(env.stat().st_mode))


class LayoutTest(unittest.TestCase):
    def test_detects_source_and_release_layouts(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.assertIsNone(paths.Layout.detect(root))
            (root / "backend").mkdir()
            (root / "backend" / "pom.xml").write_text(
                "<artifactId>paper-translator</artifactId>\n    <version>1.2.3</version>", encoding="utf-8")
            source = paths.Layout.detect(root)
            self.assertEqual(("source", "1.2.3"), (source.mode, source.version()))
            self.assertTrue(str(source.scripts).endswith(os.path.join("backend", "scripts")))
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "paper-translator.jar").write_bytes(b"jar")
            (root / "VERSION").write_text("0.9.0\n", encoding="utf-8")
            release = paths.Layout.detect(root)
            self.assertEqual(("release", "0.9.0"), (release.mode, release.version()))
            self.assertEqual(root.resolve() / "scripts", release.scripts)


class ArchiveTest(unittest.TestCase):
    def test_parses_sha256sum_output(self):
        sums = archive.parse_sums("abc123  paper-translator-1.0.0.zip\nDEF456 *paper-translator-1.0.0.tar.gz\n")
        self.assertEqual({"paper-translator-1.0.0.zip": "abc123", "paper-translator-1.0.0.tar.gz": "def456"}, sums)

    def test_rejects_path_traversal_in_zip_and_tar(self):
        with tempfile.TemporaryDirectory() as tmp:
            bad_zip = Path(tmp) / "bad.zip"
            with zipfile.ZipFile(bad_zip, "w") as bundle:
                bundle.writestr("../escape.txt", "x")
            with self.assertRaises(RuntimeError):
                archive.extract_archive(bad_zip, Path(tmp) / "out")
            bad_tar = Path(tmp) / "bad.tar.gz"
            with tarfile.open(bad_tar, "w:gz") as bundle:
                info = tarfile.TarInfo("app/link")
                info.type = tarfile.SYMTYPE
                info.linkname = "../../etc/passwd"
                bundle.addfile(info)
            with self.assertRaises(RuntimeError):
                archive.extract_archive(bad_tar, Path(tmp) / "out")
            for linkname in ("..\\..\\escape", "C:/Windows/x", "/etc/passwd"):
                windows_tar = Path(tmp) / "bad2.tar.gz"
                with tarfile.open(windows_tar, "w:gz") as bundle:
                    info = tarfile.TarInfo("app/link")
                    info.type = tarfile.SYMTYPE
                    info.linkname = linkname
                    bundle.addfile(info)
                with self.assertRaises(RuntimeError, msg=linkname):
                    archive.extract_archive(windows_tar, Path(tmp) / "out")
            self.assertFalse((Path(tmp) / "escape.txt").exists())

    def test_packages_a_release_and_installs_it_from_the_archive(self):
        with tempfile.TemporaryDirectory() as tmp:
            repo = Path(tmp) / "repo"
            (repo / "backend" / "target").mkdir(parents=True)
            (repo / "backend" / "target" / "paper-translator.jar").write_bytes(b"jar")
            (repo / "backend" / "scripts" / "tiktoken-cache").mkdir(parents=True)
            (repo / "backend" / "scripts" / "babeldoc_runner.py").write_text("print()", encoding="utf-8")
            (repo / "backend" / "scripts" / "test_runner.py").write_text("print()", encoding="utf-8")
            (repo / "backend" / "scripts" / "tiktoken-cache" / "vocab").write_bytes(b"v")
            (repo / "frontend" / "dist").mkdir(parents=True)
            (repo / "frontend" / "dist" / "index.html").write_text("<html>", encoding="utf-8")
            (repo / "cli" / "tests").mkdir(parents=True)
            (repo / "cli" / "pyproject.toml").write_text("[project]", encoding="utf-8")
            (repo / ".env.example").write_text("A=1\n", encoding="utf-8")
            out = Path(tmp) / "out"
            out.mkdir()
            root = archive.stage_release(repo, "3.4.5", out)
            self.assertFalse((root / "scripts" / "test_runner.py").exists())
            self.assertFalse((root / "cli" / "tests").exists())
            tar_path, zip_path, sums = archive.write_archives(root, out)
            self.assertEqual({tar_path.name, zip_path.name},
                             set(archive.parse_sums(sums.read_text(encoding="utf-8"))))
            for bundle in (tar_path, zip_path):
                app = Path(tmp) / ("app-" + bundle.suffix.strip("."))
                layout = releases.install_release(app, archive=bundle)
                self.assertEqual(("release", "3.4.5"), (layout.mode, layout.version()))
                self.assertTrue((app / "scripts" / "tiktoken-cache" / "vocab").is_file())
                self.assertTrue((app / "frontend" / "dist" / "index.html").is_file())
                self.assertFalse((Path(tmp) / (app.name + ".new")).exists())


class ReleaseTest(unittest.TestCase):
    def test_compares_versions_numerically(self):
        self.assertGreater(releases.version_tuple("v0.10.0"), releases.version_tuple("0.9.9"))
        self.assertEqual((1, 2, 3), releases.version_tuple("1.2.3"))


class ServiceTest(unittest.TestCase):
    def test_backend_command_points_the_jar_at_the_install_home(self):
        with tempfile.TemporaryDirectory() as tmp:
            home = paths.Home(Path(tmp) / "home dir")
            layout = paths.Layout(Path(tmp) / "app", "release")
            with mock.patch.object(service, "uv_path", return_value=str(Path(tmp) / "with space" / "uv")):
                command, env = service.backend_command(home, layout, "java", 8123)
            self.assertEqual(["java", "-jar", str(layout.jar)], command[:3])
            self.assertIn(home.env_file.as_posix() + "[.properties]", command[3])
            self.assertEqual("8123", env["SERVER_PORT"])
            self.assertEqual(str(home.settings_file), env["SETTINGS_FILE"])
            self.assertTrue(env["BABELDOC_COMMAND"].startswith('"'))
            self.assertTrue(env["FRONTEND_DIST"].startswith("file:") and env["FRONTEND_DIST"].endswith("/"))

    def test_autostart_runs_the_cli_in_the_foreground(self):
        with tempfile.TemporaryDirectory() as tmp:
            home = paths.Home(Path(tmp))
            spec = service.autostart_files(home)
            self.assertIn("start", spec["content"])
            self.assertIn("--no-browser", spec["content"])
            if sys.platform != "win32":
                self.assertIn("--foreground", spec["content"])
                self.assertIn(str(home.path), spec["content"])




class MirrorTest(unittest.TestCase):
    def test_mode_comes_from_env_then_config_then_auto(self):
        with mock.patch.dict(os.environ, {"PAPER_TRANSLATOR_MIRROR": "CN"}):
            self.assertEqual("cn", mirrors.requested({"mirror": "off"}))
        with mock.patch.dict(os.environ, {}, clear=True):
            self.assertEqual("off", mirrors.requested({"mirror": "off"}))
            self.assertEqual("auto", mirrors.requested({}))
            self.assertEqual("auto", mirrors.requested({"mirror": "nonsense"}))
            self.assertTrue(mirrors.enabled({"mirror": "cn"}))
            self.assertFalse(mirrors.enabled({"mirror": "auto"}))

    def test_auto_uses_mirrors_only_when_github_is_unreachable(self):
        with mock.patch.object(mirrors, "github_reachable", return_value=True):
            self.assertEqual("off", mirrors.resolve("auto"))
        with mock.patch.object(mirrors, "github_reachable", return_value=False):
            self.assertEqual("cn", mirrors.resolve("auto"))
            self.assertEqual("off", mirrors.resolve("off"))

    def test_uv_env_keeps_a_users_own_index(self):
        self.assertEqual({}, mirrors.uv_env(False, {}))
        self.assertEqual({"UV_DEFAULT_INDEX": mirrors.PYPI_INDEX,
                          "UV_PYTHON_INSTALL_MIRROR": mirrors.PYTHON_INSTALL_MIRROR}, mirrors.uv_env(True, {}))
        mine = mirrors.uv_env(True, {"UV_INDEX_URL": "https://pypi.example/simple",
                                     "UV_PYTHON_INSTALL_MIRROR": "https://py.example"})
        self.assertEqual({}, mine)

    def test_jdk_mirror_urls_follow_the_adoptium_layout(self):
        self.assertEqual(
            ["https://mirrors.tuna.tsinghua.edu.cn/Adoptium/21/jdk/aarch64/mac/OpenJDK21U-jdk.tar.gz",
             "https://mirror.nju.edu.cn/adoptium/21/jdk/aarch64/mac/OpenJDK21U-jdk.tar.gz"],
            mirrors.jdk_urls(21, "aarch64", "mac", "OpenJDK21U-jdk.tar.gz"))

    def test_portable_jdk_tries_mirrors_then_the_original_link(self):
        payload = b"jdk-bytes"
        listing = [{"binary": {"package": {
            "name": "jdk.tar.gz", "link": "https://github.example/jdk.tar.gz",
            "checksum": __import__("hashlib").sha256(payload).hexdigest()}}}]
        tried = []

        def fake_download(url, target):
            tried.append(url)
            if url.endswith("assets/latest/21/hotspot") or "api.adoptium.net" in url:
                Path(target).write_text(__import__("json").dumps(listing), encoding="utf-8")
            elif "tuna" in url:
                raise OSError("404")
            elif "nju" in url:
                Path(target).write_bytes(b"wrong")
            else:
                Path(target).write_bytes(payload)

        with tempfile.TemporaryDirectory() as tmp, \
                mock.patch.object(system, "download", side_effect=fake_download), \
                mock.patch.object(system, "extract_archive"):
            system.install_portable_jdk(Path(tmp) / "jdk", use_mirror=True)
        self.assertIn("tuna", tried[1])
        self.assertIn("nju", tried[2])
        self.assertEqual("https://github.example/jdk.tar.gz", tried[3])

    def test_service_passes_the_mirror_to_uv(self):
        with tempfile.TemporaryDirectory() as tmp:
            home = paths.Home(Path(tmp))
            home.save({"mirror": "cn"})
            layout = paths.Layout(Path(tmp) / "app", "release")
            with mock.patch.object(service, "uv_path", return_value="uv"), mock.patch.dict(os.environ, {}, clear=True):
                _, env = service.backend_command(home, layout, "java", 8080)
            self.assertEqual(mirrors.PYPI_INDEX, env["UV_DEFAULT_INDEX"])
            home.save({"mirror": "off"})
            with mock.patch.object(service, "uv_path", return_value="uv"), mock.patch.dict(os.environ, {}, clear=True):
                _, env = service.backend_command(home, layout, "java", 8080)
            self.assertNotIn("UV_DEFAULT_INDEX", env)


if __name__ == "__main__":
    unittest.main()
