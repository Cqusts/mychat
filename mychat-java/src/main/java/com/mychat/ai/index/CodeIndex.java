package com.mychat.ai.index;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 代码库检索索引。
 *
 * 为什么要有它：评测跑了20个任务，真实失败100%集中在编码阶段的工具预算熔断——
 * Agent 不是改错了，是在60次工具调用里压根没找到该改哪个文件。
 * 需求里点名了类/表的4条通过3.5条，只描述行为、要自己定位的6条全灭。
 *
 * 原来的 searchCode 是无排序子串匹配、命中40条就停，
 * 搜"会话"返回的是文件系统顺序里最先撞上的40行，和需求相不相关全看运气。
 *
 * 这里做三路召回再融合：
 *   1. BM25 全文——正文和中文注释都进索引，中文需求能直接命中中文注释
 *   2. 符号名匹配——类名/方法名/表名，"文件叫ChatSessionUserMapper"这个信号
 *      比"正文出现过10次session"强得多
 *   3. 文件名匹配——最强的信号，单独加权
 *
 * 用 RRF(Reciprocal Rank Fusion) 融合：不同通道的分数量纲不可比，
 * 但排名可比，取 1/(k+rank) 求和是业界最稳的做法，不用调权重
 */
public class CodeIndex {

    private static final Logger logger = LoggerFactory.getLogger(CodeIndex.class);

    /**
     * BM25 的词频饱和参数。1.2 是通用默认值：
     * 一个词在文件里出现20次和出现5次，相关性差别没有4倍那么大
     */
    private static final double BM25_K1 = 1.2;

    /**
     * BM25 的长度归一化强度。代码文件长度差异极大
     * （几十行的DTO和上千行的ServiceImpl），这个值要保持默认的0.75
     */
    private static final double BM25_B = 0.75;

    /**
     * RRF 的平滑常数。60 是原论文的取值，作用是压低头部名次的绝对优势，
     * 让第2名和第1名不至于差太远
     */
    private static final int RRF_K = 60;

    /**
     * 每一路召回取多少条参与融合
     */
    private static final int CHANNEL_DEPTH = 30;

    /**
     * 单个文件最多索引多少字符，避免个别超大文件（比如压缩过的js）拖垮索引
     */
    private static final int MAX_INDEX_CHARS = 200_000;

    private static final Set<String> INDEXED_EXTENSIONS = Set.of(
            ".java", ".xml", ".vue", ".js", ".ts", ".sql", ".yml", ".yaml", ".properties", ".md");

    private static final String[] SKIP_PATH_PARTS = {
            "/.git/", "/target/", "/node_modules/", "/out/", "/dist/", "/installPackages/"};

    //类/接口/枚举声明
    private static final Pattern JAVA_TYPE = Pattern.compile(
            "\\b(?:class|interface|enum|record)\\s+([A-Z]\\w*)");
    //方法声明：修饰符 + 返回类型 + 方法名(
    private static final Pattern JAVA_METHOD = Pattern.compile(
            "\\b(?:public|protected|private)\\s+(?:static\\s+)?[\\w<>\\[\\],.?\\s]+?\\s+(\\w+)\\s*\\(");
    //MyBatis 的 namespace 和语句 id
    private static final Pattern XML_ID = Pattern.compile(
            "(?:namespace|id)\\s*=\\s*\"([^\"]+)\"");
    //建表语句里的表名和字段名
    private static final Pattern SQL_NAME = Pattern.compile(
            "(?:CREATE TABLE|create table)\\s+`?(\\w+)`?|^\\s*`(\\w+)`", Pattern.MULTILINE);

    private final Path root;

    private final Map<String, CodeDocument> documents = new LinkedHashMap<>();

    /**
     * 词 -> 有多少个文件包含它。BM25 的 IDF 靠它算，
     * 出现在几乎所有文件里的词（比如 public）权重会被压到接近0
     */
    private final Map<String, Integer> documentFrequency = new HashMap<>();

    private double averageLength = 1;

    private CodeIndex(Path root) {
        this.root = root;
    }

    /**
     * 扫描并建立索引。240 个文件量级，一次几百毫秒，不做增量
     */
    public static CodeIndex build(Path root) {
        CodeIndex index = new CodeIndex(root);
        long start = System.currentTimeMillis();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile).forEach(index::indexFile);
        } catch (Exception e) {
            logger.error("建立代码索引失败, root:{}", root, e);
        }
        index.finish();
        logger.info("代码索引建立完成：{} 个文件，{} 个词，耗时 {}ms",
                index.documents.size(), index.documentFrequency.size(),
                System.currentTimeMillis() - start);
        return index;
    }

    private void indexFile(Path path) {
        String relative = root.relativize(path).toString().replace('\\', '/');
        String guard = "/" + relative + "/";
        for (String skip : SKIP_PATH_PARTS) {
            if (guard.contains(skip)) {
                return;
            }
        }
        int dot = relative.lastIndexOf('.');
        if (dot < 0 || !INDEXED_EXTENSIONS.contains(relative.substring(dot))) {
            return;
        }

        String content;
        try {
            content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        } catch (Exception ignore) {
            return;
        }
        if (content.length() > MAX_INDEX_CHARS) {
            content = content.substring(0, MAX_INDEX_CHARS);
        }

        //路径本身也进索引：目录名(controller/service/mappers)是很有用的定位线索
        List<String> tokens = CodeToken.tokenize(relative);
        tokens.addAll(CodeToken.tokenize(content));

        Map<String, Integer> frequency = new HashMap<>();
        for (String token : tokens) {
            frequency.merge(token, 1, Integer::sum);
        }
        for (String term : frequency.keySet()) {
            documentFrequency.merge(term, 1, Integer::sum);
        }

        Set<String> symbols = extractSymbols(relative, content);
        documents.put(relative,
                new CodeDocument(relative, frequency, tokens.size(), symbols, buildOutline(symbols)));
    }

    private void finish() {
        if (documents.isEmpty()) {
            return;
        }
        long total = 0;
        for (CodeDocument document : documents.values()) {
            total += document.getLength();
        }
        averageLength = Math.max(1, (double) total / documents.size());
    }

    private Set<String> extractSymbols(String relative, String content) {
        Set<String> symbols = new LinkedHashSet<>();
        if (relative.endsWith(".java")) {
            collect(JAVA_TYPE, content, symbols, 1);
            collect(JAVA_METHOD, content, symbols, 1);
        } else if (relative.endsWith(".xml")) {
            collect(XML_ID, content, symbols, 1);
        } else if (relative.endsWith(".sql")) {
            Matcher matcher = SQL_NAME.matcher(content);
            while (matcher.find()) {
                String name = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
                if (name != null && name.length() > 1) {
                    symbols.add(name);
                }
            }
        }
        return symbols;
    }

    private void collect(Pattern pattern, String content, Set<String> into, int group) {
        Matcher matcher = pattern.matcher(content);
        while (matcher.find() && into.size() < 200) {
            String name = matcher.group(group);
            //过滤掉 if/for/while 这类被方法正则误伤的关键字
            if (name != null && name.length() > 1 && !isKeyword(name)) {
                into.add(name);
            }
        }
    }

    private boolean isKeyword(String name) {
        switch (name) {
            case "if": case "for": case "while": case "switch": case "catch":
            case "return": case "new": case "synchronized": case "try":
                return true;
            default:
                return false;
        }
    }

    private String buildOutline(Set<String> symbols) {
        if (symbols.isEmpty()) {
            return "";
        }
        List<String> list = new ArrayList<>(symbols);
        int limit = Math.min(12, list.size());
        String text = String.join(", ", list.subList(0, limit));
        return list.size() > limit ? text + " …" : text;
    }

    // ==================== 检索 ====================

    /**
     * 混合检索：BM25 + 符号名 + 文件名，三路召回用 RRF 融合
     *
     * @param query 可以直接丢中文需求进来，比如"会话列表支持按昵称模糊搜索"
     */
    public List<Hit> search(String query, int topK) {
        if (documents.isEmpty() || query == null || query.isBlank()) {
            return List.of();
        }
        //查询要先过词典：去掉"增加/支持/提供"这类套话，
        //再把"会话"扩成 session/chatsession——中文需求和英文代码之间那道坎全在这
        List<String> terms = CodeGlossary.expand(query);
        if (terms.isEmpty()) {
            return List.of();
        }

        Map<String, Double> fused = new HashMap<>();
        mergeByRank(fused, rankByBm25(terms));
        mergeByRank(fused, rankBySymbol(query, terms));
        mergeByRank(fused, rankByFileName(terms));

        List<Hit> hits = new ArrayList<>();
        for (Map.Entry<String, Double> entry : fused.entrySet()) {
            CodeDocument document = documents.get(entry.getKey());
            if (document != null) {
                hits.add(new Hit(document, entry.getValue() * weightOf(entry.getKey())));
            }
        }
        hits.sort(Comparator.comparingDouble(Hit::getScore).reversed());
        return hits.size() > topK ? hits.subList(0, topK) : hits;
    }

    /**
     * RRF：把一路召回的排名折算成分数累加。
     * 三路的分数量纲完全不同（BM25是浮点、符号是命中数），
     * 直接加权求和要调参且不稳，换成排名倒数就没这个问题
     */
    private void mergeByRank(Map<String, Double> fused, List<String> ranked) {
        for (int i = 0; i < ranked.size(); i++) {
            fused.merge(ranked.get(i), 1.0 / (RRF_K + i + 1), Double::sum);
        }
    }

    private List<String> rankByBm25(List<String> terms) {
        int total = documents.size();
        Map<String, Double> scores = new HashMap<>();
        for (CodeDocument document : documents.values()) {
            double score = 0;
            for (String term : terms) {
                int frequency = document.frequencyOf(term);
                if (frequency == 0) {
                    continue;
                }
                int df = documentFrequency.getOrDefault(term, 1);
                //加了平滑的IDF，保证非负：太常见的词权重趋近0而不是变负数
                double idf = Math.log(1 + (total - df + 0.5) / (df + 0.5));
                double norm = frequency * (BM25_K1 + 1)
                        / (frequency + BM25_K1 * (1 - BM25_B + BM25_B * document.getLength() / averageLength));
                score += idf * norm;
            }
            if (score > 0) {
                scores.put(document.getPath(), score);
            }
        }
        return topPaths(scores);
    }

    /**
     * 符号名召回。查询词和类名/方法名对上，比正文里出现过强得多
     */
    private List<String> rankBySymbol(String query, List<String> terms) {
        String lowerQuery = query.toLowerCase();
        Map<String, Double> scores = new HashMap<>();
        for (CodeDocument document : documents.values()) {
            double score = 0;
            for (String symbol : document.getSymbols()) {
                String lowerSymbol = symbol.toLowerCase();
                if (lowerQuery.contains(lowerSymbol) && lowerSymbol.length() > 3) {
                    //需求里直接写了类名，这是最强的信号
                    score += 5;
                    continue;
                }
                for (String term : terms) {
                    if (term.length() < 3) {
                        continue;
                    }
                    if (lowerSymbol.equals(term)) {
                        score += 3;
                    } else if (lowerSymbol.contains(term)) {
                        score += 1;
                    }
                }
            }
            if (score > 0) {
                scores.put(document.getPath(), score);
            }
        }
        return topPaths(scores);
    }

    private List<String> rankByFileName(List<String> terms) {
        Map<String, Double> scores = new HashMap<>();
        for (CodeDocument document : documents.values()) {
            String name = document.getFileName().toLowerCase();
            double score = 0;
            for (String term : terms) {
                if (term.length() < 3) {
                    continue;
                }
                if (name.equals(term)) {
                    score += 4;
                } else if (name.contains(term)) {
                    score += 2;
                }
            }
            if (score > 0) {
                scores.put(document.getPath(), score);
            }
        }
        return topPaths(scores);
    }

    /**
     * 文件类型权重。
     *
     * 实测第一版把 README.md 和各种 *Test.java 排到了前面——它们把领域词
     * 写了个遍，BM25 上分很高，但"要改哪个文件"的答案永远不是它们。
     * 文档和测试压下去，主代码抬上来
     */
    private double weightOf(String path) {
        if (path.endsWith(".md")) {
            return 0.25;
        }
        if (path.contains("/src/test/") || path.endsWith("Test.java")) {
            return 0.3;
        }
        if (path.endsWith(".java") || path.endsWith("Mapper.xml")) {
            return 1.0;
        }
        if (path.endsWith(".vue") || path.endsWith(".js")) {
            return 0.8;
        }
        return 0.6;
    }

    private List<String> topPaths(Map<String, Double> scores) {
        List<String> paths = new ArrayList<>(scores.keySet());
        paths.sort((a, b) -> Double.compare(scores.get(b), scores.get(a)));
        return paths.size() > CHANNEL_DEPTH ? paths.subList(0, CHANNEL_DEPTH) : paths;
    }

    public CodeDocument getDocument(String path) {
        return documents.get(path);
    }

    public int size() {
        return documents.size();
    }

    /**
     * 一次检索的结果
     */
    public static class Hit {
        private final CodeDocument document;
        private final double score;

        Hit(CodeDocument document, double score) {
            this.document = document;
            this.score = score;
        }

        public CodeDocument getDocument() {
            return document;
        }

        public double getScore() {
            return score;
        }
    }
}
