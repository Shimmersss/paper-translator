package com.papertranslator.translate;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a failed task's exception chain into a specific message that is safe to
 * show. The chain carries the BabelDOC output tail (tracebacks, paths), so the
 * message is built from fixed sentences plus page numbers, counts and HTTP
 * status codes; nothing else from the raw text is copied.
 */
final class FailureReasons {
    private static final Pattern NO_PAGE_TRANSLATED = Pattern.compile("第 ([\\d、]+) 页没有段落翻译成功");
    private static final Pattern KEPT_SOURCE_LIMIT = Pattern.compile("有 (\\d+) 段翻译失败，超过可交付上限（([^）\\n]{1,40})）");
    // openai-python: "Error code: 401 - {...}"; httpx: "status_code=503".
    private static final Pattern MODEL_MISSING = Pattern.compile("model[^\\n]{0,80}(?:does not exist|not found)");
    private static final Pattern HTTP_STATUS = Pattern.compile("(?:error code|status_code|status)\\s*[:=]?\\s*([1-5]\\d\\d)\\b");

    private FailureReasons() {
    }

    static String describe(Throwable error) {
        String text = chainText(error);
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("mupdf error") || lower.contains("cannot save with zero pages")
                || lower.contains("cannot parse object")) {
            return "PDF 文件无法解析（文件可能损坏或加密），请更换文件后重试";
        }
        String scope = scope(text);
        String cause = cause(lower);
        if (scope != null && cause != null) return scope + "。原因：" + cause;
        if (cause != null) return "翻译失败：" + cause;
        if (scope != null) return scope + "，请重试；如反复出现，可运行 paper-translator logs 查看模型返回的错误";
        return "翻译失败（未识别的错误）。详细信息已写入服务日志，可运行 paper-translator logs 查看";
    }

    /** Which part of the document failed, when the runner or backend reported it. */
    private static String scope(String text) {
        Matcher pages = NO_PAGE_TRANSLATED.matcher(text);
        if (pages.find()) return "第 " + pages.group(1) + " 页没有任何段落翻译成功，已阻止交付";
        Matcher limit = KEPT_SOURCE_LIMIT.matcher(text);
        if (limit.find()) {
            return "有 " + limit.group(1) + " 段翻译失败，超过可交付上限（" + limit.group(2) + "）";
        }
        if (text.contains("无法定位页码")) return "有段落翻译失败且无法定位页码，已阻止交付";
        return null;
    }

    private static String cause(String lower) {
        if (lower.contains("api key 未配置")) {
            return "还没有配置翻译模型的 API Key，请在设置页填写";
        }
        if (lower.contains("babeldoc 未启用")) {
            return "BabelDOC 未启用，请设置 BABELDOC_ENABLED=true";
        }
        if (lower.contains("页面范围无效")) {
            return "页面范围无效，请重新选择起止页";
        }
        Integer status = httpStatus(lower);
        if (has(lower, "insufficient_quota", "insufficient balance", "exceeded your current quota", "余额不足")
                || (status != null && status == 402)) {
            return "模型服务账户余额或额度不足，请充值或更换 API Key";
        }
        if (has(lower, "authenticationerror", "invalid api key", "invalid_api_key", "incorrect api key")
                || (status != null && status == 401)) {
            return "模型服务拒绝了 API Key（HTTP 401），Key 可能无效、已过期或被吊销。"
                    + "请在设置页更换 Key，并用“测试连接”确认";
        }
        if (has(lower, "permissiondeniederror") || (status != null && status == 403)) {
            return "模型服务拒绝访问（HTTP 403），账号可能没有该模型的权限或已被限制";
        }
        if (has(lower, "notfounderror", "model_not_found", "unsupported model") || MODEL_MISSING.matcher(lower).find()
                || (status != null && status == 404)) {
            return "模型服务找不到该模型（" + (status == null ? "模型名无效" : "HTTP " + status)
                    + "），模型可能已下线或名称有误，请在设置页检查模型名称";
        }
        if (has(lower, "ratelimiterror") || (status != null && status == 429)) {
            return "模型服务持续限流（HTTP 429），多次重试后仍未成功。请稍后再试，或在设置页调低并发";
        }
        if (has(lower, "internalservererror", "service unavailable", "bad gateway")
                || (status != null && status >= 500)) {
            return "模型服务暂时不可用（HTTP " + (status != null && status >= 500 ? status : "5xx") + "），请稍后重试";
        }
        if (status != null && status == 400) {
            return "模型服务拒绝了请求（HTTP 400），请检查接口地址与模型是否匹配，可用设置页的“测试连接”确认";
        }
        if (has(lower, "apiconnectionerror", "apitimeouterror", "connection error", "connection reset",
                "connection refused", "connecterror", "readtimeout", "request timed out",
                "nodename nor servname", "name or service not known", "sslerror", "ssl_connect",
                "certificate verify failed")) {
            return "无法连接模型服务（网络错误或请求超时），请检查接口地址、代理和网络";
        }
        if (has(lower, "翻译完整性检查失败", "incompletetranslationerror")) {
            return "模型多次返回空白或被截断的译文（已逐步加大输出额度重试），可换用其他模型或稍后重试";
        }
        if (has(lower, "处理超时")) {
            return "处理超时，超过了单个任务的时限。请缩小页面范围，或在设置页调大超时时间";
        }
        if (has(lower, "内存压力", "等待内存恢复超时")) {
            return "服务器内存不足：已自动降低并发并改为逐页翻译，仍无法完成。"
                    + "请关闭其他占用内存的程序、缩小页面范围，或稍后重试";
        }
        if (has(lower, "o200k", "tiktoken")) {
            return "分词表加载失败（BabelDOC 需要 o200k_base），请运行 paper-translator doctor 检查安装是否完整";
        }
        if (has(lower, "cannot run program", "error=2,", "jspawnhelper", "incorrect java version")) {
            return "无法启动翻译进程：Python/uv 不可用，或 Java 在服务运行期间被升级。"
                    + "请运行 paper-translator doctor 检查，必要时重启服务";
        }
        if (has(lower, "no paragraphs")) {
            return "所选页面没有可翻译的文字（可能是扫描件且 OCR 未识别出文字），请检查 Tesseract 是否安装";
        }
        if (has(lower, "分片结果不完整")) {
            return "BabelDOC 没有生成完整的结果文件，请重试";
        }
        return null;
    }

    private static Integer httpStatus(String lower) {
        Matcher matcher = HTTP_STATUS.matcher(lower);
        return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
    }

    private static boolean has(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) return true;
        }
        return false;
    }

    private static String chainText(Throwable error) {
        StringBuilder text = new StringBuilder();
        for (Throwable current = error; current != null && text.length() < 16_384; current = current.getCause()) {
            text.append(current.getClass().getSimpleName()).append(": ")
                    .append(current.getMessage() == null ? "" : current.getMessage()).append('\n');
            if (current.getCause() == current) break;
        }
        return text.toString();
    }
}
