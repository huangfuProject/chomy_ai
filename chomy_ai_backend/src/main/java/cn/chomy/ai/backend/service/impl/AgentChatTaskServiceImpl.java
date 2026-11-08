package cn.chomy.ai.backend.service.impl;

import cn.chomy.ai.backend.entity.AgentChatTask;
import cn.chomy.ai.backend.mapper.AgentChatTaskMapper;
import cn.chomy.ai.backend.service.AgentChatTaskService;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * Agent 执行任务表级基础服务实现，由 Spring 注入 Mapper，复用通用 CRUD，避免重复实现持久化逻辑。
 * 本类不自动执行 Agent、取消任务、恢复检查点或清理关联数据；这些操作须由后续业务服务编排。
 * @author chomy
 */
@Service
public class AgentChatTaskServiceImpl extends ServiceImpl<AgentChatTaskMapper, AgentChatTask> implements AgentChatTaskService {
}
