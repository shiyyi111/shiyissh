package com.shiyi.verify;

import com.shiyi.domain.agent.adapter.repository.IWikiRepository;
import com.shiyi.domain.agent.model.entity.WikiEntryEntity;
import com.shiyi.domain.agent.service.wiki.WikiService;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 知识库服务验证。
 * <p>
 * 守三件事：
 * <ol>
 *   <li><b>结果必须可引用</b>：每条命中带从 1 开始的编号，模型回答里能写 [1]，用户能回溯是哪条经验；</li>
 *   <li><b>正文必须截断</b>：一条 wiki 可能几千字，不截会把上下文一次撑爆；</li>
 *   <li><b>没找到要说出来</b>：返回空串会被模型理解成"工具坏了"，然后开始编。</li>
 * </ol>
 *
 * @author shiyi
 */
public class WikiServiceTest {

    private final IWikiRepository repository = mock(IWikiRepository.class);

    private final WikiService service = service();

    @Test
    public void shouldNotQueryRepositoryForBlankKeywords() {
        assertTrue(service.search("u-1", "h-1", "  ", 5).isEmpty());

        verify(repository, never()).search(anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    public void shouldTruncateLongContent() {
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            longText.append("磁盘满排查");
        }
        when(repository.search(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(List.of(WikiEntryEntity.builder()
                        .id(1L).title("磁盘满").content(longText.toString()).build()));

        List<WikiService.WikiHit> hits = service.search("u-1", "h-1", "磁盘", 5);

        assertEquals(1, hits.size());
        String snippet = hits.get(0).snippet();
        assertTrue("长正文要截断，避免一条结果吃掉整个上下文", snippet.length() <= 301);
        assertTrue("截断要有可见标记", snippet.endsWith("…"));
    }

    @Test
    public void shouldRenderNumberedCitations() {
        String text = service.render(List.of(
                new WikiService.WikiHit(1L, "nginx 502 是 upstream 超时", "调大 proxy_read_timeout", "nginx,502", 1.2d),
                new WikiService.WikiHit(2L, "磁盘满先看 inode", "df -i", "磁盘", 0.8d)));

        assertTrue(text.contains("[1] nginx 502 是 upstream 超时"));
        assertTrue(text.contains("[2] 磁盘满先看 inode"));
        assertTrue("标签要一起带上，便于模型判断适用范围", text.contains("nginx,502"));
        assertTrue("要提醒模型这是历史经验而不是实时状态", text.contains("历史经验"));
    }

    @Test
    public void shouldSayNotFoundExplicitly() {
        String text = service.render(List.of());

        assertTrue(text.contains("没有找到相关条目"));
    }

    @Test
    public void shouldSurviveTouchUsageFailure() {
        when(repository.search(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(List.of(WikiEntryEntity.builder().id(3L).title("t").content("c").build()));
        doThrow(new IllegalStateException("db busy")).when(repository).touchUsage(anyList());

        List<WikiService.WikiHit> hits = service.search("u-1", "h-1", "t", 5);

        assertEquals("命中计数失败不能影响本次检索", 1, hits.size());
    }

    @Test
    public void shouldWriteEntryWithAgentSource() {
        assertTrue(service.remember("u-1", "h-1", " nginx 502 是 upstream 超时 ",
                "根因：upstream 响应超过 proxy_read_timeout", "nginx,502"));

        verify(repository).save(any(WikiEntryEntity.class));
    }

    @Test
    public void shouldRejectIncompleteEntry() {
        assertFalse(service.remember("u-1", "h-1", "  ", "content", ""));
        assertFalse(service.remember("u-1", "h-1", "title", " ", ""));

        verify(repository, never()).save(any());
    }

    @Test
    public void shouldDegradeWhenRepositoryUnavailable() {
        WikiService bare = new WikiService();

        assertTrue(bare.search("u-1", "h-1", "x", 5).isEmpty());
        assertFalse(bare.remember("u-1", "h-1", "t", "c", ""));
    }

    private WikiService service() {
        WikiService target = new WikiService();
        try {
            java.lang.reflect.Field field = WikiService.class.getDeclaredField("wikiRepository");
            field.setAccessible(true);
            field.set(target, repository);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return target;
    }
}