# frozen_string_literal: true

# SML — Ruby 宿主绑定（经 C-ABI 调 Rust cdylib，标准库 fiddle，零第三方依赖）。
#
# 只做 FFI + JSON 桥接，不实现任何 SML 语法/契约/include。
#
# ABI 宪法（rust/include/sml.h 的 4 个函数）：
#   void* sml_parse(const char*)  // SML -> JSON；失败返回 NULL
#   void* sml_dump(const char*)   // JSON -> SML；失败返回 NULL
#   void  sml_free_str(void*)     // 释放上面两个（及 sml_features 等）返回的串
#   void* sml_version()           // 版本串（静态，*切勿* free）
#
# 内存铁律（错一次就崩 / 漏一次就漏）：
#   sml_parse / sml_dump 返回的 void* 由 Rust 分配器分配，必须用 sml_free_str 释放。
#   ⚠️ rust/include/sml.h 的注释把「释放串」写成 sml_free，但 cdylib 实际导出的 sml_free
#   释放的是*值树*（*mut CSmlValue），不是 CString —— 拿它释放 JSON 串是未定义行为。
#   正确的释放函数是 sml_free_str（与 rust/tests/c_abi.rs 的测试一致）。
#   sml_version 返回指向编译期常量的静态指针（见 c_abi.rs::version_is_static_and_nonempty），
#   *绝对不能 free*。正确模式 = 先把返回指针读成 String（拷贝），再 free。
#
# 值经 JSON 文本桥接：loads = sml_parse -> JSON 串 -> JSON.parse；
# dumps = JSON.generate -> JSON 串 -> sml_dump -> SML 串。

require "fiddle"
require "fiddle/import"
require "json"

module Sml
  extend Fiddle::Importer

  # 解析失败时抛出（底层 sml_parse / sml_dump 返回 NULL 的那条）。
  class Error < StandardError; end

  @@opened = false

  module_function

  # 把库名/路径解析成可 dlopen 的路径。
  # - 若是已存在的文件，直接返回；
  # - 否则按库名 "sml" 搜索常见构建目录，让 fiddle 能找到 sml.dll / libsml.so / ...
  def resolve_lib(lib)
    return lib if lib && !lib.empty? && File.file?(lib)

    candidates = []
    ct = ENV["CARGO_TARGET_DIR"]
    candidates << File.join(ct, "release") if ct && !ct.empty?
    candidates += [
      "D:/snoware-target/release",
      File.join(Dir.pwd, "rust", "target", "release"),
    ]
    candidates.each do |d|
      next unless d && File.directory?(d)

      %w[sml.dll libsml.dll sml.so libsml.so sml.dylib libsml.dylib].each do |name|
        p = File.join(d, name)
        return p if File.file?(p)
      end
    end
    lib && !lib.empty? ? lib : "sml"
  end

  # 加载库并声明 4 个符号（默认自动找 "sml"；也可显式传路径切后端/测试）。
  def open(lib = "sml")
    return if @@opened

    path = resolve_lib(lib)
    dlload path
    extern "void* sml_parse(const char*)"
    extern "void* sml_dump(const char*)"
    extern "void sml_free_str(void*)"
    extern "void* sml_version()"
    @@opened = true
    nil
  end

  # 读后即释：把 void* 读成 String（拷贝）并立即 sml_free_str 原指针。
  def take(ptr)
    return nil if ptr.null?

    s = ptr.to_s
    s.force_encoding("UTF-8")
    sml_free_str(ptr)
    s
  end

  # SML 文本 -> Ruby 对象（Hash/Array/Integer/Float/true/false/nil/String）。解析失败抛 Error。
  def loads(text)
    raise Error, "sml: null input" if text.nil?

    j = take(sml_parse(text))
    raise Error, "sml: parse failed" unless j

    JSON.parse(j)
  end

  # Ruby 对象 -> SML 文本。序列化失败抛 Error。
  def dumps(obj)
    j = JSON.generate(obj)
    s = take(sml_dump(j))
    raise Error, "sml: dump failed" unless s

    s
  end

  # 底层库版本串。sml_version 返回静态指针，*不* free。
  def version
    p = sml_version
    return "" if p.null?

    p.to_s.force_encoding("UTF-8")
  end
end

# 默认加载一次（找不到库会在使用时抛 LoadError，便于排错）。
begin
  Sml.open
rescue Fiddle::DLError => e
  warn "Sml: 未能自动加载 cdylib（#{e.message}）；请显式 Sml.open('/path/to/sml.dll')"
end
