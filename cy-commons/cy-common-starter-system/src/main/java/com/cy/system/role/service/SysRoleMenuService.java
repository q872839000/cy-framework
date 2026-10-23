package com.cy.system.role.service;

import com.cy.system.role.entity.SysRoleMenu;
import com.mybatisflex.core.service.IService;

import java.util.List;

/**
 * @Auther: pcy
 * @Date: 2026/1/6
 * @Description: 角色菜单关联服务接口
 */
public interface SysRoleMenuService extends IService<SysRoleMenu> {

    /**
     * 根据角色ID删除角色菜单关联
     *
     * @param roleId 角色ID
     */
    void deleteByRoleId(Long roleId);

    /**
     * 根据菜单ID删除角色菜单关联
     *
     * @param menuId 菜单ID
     */
    void deleteByMenuId(Long menuId);

    /**
     * 批量保存角色菜单关联
     *
     * @param roleId  角色ID
     * @param menuIds 菜单ID列表
     */
    void saveRoleMenus(Long roleId, List<Long> menuIds);

    /**
     * 获取角色的菜单ID列表
     *
     * @param roleId 角色ID
     * @return 菜单ID列表
     */
    List<Long> getMenuIdsByRoleId(Long roleId);

    /**
     * 根据角色ID列表获取菜单ID列表
     *
     * @param roleIds 角色ID列表
     * @return 菜单ID列表（去重）
     */
    List<Long> getMenuIdsByRoleIds(List<Long> roleIds);
}
