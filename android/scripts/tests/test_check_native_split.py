# SPDX-FileCopyrightText: 2026 GooseStation contributors
# SPDX-License-Identifier: GPL-3.0
"""Tests for check-native-split.py: python3 -m unittest discover -s android/scripts/tests -p 'test_*.py'"""

import importlib.util
import io
import os
import subprocess
import tempfile
import unittest
from contextlib import redirect_stdout

_SCRIPT = os.path.join(os.path.dirname(__file__), "..", "check-native-split.py")
_spec = importlib.util.spec_from_file_location("check_native_split", _SCRIPT)
check_native_split = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(check_native_split)

HOST = """\
#include <mutex>

Log_SetChannel(AndroidHost);

namespace AndroidHost {
namespace {
// A helper both domains use.
int Twice(int x)
{
  return x * 2;
}
} // namespace

static std::mutex s_mutex;
static int s_count = 0; // guarded by s_mutex
static bool s_started = false;
} // namespace AndroidHost

// Reads the count.
int AndroidHost::GetCount()
{
  std::unique_lock lock(AndroidHost::s_mutex);
  return Twice(s_count);
}

void AndroidHost::Start()
{
  s_started = true;
  INFO_LOG("started {}", "now");
}
"""

BRIDGE = """\
#include <jni.h>

Log_SetChannel(JNIBridge);

extern "C" {

#define NATIVE_LIBRARY_FUNCTION(ret, name) \\
  JNIEXPORT ret JNICALL Java_pkg_NativeLibrary_##name

NATIVE_LIBRARY_FUNCTION(jint, getCount)(JNIEnv*, jobject)
{
  return AndroidHost::GetCount();
}

NATIVE_LIBRARY_FUNCTION(void, start)(JNIEnv*, jobject)
{
  AndroidHost::Start();
}

} // extern "C"
"""


class CheckNativeSplitTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.repo = self.tmp.name
        self.cpp = os.path.join(self.repo, "cpp")
        os.makedirs(self.cpp)
        self.write("android_host.cpp", HOST)
        self.write("jni_bridge.cpp", BRIDGE)
        for args in (["init", "-q"], ["add", "-A"], ["-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qm", "base"]):
            subprocess.run(["git", *args], cwd=self.repo, check=True)

    def tearDown(self):
        self.tmp.cleanup()

    def write(self, name: str, text: str):
        with open(os.path.join(self.cpp, name), "w") as f:
            f.write(text)

    def remove(self, name: str):
        os.remove(os.path.join(self.cpp, name))

    def check(self):
        out = io.StringIO()
        with redirect_stdout(out):
            code = check_native_split.main(["HEAD", self.cpp], repo=self.repo, originals=("android_host.cpp", "jni_bridge.cpp"))
        return code, out.getvalue()

    def split(self, host_tail: str = None, bridge: str = None):
        """The fixture split: shared state and the helper in android_host_state, the two functions in their files."""
        self.write(
            "android_host_state.h",
            "#pragma once\n#include <mutex>\n\nnamespace AndroidHost {\n// A helper both domains use.\ninline int Twice(int x)\n{\n  return x * 2;\n}\n\nextern std::mutex s_mutex;\nextern int s_count;\nextern bool s_started;\n} // namespace AndroidHost\n",
        )
        self.write(
            "android_host_state.cpp",
            "#include \"android_host_state.h\"\n\nnamespace AndroidHost {\nstd::mutex s_mutex;\nint s_count = 0; // guarded by s_mutex\nbool s_started = false;\n} // namespace AndroidHost\n",
        )
        self.write(
            "android_host.cpp",
            host_tail
            if host_tail is not None
            else "#include \"android_host_state.h\"\n\nLog_SetChannel(AndroidHost);\n\nvoid AndroidHost::Start()\n{\n  s_started = true;\n  INFO_LOG(\"started {}\", \"now\");\n}\n",
        )
        self.write(
            "android_host_count.cpp",
            "#include \"android_host_state.h\"\n\n// Reads the count.\nint AndroidHost::GetCount()\n{\n  std::unique_lock lock(AndroidHost::s_mutex);\n  return Twice(s_count);\n}\n",
        )
        if bridge is not None:
            self.write("jni_bridge.cpp", bridge)

    def test_aPureMoveIsClean(self):
        self.split()
        code, out = self.check()
        self.assertEqual(0, code, out)

    def test_staticAndNamespaceLinesAreIgnored(self):
        # The unsplit tree: nothing moved at all.
        code, out = self.check()
        self.assertEqual(0, code, out)

    def test_aForwardDeclarationMovedToAHeaderCounts(self):
        host = HOST.replace("namespace AndroidHost {\nnamespace {", "namespace AndroidHost {\nstatic void Start();\nnamespace {")
        self.write("android_host.cpp", host)
        subprocess.run(["git", "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qam", "declared"], cwd=self.repo, check=True)
        self.split()
        code, out = self.check()
        self.assertEqual(1, code)
        self.assertIn("missing (1): static void Start();", out)
        self.write(
            "android_host_state.h",
            open(os.path.join(self.cpp, "android_host_state.h")).read().replace("namespace AndroidHost {\n", "namespace AndroidHost {\nvoid Start();\n\n", 1),
        )
        code, out = self.check()
        self.assertEqual(0, code, out)

    def test_aTemplatesStaticAfterItsHeadIsIgnored(self):
        host = HOST + "\ntemplate<typename T>\nstatic T Same(T x)\n{\n  return x;\n}\n"
        self.write("android_host.cpp", host)
        subprocess.run(["git", "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qam", "template"], cwd=self.repo, check=True)
        self.split()
        self.write("android_host_template.h", "#pragma once\n\ntemplate<typename T>\nT Same(T x)\n{\n  return x;\n}\n")
        code, out = self.check()
        self.assertEqual(0, code, out)

    # From review: a blank line added between two declarations that were adjacent, and still are.
    def test_aBlankLineBetweenAdjacentItemsFails(self):
        self.split()
        self.write(
            "android_host_state.cpp",
            "#include \"android_host_state.h\"\n\nnamespace AndroidHost {\nstd::mutex s_mutex;\n\nint s_count = 0; // guarded by s_mutex\nbool s_started = false;\n} // namespace AndroidHost\n",
        )
        code, out = self.check()
        self.assertEqual(1, code)
        self.assertIn("blank line", out)

    # From review: a comment over a group of declarations must keep the whole group under it, in one file.
    def test_aCommentedGroupSplitAcrossFilesFails(self):
        host = HOST.replace("static std::mutex s_mutex;", "// The count's state.\nstatic std::mutex s_mutex;")
        self.write("android_host.cpp", host)
        subprocess.run(["git", "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qam", "group"], cwd=self.repo, check=True)
        self.split()
        self.write(
            "android_host_state.cpp",
            "#include \"android_host_state.h\"\n\nnamespace AndroidHost {\n// The count's state.\nstd::mutex s_mutex;\nint s_count = 0; // guarded by s_mutex\n} // namespace AndroidHost\n",
        )
        self.write("android_host_other.cpp", "namespace AndroidHost {\nstatic bool s_started = false;\n} // namespace AndroidHost\n")
        code, out = self.check()
        self.assertEqual(1, code)
        self.assertIn("group", out)

    # From review: an extern definition, a native or a global with an initialiser can't hide as a declaration.
    def test_anAddedExternDefinitionFails(self):
        self.split()
        self.write(
            "android_host_count.cpp",
            open(os.path.join(self.cpp, "android_host_count.cpp")).read()
            + '\nextern "C" void Sneaky()\n{\n  abort();\n}\n\nstatic const bool s_sneaky = AndroidHost::Start();\n',
        )
        code, out = self.check()
        self.assertEqual(1, code)
        self.assertIn("Sneaky", out)
        self.assertIn("s_sneaky", out)

    def test_aDeclarationInANewCppFileCounts(self):
        self.split()
        self.write(
            "android_host_count.cpp",
            open(os.path.join(self.cpp, "android_host_count.cpp")).read() + "\nextern int s_other;\n",
        )
        code, out = self.check()
        self.assertEqual(1, code)
        self.assertIn("s_other", out)

    def test_aPrototypeThatMatchesNoDefinitionFails(self):
        self.split()
        self.write(
            "android_host_state.h",
            open(os.path.join(self.cpp, "android_host_state.h")).read().replace("extern bool s_started;", "extern bool s_started;\nint Twice(int x = 2);"),
        )
        code, out = self.check()
        self.assertEqual(1, code)
        self.assertIn("Twice", out)

    def test_anAddedUsingFails(self):
        self.split()
        self.write(
            "android_host_count.cpp",
            open(os.path.join(self.cpp, "android_host_count.cpp")).read().replace("// Reads", "using namespace AndroidHost;\n\n// Reads"),
        )
        code, out = self.check()
        self.assertEqual(1, code)
        self.assertIn("using namespace AndroidHost;", out)

    # From review: an item moved into another namespace is a change; leaving an anonymous one is the only allowed move.
    def test_anItemMovedToAnotherNamespaceFails(self):
        self.split()
        self.write(
            "android_host_state.cpp",
            "#include \"android_host_state.h\"\n\nstd::mutex s_mutex;\nint s_count = 0; // guarded by s_mutex\nbool s_started = false;\n",
        )
        code, out = self.check()
        self.assertEqual(1, code)
        self.assertIn("namespace", out)

    def test_aNativesHeadAndParametersStayOneItem(self):
        bridge = BRIDGE.replace("NATIVE_LIBRARY_FUNCTION(void, start)(JNIEnv*, jobject)", "NATIVE_LIBRARY_FUNCTION(void, start)\n(JNIEnv*, jobject)")
        items = check_native_split.parse(bridge, "jni_bridge.cpp")[0]
        self.assertTrue(any("start)\n(JNIEnv*" in item.text for item in items))

    def test_aChangedBodyFails(self):
        self.split()
        self.write(
            "android_host_count.cpp",
            "#include \"android_host_state.h\"\n\n// Reads the count.\nint AndroidHost::GetCount()\n{\n  std::unique_lock lock(AndroidHost::s_mutex);\n  return Twice(s_count) ;\n}\n",
        )
        code, out = self.check()
        self.assertEqual(1, code)
        self.assertIn("AndroidHost::GetCount", out)

    def test_aDroppedItemFails(self):
        self.split()
        self.write("android_host_count.cpp", "#include \"android_host_state.h\"\n")
        code, out = self.check()
        self.assertEqual(1, code)
        self.assertIn("missing", out)

    def test_aDuplicatedHelperFails(self):
        self.split()
        self.write(
            "android_host_other.cpp",
            "namespace AndroidHost {\nnamespace {\n// A helper both domains use.\nint Twice(int x)\n{\n  return x * 2;\n}\n} // namespace\n} // namespace AndroidHost\n",
        )
        code, out = self.check()
        self.assertEqual(1, code)
        self.assertIn("Twice", out)

    def test_aGlobalDefinedTwiceFails(self):
        self.split()
        self.write("android_host_other.cpp", "namespace AndroidHost {\nstatic bool s_started = false;\n} // namespace AndroidHost\n")
        code, out = self.check()
        self.assertEqual(1, code)
        self.assertIn("s_started", out)

    def test_aLostNativeFails(self):
        self.split(
            bridge=BRIDGE.replace(
                "NATIVE_LIBRARY_FUNCTION(void, start)(JNIEnv*, jobject)\n{\n  AndroidHost::Start();\n}\n\n", ""
            )
        )
        code, out = self.check()
        self.assertEqual(1, code)
        self.assertIn("start", out)

    def test_sharedStateOutOfOrderFails(self):
        self.split()
        self.write(
            "android_host_state.cpp",
            "#include \"android_host_state.h\"\n\nnamespace AndroidHost {\nint s_count = 0; // guarded by s_mutex\nstd::mutex s_mutex;\nbool s_started = false;\n} // namespace AndroidHost\n",
        )
        code, out = self.check()
        self.assertEqual(1, code)
        self.assertIn("order", out)

    def test_aNewLogChannelFails(self):
        self.split(
            host_tail="#include \"android_host_state.h\"\n\nLog_SetChannel(JNIBridge);\n\nvoid AndroidHost::Start()\n{\n  s_started = true;\n  INFO_LOG(\"started {}\", \"now\");\n}\n"
        )
        code, out = self.check()
        self.assertEqual(1, code)
        self.assertIn("channel", out)


if __name__ == "__main__":
    unittest.main()
