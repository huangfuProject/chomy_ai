package cn.chomy.ai.backend.mapper;

import cn.chomy.ai.backend.entity.AgentChatTask;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * Agent 执行任务数据访问，复用 MyBatis-Plus 通用 CRUD。
 * 调用方须先校验所属聊天的用户所有权；本 Mapper 不负责状态流转及关联关系校验。
 * @author chomy
 */
@Mapper
public interface AgentChatTaskMapper extends BaseMapper<AgentChatTask> {
}
