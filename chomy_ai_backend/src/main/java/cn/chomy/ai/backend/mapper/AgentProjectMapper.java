package cn.chomy.ai.backend.mapper;

import cn.chomy.ai.backend.entity.AgentProject;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 项目数据访问，复用 MyBatis-Plus CRUD；所有权校验和关联清理由 Service 编排。
 * @author chomy
 */
@Mapper
public interface AgentProjectMapper extends BaseMapper<AgentProject> {
}
