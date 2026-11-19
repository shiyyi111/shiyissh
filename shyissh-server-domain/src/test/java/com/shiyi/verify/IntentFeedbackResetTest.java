package com.shiyi.verify;

import com.shiyi.domain.agent.service.intent.ContextTracker;
import com.shiyi.domain.agent.service.intent.IntentService;
import org.junit.Test;

import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;

/**
 * 连续失败计数器的重置语义（回归测试）。
 * <p>
 * 背景：{@code consecutiveFailures} 的唯一读取点是 {@code IntentService.classify()}，
 * 达到阈值后该会话后续请求会跳过规则层。如果成功时不重置，一次失败就会让这个会话
 * 永久跳过规则层——因为计数只增不减、而 {@code [intent]} 是唯一读取点。
 * <p>
 * 这里只验证计数器的读写契约，不涉及分类器本身（两个 classifier 在测试里为 null，
 * 但 {@code reportFeedback} 在成功分支最早返回，不会走到它们）。
 *
 * @author shiyi
 */
public class IntentFeedbackResetTest {

    /** 手工装配：{@code IntentService} 依赖用字段注入，测试里用反射塞一个真实的 ContextTracker。 */
    private IntentService serviceWith(ContextTracker tracker) throws Exception {
        IntentService service = new IntentService();
        Field field = IntentService.class.getDeclaredField("contextTracker");
        field.setAccessible(true);
        field.set(service, tracker);
        return service;
    }

    @Test
    public void successShouldResetConsecutiveFailures() throws Exception {
        ContextTracker tracker = new ContextTracker();
        IntentService service = serviceWith(tracker);

        tracker.recordFeedback("s-1", false);
        tracker.recordFeedback("s-1", false);
        assertEquals(2, tracker.getConsecutiveFailures("s-1"));

        // 成功必须把计数归零，否则该会话会永久跳过规则层
        service.reportFeedback("s-1", null, true, "PONG");
        assertEquals(0, tracker.getConsecutiveFailures("s-1"));
    }

    @Test
    public void successShouldResetEvenWhenLastIntentIsMissing() throws Exception {
        ContextTracker tracker = new ContextTracker();
        IntentService service = serviceWith(tracker);

        tracker.recordFeedback("s-2", false);
        tracker.recordFeedback("s-2", false);
        assertEquals(2, tracker.getConsecutiveFailures("s-2"));

        // lastIntent 为 null 时旧实现会在最前面 return，导致重置被跳过；
        // 现在成功分支排在任何提前返回之前，必须仍然归零。
        service.reportFeedback("s-2", null, true, "");
        assertEquals(0, tracker.getConsecutiveFailures("s-2"));

        // 归零之后可以重新累积
        tracker.recordFeedback("s-2", false);
        assertEquals(1, tracker.getConsecutiveFailures("s-2"));
    }
}