# SML — Ruby 绑定

经 C-ABI 加载 SML 的 Rust cdylib（`sml` / `libsml`），提供 `Sml.loads` /
`Sml.dumps` / `Sml.version`。**不重写解析器**：只做 FFI + JSON 桥接
（值经 JSON 文本在宿主与 SML 之间互转）。

## 依赖

- Ruby 3.x，标准库 `fiddle`（零第三方依赖；若机器装有 `ffi` gem 也可改用，模式不变）。

## 用法

```ruby
require_relative "lib/sml"   # 或把 lib/ 加入 $LOAD_PATH 后 require "sml"

Sml.loads("server {\n  host: example.com\n  port: 8080\n}\n")
# => {"server"=>{"host"=>"example.com", "port"=>8080}}
Sml.dumps("server"=>{"port"=>8080})
# => "server:\n{\n  port: 8080\n}\n"
Sml.version   # => "sml 0.6.2"
```

### 指定库路径

默认按库名 `sml` 加载（cdylib 产物名就是 `sml`）。也可显式传路径，便于切后端/测试：

```ruby
Sml.open("D:/snoware-target/release/sml.dll")   # Windows
Sml.open("/abs/path/to/libsml.so")              # Linux
```

加载时会把若干常见构建目录（`$CARGO_TARGET_DIR/release`、`D:/snoware-target/release`、
`rust/target/release`）纳入搜索范围，因此从仓库根目录直接
`ruby bindings/ruby/test_sml.rb` 一般无需手动设置。

## 内存纪律（最关键）

- `sml_parse` / `sml_dump` 返回的 `void*` 由 **Rust 分配器**分配，**必须用
  `sml_free_str` 释放**；绑定里统一封进 `take()`（先 `read_string` 拷成 Ruby String，
  再 `sml_free_str` 原指针）。**绝不用** Ruby 的 GC / `Fiddle::Pointer#free` 去释放。
- ⚠️ `rust/include/sml.h` 的注释把「释放串」写成 `sml_free`，但 cdylib 实际导出的
  `sml_free` 释放的是**值树**（`*mut CSmlValue`），不是 CString —— 拿它释放 JSON 串是
  未定义行为。正确、可工作的释放函数是 `sml_free_str`（与 `rust/tests/c_abi.rs`
  的测试一致）。
- `sml_version` 返回指向编译期常量的**静态指针**（见 `c_abi.rs::
  version_is_static_and_nonempty`），**切勿 free**，否则破坏常量区。绑定只读不释放。

> 切到 C 实现后端时，`c/sml_bind.c` 的 `sml_free` 必须调 C 实现自己的释放函数
> （`sml_free_cstr` / `sml_free_str`），与 Rust 后端各自的分配器配套。

## 错误处理

`sml_parse` / `sml_dump` 失败只返回 `NULL`（本轮不暴露错误码/文案）。绑定把它们转成
`Sml::Error`：

```ruby
begin
  Sml.loads("bad { a: 1")     # 未闭合块 -> cdylib 返回 NULL
rescue Sml::Error => e
  puts "解析失败: #{e}"
end
```

## 测试

```bash
ruby bindings/ruby/test_sml.rb     # 期望：ALL OK，退出码 0
```

覆盖：黄金向量解析、dumps/loads 往返稳定、未闭合块抛异常、`version()` 非空不崩、
连跑 1000 次 `loads` 内存不崩。
