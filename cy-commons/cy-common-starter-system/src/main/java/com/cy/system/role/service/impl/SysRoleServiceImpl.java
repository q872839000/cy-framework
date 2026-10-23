package com.cy.system.role.service.impl;

import com.cy.core.exception.CYException;
import com.cy.log.core.LogService;
import com.cy.log.core.LogType;
import com.cy.system.role.dao.SysRoleMapper;
import com.cy.system.role.entity.SysRole;
import com.cy.system.role.entity.condition.SysRoleCondition;
import com.cy.system.role.service.SysRoleMenuService;
import com.cy.system.role.service.SysRoleService;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * @Auther: pcy
 * @Date: 2026/1/6
 * @Description: 角色服务实现类
 */
@Service
@RequiredArgsConstructor
public class SysRoleServiceImpl extends ServiceImpl<SysRoleMapper, SysRole> implements SysRoleService {

    private final SysRoleMenuService roleMenuService;

    @Autowired(required = false)
    private LogService logService;

    @Override
    @Transactional
    public Boolean createRole(SysRole role) {
        // 检查角色编码是否已存在
        SysRole existRole = getRoleByCode(role.getRoleCode());
        if (existRole != null) {
            throw new CYException(10050);
        }
        // 设置默认状态
        if (role.getStatus() == null) {
            role.setStatus(1);
        }
        boolean result = this.save(role);

        // 审计日志：创建角色
        if (result) {
            audit("创建角色", role.getId(), null, role,
                    "创建角色: " + role.getRoleName() + " (" + role.getRoleCode() + ")");
        }

        return result;
    }

    @Override
    public Boolean updateRole(SysRole role) {
        // 查询修改前数据
        SysRole before = this.getById(role.getId());

        // 不允许修改角色编码
        role.setRoleCode(null);
        boolean result = this.updateById(role);

        // 审计日志：修改角色
        if (result && before != null) {
            audit("修改角色", role.getId(), before, role,
                    "修改角色: " + before.getRoleName());
        }

        return result;
    }

    @Override
    public SysRole getRoleById(Long id) {
        return this.getById(id);
    }

    @Override
    public SysRole getRoleByCode(String roleCode) {
        if (StringUtils.isBlank(roleCode)) {
            return null;
        }
        QueryWrapper query = QueryWrapper.create()
                .where(SysRole::getRoleCode).eq(roleCode);
        return this.getOne(query);
    }

    @Override
    public List<SysRole> listAll() {
        QueryWrapper query = QueryWrapper.create()
                .orderBy(SysRole::getSort, true);
        return this.list(query);
    }

    @Override
    @Transactional
    public Boolean deleteRole(Long id) {
        // 查询删除前数据
        SysRole before = this.getById(id);

        // 删除角色菜单关联
        roleMenuService.deleteByRoleId(id);
        // 删除角色
        boolean result = this.removeById(id);

        // 审计日志：删除角色
        if (result && before != null) {
            audit("删除角色", id, before, null,
                    "删除角色: " + before.getRoleName() + " (" + before.getRoleCode() + ")");
        }

        return result;
    }

    @Override
    public Boolean updateStatus(Long id, Integer status) {
        // 查询修改前状态
        SysRole before = this.getById(id);
        Integer oldStatus = before != null ? before.getStatus() : null;

        SysRole role = new SysRole();
        role.setId(id);
        role.setStatus(status);
        boolean result = this.updateById(role);

        // 审计日志：修改角色状态
        if (result) {
            String roleName = before != null ? before.getRoleName() : String.valueOf(id);
            audit("修改角色状态", id, null, null,
                    "角色 " + roleName + " 状态: " + oldStatus + " → " + status);
        }

        return result;
    }

    @Override
    @Transactional
    public Boolean assignMenus(Long roleId, List<Long> menuIds) {
        // 查询分配前菜单
        List<Long> oldMenuIds = roleMenuService.getMenuIdsByRoleId(roleId);

        roleMenuService.saveRoleMenus(roleId, menuIds);

        // 审计日志：分配菜单权限
        SysRole role = this.getById(roleId);
        String roleName = role != null ? role.getRoleName() : String.valueOf(roleId);
        audit("分配菜单权限", roleId, oldMenuIds, menuIds,
                "角色 " + roleName + " 菜单: " + oldMenuIds.size() + "项 → " + menuIds.size() + "项");

        return true;
    }

    @Override
    public List<Long> getRoleMenuIds(Long roleId) {
        return roleMenuService.getMenuIdsByRoleId(roleId);
    }

    @Override
    public List<SysRole> getRolesByIds(List<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return List.of();
        }
        QueryWrapper query = QueryWrapper.create()
                .where(SysRole::getId).in(roleIds)
                .and(SysRole::getStatus).eq(1);
        return this.list(query);
    }

    /**
     * 记录角色管理审计日志
     */
    private void audit(String operation, Long targetId, Object beforeData, Object afterData, String description) {
        if (logService == null) {
            return;
        }
        LogService.LogBuilder builder = logService.builder()
                .logType(LogType.AUDIT)
                .module("角色管理")
                .operation(operation)
                .targetType("Role")
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
    public Page<SysRole> pageRoles(SysRoleCondition condition) {
        QueryWrapper query = QueryWrapper.create();
        Optional<SysRoleCondition> optional = Optional.of(condition);
        optional.map(SysRoleCondition::getName).ifPresent(e -> query.like(SysRole::getRoleName, e));
        return this.page(new Page<>(condition.getPage(), condition.getSize()), query);
    }
}
