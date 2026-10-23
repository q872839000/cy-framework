package com.cy.system.user.service.impl;

import com.cy.core.constants.Constants;
import com.cy.core.exception.CYException;
import com.cy.log.annotation.AccessLog;
import com.cy.system.superadmin.SuperAdminHelper;
import com.cy.system.user.dao.SysUserMapper;
import com.cy.system.user.entity.SysUser;
import com.cy.system.user.entity.condition.SysUserCondition;
import com.cy.system.user.entity.table.SysUserTableDef;
import com.cy.system.user.service.SysUserRoleService;
import com.cy.system.user.service.SysUserService;
import com.cy.system.util.PasswordEncoder;
import com.cy.log.core.LogService;
import com.cy.log.core.LogType;
import com.cy.redis.annotation.CacheMapPut;
import com.cy.redis.utils.RedisUtils;
import com.mybatisflex.core.logicdelete.LogicDeleteManager;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * @Auther: pcy
 * @Date: 2026/1/6
 * @Description: 用户服务实现类
 */
@Service
@RequiredArgsConstructor
@AccessLog
public class SysUserServiceImpl extends ServiceImpl<SysUserMapper, SysUser> implements SysUserService {

    private static final Logger log = LoggerFactory.getLogger(SysUserServiceImpl.class);

    private final SysUserRoleService userRoleService;

    @Autowired(required = false)
    private LogService logService;

    @Override
    @Transactional
    @CacheMapPut(mapKey = "#{T(com.cy.core.constants.Constants).USER_CACHE_KEY}", hashKey = "#{#user.id}", value = "#{#user.nickname}", condition = "#result == true")
    public Boolean createUser(SysUser user) {
        // 检查用户名是否已存在
        SysUser existUser = getUserByUsername(user.getUsername());
        if (existUser != null) {
            throw new CYException(10003, user.getUsername());
        }
        // 设置默认状态
        if (user.getStatus() == null) {
            user.setStatus(1);
        }
        // 加密密码
        if (StringUtils.isBlank(user.getPassword())) {
            user.setPassword("123456");
        }
        user.setPassword(PasswordEncoder.encode(user.getPassword()));
        boolean result = this.save(user);

        // 审计日志：创建用户
        if (result) {
            SysUser safeUser = new SysUser();
            safeUser.setId(user.getId());
            safeUser.setUsername(user.getUsername());
            safeUser.setNickname(user.getNickname());
            safeUser.setStatus(user.getStatus());
            audit("创建用户", user.getId(), null, safeUser,
                    "创建用户: " + user.getUsername());
        }

        return result;
    }

    @Override
    @CacheMapPut(mapKey = "#{T(com.cy.core.constants.Constants).USER_CACHE_KEY}", hashKey = "#{#user.id}", value = "#{#user.nickname}", condition = "#result == true && #user.nickname != null")
    public Boolean updateUser(SysUser user) {
        // 查询修改前数据
        SysUser before = this.getById(user.getId());

        // 不允许修改用户名
        user.setUsername(null);
        // 不允许通过此方法修改密码
        user.setPassword(null);
        boolean result = this.updateById(user);

        // 审计日志：修改用户
        if (result && before != null) {
            before.setPassword(null);
            audit("修改用户", user.getId(), before, user,
                    "修改用户: " + before.getUsername());
        }

        return result;
    }

    @Override
    public SysUser getUserById(Long id) {
        return this.getById(id);
    }

    @Override
    public SysUser getUserByUsername(String username) {
        if (StringUtils.isBlank(username)) {
            return null;
        }
        QueryWrapper query = QueryWrapper.create()
                .where(SysUser::getUsername).eq(username);
        return this.getOne(query);
    }

    @Override
    public List<SysUser> listAll() {
        // MyBatis-Flex会自动过滤deleted=1的记录
        return this.list();
    }

    @Override
    @Transactional
    public Boolean deleteUser(Long id) {
        // 查询删除前数据
        SysUser before = this.getById(id);

        // 逻辑删除用户
        // 注意：不删除用户角色关联，也不清除用户缓存，因为查询业务数据时仍需要显示创建人/修改人名称
        boolean result = this.removeById(id);

        // 审计日志：删除用户
        if (result && before != null) {
            before.setPassword(null);
            audit("删除用户", id, before, null,
                    "删除用户: " + before.getUsername());
        }

        return result;
    }

    @Override
    public Boolean updateStatus(Long id, Integer status) {
        // 查询修改前状态
        SysUser before = this.getById(id);
        Integer oldStatus = before != null ? before.getStatus() : null;

        SysUser user = new SysUser();
        user.setId(id);
        user.setStatus(status);
        boolean result = this.updateById(user);

        // 审计日志：修改用户状态
        if (result) {
            String username = before != null ? before.getUsername() : String.valueOf(id);
            audit("修改用户状态", id, null, null,
                    "用户 " + username + " 状态: " + oldStatus + " → " + status);
        }

        return result;
    }

    @Override
    public Boolean updatePassword(Long id, String oldPassword, String newPassword) {
        SysUser user = this.getById(id);
        if (user == null) {
            throw new CYException(10006);
        }
        // 验证旧密码
        if (!PasswordEncoder.matches(oldPassword, user.getPassword())) {
            throw new CYException(10007);
        }
        SysUser updateUser = new SysUser();
        updateUser.setId(id);
        updateUser.setPassword(PasswordEncoder.encode(newPassword));
        boolean result = this.updateById(updateUser);

        // 审计日志：修改密码（不记录密码内容）
        if (result) {
            audit("修改密码", id, null, null,
                    "用户 " + user.getUsername() + " 修改密码");
        }

        return result;
    }

    @Override
    public Boolean resetPassword(Long id, String newPassword) {
        SysUser updateUser = new SysUser();
        updateUser.setId(id);
        updateUser.setPassword(PasswordEncoder.encode(newPassword));
        boolean result = this.updateById(updateUser);

        // 审计日志：重置密码（不记录密码内容）
        if (result) {
            audit("重置密码", id, null, null,
                    "管理员重置用户 " + id + " 的密码");
        }

        return result;
    }

    @Override
    @Transactional
    public Boolean assignRoles(Long userId, List<Long> roleIds) {
        // 查询分配前角色
        List<Long> oldRoleIds = userRoleService.getRoleIdsByUserId(userId);

        userRoleService.saveUserRoles(userId, roleIds);

        // 审计日志：分配角色
        audit("分配角色", userId, oldRoleIds, roleIds,
                "用户 " + userId + " 角色: " + oldRoleIds + " → " + roleIds);

        return true;
    }

    @Override
    public List<Long> getUserRoleIds(Long userId) {
        return userRoleService.getRoleIdsByUserId(userId);
    }

    @Override
    public List<SysUser> getAllActiveUsers(String name) {
        QueryWrapper query = QueryWrapper.create()
                .where(SysUser::getStatus).eq(1)
                .orderBy(SysUser::getId, true);
        Optional.ofNullable(name)
                .filter(StringUtils::isNotBlank)
                // and(组合条件) 自动加括号：status = 1 AND (username LIKE ? OR nickname LIKE ?)
                // 直接链式 .or() 会生成 status = 1 OR username LIKE ?（OR 优先级低于 AND，过滤失效）
                .ifPresent(e -> query.and(SysUserTableDef.SYS_USER.USERNAME.like(e)
                        .or(SysUserTableDef.SYS_USER.NICKNAME.like(e))));
        return this.list(query);
    }

    /**
     * 初始化用户缓存
     * <p>
     * 在应用启动时将所有用户（包括已删除的）的ID和昵称缓存到Redis中，
     * 用于查询业务数据时显示创建人/修改人名称。
     * 同时添加系统特殊用户（如超级管理员）到缓存中。
     * </p>
     */
    @PostConstruct
    public void initUserCache() {
        try {
            // 查询所有用户（包括已删除的），因为查询业务数据时需要显示创建人/修改人名称
            List<SysUser> users = getAllUsersIncludeDeleted();

            // 构建用户Map
            Map<String, String> userMap = new HashMap<>();
            
            // 添加数据库中的用户
            if (users != null && !users.isEmpty()) {
                for (SysUser user : users) {
                    userMap.put(user.getId().toString(), user.getNickname());
                }
            }

            // 添加系统特殊用户
            // 0L: 系统用户（未登录或超级管理员操作时使用）
            userMap.put("0", "系统");
            // -1L: 超级管理员虚拟用户
            userMap.put(Constants.SUPER_ADMIN_USER_ID.toString(), Constants.SUPER_ADMIN_NICKNAME);

            // 存储到Redis
            RedisUtils.setCacheMap(Constants.USER_CACHE_KEY, userMap);
            log.info("成功缓存 {} 个用户信息到Redis（含系统用户）", userMap.size());

        } catch (Exception e) {
            log.error("初始化用户缓存失败", e);
        }
    }

    /**
     * 获取所有用户（包括已删除的）
     * 用于初始化用户名称缓存，供查询业务数据时显示创建人/修改人名称
     */
    private List<SysUser> getAllUsersIncludeDeleted() {
        // 使用自定义SQL查询所有用户，跳过逻辑删除过滤
        return LogicDeleteManager.execWithoutLogicDelete(()->this.list());
    }

    /**
     * 记录用户管理审计日志
     */
    private void audit(String operation, Long targetId, Object beforeData, Object afterData, String description) {
        if (logService == null) {
            return;
        }
        LogService.LogBuilder builder = logService.builder()
                .logType(LogType.AUDIT)
                .module("用户管理")
                .operation(operation)
                .targetType("User")
                .targetId(targetId != null ? targetId.toString() : null)
                .description(description)
                .success(true);
        if (beforeData != null) {
            builder.beforeData(beforeData);
        }
        if (afterData != null) {
            builder.afterData(afterData);
        }
        builder.record();
    }

    @Override
    public Page<SysUser> pageUsers(SysUserCondition condition) {
        QueryWrapper query = QueryWrapper.create();
        
        Optional.ofNullable(condition.getName())
                .filter(StringUtils::isNotBlank)
                // and(组合条件) 自动加括号：(username LIKE ? OR nickname LIKE ?)；
                // 链式 .or() 不加括号，会与后续 status 条件形成 OR 优先级错误
                .ifPresent(name -> query.and(SysUserTableDef.SYS_USER.USERNAME.like(name)
                        .or(SysUserTableDef.SYS_USER.NICKNAME.like(name))));
        
        Optional.ofNullable(condition.getStatus())
                .ifPresent(status -> query.and(SysUser::getStatus).eq(status));
        
        query.orderBy(SysUser::getCreateTime, false);
        
        return this.page(new Page<>(condition.getPage(), condition.getSize()), query);
    }
}
