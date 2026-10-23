package com.cy.system.user.service;

import com.cy.system.user.entity.SysUserRole;
import com.mybatisflex.core.service.IService;

import java.util.List;

/**
 * @Auther: pcy
 * @Date: 2026/1/6
 * @Description: 用户角色关联服务接口
 */
public interface SysUserRoleService extends IService<SysUserRole> {

    /**
     * 根据用户ID删除用户角色关联
     *
     * @param userId 用户ID
     */
    void deleteByUserId(Long userId);

    /**
     * 批量保存用户角色关联
     *
     * @param userId  用户ID
     * @param roleIds 角色ID列表
     */
    void saveUserRoles(Long userId, List<Long> roleIds);

    /**
     * 获取用户的角色ID列表
     *
     * @param userId 用户ID
     * @return 角色ID列表
     */
    List<Long> getRoleIdsByUserId(Long userId);
}
