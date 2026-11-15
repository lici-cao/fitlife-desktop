package com.fitlife.service;

import com.fitlife.service.LlmGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P3 意图路由测试：规则路由（离线可靠路径）+ LLM 不可用时的降级。
 */
class IntentRouterTest {

    private IntentRouter router;

    @BeforeEach
    void setUp() {
        LlmGateway gateway = mock(LlmGateway.class);
        // 无可用模型：强制走规则路由（与验收的离线环境一致）
        when(gateway.hasAvailableModel()).thenReturn(false);
        router = new IntentRouter(gateway);
    }

    @Test
    @DisplayName("生成意图：重新生成/生成计划")
    void generateIntent() {
        assertEquals(IntentRouter.GENERATE,
                router.route(1L, "帮我重新生成一份计划", null, true).intent());
        assertEquals(IntentRouter.GENERATE,
                router.route(1L, "给我生成一周减脂计划", null, false).intent());
    }

    @Test
    @DisplayName("替换意图：把周三的深蹲换成臀桥")
    void replaceIntent() {
        IntentRouter.RouteResult r = router.route(1L, "把周三的深蹲换成臀桥", null, true);
        assertEquals(IntentRouter.REVISE, r.intent());
        assertEquals(IntentRouter.OP_REPLACE, r.operation());
        assertNotNull(r.exerciseName());
        assertTrue(r.exerciseName().contains("深蹲"));
        assertEquals("臀桥", r.replaceHint());
        assertFalse(r.usedLlm());
    }

    @Test
    @DisplayName("替换目标名要去掉星期前缀（定位串污染回归）")
    void replaceIntentStripsDayPrefix() {
        IntentRouter.RouteResult r = router.route(1L, "把周一的俯卧撑换成跪姿俯卧撑", null, true);
        assertEquals(IntentRouter.REVISE, r.intent());
        assertEquals(IntentRouter.OP_REPLACE, r.operation());
        assertEquals("俯卧撑", r.exerciseName());
        assertEquals("跪姿俯卧撑", r.replaceHint());
    }

    @Test
    @DisplayName("移除意图：去掉深蹲")
    void removeIntent() {
        IntentRouter.RouteResult r = router.route(1L, "去掉深蹲", null, true);
        assertEquals(IntentRouter.REVISE, r.intent());
        assertEquals(IntentRouter.OP_REMOVE, r.operation());
        assertNotNull(r.exerciseName());
        assertTrue(r.exerciseName().contains("深蹲"));
    }

    @Test
    @DisplayName("强度意图：强度降一点/太累了")
    void adjustRpeIntent() {
        IntentRouter.RouteResult r = router.route(1L, "太累了，强度降一点", null, true);
        assertEquals(IntentRouter.REVISE, r.intent());
        assertEquals(IntentRouter.OP_ADJUST_RPE, r.operation());
    }

    @Test
    @DisplayName("查询意图：为什么这么排")
    void queryIntent() {
        assertEquals(IntentRouter.QUERY,
                router.route(1L, "为什么这么安排训练", null, true).intent());
    }

    @Test
    @DisplayName("闲聊意图：今天天气不错")
    void chatIntent() {
        assertEquals(IntentRouter.CHAT,
                router.route(1L, "今天天气不错", null, true).intent());
    }

    @Test
    @DisplayName("无生效计划时修订请求不进 REVISE（规则层）")
    void reviseNeedsActivePlan() {
        // hasActivePlan=false：规则层不会给出 REVISE（LLM 层已有升级 GENERATE 逻辑）
        IntentRouter.RouteResult r = router.route(1L, "把深蹲换成臀桥", null, false);
        assertNotEquals(IntentRouter.REVISE, r.intent());
    }

    @Test
    @DisplayName("空消息安全")
    void blankMessage() {
        assertEquals(IntentRouter.CHAT, router.route(1L, "  ", null, true).intent());
    }
}
