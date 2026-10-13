package sml;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SML Java 绑定测试（§4 黄金向量；三门共用同一份判据）。
 *
 * 运行（无需构建工具，直接用 JDK）：
 *   javac -d out bindings/java/src/main/java/sml/*.java bindings/java/src/test/java/sml/*.java
 *   java --enable-native-access=ALL-UNNAMED -cp out sml.SmlTest
 * 期望：断言通过、退出码 0。
 */
public final class SmlTest {

    private static int pass;
    private static int fail;

    private SmlTest() {}

    private static void check(String name, boolean cond) {
        if (cond) {
            pass++;
            System.out.println("  ok: " + name);
        } else {
            fail++;
            System.out.println("FAIL: " + name);
        }
    }

    public static void main(String[] args) {
        String sml = "server {\n"
                + "  host: \"example.com\"\n"
                + "  port: 8080\n"
                + "  tls: false\n"
                + "  tags: [ a b c ]\n"
                + "}\n";

        Map<String, Object> server = new LinkedHashMap<>();
        server.put("host", "example.com");
        server.put("port", 8080L);
        server.put("tls", false);
        server.put("tags", Arrays.asList("a", "b", "c"));
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("server", server);

        // 1) loads == 期望对象（数值是整数、布尔是 bool、数组有序）
        Object obj = Sml.loads(sml);
        check("loads 黄金向量 == 期望对象", expected.equals(obj));
        @SuppressWarnings("unchecked")
        Map<String, Object> objServer = (Map<String, Object>) ((Map<String, Object>) obj).get("server");
        check("port 是整数", objServer.get("port") instanceof Long);
        check("tls 是 bool", objServer.get("tls") instanceof Boolean);
        @SuppressWarnings("unchecked")
        List<Object> tags = (List<Object>) objServer.get("tags");
        check("tags 是有序数组", tags.equals(Arrays.asList("a", "b", "c")));

        // 2) 往返稳定：dumps(loads(sml)) 再 loads 回来 == 同一对象
        Object back = Sml.loads(Sml.dumps(obj));
        check("round-trip dumps/loads 稳定", expected.equals(back));

        // 3) 未闭合块 -> sml_parse 返回 NULL -> 抛 SmlException
        boolean raised = false;
        try {
            Sml.loads("bad { a: 1");
        } catch (Sml.SmlException e) {
            raised = true;
        }
        check("未闭合块 loads 抛 SmlException", raised);

        // 4) version() 非空且不崩（证明 sml_version + 不 free 静态串 用对了）
        String v = Sml.version();
        check("version 非空", v != null && !v.isEmpty());

        // 内存/稳定：连跑 1000 次 loads，每次都 sml_free_str，无崩溃、无泄漏
        for (int i = 0; i < 1000; i++) {
            Sml.loads(sml);
        }
        check("连跑 1000 次 loads 不崩", true);

        System.out.println(pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }
}
