package com.cy.system.user.service;

import com.cy.system.user.entity.SysUser;
import com.cy.system.user.entity.condition.SysUserCondition;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.service.IService;

import java.util.List;

/**
 * @Auther: pcy
 * @Date: 2026/1/6
 * @Description: 用户服务接口
 */
public interface SysUserService extends IService<SysUser> {

    /**
     * 创建用户
     *
     * @param user 用户实体
     * @return 是否创建成功
     */
    Boolean createUser(SysUser user);

    /**
     * 更新用户
     *
     * @param user 用户实体
     * @return 是否更新成功
     */
    Boolean updateUser(SysUser user);

    /**
     * 根据ID查询用户
     *
     * @param id 用户ID
     * @return 用户实体
     */
    SysUser getUserById(Long id);

    /**
     * 根据用户名查询用户
     *
     * @param username 用户名
     * @return 用户实体
     */
    SysUser getUserByUsername(String username);

    /**
     * 查询所有用户
     *
     * @return 用户列表
     */
    List<SysUser> listAll();

    /**
     * 根据ID删除用户
     *
     * @param id 用户ID
     * @return 是否删除成功
     */
    Boolean deleteUser(Long id);

    /**
     * 修改用户状态
     *
     * @param id     用户ID
     * @param status 状态
     * @return 是否修改成功
     */
    Boolean updateStatus(Long id, Integer status);

    /**
     * 修改用户密码
     *
     * @param id          用户ID
     * @param oldPassword 旧密码
     * @param newPassword 新密码
     * @return 是否修改成功
     */
    Boolean updatePassword(Long id, String oldPassword, String newPassword);

    /**
     * 重置用户密码
     *
     * @param id          用户ID
     * @param newPassword 新密码
     * @return 是否重置成功
     */
    Boolean resetPassword(Long id, String newPassword);

    /**
     * 分配角色给用户
     *
     * @param userId  用户ID
     * @param roleIds 角色ID列表
     * @return 是否分配成功
     */
    Boolean assignRoles(Long userId, List<Long> roleIds);

    /**
     * 获取用户的角色ID列表
     *
     * @param userId 用户ID
     * @return 角色ID列表
     */
    List<Long> getUserRoleIds(Long userId);

    /**
     * 分页查询用户
     *
     * @param condition 查询条件
     * @return 分页结果
     */
    Page<SysUser> pageUsers(SysUserCondition condition);

    /**
     * 获取所有活跃用户（可根据名称模糊搜索）
     *
     * @return 活跃用户列表
     */
    List<SysUser> getAllActiveUsers(String name);
}
