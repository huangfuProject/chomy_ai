package cn.chomy.ai.backend.stream;

import java.net.ServerSocket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import static org.assertj.core.api.Assertions.*;

/**
 * 使用临时本地 Redis 进程验证真实 XREAD 行为，不访问项目配置中的 Redis，也不保存磁盘快照。
 * 机器未安装 redis-server 时跳过此环境集成测试；纯业务测试仍可独立运行。
 * @author chomy
 */
class ChatEventReaderTest {
    private static Process server;
    private static LettuceConnectionFactory connections;
    private static StringRedisTemplate redis;
    private ChatEventReader reader;

    /** 启动仅绑定回环地址的临时进程，随机端口，不加载生产密码或配置。 */
    @BeforeAll
    static void startRedis() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        try {
            server = new ProcessBuilder("redis-server", "--bind", "127.0.0.1", "--port", Integer.toString(port),
                    "--save", "", "--appendonly", "no").redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        } catch (java.io.IOException exception) {
            Assumptions.assumeTrue(false, "redis-server 不可用，跳过本地 Redis 集成测试");
        }
        connections = new LettuceConnectionFactory("127.0.0.1", port);
        connections.afterPropertiesSet(); connections.start();
        redis = new StringRedisTemplate(connections);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (true) {
            try (var connection = connections.getConnection()) { connection.ping(); break; }
            catch (org.springframework.data.redis.RedisConnectionFailureException exception) {
                if (System.nanoTime() >= deadline) { throw exception; }
                Thread.sleep(25);
            }
        }
    }

    /** 释放连接并终止临时 Redis，不留下后台进程。 */
    @AfterAll
    static void stopRedis() throws Exception {
        if (connections != null) { connections.destroy(); }
        if (server != null) {
            server.destroy();
            if (!server.waitFor(2, TimeUnit.SECONDS)) { server.destroyForcibly(); }
        }
    }

    /** 每个测试只操作本临时进程中固定的测试键。 */
    @BeforeEach
    void setUp() { reader = new ChatEventReader(redis); redis.delete(ChatEventReader.key(10L)); }

    /** 流尚不存在时 XREAD 返回空集合，不能因建立订阅而创建任何 Redis 键。 */
    @Test
    void emptyReadDoesNotCreateStream() {
        assertThat(reader.read(10L, "0-0", false)).isEmpty();
        assertThat(reader.read(10L, "0-0", true)).isEmpty();
        assertThat(redis.hasKey(ChatEventReader.key(10L))).isFalse();
    }

    /** 两个阻塞读取者都能收到同一新增事件，不能像消费组一样相互分摊。 */
    @Test
    void broadcastsToIndependentSubscribersAndResumesAfterCursor() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> reader.read(10L, "0-0", true));
            var second = executor.submit(() -> reader.read(10L, "0-0", true));
            var id = redis.opsForStream().add(ChatEventReader.key(10L), Map.of("event", "message.delta", "data", "{\"delta\":\"你好\"}"));
            assertThat(first.get(3, TimeUnit.SECONDS)).singleElement().satisfies(record -> assertThat(record.getId()).isEqualTo(id));
            assertThat(second.get(3, TimeUnit.SECONDS)).singleElement().satisfies(record -> assertThat(record.getId()).isEqualTo(id));
            assertThat(reader.read(10L, id.getValue(), false)).isEmpty();
            var next = redis.opsForStream().add(ChatEventReader.key(10L), Map.of("event", "task.completed", "data", "{}"));
            assertThat(reader.read(10L, id.getValue(), false)).singleElement()
                    .satisfies(record -> assertThat(record.getId()).isEqualTo(next));
            assertThat(redis.opsForStream().size(ChatEventReader.key(10L))).isEqualTo(2);
        }
    }

    /** 有大量保留事件时只读取有界批次，下一次读取从末条 ID 接续，不一次加载全部事件。 */
    @Test
    void capsReplayBatchAndKeepsEvents() {
        for (int i = 0; i < 101; i++) {
            redis.opsForStream().add(ChatEventReader.key(10L), Map.of("data", "{}"));
        }
        var batch = reader.read(10L, "0-0", false);
        assertThat(batch).hasSize(100);
        assertThat(reader.read(10L, batch.getLast().getId().getValue(), false)).hasSize(1);
        assertThat(redis.opsForStream().size(ChatEventReader.key(10L))).isEqualTo(101);
    }
}
