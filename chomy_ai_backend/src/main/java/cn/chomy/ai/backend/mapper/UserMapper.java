package cn.chomy.ai.backend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import cn.chomy.ai.backend.entity.User;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户表持久化接口，复用 MyBatis-Plus 提供的单表增删改查能力。
 * 用户名、手机号唯一性由数据库唯一索引保障，调用方需处理并发写入导致的重复键异常。
 * 状态及关联关系由业务代码校验，更新时间由更新业务显式赋值，不依赖数据库触发器。
 * 普通查询默认不读取密码哈希，认证查询需要显式选择该字段。
 *
 * @author chomy
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {
}
