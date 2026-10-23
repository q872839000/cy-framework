package com.cy.system.menu.service.impl;

import com.cy.core.exception.CYException;
import com.cy.log.core.LogService;
import com.cy.log.core.LogType;
import com.cy.system.menu.dao.SysMenuMapper;
import com.cy.system.menu.entity.SysMenu;
import com.cy.system.menu.service.SysMenuService;
import com.cy.system.menu.vo.MenuTreeVO;
import com.cy.system.role.service.SysRoleMenuService;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @Auther: pcy
 * @Date: 2026/1/6
 * @Description: 菜单服务实现类
 */
@Service
@RequiredArgsConstructor
public class SysMenuServiceImpl extends ServiceImpl<SysMenuMapper, SysMenu> implements SysMenuService {

    private final SysRoleMenuService roleMenuService;

    @Autowired(required = false)
    private LogService logService;

    @Override
    @Transactional
    public Boolean createMenu(SysMenu menu) {
        // 设置默认值
        if (menu.getParentId() == null) {
            menu.setParentId(0L);
        }
        if (menu.getSort() == null) {
            menu.setSort(0);
        }
        if (menu.getVisible() == null) {
            menu.setVisible(1);
        }
        if (menu.getStatus() == null) {
            menu.setStatus(1);
        }
        boolean result = this.save(menu);

        // 审计日志：创建菜单
        if (result) {
            audit("创建菜单", menu.getId(), null, menu,
                    "创建菜单: " + menu.getMenuName());
        }

        return result;
    }

    @Override
    public Boolean updateMenu(SysMenu menu) {
        // 查询修改前数据
        SysMenu before = this.getById(menu.getId());

        boolean result = this.updateById(menu);

        // 审计日志：修改菜单
        if (result && before != null) {
            audit("修改菜单", menu.getId(), before, menu,
                    "修改菜单: " + before.getMenuName());
        }

        return result;
    }

    @Override
    public SysMenu getMenuById(Long id) {
        return this.getById(id);
    }

    @Override
    public List<SysMenu> listAll() {
        QueryWrapper query = QueryWrapper.create()
                .orderBy(SysMenu::getSort, true);
        return this.list(query);
    }

    @Override
    public List<MenuTreeVO> getMenuTree() {
        List<SysMenu> menus = listAll();
        return buildMenuTree(menus, 0L);
    }

    @Override
    @Transactional
    public Boolean deleteMenu(Long id) {
        // 检查是否有子菜单
        QueryWrapper childQuery = QueryWrapper.create()
                .where(SysMenu::getParentId).eq(id);
        long childCount = this.count(childQuery);
        if (childCount > 0) {
            throw new CYException(10100);
        }

        // 查询删除前数据
        SysMenu before = this.getById(id);

        // 删除角色菜单关联
        roleMenuService.deleteByMenuId(id);

        // 删除菜单
        boolean result = this.removeById(id);

        // 审计日志：删除菜单
        if (result && before != null) {
            audit("删除菜单", id, before, null,
                    "删除菜单: " + before.getMenuName());
        }

        return result;
    }

    @Override
    public Boolean updateStatus(Long id, Integer status) {
        // 查询修改前状态
        SysMenu before = this.getById(id);
        Integer oldStatus = before != null ? before.getStatus() : null;

        SysMenu menu = new SysMenu();
        menu.setId(id);
        menu.setStatus(status);
        boolean result = this.updateById(menu);

        // 审计日志：修改菜单状态
        if (result) {
            String menuName = before != null ? before.getMenuName() : String.valueOf(id);
            audit("修改菜单状态", id, null, null,
                    "菜单 " + menuName + " 状态: " + oldStatus + " → " + status);
        }

        return result;
    }

    @Override
    public List<SysMenu> getMenusByRoleIds(List<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return new ArrayList<>();
        }

        // 查询角色菜单关联
        List<Long> menuIds = roleMenuService.getMenuIdsByRoleIds(roleIds);

        if (menuIds.isEmpty()) {
            return new ArrayList<>();
        }

        // 查询菜单
        QueryWrapper menuQuery = QueryWrapper.create()
                .where(SysMenu::getId).in(menuIds)
                .and(SysMenu::getStatus).eq(1)
                .orderBy(SysMenu::getSort, true);
        return this.list(menuQuery);
    }

    @Override
    public List<MenuTreeVO> getMenuTreeByRoleIds(List<Long> roleIds) {
        List<SysMenu> menus = getMenusByRoleIds(roleIds);
        return buildMenuTree(menus, 0L);
    }

    /**
     * 记录菜单管理审计日志
     */
    private void audit(String operation, Long targetId, Object beforeData, Object afterData, String description) {
        if (logService == null) {
            return;
        }
        LogService.LogBuilder builder = logService.builder()
                .logType(LogType.AUDIT)
                .module("菜单管理")
                .operation(operation)
                .targetType("Menu")
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

    /**
     * 构建菜单树形结构
     *
     * @param menus    菜单列表
     * @param parentId 父菜单ID
     * @return 树形菜单列表
     */
    private List<MenuTreeVO> buildMenuTree(List<SysMenu> menus, Long parentId) {
        return menus.stream()
                .filter(menu -> menu.getParentId().equals(parentId))
                .map(menu -> {
                    MenuTreeVO vo = new MenuTreeVO();
                    BeanUtils.copyProperties(menu, vo);
                    vo.setChildren(buildMenuTree(menus, menu.getId()));
                    return vo;
                })
                .collect(Collectors.toList());
    }
}
