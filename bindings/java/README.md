# SML — Java 绑定

经 C-ABI 加载 SML 的 Rust cdylib（`sml` / `libsml`），提供 `Sml.loads` /
`Sml.dumps` / `Sml.version`。**不重写解析器**：只做 FFI + JSON 桥接
（值经 JSON 文本在宿主与 SML 之间互转）。

## 依赖

- JDK 22+（用标准库 **Panama FFM** `java.lang.foreign`，零第三方依赖；
  若机器装有 JNA 也可用 JNA，模式不变）。本实现用 FFM，无需下载任何 jar。

## 构建 / 运行测试（无需 Maven/Gradle）

```bash
javac -d out \
  bindings/java/src/main/java/sml/*.java \
  bindings/java/src/test/java/sml/*.java
java --enable-native-access=ALL-UNNAMED -cp out sml.SmlTest
# 期望：8 passed, 0 failed，退出码 0
```

> `--enable-native-access=ALL-UNNAMED` 是 JDK 22+ 对 FFM 调用本机函数的硬性要求
> （无名模块下）。

## 用法

```java
import sml.Sml;

Object obj = Sml.loads("server {\n  host: example.com\n  port: 8080\n}\n");
// -> Map{server=Map{host="example.com", port=8080L}}
String txt = Sml.dumps(Map.of("server", Map.of("port", 8080L)));
// -> "server:\n{\n  port: 8080\n}\n"
System.out.println(Sml.version());   // -> "sml 0.6.2"
```

宿主对象用标准容器表示：对象 `Map<String,Object>`、数组 `List<Object>`、
字符串 `String`、整数 `Long`、浮点 `Double`、布尔 `Boolean`、null。
JSON 编解码是绑定自带的极简实现（`sml.Json`，零依赖），覆盖 SML 产出的 JSON 形态。

### 指定库路径

默认按库名 `sml` 加载（cdylib 产物名就是 `sml`）。也可经环境变量 `SML_LIB`
或（在 `Sml.resolveLib` 里）显式传绝对路径切后端/测试：

```bash
SML_LIB=D:/snoware-target/release/sml.dll java --enable-native-access=ALL-UNNAMED -cp out sml.SmlTest
```

加载时会把若干常见构建目录（`$CARGO_TARGET_DIR/release`、`D:/snoware-target/release`、
`rust/target/release`）纳入搜索范围。

## 内存纪律（最关键）

- `sml_parse` / `sml_dump` 返回的 `void*` 由 **Rust 分配器**分配，**必须用
  `sml_free_str` 释放**；绑定里统一封进 `take()`（先 `reinterpret` 出可读取边界、读成
  Java String 拷贝，再 `sml_free_str` 原指针）。**绝不用** Java 的 GC 去释放。
- ⚠️ `rust/include/sml.h` 的注释把「释放串」写成 `sml_free`，但 cdylib 实际导出的
  `sml_free` 释放的是**值树**（`*mut CSmlValue`），不是 CString —— 拿它释放 JSON 串是
  未定义行为。正确、可工作的释放函数是 `sml_free_str`（与 `rust/tests/c_abi.rs`
  的测试一致）。
- `sml_version` 返回指向编译期常量的**静态指针**（见 `c_abi.rs::
  version_is_static_and_nonempty`），**切勿 free**，否则破坏常量区。绑定只读不释放。

> 切到 C 实现后端时，`c/sml_bind.c` 的 `sml_free_str` 必须调 C 实现自己的释放函数
> （`sml_free_cstr`），与 Rust 后端各自的分配器配套。

## 错误处理

`sml_parse` / `sml_dump` 失败只返回 `NULL`（本轮不暴露错误码/文案）。绑定把它们转成
`Sml.SmlException`：

```java
try {
    Sml.loads("bad { a: 1");      // 未闭合块 -> cdylib 返回 NULL
} catch (Sml.SmlException e) {
    System.out.println("解析失败: " + e.getMessage());
}
```

## 测试

```bash
java --enable-native-access=ALL-UNNAMED -cp out sml.SmlTest     # 期望：8 passed
```

覆盖：黄金向量解析、dumps/loads 往返稳定、未闭合块抛异常、`version()` 非空不崩、
连跑 1000 次 `loads` 内存不崩。
