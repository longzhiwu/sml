# frozen_string_literal: true

# SML Ruby 绑定测试（§4 黄金向量；三门共用同一份判据）。
#
# 运行：ruby bindings/ruby/test_sml.rb
# 期望：断言通过、退出码 0。

$LOAD_PATH.unshift File.expand_path("lib", __dir__)
require "sml"

# 黄金向量（与 js/sml.mjs 的 parse 同判据）：SML -> 期望宿主对象
SML = <<~SML
  server {
    host: "example.com"
    port: 8080
    tls: false
    tags: [ a b c ]
  }
SML

EXPECTED = {
  "server" => {
    "host" => "example.com",
    "port" => 8080,
    "tls" => false,
    "tags" => ["a", "b", "c"],
  },
}.freeze

def check(name, cond)
  if cond
    puts "  ok: #{name}"
  else
    puts "FAIL: #{name}"
    raise name
  end
end

begin
  # 1) loads == 期望对象（数值是整数、布尔是 bool、数组有序）
  obj = Sml.loads(SML)
  check("loads 黄金向量 == 期望对象", obj == EXPECTED)
  check("port 是整数", obj["server"]["port"].is_a?(Integer))
  check("tls 是 false", obj["server"]["tls"] == false)
  check("tags 是有序数组", obj["server"]["tags"] == ["a", "b", "c"])

  # 2) 往返稳定：dumps(loads(sml)) 再 loads 回来 == 同一对象
  back = Sml.loads(Sml.dumps(obj))
  check("round-trip dumps/loads 稳定", back == EXPECTED)

  # 3) 未闭合块 -> sml_parse 返回 NULL -> 抛 Sml::Error
  raised = false
  begin
    Sml.loads("bad { a: 1")
  rescue Sml::Error
    raised = true
  end
  check("未闭合块 loads 抛 Sml::Error", raised)

  # 4) version() 非空且不崩（证明 sml_version + 不 free 静态串 用对了）
  v = Sml.version
  check("version 非空", v.is_a?(String) && !v.empty?)

  # 内存/稳定：连跑 1000 次 loads，每次都 sml_free_str，无崩溃、无泄漏
  1000.times { Sml.loads(SML) }
  check("连跑 1000 次 loads 不崩", true)

  puts "ALL OK"
rescue StandardError => e
  puts "\nFAILURES: #{e}"
  exit 1
end

exit 0
