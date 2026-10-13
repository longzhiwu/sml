"""SML — Python 宿主绑定（经 C-ABI 调 Rust cdylib）。

只做 FFI + JSON 桥接，不实现任何 SML 语法/契约/include。

ABI 宪法（rust/include/sml.h 的 4 个函数）：
    char *sml_parse(const char *text);  // SML -> JSON；失败返回 NULL
    char *sml_dump(const char *json);   // JSON -> SML；失败返回 NULL
    void  sml_free_str(char *p);        // 释放上面两个（及 sml_features 等）返回的串
    char *sml_version(void);            // 版本串（静态，*切勿* free）

内存铁律（错一次就崩 / 漏一次就漏）：
    sml_parse / sml_dump 返回的 char* 由 Rust 分配器分配，必须用 sml_free_str 释放。
    ⚠️ 注意：rust/include/sml.h 的注释把「释放串」写成 sml_free，但 cdylib 实际导出
    的 sml_free 释放的是*值树*（*mut CSmlValue），不是 CString —— 拿它去 free JSON
    串是未定义行为。正确、可工作的释放函数是 sml_free_str（与 c_abi.rs 的测试一致）。
    sml_version 返回指向编译期常量的静态指针（见 c_abi.rs::version_is_static_and_nonempty），
    **绝对不能 free**，否则破坏常量区。正确模式 = 先把返回指针读成 bytes（拷贝），再 free。

值经 JSON 文本桥接：loads = sml_parse -> JSON 串 -> json.loads；
dumps = json.dumps -> JSON 串 -> sml_dump -> SML 串。
"""

import ctypes
import json
import os

__all__ = ["Sml", "SmlError", "loads", "dumps", "version"]


class SmlError(Exception):
    """解析/序列化失败（底层 sml_parse / sml_dump 返回 NULL 时抛出）。"""


def _resolve_lib(lib):
    """把库名/路径解析成可加载对象。

    - 若 lib 是已存在的文件路径，直接返回该路径串；
    - 否则当作库名（如 "sml"），并把若干常见构建目录加入本进程的 DLL 搜索路径，
      让 ctypes.CDLL("sml") 能在 Windows 上找到 sml.dll（cdylib 产物名即 sml）。
    """
    if lib and os.path.sep in lib and os.path.isfile(lib):
        return lib

    # 候选目录：CARGO_TARGET_DIR/release、常见相对/绝对构建目录。
    candidates = []
    ct = os.environ.get("CARGO_TARGET_DIR")
    if ct:
        candidates.append(os.path.join(ct, "release"))
    candidates += [
        "D:/snoware-target/release",
        os.path.join(os.getcwd(), "rust", "target", "release"),
        os.path.join(os.getcwd(), "..", "rust", "target", "release"),
    ]
    for d in candidates:
        if d and os.path.isdir(d):
            try:
                os.add_dll_directory(d)  # Python 3.8+；失败（如已加过）忽略
            except Exception:
                pass
    return lib  # 库名，交给 ctypes 按平台规则找 sml.dll / libsml.so / libsml.dylib


class Sml:
    """加载 SML cdylib 并提供 loads/dumps/version。

    例：
        from sml import Sml
        sml = Sml()                 # 自动找 "sml"（sml.dll / libsml.so / ...）
        obj = sml.loads("a: 1\\n") # -> {"a": 1}
        txt = sml.dumps({"a": 1})  # -> "a: 1\\n"
        print(sml.version())       # -> "sml x.y.z"
    """

    def __init__(self, lib="sml"):
        self._lib = ctypes.CDLL(_resolve_lib(lib))

        # 保留指针以便释放：restype 用 c_void_p，不能用 c_char_p（那样拿不到原指针）
        self._lib.sml_parse.restype = ctypes.c_void_p
        self._lib.sml_parse.argtypes = [ctypes.c_char_p]
        self._lib.sml_dump.restype = ctypes.c_void_p
        self._lib.sml_dump.argtypes = [ctypes.c_char_p]
        self._lib.sml_free_str.argtypes = [ctypes.c_void_p]
        self._lib.sml_version.restype = ctypes.c_void_p

    @staticmethod
    def _take(lib, p):
        """读后即释：把 char* 拷成 bytes 并立即 sml_free_str 原指针。"""
        if not p:
            return None
        s = ctypes.cast(p, ctypes.c_char_p).value  # 拷贝成 bytes
        lib.sml_free_str(p)                          # 立即释放 Rust 分配
        return s

    def loads(self, text):
        """SML 文本 -> Python 对象（dict/list/int/float/bool/None/str）。解析失败抛 SmlError。"""
        if text is None:
            raise SmlError("sml: null input")
        p = self._lib.sml_parse(text.encode("utf-8"))
        j = Sml._take(self._lib, p)
        if j is None:
            raise SmlError("sml: parse failed")
        return json.loads(j.decode("utf-8"))

    def dumps(self, obj):
        """Python 对象 -> SML 文本。序列化失败抛 SmlError。"""
        j = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        p = self._lib.sml_dump(j)
        s = Sml._take(self._lib, p)
        if s is None:
            raise SmlError("sml: dump failed")
        return s.decode("utf-8")

    def version(self):
        """底层库版本串。sml_version 返回静态指针，*不* free。"""
        p = self._lib.sml_version()
        if not p:
            return ""
        return ctypes.cast(p, ctypes.c_char_p).value.decode("utf-8")


# 模块级便捷函数（用默认库实例）
_default = None


def _default_inst():
    global _default
    if _default is None:
        _default = Sml()
    return _default


def loads(text):
    return _default_inst().loads(text)


def dumps(obj):
    return _default_inst().dumps(obj)


def version():
    return _default_inst().version()
