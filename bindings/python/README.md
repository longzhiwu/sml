# SML — Python 绑定

经 C-ABI 加载 SML 的 Rust cdylib（`sml` / `libsml`），提供 `loads` / `dumps` /
`version`。**不重写解析器**：只做 FFI + JSON 桥接（值经 JSON 文本在宿主与 SML 之间互转）。

## 依赖

- Python 3.8+（标准库 `ctypes` / `json`，零第三方依赖）。

## 用法

```python
from sml import Sml

sml = Sml()                      # 自动找 "sml"（sml.dll / libsml.so / libsml.dylib）
obj = sml.loads("server {\n  host: example.com\n  port: 8080\n}\n")
# -> {"server": {"host": "example.com", "port": 8080}}
txt = sml.dumps({"server": {"port": 8080}})
# -> "server:\n{\n  port: 8080\n}\n"
print(sml.version())             # -> "sml 0.6.2"
```

模块级便捷函数用默认库实例：`from sml import loads, dumps, version`。

### 指定库路径

默认按库名 `sml` 加载（cdylib 产物名就是 `sml`）。也可显式传路径，便于切后端/测试：

```python
sml = Sml("D:/snoware-target/release/sml.dll")   # Windows
sml = Sml("/abs/path/to/libsml.so")              # Linux
```

加载时会把若干常见构建目录（`$CARGO_TARGET_DIR/release`、`D:/snoware-target/release`、
`rust/target/release`）加入本进程 DLL 搜索路径，因此从仓库根目录直接
`python bindings/python/test_sml.py` 一般无需手动设置。

## 内存纪律（最关键）

- `sml_parse` / `sml_dump` 返回的 `char*` 由 **Rust 分配器**分配，**必须用
  `sml_free_str` 释放**；绑定里统一封进 `_take()`（先 `ctypes.cast` 拷成 bytes，
  再 `sml_free_str` 原指针）。**绝不用** Python 的 GC / `ctypes.free` 去释放。
- ⚠️ `rust/include/sml.h` 的注释把「释放串」写成 `sml_free`，但 cdylib 实际导出的
  `sml_free` 释放的是**值树**（`*mut CSmlValue`），不是 CString —— 拿它释放 JSON 串是
  未定义行为。正确、可工作的释放函数是 `sml_free_str`（与 `rust/tests/c_abi.rs`
  的测试一致）。
- `sml_version` 返回指向编译期常量的**静态指针**（见 `c_abi.rs::
  version_is_static_and_nonempty`），**切勿 free**，否则破坏常量区。绑定只读不释放。

> 切到 C 实现后端时，注意 `c/sml_bind.c` 的 `sml_free` 必须调 C 实现自己的释放函数
> （`sml_free_cstr` / `sml_free_str`），与 Rust 后端各自的分配器配套——这正是
> 「helper 封装读后即释」的意义。

## 错误处理

`sml_parse` / `sml_dump` 失败只返回 `NULL`（本轮不暴露错误码/文案，见 TASK 范围）。
绑定把它们转成 `sml.SmlError`：

```python
from sml import Sml, SmlError
try:
    Sml().loads("bad { a: 1")      # 未闭合块 -> cdylib 返回 NULL
except SmlError as e:
    print("解析失败:", e)
```

## 测试

```bash
python bindings/python/test_sml.py     # 期望：ALL OK，退出码 0
```

覆盖：黄金向量解析、dumps/loads 往返稳定、未闭合块抛异常、`version()` 非空不崩、
连跑 1000 次 `loads` 内存不崩。
