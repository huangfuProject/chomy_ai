package cn.chomy.ai.backend.stream;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Chat 级 Redis Streams 只读边界，使用 XREAD 而非消费组，保证多个窗口能各自收到全部事件。
 * 不创建 Stream、不确认或删除记录、不持有跨请求共享游标，不负责任务生产与执行。
 * @author chomy
 */
@Component
@RequiredArgsConstructor
public class ChatEventReader {
    /** 复用应用 Redis 连接配置，阻塞读取最长 1 秒，小于当前 3 秒 Redis 命令超时。 */
    private final StringRedisTemplate redis;

    /**
     * 读取游标之后最多 100 条事件；流不存在或没有新事件时返回空集合。
     * @param chatId 已验证所有权的聊天 ID
     * @param cursor 排他起始 Stream ID，不使用每次变化的 $，避免读取间隙丢事件
     * @param blocking 是否最多等待 1 秒，建连前检查使用非阻塞读取
     * @return Redis 原始字符串字段，约定 event 为事件名、data 为合法 JSON
     */
    public List<MapRecord<String, String, String>> read(Long chatId, String cursor, boolean blocking) {
        StreamReadOptions options = StreamReadOptions.empty().count(100);
        if (blocking) { options = options.block(Duration.ofSeconds(1)); }
        // 独立 XREAD 游标实现广播，不使用 XREADGROUP 将事件分摊给不同窗口。
        var records = redis.<String, String>opsForStream().read(options,
                StreamOffset.create(key(chatId), ReadOffset.from(cursor)));
        return records == null ? List.of() : records;
    }

    /**
     * 获取聊天事件 Stream 键，后续发布方必须遵循同一协议。
     * @param chatId 聊天 ID
     * @return 仅由服务端正数 ID 派生的 Redis 键，不含客户端任意前缀
     */
    public static String key(Long chatId) { return "chomy:chat:events:" + chatId; }
}
