package com.cy.system.role.service;

import com.cy.system.role.entity.SysRole;
import com.cy.system.role.entity.condition.SysRoleCondition;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.service.IService;

import java.util.List;

/**
 * @Auther: pcy
 * @Date: 2026/1/6
 * @Description: 角色服务接口
 */
public interface SysRoleService extends IService<SysRole> {

    /**
     * 创建角色
     *
     * @param role 角色实体
     * @return 是否创建成功
     */
    Boolean createRole(SysRole role);

    /**
     * 更新角色
     *
     * @param role 角色实体
     * @return 是否更新成功
     */
    Boolean updateRole(SysRole role);

    /**
     * 根据ID查询角色
     *
     * @param id 角色ID
     * @return 角色实体
     */
    SysRole getRoleById(Long id);

    /**
     * 根据角色编码查询角色
     *
     * @param roleCode 角色编码
     * @return 角色实体
     */
    SysRole getRoleByCode(String roleCode);

    /**
     * 查询所有角色
     *
     * @return 角色列表
     */
    List<SysRole> listAll();

    /**
     * 根据ID删除角色
     *
     * @param id 角色ID
     * @return 是否删除成功
     */
    Boolean deleteRole(Long id);

    /**
     * 修改角色状态
     *
     * @param id     角色ID
     * @param status 状态
     * @return 是否修改成功
     */
    Boolean updateStatus(Long id, Integer status);

    /**
     * 分配菜单给角色
     *
     * @param roleId  角色ID
     * @param menuIds 菜单ID列表
     * @return 是否分配成功
     */
    Boolean assignMenus(Long roleId, List<Long> menuIds);

    /**
     * 获取角色的菜单ID列表
     *
     * @param roleId 角色ID
     * @return 菜单ID列表
     */
    List<Long> getRoleMenuIds(Long roleId);

    /**
     * 根据角色ID列表批量查询角色
     *
     * @param roleIds 角色ID列表
     * @return 角色列表
     */
    List<SysRole> getRolesByIds(List<Long> roleIds);

    /**
     * 分页查询角色
     *
     * @param condition 查询条件
     * @return 分页结果
     */
    Page<SysRole> pageRoles(SysRoleCondition condition);
}
