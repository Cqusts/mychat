package com.easychat.ai;

import com.easychat.utils.StringTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 程序员助手的代码工作区。
 *
 * 这是整个流水线里唯一会在磁盘上写文件、执行外部命令、推送git的地方，
 * 所以安全边界全部收在这一个类里：
 *
 *   1. 独立工作区：从远端另外克隆一份，绝不碰你正在用的工作树，
 *      免得AI改了一半和你手上的改动搅在一起
 *   2. 路径越界防护：所有文件操作都要经过resolveSafe，解析后必须仍在工作区内，
 *      挡掉 ../../ 和绝对路径
 *   3. 命令白名单：只跑写死的git/mvn子命令，参数以数组传给ProcessBuilder，
 *      不拼shell字符串——模型输出的任何内容都不可能变成可执行命令
 *   4. 分支白名单：只允许推 ai/ 前缀的分支，碰不到main和你的开发分支
 */
@Component("coderWorkspace")
public class CoderWorkspace {

    private static final Logger logger = LoggerFactory.getLogger(CoderWorkspace.class);

    /**
     * 只允许推这个前缀的分支
     */
    private static final String BRANCH_PREFIX = "ai/";

    private static final Pattern BRANCH_PATTERN = Pattern.compile("^ai/[A-Za-z0-9._-]{1,80}$");

    /**
     * 单个文件最多读多少字符，防止把一个大文件整个灌进上下文
     */
    private static final int MAX_READ_CHARS = 20000;

    /**
     * 命令输出最多保留多少字符，编译日志动辄几百行
     */
    private static final int MAX_OUTPUT_CHARS = 6000;

    @Value("${ai.coder.enabled:false}")
    private Boolean coderEnabled;

    /**
     * 工作区目录。会在这里放一份独立的克隆，和你本地开发用的那份互不干扰
     */
    @Value("${ai.coder.workspace:}")
    private String workspacePath;

    /**
     * 仓库地址。留空则从 ai.coder.source-repo 指向的本地仓库里读origin
     */
    @Value("${ai.coder.git-url:}")
    private String gitUrl;

    @Value("${ai.coder.source-repo:}")
    private String sourceRepo;

    /**
     * 从哪个分支拉出来改
     */
    @Value("${ai.coder.base-branch:Aibot}")
    private String baseBranch;

    @Value("${ai.coder.maven-command:mvn}")
    private String mavenCommand;

    @Value("${ai.coder.git-command:git}")
    private String gitCommand;

    @Value("${ai.coder.command-timeout-seconds:600}")
    private Integer commandTimeoutSeconds;

    public boolean isEnabled() {
        return Boolean.TRUE.equals(coderEnabled) && !StringTools.isEmpty(workspacePath);
    }

    public String getBaseBranch() {
        return baseBranch;
    }

    /**
     * 确保工作区存在。第一次用会克隆一份
     */
    public synchronized void ensureWorkspace() throws Exception {
        Path root = Paths.get(workspacePath).toAbsolutePath().normalize();
        if (Files.isDirectory(root.resolve(".git"))) {
            return;
        }
        String url = resolveGitUrl();
        if (StringTools.isEmpty(url)) {
            throw new IllegalStateException("没有配置 ai.coder.git-url，也无法从 ai.coder.source-repo 读出origin地址");
        }
        Files.createDirectories(root.getParent() == null ? root : root.getParent());
        logger.info("初始化代码工作区: {} <- {}", root, url);
        ExecResult result = exec(root.getParent().toFile(),
                Arrays.asList(gitCommand, "clone", url, root.toString()));
        if (!result.success()) {
            throw new IllegalStateException("克隆仓库失败：" + result.output);
        }
    }

    /**
     * 从ai.coder.source-repo指向的本地仓库读origin地址，省得再配一遍
     */
    private String resolveGitUrl() {
        if (!StringTools.isEmpty(gitUrl)) {
            return gitUrl;
        }
        if (StringTools.isEmpty(sourceRepo)) {
            return null;
        }
        try {
            ExecResult result = exec(new File(sourceRepo),
                    Arrays.asList(gitCommand, "remote", "get-url", "origin"));
            return result.success() ? result.output.trim() : null;
        } catch (Exception e) {
            logger.error("读取源仓库origin失败, sourceRepo:{}", sourceRepo, e);
            return null;
        }
    }

    /**
     * 拉取最新代码并切到一个全新的分支。
     * 用 checkout -B 强制重建：每个任务都从干净的基线开始，
     * 不会带上一次任务残留的改动
     */
    public void prepareBranch(String branch) throws Exception {
        checkBranchName(branch);
        ensureWorkspace();
        File root = rootFile();
        ExecResult fetch = exec(root, Arrays.asList(gitCommand, "fetch", "origin", baseBranch));
        if (!fetch.success()) {
            throw new IllegalStateException("拉取基线分支失败：" + fetch.output);
        }
        ExecResult checkout = exec(root,
                Arrays.asList(gitCommand, "checkout", "-B", branch, "origin/" + baseBranch));
        if (!checkout.success()) {
            throw new IllegalStateException("切换分支失败：" + checkout.output);
        }
        logger.info("工作区已切到分支 {}（基于 origin/{}）", branch, baseBranch);
    }

    /**
     * 开工前先确认 git 和 mvn 真的能跑起来。
     *
     * 不做这一步的代价是实打实踩过的：机器上没装 mvn，compile 工具返回
     * "命令无法执行"，模型看不懂这不是它能修的问题，反复搜"怎么编译"，
     * 同一个 searchCode 刷了一千七百多次，只能重启服务端才停下来。
     * 环境问题就该在环境这一层拦掉，不要交给模型去猜。
     *
     * @return 没问题返回null，否则返回一句能直接发给用户的说明
     */
    public String checkToolchain() {
        String gitProblem = probe(gitCommand, "--version", "ai.coder.git-command");
        if (gitProblem != null) {
            return gitProblem;
        }
        return probe(mavenCommand, "-v", "ai.coder.maven-command");
    }

    private String probe(String command, String versionArg, String configKey) {
        try {
            ExecResult result = exec(rootFile().isDirectory() ? rootFile() : new File("."),
                    Arrays.asList(command, versionArg));
            if (result.success()) {
                return null;
            }
            return "`" + command + " " + versionArg + "` 执行失败：" + trimOutput(result.output);
        } catch (Exception e) {
            return "找不到命令 `" + command + "`，请把它加进服务端进程的 PATH，"
                    + "或者在配置里把 " + configKey + " 填成绝对路径"
                    + "（Windows 例：D:/apache-maven-3.9.6/bin/mvn.cmd）。"
                    + "注意 IDE 启动的进程不一定继承你终端里的 PATH，改完要重启服务端。";
        }
    }

    /**
     * 编译后端。这是整条编码链路里最关键的一环——
     * 没有编译反馈，模型产出的Java基本编不过
     */
    public ExecResult compile() throws Exception {
        File backend = resolveSafe("easychat-java").toFile();
        return exec(backend, Arrays.asList(mavenCommand, "-q", "-B", "compile"));
    }

    /**
     * 跑单元测试。
     * 显式传 -DskipTests=false：项目的pom里把skipTests设成了true，
     * 不覆盖的话测试会被直接跳过，跑出来永远是"成功"
     */
    public ExecResult runTests() throws Exception {
        File backend = resolveSafe("easychat-java").toFile();
        return exec(backend, Arrays.asList(mavenCommand, "-B", "-DskipTests=false", "test"));
    }

    /**
     * 有没有实际改动
     */
    public boolean hasChanges() throws Exception {
        ExecResult result = exec(rootFile(), Arrays.asList(gitCommand, "status", "--porcelain"));
        return result.success() && !result.output.trim().isEmpty();
    }

    /**
     * 改动概览，用来汇报给用户
     */
    public String diffStat() throws Exception {
        File root = rootFile();
        //新建的文件在git眼里还是untracked，git diff根本看不见它们，
        //先用 add -N 登记成"打算加入"，改动概览才不会漏掉新增的文件
        exec(root, Arrays.asList(gitCommand, "add", "-N", "."));
        ExecResult result = exec(root, Arrays.asList(gitCommand, "diff", "--stat", "HEAD"));
        return result.success() ? result.output.trim() : "";
    }

    /**
     * 提交并推送。只在编译通过后由引擎调用，模型没有触发推送的工具
     */
    public void commitAndPush(String branch, String message) throws Exception {
        checkBranchName(branch);
        File root = rootFile();
        ExecResult add = exec(root, Arrays.asList(gitCommand, "add", "-A"));
        if (!add.success()) {
            throw new IllegalStateException("git add 失败：" + add.output);
        }
        ExecResult commit = exec(root, Arrays.asList(gitCommand, "commit", "-m", message));
        if (!commit.success()) {
            throw new IllegalStateException("git commit 失败：" + commit.output);
        }
        ExecResult push = exec(root, Arrays.asList(gitCommand, "push", "-u", "origin", branch));
        if (!push.success()) {
            throw new IllegalStateException("git push 失败：" + push.output);
        }
        logger.info("代码已推送到分支 {}", branch);
    }

    // ==================== 文件操作，全部经过路径校验 ====================

    /**
     * 给模型看的读取，超长会截断，避免一个文件就把上下文吃满
     */
    public String readFile(String relativePath) throws Exception {
        String content = readFileRaw(relativePath);
        if (content == null) {
            return null;
        }
        if (content.length() > MAX_READ_CHARS) {
            return content.substring(0, MAX_READ_CHARS)
                    + "\n……（文件过长已截断，共" + content.length() + "字符）";
        }
        return content;
    }

    /**
     * 读全文，不截断。
     *
     * 凡是"读出来改一改再写回去"的地方都必须用这个：
     * 之前 replaceInFile 用的是上面那个截断版，一旦文件超过MAX_READ_CHARS，
     * 替换完写回去就把文件从两万字符处齐根砍断了，而且编译不一定立刻报错，
     * 是个会悄悄毁文件的bug
     */
    public String readFileRaw(String relativePath) throws Exception {
        Path file = resolveSafe(relativePath);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    public void writeFile(String relativePath, String content) throws Exception {
        Path file = resolveSafe(relativePath);
        Files.createDirectories(file.getParent());
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }

    public boolean exists(String relativePath) throws Exception {
        return Files.exists(resolveSafe(relativePath));
    }

    /**
     * 按关键词搜代码。自己遍历而不是调用grep：
     * Windows上没有grep，调外部命令还得考虑各平台差异
     */
    public List<String> searchCode(String keyword, String extension, int maxHits) throws Exception {
        List<String> hits = new ArrayList<>();
        Path root = rootPath();
        String lowerKeyword = keyword.toLowerCase();
        try (var stream = Files.walk(root)) {
            for (Path path : (Iterable<Path>) stream.filter(Files::isRegularFile)::iterator) {
                if (hits.size() >= maxHits) {
                    break;
                }
                String relative = root.relativize(path).toString().replace('\\', '/');
                //跳过构建产物和依赖目录，不然全是噪音
                if (relative.startsWith(".git/") || relative.contains("/target/")
                        || relative.contains("/node_modules/") || relative.contains("/out/")) {
                    continue;
                }
                if (!StringTools.isEmpty(extension) && !relative.endsWith(extension)) {
                    continue;
                }
                List<String> lines;
                try {
                    lines = Files.readAllLines(path, StandardCharsets.UTF_8);
                } catch (Exception ignore) {
                    //二进制文件读不了，跳过
                    continue;
                }
                for (int i = 0; i < lines.size() && hits.size() < maxHits; i++) {
                    if (lines.get(i).toLowerCase().contains(lowerKeyword)) {
                        hits.add(relative + ":" + (i + 1) + ": " + lines.get(i).trim());
                    }
                }
            }
        }
        return hits;
    }

    // ==================== 安全底座 ====================

    /**
     * 把相对路径解析到工作区内，并确认没有越界。
     * 这是文件操作唯一的入口，模型给什么路径都得先过这一关
     */
    public Path resolveSafe(String relativePath) throws Exception {
        if (StringTools.isEmpty(relativePath)) {
            throw new IllegalArgumentException("路径不能为空");
        }
        Path root = rootPath();
        Path resolved = root.resolve(relativePath).normalize();
        if (!resolved.startsWith(root)) {
            logger.warn("拦截越界路径访问: {}", relativePath);
            throw new SecurityException("路径超出了工作区范围：" + relativePath);
        }
        return resolved;
    }

    private void checkBranchName(String branch) {
        if (branch == null || !BRANCH_PATTERN.matcher(branch).matches()) {
            throw new SecurityException("分支名不合法，只允许 " + BRANCH_PREFIX + " 前缀：" + branch);
        }
    }

    private Path rootPath() {
        return Paths.get(workspacePath).toAbsolutePath().normalize();
    }

    private File rootFile() {
        return rootPath().toFile();
    }

    /**
     * 执行外部命令。
     * 参数以List传给ProcessBuilder，不经过shell拼接——
     * 这样即使模型想构造 "; rm -rf /" 之类的内容，也只会变成某个参数的字面值，不会被执行
     */
    private ExecResult exec(File workDir, List<String> command) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workDir);
        builder.redirectErrorStream(true);
        //输出是按UTF-8读的，但maven在中文Windows上默认按GBK写，
        //不统一的话编译报错到了模型手里就是一堆乱码，它根本没法照着修
        String mavenOpts = builder.environment().getOrDefault("MAVEN_OPTS", "");
        builder.environment().put("MAVEN_OPTS",
                (mavenOpts + " -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8"
                        + " -Dsun.stderr.encoding=UTF-8").trim());
        logger.info("执行命令: {} (cwd={})", String.join(" ", command), workDir);
        Process process = builder.start();
        StringBuilder output = new StringBuilder();
        try (var reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }
        boolean finished = process.waitFor(commandTimeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            return new ExecResult(-1, "命令执行超时（" + commandTimeoutSeconds + "秒）");
        }
        return new ExecResult(process.exitValue(), trimOutput(output.toString()));
    }

    /**
     * 编译日志可能上千行，只留头尾，中间省略
     */
    private String trimOutput(String output) {
        if (output.length() <= MAX_OUTPUT_CHARS) {
            return output;
        }
        int half = MAX_OUTPUT_CHARS / 2;
        return output.substring(0, half)
                + "\n……（输出过长已省略中间部分）……\n"
                + output.substring(output.length() - half);
    }

    /**
     * 命令执行结果
     */
    public static class ExecResult {
        public final int exitCode;
        public final String output;

        public ExecResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }

        public boolean success() {
            return exitCode == 0;
        }
    }
}
