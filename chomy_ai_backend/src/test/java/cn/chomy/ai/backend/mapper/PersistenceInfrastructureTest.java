package cn.chomy.ai.backend.mapper;

import java.util.Map;

import cn.chomy.ai.backend.service.PermissionService;
import cn.chomy.ai.backend.service.RolePermissionService;
import cn.chomy.ai.backend.service.RoleService;
import cn.chomy.ai.backend.service.UserRoleService;
import cn.chomy.ai.backend.service.UserService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.service.IService;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 使用真实 MyBatis-Plus SQL 注入器验证五组 Mapper、Service 的扫描、装配及表映射。
 * 不加载完整应用和 Nacos，不执行 SQL，也不访问数据库，保证离线可重复运行。
 *
 * @author chomy
 */
class PersistenceInfrastructureTest {

    /** 检查每个 Mapper 都有对应 Service，且框架成功生成了指向正确表的查询语句。 */
    @Test
    void registersServicesAndGeneratesCrudStatements() {
        try (var context = new AnnotationConfigApplicationContext(PersistenceTestConfig.class)) {
            Map<Class<?>, Class<?>> services = Map.of(
                    UserMapper.class, UserService.class,
                    RoleMapper.class, RoleService.class,
                    PermissionMapper.class, PermissionService.class,
                    UserRoleMapper.class, UserRoleService.class,
                    RolePermissionMapper.class, RolePermissionService.class);
            Map<Class<?>, String> tables = Map.of(
                    UserMapper.class, "public.sys_user",
                    RoleMapper.class, "public.sys_role",
                    PermissionMapper.class, "public.sys_permission",
                    UserRoleMapper.class, "public.sys_user_role",
                    RolePermissionMapper.class, "public.sys_role_permission");
            var configuration = context.getBean(SqlSessionFactory.class).getConfiguration();

            assertThat(context.getBeansOfType(IService.class)).hasSize(5);
            services.forEach((mapperType, serviceType) -> {
                assertThat(context.getBean(mapperType)).isNotNull();
                assertThat(context.getBean(serviceType)).isNotNull();
                String namespace = mapperType.getName();
                for (String method : new String[]{"insert", "updateById", "deleteById", "selectById"}) {
                    assertThat(configuration.hasStatement(namespace + "." + method)).isTrue();
                }
                String sql = configuration.getMappedStatement(namespace + ".selectById").getBoundSql(1L).getSql();
                assertThat(sql).contains("FROM " + tables.get(mapperType));
                if (mapperType == UserMapper.class) {
                    assertThat(sql).doesNotContain("password_hash");
                    var wrapper = com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaQuery(cn.chomy.ai.backend.entity.User.class)
                            .select(cn.chomy.ai.backend.entity.User.class, field -> true)
                            .eq(cn.chomy.ai.backend.entity.User::getUsername, "test-only");
                    String loginSql = configuration.getMappedStatement(namespace + ".selectList")
                            .getBoundSql(Map.of("ew", wrapper)).getSql();
                    assertThat(loginSql).contains("password_hash");
                }
            });
        }
    }

    /**
     * 只装配持久化相关组件，显式构造会话工厂，避免配置中心或其他基础设施影响测试。
     *
     * @author chomy
     */
    @Configuration(proxyBeanMethods = false)
    @MapperScan("cn.chomy.ai.backend.mapper")
    @Import({cn.chomy.ai.backend.service.impl.UserServiceImpl.class,
            cn.chomy.ai.backend.service.impl.RoleServiceImpl.class,
            cn.chomy.ai.backend.service.impl.PermissionServiceImpl.class,
            cn.chomy.ai.backend.service.impl.UserRoleServiceImpl.class,
            cn.chomy.ai.backend.service.impl.RolePermissionServiceImpl.class})
    static class PersistenceTestConfig {

        /** 提供密码编码器，只用于用户服务构造，不启用完整的安全过滤器链。 */
        @Bean
        org.springframework.security.crypto.password.PasswordEncoder passwordEncoder() {
            return new cn.chomy.ai.backend.config.SecurityConfig().passwordEncoder();
        }


        /**
         * 构造只用于 SQL 生成的会话工厂；占位连接地址不会被访问，也不包含真实凭据。
         *
         * @return 已执行 MyBatis-Plus Mapper SQL 注入的会话工厂
         * @throws Exception 会话工厂初始化失败时抛出，使测试及时暴露映射或配置错误
         */
        @Bean
        SqlSessionFactory sqlSessionFactory() throws Exception {
            var factory = new MybatisSqlSessionFactoryBean();
            factory.setConfiguration(new MybatisConfiguration());
            factory.setDataSource(new UnpooledDataSource(
                    "org.postgresql.Driver", "jdbc:postgresql://127.0.0.1:1/not-used", "test-only", "test-only"));
            return factory.getObject();
        }
    }
}
