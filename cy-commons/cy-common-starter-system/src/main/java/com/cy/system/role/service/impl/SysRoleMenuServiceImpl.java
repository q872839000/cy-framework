package com.cy.system.role.service.impl;

import com.cy.system.role.dao.SysRoleMenuMapper;
import com.cy.system.role.entity.SysRoleMenu;
import com.cy.system.role.service.SysRoleMenuService;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * @Auther: pcy
 * @Date: 2026/1/6
 * @Description: 角色菜单关联服务实现类
 */
@Service
public class SysRoleMenuServiceImpl extends ServiceImpl<SysRoleMenuMapper, SysRoleMenu> implements SysRoleMenuService {

    @Override
    public void deleteByRoleId(Long roleId) {
        QueryWrapper query = QueryWrapper.create()
                .where(SysRoleMenu::getRoleId).eq(roleId);
        this.remove(query);
    }

    @Override
    public void deleteByMenuId(Long menuId) {
        QueryWrapper query = QueryWrapper.create()
                .where(SysRoleMenu::getMenuId).eq(menuId);
        this.remove(query);
    }

    @Override
    @Transactional
    public void saveRoleMenus(Long roleId, List<Long> menuIds) {
        // 先删除原有关联
        deleteByRoleId(roleId);
        // 添加新的关联
        if (menuIds != null && !menuIds.isEmpty()) {
            for (Long menuId : menuIds) {
                SysRoleMenu roleMenu = new SysRoleMenu();
                roleMenu.setRoleId(roleId);
                roleMenu.setMenuId(menuId);
                this.save(roleMenu);
            }
        }
    }

    @Override
    public List<Long> getMenuIdsByRoleId(Long roleId) {
        QueryWrapper query = QueryWrapper.create()
                .select(SysRoleMenu::getMenuId)
                .where(SysRoleMenu::getRoleId).eq(roleId);
        List<SysRoleMenu> roleMenus = this.list(query);
        return roleMenus.stream().map(SysRoleMenu::getMenuId).toList();
    }

    @Override
    public List<Long> getMenuIdsByRoleIds(List<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return new ArrayList<>();
        }
        QueryWrapper query = QueryWrapper.create()
                .select(SysRoleMenu::getMenuId)
                .where(SysRoleMenu::getRoleId).in(roleIds);
        List<SysRoleMenu> roleMenus = this.list(query);
        return roleMenus.stream()
                .map(SysRoleMenu::getMenuId)
                .distinct()
                .toList();
    }
}
