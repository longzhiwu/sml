package sml;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * SML — Java 宿主绑定（经 C-ABI 调 Rust cdylib，使用 JDK Panama FFM，零第三方依赖）。
 *
 * 只做 FFI + JSON 桥接，不实现任何 SML 语法/契约/include。
 *
 * ABI 宪法（rust/include/sml.h 的 4 个函数）：
 *   void* sml_parse(const char*)  // SML -> JSON；失败返回 NULL
 *   void* sml_dump(const char*)   // JSON -> SML；失败返回 NULL
 *   void  sml_free_str(void*)     // 释放上面两个（及 sml_features 等）返回的串
 *   void* sml_version()           // 版本串（静态，*切勿* free）
 *
 * 内存铁律（错一次就崩 / 漏一次就漏）：
 *   sml_parse / sml_dump 返回的 void* 由 Rust 分配器分配，必须用 sml_free_str 释放。
 *   ⚠️ rust/include/sml.h 的注释把「释放串」写成 sml_free，但 cdylib 实际导出的 sml_free
 *   释放的是*值树*（*mut CSmlValue），不是 CString —— 拿它释放 JSON 串是未定义行为。
 *   正确的释放函数是 sml_free_str（与 rust/tests/c_abi.rs 的测试一致）。
 *   sml_version 返回指向编译期常量的静态指针（见 c_abi.rs::version_is_static_and_nonempty），
 *   *绝对不能 free*。正确模式 = 先把返回指针读成 Java String（拷贝），再 free。
 *
 * 值经 JSON 文本桥接：loads = sml_parse -> JSON 串 -> Json.parse；
 * dumps = Json.stringify -> JSON 串 -> sml_dump -> SML 串。
 */
public final class Sml {

    /** 解析/序列化失败（底层 sml_parse / sml_dump 返回 NULL 的那条）。 */
    public static final class SmlException extends RuntimeException {
        public SmlException(String message) {
            super(message);
        }
    }

    private static final Linker LINKER = Linker.nativeLinker();
    private static final Arena LIB_ARENA = Arena.ofAuto();
    private static final SymbolLookup LIB = SymbolLookup.libraryLookup(resolveLib(), LIB_ARENA);

    private static final FunctionDescriptor PARSE_FD = FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS);
    private static final FunctionDescriptor DUMP_FD = FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS);
    private static final FunctionDescriptor FREE_FD = FunctionDescriptor.ofVoid(ValueLayout.ADDRESS);
    private static final FunctionDescriptor VERSION_FD = FunctionDescriptor.of(ValueLayout.ADDRESS);

    private static final java.lang.invoke.MethodHandle SML_PARSE =
            LINKER.downcallHandle(LIB.find("sml_parse").orElseThrow(), PARSE_FD);
    private static final java.lang.invoke.MethodHandle SML_DUMP =
            LINKER.downcallHandle(LIB.find("sml_dump").orElseThrow(), DUMP_FD);
    private static final java.lang.invoke.MethodHandle SML_FREE =
            LINKER.downcallHandle(LIB.find("sml_free_str").orElseThrow(), FREE_FD);
    private static final java.lang.invoke.MethodHandle SML_VERSION =
            LINKER.downcallHandle(LIB.find("sml_version").orElseThrow(), VERSION_FD);

    private Sml() {}

    /** 把库名/路径解析成可加载的绝对路径。 */
    private static String resolveLib() {
        String env = System.getenv("SML_LIB");
        if (env != null && !env.isEmpty() && Files.exists(Path.of(env))) {
            return Path.of(env).toAbsolutePath().toString();
        }
        String[] names = {"sml.dll", "libsml.dll", "sml.so", "libsml.so", "sml.dylib", "libsml.dylib"};
        String[] dirs = {
                System.getenv("CARGO_TARGET_DIR") != null
                        ? System.getenv("CARGO_TARGET_DIR") + "/release" : null,
                "D:/snoware-target/release",
                "rust/target/release",
                "../rust/target/release",
        };
        for (String d : dirs) {
            if (d == null) continue;
            for (String n : names) {
                Path p = Path.of(d, n);
                if (Files.exists(p)) return p.toAbsolutePath().toString();
            }
        }
        return "sml"; // 退回库名，交给系统加载器按平台规则找
    }

    /** 读 void* 指向的 NUL 结尾 C 字符串 -> Java String（UTF-8），并 sml_free_str 原指针。 */
    private static String take(MemorySegment p) {
        String s = readCString(p);
        if (s == null) return null;
        try {
            SML_FREE.invokeExact(p);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
        return s;
    }

    /** 读无空间边界的返回指针指向的 NUL 结尾 C 字符串 -> Java String（UTF-8）。
     *  返回的段 byteSize=0，必须先 reinterpret 一个边界才能按索引读取；
     *  逐字节扫描 NUL 并按需把边界翻倍扩大（sml_parse/sml_version 的输出必以 NUL 结尾）。 */
    private static String readCString(MemorySegment p) {
        if (p == null || p.equals(MemorySegment.NULL)) return null;
        long cap = 64;
        MemorySegment seg = p.reinterpret(cap);
        long len = 0;
        while (true) {
            if (len >= cap) {
                cap <<= 1;
                seg = p.reinterpret(cap);
            }
            if (seg.get(ValueLayout.JAVA_BYTE, len) == 0) break;
            len++;
        }
        byte[] bytes = new byte[(int) len];
        MemorySegment.copy(seg, ValueLayout.JAVA_BYTE, 0L, bytes, 0, (int) len);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** SML 文本 -> Java 对象（Map/List/String/Long/Double/Boolean/null）。解析失败抛 SmlException。 */
    public static Object loads(String text) {
        if (text == null) throw new SmlException("sml: null input");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment ctext = arena.allocateFrom(text, StandardCharsets.UTF_8);
            MemorySegment p;
            try {
                p = (MemorySegment) SML_PARSE.invokeExact(ctext);
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
            String json = take(p);
            if (json == null) throw new SmlException("sml: parse failed");
            return Json.parse(json);
        }
    }

    /** Java 对象 -> SML 文本。序列化失败抛 SmlException。 */
    public static String dumps(Object obj) {
        String json = Json.stringify(obj);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment cjson = arena.allocateFrom(json, StandardCharsets.UTF_8);
            MemorySegment p;
            try {
                p = (MemorySegment) SML_DUMP.invokeExact(cjson);
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
            String s = take(p);
            if (s == null) throw new SmlException("sml: dump failed");
            return s;
        }
    }

    /** 底层库版本串。sml_version 返回静态指针，*不* free。 */
    public static String version() {
        MemorySegment p;
        try {
            p = (MemorySegment) SML_VERSION.invokeExact();
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
        String s = readCString(p);
        return s == null ? "" : s; // 静态指针，不 free
    }
}
