package com.mychat.ai.index;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 索引里的一个文件。
 *
 * 除了BM25要的词频，还单独存了符号表（类名/方法名/表名）——
 * 因为代码检索里"文件名叫 ChatSessionUserMapper"这个信号，
 * 比"正文里出现过10次session"强得多，两者要分开打分再融合
 */
public class CodeDocument {

    private final String path;

    /**
     * 词 -> 在本文件里出现的次数
     */
    private final Map<String, Integer> termFrequency;

    /**
     * 本文件的总词数，BM25 用它做长度归一化，否则长文件天然占便宜
     */
    private final int length;

    /**
     * 类名、接口名、方法名、字段名、mapper的id、表名
     */
    private final Set<String> symbols;

    /**
     * 给人看的一句话摘要：这个文件里有什么
     */
    private final String outline;

    public CodeDocument(String path, Map<String, Integer> termFrequency, int length,
                        Set<String> symbols, String outline) {
        this.path = path;
        this.termFrequency = termFrequency;
        this.length = length;
        this.symbols = symbols;
        this.outline = outline;
    }

    public String getPath() {
        return path;
    }

    public Map<String, Integer> getTermFrequency() {
        return termFrequency;
    }

    public int getLength() {
        return length;
    }

    public Set<String> getSymbols() {
        return symbols;
    }

    public String getOutline() {
        return outline;
    }

    public int frequencyOf(String term) {
        Integer count = termFrequency.get(term);
        return count == null ? 0 : count;
    }

    /**
     * 文件名（不含目录和后缀）。命中文件名是很强的信号，单独拿出来打分
     */
    public String getFileName() {
        int slash = path.lastIndexOf('/');
        String name = slash < 0 ? path : path.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    public List<String> getSymbolList() {
        return new ArrayList<>(new LinkedHashSet<>(symbols));
    }
}
