package com.cy.system.role.controller;

import com.cy.core.base.R;
import com.cy.log.annotation.SysLog;
import com.cy.system.role.entity.SysRole;
import com.cy.system.role.entity.condition.SysRoleCondition;
import com.cy.system.role.service.SysRoleService;
import com.cy.system.role.vo.AssignMenusRequest;
import com.cy.userfill.annotation.FillUserName;
import com.mybatisflex.core.paginate.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * @Auther: pcy
 * @Date: 2026/1/6
 * @Description: 角色管理控制器
 */
@RestController
@RequestMapping("/system/role")
@Validated
@Tag(name = "角色管理")
public class SysRoleController {

    private final SysRoleService roleService;

    public SysRoleController(SysRoleService roleService) {
        this.roleService = roleService;
    }

    @Operation(summary = "创建角色")
    @PostMapping("/create")
    @SysLog(module = "角色管理", operation = "创建角色", targetType = "Role")
    public R<Boolean> create(@RequestBody SysRole role) {
        return R.ok(roleService.createRole(role));
    }

    @Operation(summary = "更新角色")
    @PostMapping("/update")
    @SysLog(module = "角色管理", operation = "更新角色", targetType = "Role")
    public R<Boolean> update(@RequestBody SysRole role) {
        return R.ok(roleService.updateRole(role));
    }

    @Operation(summary = "查询角色详情")
    @GetMapping("/get-info")
    public R<SysRole> getInfo(@Parameter(description = "角色ID") @RequestParam Long id) {
        return R.ok(roleService.getRoleById(id));
    }

    @Operation(summary = "查询所有角色")
    @GetMapping("/list-all")
    @FillUserName
    public R<List<SysRole>> listAll() {
        return R.ok(roleService.listAll());
    }

    @Operation(summary = "删除角色")
    @PostMapping("/remove")
    @SysLog(module = "角色管理", operation = "删除角色", targetType = "Role", targetIdEl = "#id")
    public R<Boolean> remove(@Parameter(description = "角色ID") @RequestParam Long id) {
        return R.ok(roleService.deleteRole(id));
    }

    @Operation(summary = "修改角色状态")
    @PostMapping("/update-status")
    @SysLog(module = "角色管理", operation = "修改角色状态", targetType = "Role", targetIdEl = "#id")
    public R<Boolean> updateStatus(
            @Parameter(description = "角色ID") @RequestParam Long id,
            @Parameter(description = "状态：0-禁用，1-正常") @RequestParam Integer status) {
        return R.ok(roleService.updateStatus(id, status));
    }

    @Operation(summary = "分配菜单给角色")
    @PostMapping("/assign-menus")
    @SysLog(module = "角色管理", operation = "分配菜单", targetType = "Role", targetIdEl = "#request.roleId")
    public R<Boolean> assignMenus(@Valid @RequestBody AssignMenusRequest request) {
        return R.ok(roleService.assignMenus(request.getRoleId(), request.getMenuIds()));
    }

    @Operation(summary = "获取角色的菜单ID列表")
    @GetMapping("/menu-ids")
    public R<List<Long>> getRoleMenuIds(@Parameter(description = "角色ID") @RequestParam Long roleId) {
        return R.ok(roleService.getRoleMenuIds(roleId));
    }

    @Operation(summary = "分页查询角色", description = "支持角色名模糊查询")
    @GetMapping("/page")
    @FillUserName
    public R<Page<SysRole>> pageUsers(SysRoleCondition condition) {
        Page<SysRole> page = roleService.pageRoles(condition);
        return R.ok(page);
    }

}
