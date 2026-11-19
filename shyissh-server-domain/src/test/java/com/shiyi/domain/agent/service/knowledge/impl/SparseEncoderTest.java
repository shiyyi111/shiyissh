package com.shiyi.domain.agent.service.knowledge.impl;

import com.shiyi.domain.agent.adapter.vector.SparseVector;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@link SparseEncoder} 的单元测试。
 * <p>
 * 重点不是"能编码"，而是<strong>编码规则本身</strong>：切分方式、哈希稳定性、权重、边界。
 * 稀疏向量一旦规则变了，历史文档与查询就对不上（同一句话算不出同一批下标），
 * 所以这些断言同时充当"格式契约"——改规则必须是有意识的，且要重建索引。
 */
public class SparseEncoderTest {

    @Test
    public void tokensShouldSplitCjkIntoBigramsAndKeepAsciiWordsWhole() {
        List<String> tokens = SparseEncoder.tokens("磁盘 inode 耗尽，df -h 查看");
        assertTrue("连续汉字应切成 2-gram", tokens.contains("磁盘"));
        assertTrue(tokens.contains("耗尽"));
        assertTrue("ASCII 单词应整词保留", tokens.contains("inode"));
        assertTrue(tokens.contains("df"));
        assertTrue(tokens.contains("h"));
        assertFalse("标点与空白不产生词项", tokens.contains("，"));
        // CJK 三段各 1 个 2-gram（磁盘/耗尽/查看）+ 三个 ASCII 词（inode/df/h）
        assertEquals(6, tokens.size());
    }

    @Test
    public void singleCjkCharShouldStayAsSingleToken() {
        List<String> tokens = SparseEncoder.tokens("查");
        assertEquals(List.of("查"), tokens);
    }

    @Test
    public void encodeShouldBeDeterministicAndCaseInsensitive() {
        SparseVector first = SparseEncoder.encode("Nginx 502 Bad Gateway");
        SparseVector second = SparseEncoder.encode("nginx 502 bad gateway");
        assertEquals("同一段文本两次编码必须一致（写入与查询的唯一前提）",
                first.indices().length, second.indices().length);
        for (int i = 0; i < first.indices().length; i++) {
            assertEquals(first.indices()[i], second.indices()[i]);
            assertEquals(first.values()[i], second.values()[i], 1e-6);
        }
        assertNotEquals("不同文本不应塌成同一个向量",
                SparseEncoder.encode("磁盘空间不足").indices().length,
                SparseEncoder.encode("nginx 502").indices().length);
    }

    @Test
    public void indicesShouldBeAscendingAndWeightsSublinear() {
        SparseVector vector = SparseEncoder.encode("磁盘 磁盘 磁盘");
        long previous = -1L;
        for (long index : vector.indices()) {
            assertTrue("下标必须升序：Qdrant 要求 sparse indices 严格递增", index > previous);
            previous = index;
        }
        float value = vector.values()[0];
        // sublinear tf：1 + ln(3) ≈ 2.0986，不是 3（线性）也不是 1（二值）
        assertEquals(2.0986d, value, 1e-3);
    }

    @Test
    public void blankAndPunctuationOnlyInputShouldBeEmpty() {
        assertTrue(SparseEncoder.encode(null).isEmpty());
        assertTrue(SparseEncoder.encode("   ").isEmpty());
        assertTrue(SparseEncoder.encode("，。！？---").isEmpty());
        assertTrue(SparseEncoder.encodeQuery(" ").isEmpty());
    }

    @Test
    public void documentEncodingShouldCapTermsWhileQueryShouldNot() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 600; i++) {
            text.append("w").append(i).append(' ');
        }
        // 文档侧有 512 项上限（payload 体积与检索延迟都有界）
        assertEquals(512, SparseEncoder.encode(text.toString()).indices().length);
        // 查询侧不截断：查询本来就很短，截断只会丢掉性命中的词
        assertEquals(600, SparseEncoder.encodeQuery(text.toString()).indices().length);
    }
}