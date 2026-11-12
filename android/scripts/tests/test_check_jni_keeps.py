# SPDX-FileCopyrightText: 2026 GooseStation contributors
# SPDX-License-Identifier: GPL-3.0

"""Tests for check-jni-keeps.py: python3 -m unittest discover -s android/scripts/tests -p 'test_*.py'"""

import importlib.util
import io
import os
import tempfile
import unittest
from contextlib import redirect_stdout

_SCRIPT = os.path.join(os.path.dirname(__file__), "..", "check-jni-keeps.py")
_spec = importlib.util.spec_from_file_location("check_jni_keeps", _SCRIPT)
check_jni_keeps = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(check_jni_keeps)

PKG = "io.github.ilyalissoboi.goosestation"
SEEDS = f"""\
{PKG}.core.HttpBridge
{PKG}.core.HttpBridge: void start(long,long,java.lang.String,byte[],java.lang.String,int)
{PKG}.core.HttpBridge: void cancel(long,long)
{PKG}.core.GameDisc
{PKG}.core.GameDisc: GameDisc(java.lang.String,java.lang.String,java.lang.String,int)
{PKG}.storage.NativeDocument
{PKG}.storage.NativeDocument: java.lang.String documentId
{PKG}.storage.NativeDocument: long[] sizes
androidx.compose.ui.focus.FocusEventElement
"""


class CheckJniKeepsTest(unittest.TestCase):
    def run_check(self, cpp: str, seeds: str = SEEDS):
        with tempfile.TemporaryDirectory() as tmp:
            seeds_path = os.path.join(tmp, "seeds.txt")
            with open(seeds_path, "w") as f:
                f.write(seeds)
            cpp_dir = os.path.join(tmp, "cpp")
            os.mkdir(cpp_dir)
            with open(os.path.join(cpp_dir, "bridge.cpp"), "w") as f:
                f.write(cpp)
            out = io.StringIO()
            with redirect_stdout(out):
                code = check_jni_keeps.main([seeds_path, cpp_dir])
            return code, out.getvalue()

    def testKeptClassAndMethodPass(self):
        code, out = self.run_check(
            'constexpr const char* C = "io/github/ilyalissoboi/goosestation/core/HttpBridge";\n'
            'env->GetStaticMethodID(c, "cancel", "(JJ)V");\n'
        )
        self.assertEqual(0, code, out)
        self.assertIn("JNI names: 1 classes and 1 members kept.", out)

    def testMissingClassFails(self):
        code, out = self.run_check('\n\nFindClass("io/github/ilyalissoboi/goosestation/core/Gone");\n')
        self.assertEqual(1, code)
        self.assertIn("bridge.cpp:3: io.github.ilyalissoboi.goosestation.core.Gone isn't kept by R8", out)

    def testMissingMethodFails(self):
        code, out = self.run_check(
            'FindClass("io/github/ilyalissoboi/goosestation/core/HttpBridge");\n'
            'env->GetStaticMethodID(c, "start", "(JJLjava/lang/String;)V");\n'
        )
        self.assertEqual(1, code)
        self.assertIn("bridge.cpp:2: void start(long,long,java.lang.String) isn't kept by R8", out)

    def testConstructorMatchesBySignature(self):
        cpp = (
            'FindClass("io/github/ilyalissoboi/goosestation/core/GameDisc");\n'
            'env->GetMethodID(c, "<init>", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)V");\n'
        )
        self.assertEqual(0, self.run_check(cpp)[0])
        code, out = self.run_check(cpp.replace(";I)V", ";J)V"))
        self.assertEqual(1, code)
        self.assertIn("a constructor (java.lang.String,java.lang.String,java.lang.String,long)", out)

    def testFieldMatches(self):
        cpp = (
            'FindClass("io/github/ilyalissoboi/goosestation/storage/NativeDocument");\n'
            'env->GetFieldID(c, "documentId", "Ljava/lang/String;");\n'
            'env->GetFieldID(c, "sizes", "[J");\n'
        )
        self.assertEqual(0, self.run_check(cpp)[0])
        code, out = self.run_check(cpp + 'env->GetFieldID(c, "mimeType", "Ljava/lang/String;");\n')
        self.assertEqual(1, code)
        self.assertIn("bridge.cpp:4: java.lang.String mimeType isn't kept by R8", out)

    def testCallAcrossLinesAndJoinedLiterals(self):
        cpp = (
            'FindClass("io/github/ilyalissoboi/goosestation/core/HttpBridge");\n'
            "(s_start = env->GetStaticMethodID(bridge_class, \"start\",\n"
            '                                   "(JJLjava/lang/String;[B"\n'
            '                                   "Ljava/lang/String;I)V"));\n'
        )
        code, out = self.run_check(cpp)
        self.assertEqual(0, code, out)
        self.assertIn("1 classes and 1 members", out)

    def testCommentedLookupIgnored(self):
        cpp = (
            '// FindClass("io/github/ilyalissoboi/goosestation/core/Gone");\n'
            '/* env->GetMethodID(c, "gone", "()V"); */\n'
        )
        code, out = self.run_check(cpp)
        self.assertEqual(0, code, out)
        self.assertIn("0 classes and 0 members", out)

    def testVariableNamesSkipped(self):
        cpp = (
            "env->FindClass(name);\n"
            'env->GetMethodID(*cls, "<init>", signature);\n'
            "env->GetStaticFieldID(cls, names[i], signature.c_str());\n"
        )
        code, out = self.run_check(cpp)
        self.assertEqual(0, code, out)
        self.assertIn("0 classes and 0 members", out)

    def testOtherPackagesIgnored(self):
        cpp = 'FindClass("java/lang/Runnable");\nenv->GetMethodID(c, "run", "()V");\n'
        code, out = self.run_check(cpp)
        self.assertEqual(0, code, out)
        self.assertIn("0 classes and 0 members", out)

    def testAnUnlistedFrameworkMemberFails(self):
        code, out = self.run_check('FindClass("java/lang/String");\nenv->GetMethodID(c, "length", "()I");\n')
        self.assertEqual(1, code)
        self.assertIn("int length() isn't kept by R8", out)


    # JNI_OnLoad's table rows: MethodLookup{"name", "signature", &id} and
    # ClassLookup{"class", &cls, "constructor signature" or nullptr, &id}.
    def testMethodRowChecked(self):
        code, out = self.run_check('MethodLookup{"cancel", "(JJ)V", &s_cancel},\n')
        self.assertEqual(0, code, out)
        self.assertIn("0 classes and 1 members", out)
        code, out = self.run_check('\nMethodLookup{"gone", "()V", &s_gone},\n')
        self.assertEqual(1, code)
        self.assertIn("bridge.cpp:2: void gone() isn't kept by R8", out)

    def testClassRowConstructorChecked(self):
        row = (
            'ClassLookup{"io/github/ilyalissoboi/goosestation/core/GameDisc", &s_game_disc_class, '
            '"(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)V", &s_game_disc_constructor},\n'
        )
        code, out = self.run_check(row)
        self.assertEqual(0, code, out)
        self.assertIn("1 classes and 1 members", out)
        code, out = self.run_check(row.replace("Ljava/lang/String;I)V", "Ljava/lang/String;)V"))
        self.assertEqual(1, code)
        self.assertIn("a constructor (java.lang.String,java.lang.String,java.lang.String) isn't kept by R8", out)

    def testClassRowWithoutConstructor(self):
        code, out = self.run_check(
            'ClassLookup{"io/github/ilyalissoboi/goosestation/core/GameDisc", &s_game_disc_class, nullptr, nullptr},\n'
        )
        self.assertEqual(0, code, out)
        self.assertIn("1 classes and 0 members", out)

    def testRowAcrossLines(self):
        cpp = (
            "ClassLookup{\n"
            '  "io/github/ilyalissoboi/goosestation/core/GameDisc", &s_game_disc_class,\n'
            '  "(Ljava/lang/String;Ljava/lang/String;"\n'
            '  "Ljava/lang/String;I)V",\n'
            "  &s_game_disc_constructor},\n"
            'MethodLookup{"start",\n'
            '             "(JJLjava/lang/String;[BLjava/lang/String;I)V", &s_start},\n'
        )
        code, out = self.run_check(cpp)
        self.assertEqual(0, code, out)
        self.assertIn("1 classes and 2 members", out)

    def testRowFromVariablesSkipped(self):
        cpp = (
            "rows.push_back(MethodLookup{name.c_str(), signature.c_str(), &ids[i]});\n"
            "rows.push_back(ClassLookup{names[i].c_str(), &classes[i], constructor, &ids[i]});\n"
        )
        code, out = self.run_check(cpp)
        self.assertEqual(0, code, out)
        self.assertIn("0 classes and 0 members", out)


if __name__ == "__main__":
    unittest.main()
