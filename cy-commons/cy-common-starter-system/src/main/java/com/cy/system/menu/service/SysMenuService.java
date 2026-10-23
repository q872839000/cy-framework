package com.cy.system.menu.service;

import com.cy.system.menu.entity.SysMenu;
import com.cy.system.menu.vo.MenuTreeVO;
import com.mybatisflex.core.service.IService;

import java.util.List;

/**
 * @Auther: pcy
 * @Date: 2026/1/6
 * @Description: 菜单服务接口
 */
public interface SysMenuService extends IService<SysMenu> {

    /**
     * 创建菜单
     *
     * @param menu 菜单实体
     * @return 是否创建成功
     */
    Boolean createMenu(SysMenu menu);

    /**
     * 更新菜单
     *
     * @param menu 菜单实体
     * @return 是否更新成功
     */
    Boolean updateMenu(SysMenu menu);

    /**
     * 根据ID查询菜单
     *
     * @param id 菜单ID
     * @return 菜单实体
     */
    SysMenu getMenuById(Long id);

    /**
     * 查询所有菜单
     *
     * @return 菜单列表
     */
    List<SysMenu> listAll();

    /**
     * 获取菜单树形结构
     *
     * @return 菜单树形列表
     */
    List<MenuTreeVO> getMenuTree();

    /**
     * 根据ID删除菜单
     *
     * @param id 菜单ID
     * @return 是否删除成功
     */
    Boolean deleteMenu(Long id);

    /**
     * 修改菜单状态
     *
     * @param id     菜单ID
     * @param status 状态
     * @return 是否修改成功
     */
    Boolean updateStatus(Long id, Integer status);

    /**
     * 根据角色ID列表获取菜单列表
     *
     * @param roleIds 角色ID列表
     * @return 菜单列表
     */
    List<SysMenu> getMenusByRoleIds(List<Long> roleIds);

    /**
     * 根据角色ID列表获取菜单树形结构
     *
     * @param roleIds 角色ID列表
     * @return 菜单树形列表
     */
    List<MenuTreeVO> getMenuTreeByRoleIds(List<Long> roleIds);
}
