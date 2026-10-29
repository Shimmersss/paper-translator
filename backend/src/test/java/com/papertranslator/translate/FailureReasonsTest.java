package com.papertranslator.translate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FailureReasonsTest {

    /** What a chunk failure looks like: runner traceback tail wrapped twice by BabelDocService. */
    private static String describeRunnerFailure(String runnerError) {
        IllegalStateException run = new IllegalStateException("BabelDOC 执行失败: Traceback (most recent call last):\n"
                + "  File \"/home/someone/.paper-translator/app/scripts/babeldoc_runner.py\", line 470, in <module>\n"
                + "RuntimeError: " + runnerError);
        return FailureReasons.describe(new IllegalStateException("BabelDOC 分片翻译失败: " + run.getMessage(), run));
    }

    @Test
    void namesTheRejectedKeyAndTheAffectedPages() {
        String message = describeRunnerFailure("第 1、2 页没有段落翻译成功，已阻止交付（AuthenticationError: Error code: 401 - "
                + "{'error': {'message': 'Invalid API Key', 'type': 'invalid_request_error'}}）");

        assertEquals("第 1、2 页没有任何段落翻译成功，已阻止交付。原因：模型服务拒绝了 API Key（HTTP 401），"
                + "Key 可能无效、已过期或被吊销。请在设置页更换 Key，并用“测试连接”确认", message);
        assertFalse(message.contains("/home/"));
        assertFalse(message.contains("Traceback"));
    }

    @Test
    void explainsProviderErrorsByStatus() {
        assertTrue(describeRunnerFailure("第 3 页没有段落翻译成功，已阻止交付（BadRequestError: Error code: 400 - "
                + "{'error': {'message': 'Unsupported model'}}）").contains("模型可能已下线或名称有误"));
        assertTrue(describeRunnerFailure("第 3 页没有段落翻译成功，已阻止交付（NotFoundError: Error code: 404 - "
                + "{'error': {'message': 'The model `x` does not exist'}}）").contains("HTTP 404"));
        assertTrue(describeRunnerFailure("第 3 页没有段落翻译成功，已阻止交付（RateLimitError: Error code: 429 - "
                + "{'error': {'code': 'insufficient_quota'}}）").contains("余额或额度不足"));
        assertTrue(describeRunnerFailure("第 3 页没有段落翻译成功，已阻止交付（RateLimitError: Error code: 429 - {}）")
                .contains("持续限流（HTTP 429）"));
        assertTrue(describeRunnerFailure("第 3 页没有段落翻译成功，已阻止交付（InternalServerError: Error code: 503 - {}）")
                .contains("暂时不可用（HTTP 503）"));
        assertTrue(describeRunnerFailure("第 3 页没有段落翻译成功，已阻止交付（APIConnectionError: Connection error.）")
                .contains("无法连接模型服务"));
        assertTrue(describeRunnerFailure("第 3 页没有段落翻译成功，已阻止交付（IncompleteTranslationError: "
                + "翻译完整性检查失败，重试后仍无完整译文: empty）").contains("空白或被截断"));
    }

    @Test
    void keepsTheKeptSourceLimitAndOtherBackendReasons() {
        assertEquals("有 5 段翻译失败，超过可交付上限（不超过 3 段且不超过 2%），请重试；"
                        + "如反复出现，可运行 paper-translator logs 查看模型返回的错误",
                FailureReasons.describe(new IllegalStateException(
                        "有 5 段翻译失败，超过可交付上限（不超过 3 段且不超过 2%），请重试")));
        assertTrue(FailureReasons.describe(new IllegalStateException(
                "BabelDOC 处理超时，请缩小页面范围或调大 BABELDOC_TIMEOUT_SECONDS")).contains("处理超时"));
        assertTrue(FailureReasons.describe(new BabelDocService.ResourcePressureException(
                "检测到服务器内存压力，已停止当前翻译进程: memory", 1)).contains("服务器内存不足"));
        assertTrue(FailureReasons.describe(new IllegalStateException("BabelDOC 生成翻译 PDF 失败: "
                + "Cannot run program \"uv\": error=2, No such file or directory")).contains("无法启动翻译进程"));
        assertTrue(FailureReasons.describe(new IllegalStateException(
                "BabelDOC API Key 未配置，请在 .env 中设置 BABELDOC_OPENAI_API_KEY")).contains("还没有配置"));
    }

    @Test
    void unknownErrorsPointToTheLogWithoutLeakingDetails() {
        String message = FailureReasons.describe(new IllegalStateException("BabelDOC 执行失败: KeyError: '/secret/path'"));
        assertEquals("翻译失败（未识别的错误）。详细信息已写入服务日志，可运行 paper-translator logs 查看", message);
    }
}
