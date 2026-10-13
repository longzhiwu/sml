"""SML Python 绑定测试（§4 黄金向量；三门共用同一份判据）。

运行：python bindings/python/test_sml.py
期望：4 条断言过、退出码 0。
"""

import os
import sys

# 让 `from sml import Sml` 能找到本目录的包（无论从哪 cwd 跑）。
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from sml import Sml, SmlError  # noqa: E402


# 黄金向量（与 js/sml.mjs 的 parse 同判据）：SML -> 期望宿主对象
SML = """server {
  host: "example.com"
  port: 8080
  tls: false
  tags: [ a b c ]
}
"""

EXPECTED = {
    "server": {
        "host": "example.com",
        "port": 8080,
        "tls": False,
        "tags": ["a", "b", "c"],
    }
}


def check(name, cond):
    if cond:
        print(f"  ok: {name}")
    else:
        print(f"FAIL: {name}")
        raise AssertionError(name)


def main():
    sml = Sml()

    # 1) loads == 期望对象（数值是整数、布尔是 bool、数组有序）
    obj = sml.loads(SML)
    check("loads 黄金向量 == 期望对象", obj == EXPECTED)
    check("port 是整数", isinstance(obj["server"]["port"], int))
    check("tls 是 bool", isinstance(obj["server"]["tls"], bool))
    check("tags 是有序数组", obj["server"]["tags"] == ["a", "b", "c"])

    # 2) 往返稳定：dumps(loads(sml)) 再 loads 回来 == 同一对象
    back = sml.loads(sml.dumps(obj))
    check("round-trip dumps/loads 稳定", back == EXPECTED)

    # 3) 未闭合块 -> sml_parse 返回 NULL -> 抛 SmlError
    raised = False
    try:
        sml.loads("bad { a: 1")
    except SmlError:
        raised = True
    check("未闭合块 loads 抛 SmlError", raised)

    # 4) version() 非空且不崩（证明 sml_version + 不 free 静态串 用对了）
    v = sml.version()
    check("version 非空", isinstance(v, str) and len(v) > 0)

    # 内存/稳定：连跑 1000 次 loads，每次都 sml_free_str，无崩溃、无泄漏
    for _ in range(1000):
        sml.loads(SML)
    check("连跑 1000 次 loads 不崩", True)

    print("ALL OK")


if __name__ == "__main__":
    try:
        main()
    except AssertionError as e:
        print(f"\nFAILURES: {e}")
        sys.exit(1)
    sys.exit(0)
